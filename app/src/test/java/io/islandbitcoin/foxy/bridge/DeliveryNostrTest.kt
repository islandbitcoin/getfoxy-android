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
import io.islandbitcoin.foxy.network.Route
import io.islandbitcoin.foxy.nostr.Bech32
import io.islandbitcoin.foxy.nostr.FakeRelays
import io.islandbitcoin.foxy.nostr.NostrAddress
import io.islandbitcoin.foxy.nostr.NostrCrypto
import io.islandbitcoin.foxy.nostr.NostrDelivery
import io.islandbitcoin.foxy.nostr.NostrEvent
import io.islandbitcoin.foxy.nostr.NostrInbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * The Nostr half of FoxyBridge+Delivery.swift (ENG-677), through the bridge's
 * own `onMessage`: the `nostr` field of `inboxOpen`, the key kept while it
 * listens and replaced when it does not, a payment over Nostr pushed as
 * `_requestPaid(body, "")` with nothing waiting in [InboxAnswers], the key ended
 * by `inboxClose` and by Foxy being put away (and none made while it is), and
 * `nostrSend`'s answers and refusals, iOS's words.
 */
class DeliveryNostrTest {
    /** An onion inbox that opens at once, as DeliveryBridgeTest's do. */
    private class OpenInbox(override var note: String, n: Int) : Inbox {
        override val url: String = "http://" + "a".repeat(54) + ('a' + n) + "d.onion/" + "%032x".format(n)
        var closed = false
        override val isOpen: Boolean get() = !closed

        override fun open(done: (String?) -> Unit) = done(url)

        override fun close() {
            closed = true
        }
    }

    /** A Nostr key that listens when told. */
    inner class StubNostr(val onPayment: (String) -> Unit) : NostrAddress {
        val pubkey: ByteArray = NostrCrypto.publicKey(NostrCrypto.newKey())
        var opened: ((Boolean) -> Unit)? = null
        var listening = false
        var closed = false
        override val nprofile: String? get() = if (closed) null else Bech32.nprofile(pubkey, NostrInbox.RELAYS.take(4))
        override val isOpen: Boolean get() = !closed && listening

        override fun open(done: (Boolean) -> Unit) {
            opened = done
            if (autoListen) listen(true)
        }

        fun listen(ok: Boolean) {
            val done = opened ?: error("never opened")
            opened = null
            listening = ok
            done(ok)
        }

        override fun close() {
            closed = true
            listening = false
        }
    }

    private val inboxes = mutableListOf<OpenInbox>()
    private val keys = mutableListOf<StubNostr>()
    private var autoListen = true
    private val sends = mutableListOf<Pair<String, String>>()
    private var sendAnswer: NostrDelivery.Result =
        NostrDelivery.Result.Sent(NostrDelivery.Outcome(listOf("wss://a.example", "wss://b.example"), mapOf("wss://c.example" to "blocked: no")))

    private val env = DeliveryEnvironment(
        torRunning = { true },
        newInbox = { note, _: (String, InboxAnswer) -> Unit -> OpenInbox(note, inboxes.size).also { inboxes.add(it) } },
        post = { _, _, _, _, done -> done(OnionPost.Result.Failed(OnionPost.Failure.NoTor)) },
        after = { _, _ -> },
        newNostrInbox = { onPayment -> StubNostr(onPayment).also { keys.add(it) } },
        nostrSend = { payload, target, done ->
            sends.add(payload to target)
            done(sendAnswer)
        },
    )

    private val host = FakeHost()
    private val dir = TestSupport.tempDir("delivery-nostr")
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

    @Suppress("UNCHECKED_CAST")
    private fun opened(id: String): Map<String, String> = Json.parse(reply(id).second!!) as Map<String, String>

    private fun paid(): List<List<String>> = host.evaluated.filter { it.contains("._requestPaid(") }.map { js ->
        @Suppress("UNCHECKED_CAST")
        Json.parse("[" + js.substringAfterLast("._requestPaid(").removeSuffix(")") + "]") as List<String>
    }

    // MARK: inboxOpen's nostr

