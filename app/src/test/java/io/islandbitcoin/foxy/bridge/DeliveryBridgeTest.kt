package io.islandbitcoin.foxy.bridge

import io.islandbitcoin.foxy.FakeHost
import io.islandbitcoin.foxy.TestSupport
import io.islandbitcoin.foxy.keychain.CounterStore
import io.islandbitcoin.foxy.keychain.Flags
import io.islandbitcoin.foxy.keychain.SeedCandidates
import io.islandbitcoin.foxy.keychain.SeedEnvironment
import io.islandbitcoin.foxy.keychain.SeedVault
import io.islandbitcoin.foxy.network.Inbox
import io.islandbitcoin.foxy.network.InboxAnswer
import io.islandbitcoin.foxy.network.OnionPost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * The onion actions as FoxyBridge+Delivery.swift answers them (ENG-672),
 * through the bridge's own `onMessage`, with stand-in inboxes: the open one,
 * the warm spare handed over and replaced, a new one when there is none, the
 * hold on close, the page's answer carried to the payer in iOS's bytes, the
 * post's answers and refusals, and nothing of this phone's left published
 * while Foxy is put away.
 */
class DeliveryBridgeTest {
    /** An inbox that opens when told, and says what happened to it. */
    inner class StubInbox(override var note: String, val onPayment: (String, InboxAnswer) -> Unit) : Inbox {
        private val n = made.size
        var opened: ((String?) -> Unit)? = null
        var closed = false
        private var address: String? = null
        override val url: String? get() = address?.takeIf { !closed }
        override val isOpen: Boolean get() = url != null

        override fun open(done: (String?) -> Unit) {
            opened = done
            if (autoOpen) give(true)
        }

        /** Tor answers: an address, or none. */
        fun give(ok: Boolean) {
            val done = opened ?: error("never opened")
            opened = null
            // a v3 id (56 characters, the last a "d") and a 128-bit path, each stub's its own
            address = if (ok) "http://" + "a".repeat(54) + ('a' + n) + "d.onion/" + "%032x".format(n) else null
            done(address)
        }

        override fun close() {
            closed = true
        }
    }

    private val made = mutableListOf<StubInbox>()
    private var autoOpen = true
    private var tor = true
    /** The Tor daemon's generation: one more is a relaunch, which takes every address with the old one. */
    private var generation = 0
    private val timers = mutableListOf<Pair<Long, () -> Unit>>()
    private val posts = mutableListOf<List<Any>>()
    private var postAnswer: OnionPost.Result = OnionPost.Result.Done(200, "{\"ok\":true}")

    private val env = DeliveryEnvironment(
        torRunning = { tor },
        daemonGeneration = { generation },
        newInbox = { note, onPayment -> StubInbox(note, onPayment).also { made.add(it) } },
        post = { url, body, circuit, timeoutMs, done ->
            posts.add(listOf(url, String(body), circuit, timeoutMs))
            done(postAnswer)
        },
        after = { ms, r -> timers.add(ms to r) },
    )

    private val host = FakeHost()
    private val dir = TestSupport.tempDir("delivery")
    private val seeds = SeedEnvironment(
        savedSeed = { SeedVault.SecretsSeed.Absent }, seedPresent = { false }, write = { null },
        counters = CounterStore(dir), candidates = SeedCandidates(), wordlist = { TestSupport.wordlist },
        generate = { TestSupport.MADE }, migrationWindowOpen = { false }, closeMigrationWindow = {}, flags = Flags(dir),
    )
    private val bridge = FoxyBridge(host, { seeds }, Executor { it.run() }, isDebug = false, deliveryEnvironment = env)

    private fun send(json: String) = bridge.onMessage(json, true, FoxyBridge.ORIGIN)

    private fun replies(): List<Triple<String?, String?, String?>> = host.evaluated.filter { it.contains("._scanResult(") }.map { js ->
        @Suppress("UNCHECKED_CAST")
        val list = Json.parse("[" + js.substringAfter("_scanResult(").removeSuffix(")") + "]") as List<String?>
        Triple(list[0], list[1], list[2])
    }

    private fun reply(id: String) = replies().last { it.first == id }

    private fun onion(id: String): String = (Json.parse(reply(id).second!!) as Map<*, *>)["onion"] as String

    /** The _requestPaid pushes: (body, answer key). */
    private fun paid(): List<Pair<String, String>> = host.evaluated.filter { it.contains("._requestPaid(") }.map { js ->
        @Suppress("UNCHECKED_CAST")
        val list = Json.parse("[" + js.substringAfterLast("._requestPaid(").removeSuffix(")") + "]") as List<String>
        list[0] to list[1]
    }

    private fun runTimers(atMost: Long = Long.MAX_VALUE) {
        val due = timers.filter { it.first <= atMost }
        timers.removeAll(due)
        due.forEach { it.second() }
    }

    // MARK: inboxOpen

    @Test
    fun inboxOpenAnswersTheOnionAloneAndTheSameOneUntilItCloses() {
        send("""{"action":"inboxOpen","id":"o1"}""")
        val first = onion("o1")
        assertTrue(first, Regex("^http://[a-z2-7]{55}d\\.onion/[0-9a-f]{16,64}$").matches(first))
        // no Nostr key is wired here (DeliveryNostrTest has one): the answer has the onion and nothing else
        assertEquals(setOf("onion"), (Json.parse(reply("o1").second!!) as Map<*, *>).keys)
        send("""{"action":"inboxOpen","id":"o2"}""")
        assertEquals("the open one is answered again", first, onion("o2"))
        assertEquals("one inbox, for the request", 1, made.size)
    }

    @Test
    fun theWarmSpareIsHandedOverAndAnotherStartedAtOnce() {
        bridge.torChanged()
        assertEquals(1, made.size)
        val spare = made[0]
        assertEquals("the spare", spare.note)
        send("""{"action":"inboxOpen","id":"o1"}""")
        assertEquals(spare.url, onion("o1"))
        assertEquals("it stops being called the spare when it is handed over", "for the request on screen", spare.note)
        assertEquals("and a new spare is warming", 2, made.size)
        assertEquals("the spare", made[1].note)
        // Tor changing again starts nothing more while a spare is there
        bridge.torChanged()
        assertEquals(2, made.size)
    }

    /**
     * tor-android's thread ended and Tor was launched again: the spare's
     * listener is still up, so it says it is open, but its address died with
     * the old daemon. It is never handed out as already published; a new one is
     * made, and the Tor change starts a fresh spare too.
     */
    @Test
    fun aDaemonRelaunchedSpareIsNotHandedOut() {
        bridge.torChanged()
        val stale = made.single()
        assertTrue("its listener still says open", stale.isOpen)
        generation += 1
        send("""{"action":"inboxOpen","id":"o1"}""")
        assertTrue("the old daemon's spare is closed", stale.closed)
        assertTrue("the answer is a new address", onion("o1") != stale.url)
        assertEquals("a new inbox was made for the request", 2, made.size)
        assertEquals(made[1].url, onion("o1"))
        // and the Tor change after the relaunch warms a spare of the new daemon's
        bridge.torChanged()
        assertEquals(3, made.size)
        assertEquals("the spare", made[2].note)
    }

    @Test
    fun aDaemonRelaunchedSpareIsReplacedWhenTorChanges() {
        bridge.torChanged()
        val stale = made.single()
        generation += 1
        bridge.torChanged()
        assertTrue("the old daemon's spare is closed", stale.closed)
        assertEquals("and a new one warms", 2, made.size)
        send("""{"action":"inboxOpen","id":"o1"}""")
        assertEquals("the new daemon's spare is handed over", made[1].url, onion("o1"))
    }

    @Test
    fun theOpenInboxOfARelaunchedDaemonIsNotAnsweredAgain() {
        send("""{"action":"inboxOpen","id":"o1"}""")
        val first = made.single()
        generation += 1
        send("""{"action":"inboxOpen","id":"o2"}""")
        assertTrue("the old daemon's inbox is closed", first.closed)
        assertTrue(onion("o2") != onion("o1"))
        assertEquals(made.last().url, onion("o2"))
    }

    @Test
    fun anAddressThatArrivesAfterARelaunchIsNotUsed() {
        autoOpen = false
        send("""{"action":"inboxOpen","id":"o1"}""")
        generation += 1
        made.single().give(true)
        assertEquals(Triple("o1", null, DeliveryBridge.NO_ONION), reply("o1"))
        assertTrue(made.single().closed)
    }

    @Test
    fun noTorNoSpareAndInboxOpenAnswersTheIosError() {
        tor = false
        bridge.torChanged()
        assertTrue("no spare without Tor", made.isEmpty())
        autoOpen = false
        send("""{"action":"inboxOpen","id":"o1"}""")
        made.single().give(false)
        assertEquals(Triple("o1", null, DeliveryBridge.NO_ONION), reply("o1"))
        assertTrue("an inbox that got no address is closed", made.single().closed)
    }