    @Test
    fun inboxOpenAnswersTheOnionAndANostrKeyMadeForTheRequest() {
        send("""{"action":"inboxOpen","id":"o1"}""")
        val got = opened("o1")
        assertEquals(setOf("onion", "nostr"), got.keys)
        assertEquals(inboxes[0].url, got["onion"])
        assertEquals(keys[0].nprofile, got["nostr"])
        val read = Bech32.nprofile(got["nostr"]!!)!!
        assertTrue(read.pubkey.contentEquals(keys[0].pubkey))
        assertEquals(NostrDelivery.FALLBACK_RELAYS.take(4), read.relays)
        // the exact JSON: onion first, then nostr, as iOS joins them
        assertEquals("{\"onion\":\"${inboxes[0].url}\",\"nostr\":\"${keys[0].nprofile}\"}", reply("o1").second)
    }

    @Test
    fun theKeyIsKeptWhileItListensAndReplacedWhenItDoesNot() {
        send("""{"action":"inboxOpen","id":"o1"}""")
        send("""{"action":"inboxOpen","id":"o2"}""")
        assertEquals("the same key while it listens", opened("o1")["nostr"], opened("o2")["nostr"])
        assertEquals(1, keys.size)
        // its relays stopped listening: a fresh key, the old one closed
        keys[0].listening = false
        send("""{"action":"inboxOpen","id":"o3"}""")
        assertEquals(2, keys.size)
        assertTrue(keys[0].closed)
        assertNotEquals(opened("o1")["nostr"], opened("o3")["nostr"])
        assertEquals(keys[1].nprofile, opened("o3")["nostr"])
    }

    @Test
    fun noRelayWouldListenAndTheRequestGoesWithTheOnionAlone() {
        autoListen = false
        send("""{"action":"inboxOpen","id":"o1"}""")
        assertTrue("not answered until the relays have", replies().none { it.first == "o1" })
        keys[0].listen(false)
        assertEquals(mapOf("onion" to inboxes[0].url), opened("o1"))
        assertTrue("a key nobody listens on is thrown away", keys[0].closed)
        // and the next ask makes another rather than naming it
        autoListen = true
        send("""{"action":"inboxOpen","id":"o2"}""")
        assertEquals(2, keys.size)
        assertEquals(keys[1].nprofile, opened("o2")["nostr"])
    }

    @Test
    fun aKeyReplacedBeforeItListenedIsClosedAndNotNamed() {
        autoListen = false
        send("""{"action":"inboxOpen","id":"o1"}""")
        // the screen asks again before the first key's relays answered: a second key is made
        send("""{"action":"inboxOpen","id":"o2"}""")
        assertEquals(2, keys.size)
        assertTrue(keys[0].closed)
        keys[0].listen(true)
        assertEquals("the first request goes with its onion alone", mapOf("onion" to inboxes[0].url), opened("o1"))
        keys[1].listen(true)
        assertEquals(keys[1].nprofile, opened("o2")["nostr"])
    }

    @Test
    fun aKeyThatCannotBeMadeLeavesTheOnionAlone() {
        val failing = DeliveryEnvironment(
            torRunning = { true },
            newInbox = { note, _ -> OpenInbox(note, 0) },
            post = { _, _, _, _, _ -> },
            after = { _, _ -> },
            newNostrInbox = { throw NostrCrypto.Failure(NostrCrypto.Failure.Kind.PLATFORM) },
        )
        val b = FoxyBridge(host, { seeds }, Executor { it.run() }, isDebug = false, deliveryEnvironment = failing)
        b.onMessage("""{"action":"inboxOpen","id":"f1"}""", true, FoxyBridge.ORIGIN)
        assertEquals(setOf("onion"), opened("f1").keys)
    }

    // MARK: a payment over Nostr

    @Test
    fun aNostrPaymentIsPushedWithAnEmptyAnswerIdAndNothingWaitsOnIt() {
        send("""{"action":"inboxOpen","id":"o1"}""")
        val payment = """{"id":"74134073","mint":"https://mint.test/Bitcoin","unit":"sat","proofs":[]}"""
        keys[0].onPayment(payment)
        assertEquals(listOf(listOf(payment, "")), paid())
        assertTrue("the exact push", host.evaluated.any {
            it == "window.FoxyWallet && window.FoxyWallet._requestPaid && window.FoxyWallet._requestPaid(" + Json.quote(payment) + ", \"\")"
        })
        // the page answers it as it answers every payment; nobody is waiting, as on iOS
        send("""{"action":"inboxAnswer","id":"a1","answer":"","status":200,"text":""}""")
        assertEquals(Triple("a1", null, InboxAnswers.NO_PAYMENT_WAITING), reply("a1"))
    }