    @Test
    fun aSpareThatGotNoAddressIsClosedAndForgotten() {
        autoOpen = false
        bridge.torChanged()
        made[0].give(false)
        assertTrue(made[0].closed)
        bridge.torChanged()
        assertEquals("a new spare once the old one failed", 2, made.size)
    }

    @Test
    fun anInboxReplacedBeforeItOpenedIsClosedWhenItDoes() {
        autoOpen = false
        send("""{"action":"inboxOpen","id":"o1"}""")
        val first = made[0]
        send("""{"action":"inboxOpen","id":"o2"}""")
        val second = made[1]
        assertTrue("the first closed when the second was made", first.closed)
        first.give(true)
        assertEquals(Triple("o1", null, DeliveryBridge.NO_ONION), reply("o1"))
        second.give(true)
        assertTrue(reply("o2").second!!.contains("\"onion\""))
    }

    // MARK: inboxClose

    @Test
    fun inboxCloseWithNoHoldEndsTheAddressAtOnce() {
        send("""{"action":"inboxOpen","id":"o1"}""")
        val inbox = made[0]
        send("""{"action":"inboxClose","id":"c1","hold":0}""")
        assertEquals(Triple("c1", "ok", null), reply("c1"))
        assertTrue(inbox.closed)
        assertTrue("no timer for a close with no hold", timers.isEmpty())
    }

    @Test
    fun aHoldKeepsTheAddressAnsweringThenEndsIt() {
        send("""{"action":"inboxOpen","id":"o1"}""")
        val inbox = made[0]
        send("""{"action":"inboxClose","id":"c1","hold":45}""")
        assertEquals(Triple("c1", "ok", null), reply("c1"))
        assertFalse("still answering", inbox.closed)
        assertEquals(45_000L, timers.single().first)
        // a payment reaching it while it retires is still handed to the page
        inbox.onPayment("cashuBheld") { _, _ -> }
        assertEquals("cashuBheld", paid().single().first)
        runTimers()
        assertTrue(inbox.closed)
        // the next request gets the spare warmed at the close, a new address
        send("""{"action":"inboxOpen","id":"o2"}""")
        assertEquals(made[1].url, onion("o2"))
        assertFalse(onion("o2") == onion("o1"))
    }

    @Test
    fun theHoldIsClampedAndItsDefaultIsNone() {
        assertEquals(0.0, DeliveryBridge.holdSeconds(null), 0.0)
        assertEquals(0.0, DeliveryBridge.holdSeconds(-5.0), 0.0)
        assertEquals(0.0, DeliveryBridge.holdSeconds("90"), 0.0)
        assertEquals(12.5, DeliveryBridge.holdSeconds(12.5), 0.0)
        assertEquals(180.0, DeliveryBridge.holdSeconds(1e9), 0.0)
        send("""{"action":"inboxOpen","id":"o1"}""")
        send("""{"action":"inboxClose","id":"c1","hold":100000}""")
        assertEquals(180_000L, timers.single().first)
    }

    @Test
    fun inboxCloseWithNothingOpenIsStillOk() {
        tor = false
        send("""{"action":"inboxClose","id":"c1","hold":10}""")
        assertEquals(Triple("c1", "ok", null), reply("c1"))
    }

    // MARK: A payment, and the page's answer

    @Test
    fun aPaymentIsPushedWithAFreshAnswerKeyAndThePagesAnswerReachesThePayer() {
        send("""{"action":"inboxOpen","id":"o1"}""")
        val heard = mutableListOf<Pair<Int, String>>()
        made[0].onPayment("""{"mint":"https://m.example.com","proofs":[]}""") { status, body -> heard.add(status to body) }
        val (body, key) = paid().single()
        assertEquals("""{"mint":"https://m.example.com","proofs":[]}""", body)
        assertTrue(key, Regex("^[0-9a-f]{32}$").matches(key))
        assertEquals("the key is kept 110 s", DeliveryBridge.ANSWER_KEPT_MS, timers.single().first)
        send("""{"action":"inboxAnswer","id":"a1","answer":"$key","status":200,"text":""}""")
        assertEquals(Triple("a1", "ok", null), reply("a1"))
        assertEquals(listOf(200 to "{\"ok\":true}"), heard)
        // answered once: the key is gone
        send("""{"action":"inboxAnswer","id":"a2","answer":"$key","status":200,"text":""}""")
        assertEquals(Triple("a2", null, InboxAnswers.NO_PAYMENT_WAITING), reply("a2"))
    }