    /** The whole receiving path with the real inbox: a wrap from a relay reaches the page. */
    @Test
    fun aRealInboxCarriesAWrapFromARelayToThePage() {
        val relays = FakeRelays()
        var inbox: NostrInbox? = null
        val real = DeliveryEnvironment(
            torRunning = { true },
            newInbox = { note, _ -> OpenInbox(note, 0) },
            post = { _, _, _, _, _ -> },
            after = { _, _ -> },
            newNostrInbox = { onPayment -> NostrInbox(onPayment, relays.connect, relays.main).also { inbox = it } },
        )
        val b = FoxyBridge(host, { seeds }, Executor { it.run() }, isDebug = false, deliveryEnvironment = real)
        b.onMessage("""{"action":"inboxOpen","id":"r1"}""", true, FoxyBridge.ORIGIN)
        assertTrue("waiting on the relays", replies().none { it.first == "r1" })
        relays.sockets[0].listener.opened()
        val nprofile = opened("r1")["nostr"]!!
        val pubkey = Bech32.nprofile(nprofile)!!.pubkey
        // a payer wraps a payment for the key the request named, and a relay hands it over
        val payment = """{"id":"8c0e1f22","mint":"https://mint.test","unit":"sat","proofs":[]}"""
        val line = "[\"EVENT\",\"${inbox!!.subscription}\"," + NostrEvent.json(NostrEvent.giftWrapped(payment, pubkey)) + "]"
        relays.sockets[0].listener.text(line)
        relays.sockets[3].listener.text(line)
        assertEquals("once, though two relays sent it", listOf(listOf(payment, "")), paid())
        // the request ends: the key and every socket with it
        b.onMessage("""{"action":"inboxClose","id":"r2","hold":0}""", true, FoxyBridge.ORIGIN)
        assertTrue(relays.sockets.all { it.closed })
        assertNull(inbox!!.nprofile)
    }

    // MARK: the key's end

    @Test
    fun inboxCloseEndsTheKeyEvenWithAHold() {
        send("""{"action":"inboxOpen","id":"o1"}""")
        send("""{"action":"inboxClose","id":"c1","hold":30}""")
        assertTrue("the key dies with the request it was made for", keys[0].closed)
        assertEquals(Triple("c1", "ok", null), reply("c1"))
    }

    @Test
    fun putAwayTheKeyEndsAndNoneIsMadeUntilFoxyIsBack() {
        send("""{"action":"inboxOpen","id":"o1"}""")
        bridge.letGo()
        assertTrue(keys[0].closed)
        send("""{"action":"inboxOpen","id":"o2"}""")
        assertEquals(Triple("o2", null, DeliveryBridge.NO_ONION), reply("o2"))
        assertEquals("nothing made while put away", 1, keys.size)
        bridge.cameBack()
        send("""{"action":"inboxOpen","id":"o3"}""")
        assertEquals(2, keys.size)
        assertEquals(keys[1].nprofile, opened("o3")["nostr"])
    }

    @Test
    fun aKeyThatListensOnlyAfterFoxyWasPutAwayIsClosedAndNotNamed() {
        autoListen = false
        send("""{"action":"inboxOpen","id":"o1"}""")
        bridge.letGo()
        assertTrue(keys[0].closed)
        keys[0].listen(true)
        assertTrue(keys[0].closed)
        assertEquals(setOf("onion"), opened("o1").keys)
    }

    @Test
    fun aPageGoneEndsTheKey() {
        send("""{"action":"inboxOpen","id":"o1"}""")
        bridge.pageGone()
        assertTrue(keys[0].closed)
    }

    // MARK: nostrSend

    @Test
    fun nostrSendAnswersHowManyRelaysTookIt() {
        send("""{"action":"nostrSend","id":"s1","target":"nprofile1abc","body":"{\"id\":\"x\"}"}""")
        assertEquals(listOf("{\"id\":\"x\"}" to "nprofile1abc"), sends)
        assertEquals(Triple("s1", "2", null), reply("s1"))
    }

    @Test
    fun nostrSendWhenNoRelayTookItSaysWhy() {
        sendAnswer = NostrDelivery.Result.Sent(NostrDelivery.Outcome(emptyList(), linkedMapOf("wss://a.example" to "blocked: no", "wss://b.example" to "no answer in time")))
        send("""{"action":"nostrSend","id":"s1","target":"nprofile1abc","body":"x"}""")
        assertEquals(Triple("s1", null, "No relay took the payment: blocked: no"), reply("s1"))
        sendAnswer = NostrDelivery.Result.Sent(NostrDelivery.Outcome(emptyList(), emptyMap()))
        send("""{"action":"nostrSend","id":"s2","target":"nprofile1abc","body":"x"}""")
        assertEquals(Triple("s2", null, "No relay took the payment: no relay took it"), reply("s2"))
    }

    @Test
    fun nostrSendThatCouldNotWrapIsIosWords() {
        sendAnswer = NostrDelivery.Result.Failed(NostrCrypto.Failure(NostrCrypto.Failure.Kind.BAD_PEER))
        send("""{"action":"nostrSend","id":"s1","target":"nprofile1abc","body":"x"}""")
        assertEquals(Triple("s1", null, "The payment could not be sent over Nostr."), reply("s1"))
    }

    @Test
    fun nostrSendRefusesNoTargetAndAnEmptyOrTooLargeBody() {
        send("""{"action":"nostrSend","id":"s1","body":"x"}""")
        send("""{"action":"nostrSend","id":"s2","target":"","body":"x"}""")
        send("""{"action":"nostrSend","id":"s3","target":5,"body":"x"}""")
        send("""{"action":"nostrSend","id":"s4","target":"nprofile1abc"}""")
        send("""{"action":"nostrSend","id":"s5","target":"nprofile1abc","body":""}""")
        // half of NIP-44's largest message, in UTF-8 bytes: 32767 goes, 32768 does not, and a two-byte character counts twice
        send("""{"action":"nostrSend","id":"s6","target":"nprofile1abc","body":"${"a".repeat(32768)}"}""")
        send("""{"action":"nostrSend","id":"s7","target":"nprofile1abc","body":"${"é".repeat(16384)}"}""")
        send("""{"action":"nostrSend","id":"s8","target":"nprofile1abc","body":"${"a".repeat(32767)}"}""")
        assertEquals(DeliveryBridge.NO_NOSTR_ADDRESS, reply("s1").third)
        assertEquals(DeliveryBridge.NO_NOSTR_ADDRESS, reply("s2").third)
        assertEquals(DeliveryBridge.NO_NOSTR_ADDRESS, reply("s3").third)
        assertEquals(DeliveryBridge.TOO_LARGE_FOR_NOSTR, reply("s4").third)
        assertEquals(DeliveryBridge.TOO_LARGE_FOR_NOSTR, reply("s5").third)
        assertEquals(DeliveryBridge.TOO_LARGE_FOR_NOSTR, reply("s6").third)
        assertEquals(DeliveryBridge.TOO_LARGE_FOR_NOSTR, reply("s7").third)
        assertEquals(Triple("s8", "2", null), reply("s8"))
        assertEquals("only the last one was sent", 1, sends.size)
    }

    @Test
    fun withNothingWiredNostrSendReachesNoRelayAndInboxOpenHasNoKey() {
        val plain = FoxyBridge(host, { seeds }, Executor { it.run() }, isDebug = false)
        host.evaluated.clear()
        val target = Bech32.nprofile(NostrCrypto.publicKey(NostrCrypto.newKey()), listOf("wss://relay.example"))!!
        plain.onMessage("""{"action":"nostrSend","id":"n1","target":"$target","body":"{}"}""", true, FoxyBridge.ORIGIN)
        plain.onMessage("""{"action":"nostrSend","id":"n2","target":"nprofile1bent","body":"{}"}""", true, FoxyBridge.ORIGIN)
        assertEquals(Triple("n1", null, "No relay took the payment: " + Route.REFUSAL), reply("n1"))
        assertEquals(Triple("n2", null, DeliveryBridge.NOSTR_FAILED), reply("n2"))
    }
}