    @Test
    fun aRefusalCarriesItsWordsInIosJsonAndAnyOtherStatusIs422() {
        send("""{"action":"inboxOpen","id":"o1"}""")
        val heard = mutableListOf<Pair<Int, String>>()
        for ((n, status) in listOf(409.0, 422.0, 500.0, 200.7, 0.0).withIndex()) {
            made[0].onPayment("p$n") { s, b -> heard.add(s to b) }
            val key = paid().last().second
            send("""{"action":"inboxAnswer","id":"a$n","answer":"$key","status":$status,"text":"Token already spent / \"twice\""}""")
        }
        val words = "{\"error\":\"Token already spent \\/ \\\"twice\\\"\"}"
        assertEquals(listOf(409 to words, 422 to words, 422 to words, 200 to "{\"ok\":true}", 422 to words), heard)
    }

    @Test
    fun theTextIsCutAt300CharactersAsAPersonCountsThem() {
        assertEquals("a".repeat(300), InboxAnswers.prefixCharacters("a".repeat(400), 300))
        assertEquals("short", InboxAnswers.prefixCharacters("short", 300))
        // a fox is one character, two UTF-16 units: never cut in half
        val foxes = "\uD83E\uDD8A".repeat(301)
        assertEquals("\uD83E\uDD8A".repeat(300), InboxAnswers.prefixCharacters(foxes, 300))
        // an e with its accent is one character
        assertEquals("e\u0301".repeat(2), InboxAnswers.prefixCharacters("e\u0301".repeat(3), 2))
    }

    @Test
    fun appleJsonEscapesAsJsonSerializationDoes() {
        assertEquals("\"a\\/b\"", InboxAnswers.appleJsonString("a/b"))
        assertEquals("\"\\\"\\\\\\n\\r\\t\\b\\f\\u0001\\u001f\"", InboxAnswers.appleJsonString("\"\\\n\r\t\b\u000C\u0001\u001f"))
        assertEquals("\"\u00e9\uD83E\uDD8A\u007f\"", InboxAnswers.appleJsonString("\u00e9\uD83E\uDD8A\u007f"))
        assertEquals("{\"ok\":true}", InboxAnswers.answerBody(200, "ignored"))
        assertEquals("{\"error\":\"\"}", InboxAnswers.answerBody(422, ""))
    }

    @Test
    fun anUnknownOrExpiredAnswerKeyIsRefused() {
        send("""{"action":"inboxAnswer","id":"a1","answer":"nope","status":200,"text":""}""")
        assertEquals(Triple("a1", null, InboxAnswers.NO_PAYMENT_WAITING), reply("a1"))
        send("""{"action":"inboxAnswer","id":"a2","status":200}""")
        assertEquals(Triple("a2", null, InboxAnswers.NO_PAYMENT_WAITING), reply("a2"))
        send("""{"action":"inboxOpen","id":"o1"}""")
        made[0].onPayment("p") { _, _ -> }
        val key = paid().single().second
        runTimers()
        send("""{"action":"inboxAnswer","id":"a3","answer":"$key","status":200,"text":""}""")
        assertEquals(Triple("a3", null, InboxAnswers.NO_PAYMENT_WAITING), reply("a3"))
    }

    // MARK: Put away, and back

    @Test
    fun putAwayEveryAddressEndsAndNoneIsMadeUntilFoxyIsBack() {
        bridge.torChanged()
        send("""{"action":"inboxOpen","id":"o1"}""")
        send("""{"action":"inboxClose","id":"c1","hold":60}""")
        // now: one retiring (the first spare, handed over), one new spare
        send("""{"action":"inboxOpen","id":"o2"}""")
        made[1].onPayment("p") { _, _ -> }
        val key = paid().single().second
        val before = made.toList()
        bridge.letGo()
        assertTrue("every inbox closed: ${before.map { it.note to it.closed }}", before.all { it.closed })
        // nothing comes back while away: not on a Tor change, not on the page's ask
        bridge.torChanged()
        send("""{"action":"inboxOpen","id":"o3"}""")
        assertEquals(Triple("o3", null, DeliveryBridge.NO_ONION), reply("o3"))
        assertEquals("nothing new was made while away", before.size, made.size)
        // the answers went with the addresses
        send("""{"action":"inboxAnswer","id":"a1","answer":"$key","status":200,"text":""}""")
        assertEquals(Triple("a1", null, InboxAnswers.NO_PAYMENT_WAITING), reply("a1"))
        // back: a spare warms, and the page gets a fresh address
        bridge.cameBack()
        assertEquals(before.size + 1, made.size)
        assertEquals("the spare", made.last().note)
        send("""{"action":"inboxOpen","id":"o4"}""")
        assertEquals(made[before.size].url, onion("o4"))
    }

    @Test
    fun aPageGoneLeavesNothingPublished() {
        bridge.torChanged()
        send("""{"action":"inboxOpen","id":"o1"}""")
        bridge.pageGone()
        assertTrue(made.all { it.closed })
    }

    // MARK: onionPost

    @Test
    fun onionPostAnswersStatusAndBodyAndPassesTheCircuitAndCeiling() {
        val url = "http://abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwd.onion/0123456789abcdef0123456789abcdef"
        send("""{"action":"onionPost","id":"p1","url":"$url","body":"{\"proofs\":[]}","timeout":28500,"circuit":"00112233445566778899aabbccddeeff"}""")
        assertEquals(Triple("p1", "200\n{\"ok\":true}", null), reply("p1"))
        assertEquals(listOf<Any>(url, "{\"proofs\":[]}", "00112233445566778899aabbccddeeff", 28_500L), posts.single())
        // an unlabelled post gets a circuit of its own
        send("""{"action":"onionPost","id":"p2","url":"$url","body":"x"}""")
        assertTrue(Regex("^[0-9a-f]{32}$").matches(posts[1][2] as String))
        assertEquals("no timeout given: 120 s", 120_000L, posts[1][3])
    }

    @Test
    fun theCeilingIsThreeTo120Seconds() {
        assertEquals(120_000L, DeliveryBridge.postCeilingMs(null))
        assertEquals(120_000L, DeliveryBridge.postCeilingMs(0.0))
        assertEquals(120_000L, DeliveryBridge.postCeilingMs(-1.0))
        assertEquals(3_000L, DeliveryBridge.postCeilingMs(1.0))
        assertEquals(30_000L, DeliveryBridge.postCeilingMs(30_000.0))
        assertEquals(120_000L, DeliveryBridge.postCeilingMs(1e12))
    }

    @Test
    fun onionPostRefusesNoUrlAnEmptyOrTooLargeBodyAndSaysThePostsFailure() {
        val url = "http://abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwd.onion/0123456789abcdef0123456789abcdef"
        send("""{"action":"onionPost","id":"p1","body":"x"}""")
        assertEquals(Triple("p1", null, "That is not an onion address."), reply("p1"))
        send("""{"action":"onionPost","id":"p2","url":"$url","body":""}""")
        assertEquals(Triple("p2", null, DeliveryBridge.TOO_LARGE), reply("p2"))
        send("""{"action":"onionPost","id":"p3","url":"$url","body":"${"x".repeat(256 * 1024 + 1)}"}""")
        assertEquals(Triple("p3", null, DeliveryBridge.TOO_LARGE), reply("p3"))
        assertTrue("nothing was posted", posts.isEmpty())
        for ((n, f) in listOf(OnionPost.Failure.NotOnion, OnionPost.Failure.NoTor, OnionPost.Failure.Unreachable(0xF0),
            OnionPost.Failure.RefusedBySocks, OnionPost.Failure.TimedOut, OnionPost.Failure.Broken).withIndex()) {
            postAnswer = OnionPost.Result.Failed(f)
            send("""{"action":"onionPost","id":"f$n","url":"$url","body":"x"}""")
            assertEquals(Triple("f$n", null, f.text), reply("f$n"))
        }
    }

    @Test
    fun withNothingWiredEveryOnionActionAnswersAsAPhoneWithoutTor() {
        val plain = FoxyBridge(host, { seeds }, Executor { it.run() }, isDebug = false)
        host.evaluated.clear()
        plain.onMessage("""{"action":"inboxOpen","id":"n1"}""", true, FoxyBridge.ORIGIN)
        plain.onMessage("""{"action":"inboxClose","id":"n2","hold":0}""", true, FoxyBridge.ORIGIN)
        plain.onMessage("""{"action":"onionPost","id":"n3","url":"http://x.onion/","body":"x"}""", true, FoxyBridge.ORIGIN)
        assertEquals(listOf(Triple("n1", null, DeliveryBridge.NO_ONION), Triple("n2", "ok", null), Triple("n3", null, "Foxy is not connected to Tor.")),
            replies())
    }
}
