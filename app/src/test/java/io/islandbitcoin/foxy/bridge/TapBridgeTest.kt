package io.islandbitcoin.foxy.bridge

import io.islandbitcoin.foxy.FakeHost
import io.islandbitcoin.foxy.TestSupport
import io.islandbitcoin.foxy.keychain.CounterStore
import io.islandbitcoin.foxy.keychain.Flags
import io.islandbitcoin.foxy.keychain.SeedCandidates
import io.islandbitcoin.foxy.keychain.SeedEnvironment
import io.islandbitcoin.foxy.keychain.SeedVault
import io.islandbitcoin.foxy.tap.FakeBluetooth
import io.islandbitcoin.foxy.tap.FakeCentral
import io.islandbitcoin.foxy.tap.TapAir
import io.islandbitcoin.foxy.tap.TapBluetooth
import io.islandbitcoin.foxy.tap.TapPayerLink
import io.islandbitcoin.foxy.tap.TapReceiverLink
import io.islandbitcoin.foxy.tap.TapStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * ENG-670: tap to pay's receiving side through the bridge (FoxyBridge+Tap.swift
 * at 8df0bb1), with Bluetooth faked at the GATT level and a real
 * [TapPayerLink] paying over it: the first-install question and its answers,
 * TAP on the air, the stages pushed as iOS's `pushTap` writes them, a payment
 * handed to the page as `_requestPaid(body, key, "tap")` and answered through
 * `inboxAnswer` into M6, change and its late word, M9 and M11, a receiver that
 * is still carrying money, shake to TAP, `_turned`, and nothing on the air
 * while Foxy is away.
 */
class TapBridgeTest {
    private val dir = TestSupport.tempDir("tap-bridge")
    private val env = SeedEnvironment(
        savedSeed = { SeedVault.SecretsSeed.Absent }, seedPresent = { false }, write = { null },
        counters = CounterStore(dir), candidates = SeedCandidates(), wordlist = { TestSupport.wordlist },
        generate = { TestSupport.MADE }, migrationWindowOpen = { false }, closeMigrationWindow = {}, flags = Flags(dir),
    )
    private val bt = FakeBluetooth()
    private val host = FakeHost().apply { tapBluetooth = bt }
    private val bridge = FoxyBridge(host, { env }, Executor { it.run() }, isDebug = false)

    private fun send(json: String) = bridge.onMessage(json, true, FoxyBridge.ORIGIN)

    private fun settle() {
        while (true) {
            if (bt.pending.isNotEmpty()) {
                bt.pending.removeFirst()()
                continue
            }
            if (!bt.clock.runDue()) return
        }
    }

    private fun advance(seconds: Double) = bt.clock.advance(seconds) { settle() }

    private fun reply(id: String, text: String?, error: String? = null) = FoxyBridge.replyScript(id, text, error)

    private fun tap(stage: String, extra: String = "") =
        "window.FoxyWallet && window.FoxyWallet._tap && window.FoxyWallet._tap({\"side\":\"receive\",\"stage\":\"$stage\"$extra})"

    private val taps: List<String> get() = host.evaluated.filter { it.contains("._tap(") }

    /** The payer's phone, over the receiver the bridge put on the air. */
    private inner class Payer(mtu: Int = 185) {
        val stages = ArrayList<TapStage>()
        var offer: Pair<String, String>? = null
        val changes = ArrayList<String>()
        var keep: (String, (Boolean) -> Unit) -> Unit = { _, k -> k(true) }
        val central = FakeCentral("payer-1", bt, mtu)
        val link = TapPayerLink(
            bt.peripheral.layout!!.service, central, bt.clock,
            onStage = { stages.add(it) },
            done = { text, code -> offer = text to code },
            onChange = { text, k ->
                changes.add(text)
                keep(text, k)
            },
            diary = {},
        ).also { central.payer = it }

        fun linkUp() {
            central.connect()
            settle()
            link.connecting()
            link.speak()
            settle()
        }

        fun pay(body: String): Array<TapPayerLink.Handover?> {
            val how = arrayOfNulls<TapPayerLink.Handover>(1)
            link.send(body) { how[0] = it }
            settle()
            return how
        }
    }

    private fun onTheAir(payload: String = Json.write(mapOf("v" to 2, "req" to "creqAabcdefghijklmnopqrstuv"))) {
        send("""{"action":"tapReceiveStart","id":"t1","payload":${Json.quote(payload)},"early":false}""")
        settle()
    }

    /** The key the page was handed with the payment. */
    private fun paidKey(): String {
        val js = host.evaluated.last { it.contains("._requestPaid(") }
        val args = Json.parse("[" + js.substringAfter("._requestPaid(").removeSuffix(")") + "]") as List<*>
        assertEquals("tap", args[2])
        return args[1] as String
    }

    // MARK: the first-install question

    @Test
    fun approveAsksForTheTwoPermissionsAndAnswersAllowedPushedFirst() {
        bt.granted = false
        send("""{"id":"bt-1","action":"bluetoothAsk"}""")
        assertEquals("the system's own question", 1, bt.asks)
        assertEquals(listOf(PlatformBridge.bluetoothAnswer("allowed"), reply("bt-1", "allowed")), host.evaluated.toList())
        assertTrue("no scan and no advertisement: no GATT server was opened", bt.opened.isEmpty())
    }

    @Test
    fun aRefusalIsRememberedAndTheNextAskIsDeniedWithNoSecondQuestion() {
        bt.granted = false
        bt.grantOnAsk = false
        send("""{"id":"bt-1","action":"bluetoothAsk"}""")
        assertEquals(1, bt.asks)
        assertEquals(reply("bt-1", "denied"), host.evaluated.last())
        host.evaluated.clear()
        send("""{"id":"bt-2","action":"bluetoothAsk"}""")
        assertEquals("iOS reads `.unauthorized`: no dialog the second time", 1, bt.asks)
        assertEquals(listOf(PlatformBridge.bluetoothAnswer("denied"), reply("bt-2", "denied")), host.evaluated.toList())
        // and a TAP after it is denied without a dialog too
        host.evaluated.clear()
        send("""{"action":"tapReceiveStart","id":"t1","payload":"","early":false}""")
        settle()
        assertEquals(1, bt.asks)
        assertEquals(listOf(tap("denied")), taps)
    }

    @Test
    fun eachStateIsAnsweredAsTheContractSays() {
        val cases = listOf<Pair<(FakeBluetooth) -> Unit, String>>(
            { b: FakeBluetooth -> b.granted = false; b.grantOnAsk = false } to "denied",
            { b: FakeBluetooth -> b.powerNow = TapBluetooth.Power.OFF } to "off",
            { b: FakeBluetooth -> b.adapter = false } to "unsupported",
            { b: FakeBluetooth -> b.advertises = false; b.granted = false } to "unsupported",
        )
        for ((setup, want) in cases) {
            val b = FakeBluetooth()
            setup(b)
            val h = FakeHost().apply { tapBluetooth = b }
            FoxyBridge(h, { env }, Executor { it.run() }, isDebug = false).onMessage("""{"id":"q","action":"bluetoothAsk"}""", true, FoxyBridge.ORIGIN)
            assertEquals(want, listOf(PlatformBridge.bluetoothAnswer(want), reply("q", want)), h.evaluated.toList())
            if (want == "unsupported") assertEquals("nothing asked of a phone that could not use it", 0, b.asks)
        }
    }

    @Test
    fun aRadioTurningOnIsWaitedForAndAMinuteWithNoStateIsUnknown() {
        bt.powerNow = TapBluetooth.Power.TURNING
        send("""{"id":"w1","action":"bluetoothAsk"}""")
        assertTrue("not an answer yet", host.evaluated.isEmpty())
        bt.setPower(TapBluetooth.Power.ON)
        assertEquals(listOf(PlatformBridge.bluetoothAnswer("allowed"), reply("w1", "allowed")), host.evaluated.toList())
        assertEquals("the watch is let go once answered", 0, bt.watching)
        // the question left on screen for a minute
        host.evaluated.clear()
        bt.granted = false
        bt.grantOnAsk = null
        send("""{"id":"w2","action":"bluetoothAsk"}""")
        advance(59.9)
        assertTrue(host.evaluated.isEmpty())
        advance(0.2)
        assertEquals(listOf(PlatformBridge.bluetoothAnswer("unknown"), reply("w2", "unknown")), host.evaluated.toList())
        // and an answer after that changes nothing
        bt.answer(true)
        assertEquals(2, host.evaluated.size)
    }

    // MARK: TAP, a payment, and the page's answer

    @Test
    fun tapGoesOnTheAirAfterTheReplyAndAPaymentIsThePagesToAnswer() {
        onTheAir()
        assertEquals(reply("t1", "ok"), host.evaluated.first())
        assertEquals(listOf(tap("searching")), taps)
        assertTrue(bt.peripheral.onAir)
        val payer = Payer()
        payer.linkUp()
        val code = payer.offer?.second ?: error("no offer")
        assertEquals(listOf(tap("searching"), tap("connecting"), tap("code", ",\"code\":\"$code\"")), taps)
        assertFalse("off the air with a payer", bt.peripheral.onAir)

        val how = payer.pay("{\"proofs\":[{\"amount\":21}]}")
        val key = paidKey()
        assertEquals("a fresh 32-hex key", 32, key.length)
        assertEquals(
            TapBridge.requestPaid("{\"proofs\":[{\"amount\":21}]}", key),
            host.evaluated.last { it.contains("._requestPaid(") },
        )
        assertNull("nothing goes back until the page answers", how[0])
        host.evaluated.clear()
        send("""{"action":"inboxAnswer","id":"a1","answer":"$key","status":200,"text":"kept"}""")
        settle()
        assertEquals(listOf(reply("a1", "ok")), host.evaluated.toList())
        val m6 = (how[0] as TapPayerLink.Handover.Answered).text
        assertEquals("{\"v\":2,\"ok\":true,\"code\":200}", m6)
        // the key is answered once
        host.evaluated.clear()
        send("""{"action":"inboxAnswer","id":"a2","answer":"$key","status":200,"text":""}""")
        assertEquals(listOf(reply("a2", null, TapBridge.NO_PAYMENT_WAITING)), host.evaluated.toList())
    }

    @Test
    fun twoTapsQueuedBeforeTheFirstIsMadeMakeOneReceiverWithTheLatest() {
        // the amount screen arms early with nothing to offer, the invoice lands before the first post has run
        host.holdPosts = true
        val invoice = Json.write(mapOf("v" to 2, "req" to "creqAabcdefghijklmnopqrstuv"))
        send("""{"action":"tapReceiveStart","id":"t1","payload":"","early":true}""")
        send("""{"action":"tapReceiveStart","id":"t2","payload":${Json.quote(invoice)},"early":false}""")
        host.runPosts()
        host.holdPosts = false
        settle()
        assertEquals("one receiver", 1, bt.opened.size)
        assertEquals(listOf(reply("t1", "ok"), reply("t2", "ok")), host.evaluated.filter { it.contains("t1") || it.contains("t2") })
        val payer = Payer()
        payer.linkUp()
        val (text, code) = payer.offer ?: error("the payer was refused: the receiver was made early")
        assertEquals("M4 carries the invoice, not the empty early offer", invoice, text)
        assertEquals(tap("code", ",\"code\":\"$code\""), taps.last())
    }

    @Test
    fun theAnswersStatusIsClampedAndItsTextCutAsIosCutsIt() {
        for ((asked, want) in listOf(409 to 409, 422 to 422, 500 to 422, 201 to 422)) {
            val b = FakeBluetooth()
            val h = FakeHost().apply { tapBluetooth = b }
            val fb = FoxyBridge(h, { env }, Executor { it.run() }, isDebug = false)
            fb.onMessage("""{"action":"tapReceiveStart","id":"t","payload":${Json.quote(TapAir.OFFER)}}""", true, FoxyBridge.ORIGIN)
            while (b.pending.isNotEmpty() || b.clock.runDue()) if (b.pending.isNotEmpty()) b.pending.removeFirst()()
            val central = FakeCentral("p", b, 185)
            val how = arrayOfNulls<TapPayerLink.Handover>(1)
            val payer = TapPayerLink(b.peripheral.layout!!.service, central, b.clock, onStage = {}, done = { _, _ -> }, diary = {}).also { central.payer = it }
            central.connect()
            payer.connecting()
            payer.speak()
            while (b.pending.isNotEmpty() || b.clock.runDue()) if (b.pending.isNotEmpty()) b.pending.removeFirst()()
            payer.send("{}") { how[0] = it }
            while (b.pending.isNotEmpty() || b.clock.runDue()) if (b.pending.isNotEmpty()) b.pending.removeFirst()()
            val js = h.evaluated.last { it.contains("._requestPaid(") }
            val key = (Json.parse("[" + js.substringAfter("._requestPaid(").removeSuffix(")") + "]") as List<*>)[1] as String
            val why = "a".repeat(400)
            fb.onMessage("""{"action":"inboxAnswer","id":"a","answer":"$key","status":$asked,"text":"$why"}""", true, FoxyBridge.ORIGIN)
            while (b.pending.isNotEmpty() || b.clock.runDue()) if (b.pending.isNotEmpty()) b.pending.removeFirst()()
            @Suppress("UNCHECKED_CAST")
            val m6 = Json.parse((how[0] as TapPayerLink.Handover.Answered).text) as Map<String, Any?>
            assertEquals("$asked", want.toDouble(), m6["code"])
            // 300 characters into inboxAnswer, then 160 into M6 (TapReceiverLink.MOST_WHY)
            assertEquals(TapReceiverLink.MOST_WHY, (m6["why"] as String).length)
        }
        // characters as a person reads them, not UTF-16 units or bytes
        assertEquals("é".repeat(300), TapBridge.prefix("é".repeat(400), TapBridge.MOST_ANSWER_TEXT))
        assertEquals("😀".repeat(300), TapBridge.prefix("😀".repeat(301), TapBridge.MOST_ANSWER_TEXT))
    }

    @Test
    fun anAnswerTheReceiverGaveUpOnFindsNothing() {
        onTheAir()
        val payer = Payer()
        payer.linkUp()
        val how = payer.pay("{}")
        val key = paidKey()
        advance(TapReceiverLink.RESULT_WAIT + 0.1)
        assertEquals("504", "{\"v\":2,\"ok\":false,\"code\":504,\"why\":\"Their wallet did not answer in time.\"}",
            (how[0] as TapPayerLink.Handover.Answered).text)
        advance(5.0)
        host.evaluated.clear()
        send("""{"action":"inboxAnswer","id":"late","answer":"$key","status":200,"text":""}""")
        assertEquals(listOf(reply("late", null, TapBridge.NO_PAYMENT_WAITING)), host.evaluated.toList())
    }

    // MARK: change, M9, M11

    @Test
    fun changeIsAnsweredWhenThePayerKeepsItAndLateAfterSevenSeconds() {
        onTheAir()
        val payer = Payer()
        payer.linkUp()
        send("""{"action":"tapChangeDue","id":"d"}""")
        payer.pay("{}")
        send("""{"action":"inboxAnswer","id":"a","answer":"${paidKey()}","status":200,"text":""}""")
        settle()
        host.evaluated.clear()
        send("""{"action":"tapChange","id":"c1","body":"cashuBchange"}""")
        settle()
        assertEquals(listOf("cashuBchange"), payer.changes)
        assertEquals(listOf(reply("c1", "ok")), host.evaluated.toList())

        // a payer slower than seven seconds: not handed over, then late
        val late = TapBridgeTest()
        late.onTheAir()
        val p2 = late.Payer()
        var keepLater: ((Boolean) -> Unit)? = null
        p2.keep = { _, k -> keepLater = k }
        p2.linkUp()
        late.send("""{"action":"tapChangeDue","id":"d"}""")
        p2.pay("{}")
        late.send("""{"action":"inboxAnswer","id":"a","answer":"${late.paidKey()}","status":200,"text":""}""")
        late.settle()
        late.host.evaluated.clear()
        late.send("""{"action":"tapChange","id":"c2","body":"cashuBchange"}""")
        late.settle()
        late.advance(7.1)
        assertEquals(listOf(reply("c2", null, TapBridge.CHANGE_NOT_KEPT)), late.host.evaluated.toList())
        keepLater!!(true)
        late.settle()
        assertEquals(PlatformBridge.pushNoArgument("_tapChangeKeptLate"), late.host.evaluated.last())
    }

    @Test
    fun withNoLinkTheReceivingSidesWordsAreRefusedAsIosRefusesThem() {
        send("""{"action":"tapChange","id":"c0","body":""}""")
        send("""{"action":"tapChange","id":"c1","body":"cashuBx"}""")
        send("""{"action":"tapAsking","id":"k"}""")
        send("""{"action":"tapTerms","id":"m","body":"{}"}""")
        send("""{"action":"tapChangeDue","id":"d"}""")
        assertEquals(
            listOf(reply("c0", null, TapBridge.NO_CHANGE), reply("c1", null, TapBridge.NO_LINK), reply("k", "no link"),
                reply("m", null, TapBridge.NO_LINK), reply("d", "ok")),
            host.evaluated.toList(),
        )
        // on the air with no payer is no link either: nothing is sealed for nobody
        onTheAir()
        host.evaluated.clear()
        send("""{"action":"tapChange","id":"c2","body":"cashuBx"}""")
        assertEquals(listOf(reply("c2", null, TapBridge.NO_LINK)), host.evaluated.toList())
    }

    @Test
    fun askingAndTermsGoToThePayerOverTheLink() {
        onTheAir()
        val payer = Payer()
        payer.linkUp()
        host.evaluated.clear()
        send("""{"action":"tapAsking","id":"k"}""")
        send("""{"action":"tapTerms","id":"m","body":"{\"v\":2}"}""")
        settle()
        assertEquals(listOf(reply("k", "ok"), reply("m", "ok")), host.evaluated.toList())
        assertTrue(TapStage.ASKING in payer.stages)
    }

    @Test
    fun aQuoteFromThePayerIsPushedToThePage() {
        onTheAir()
        val payer = Payer()
        payer.linkUp()
        assertTrue(payer.link.sendQuote("{\"price\":1}"))
        settle()
        assertEquals(PlatformBridge.push("_tapQuote", "{\"price\":1}"), host.evaluated.last())
    }

    // MARK: TAP pressed again

    @Test
    fun tapAgainWhileAPaymentCrossesIsRefusedAndWhileThePayerDecidesKeepsTheirOffer() {
        onTheAir()
        val payer = Payer()
        payer.linkUp()
        val service = bt.peripheral.layout!!.service
        // the payer holds the offer and has not paid: the screen's new offer waits, the link stays
        host.evaluated.clear()
        send("""{"action":"tapReceiveStart","id":"t2","payload":${Json.quote(TapAir.OFFER)}}""")
        settle()
        assertEquals(listOf(reply("t2", "ok")), host.evaluated.toList())
        assertEquals(service, bt.peripheral.layout!!.service)
        // a payment crossing: refused with iOS's words
        payer.pay("{}")
        val key = paidKey()
        host.evaluated.clear()
        send("""{"action":"tapReceiveStart","id":"t3","payload":""}""")
        assertEquals(listOf(reply("t3", null, TapBridge.STILL_GOING)), host.evaluated.toList())
        // answered: the next press is a new receiver, a new service UUID on the air
        send("""{"action":"inboxAnswer","id":"a","answer":"$key","status":200,"text":""}""")
        settle()
        host.evaluated.clear()
        send("""{"action":"tapReceiveStart","id":"t4","payload":""}""")
        settle()
        assertEquals(reply("t4", "ok"), host.evaluated.first())
        assertEquals(tap("searching"), taps.last())
        assertNotEquals(service, bt.peripheral.layout!!.service)
        assertTrue(bt.opened.first().closed)
    }

    @Test
    fun stopHoldsAReceiverCarryingMoneyAndLetsGoOfOneThatIsNot() {
        onTheAir()
        send("""{"action":"tapReceiveStop","id":"s1"}""")
        assertTrue(bt.peripheral.closed)
        assertEquals(reply("s1", "ok"), host.evaluated.last())

        onTheAir()
        val payer = Payer()
        payer.linkUp()
        payer.pay("{}")
        send("""{"action":"tapReceiveStop","id":"s2"}""")
        assertEquals(reply("s2", "ok"), host.evaluated.last())
        assertFalse("held while the payment is answered", bt.peripheral.closed)
        // and the page's answer still reaches the payer
        send("""{"action":"inboxAnswer","id":"a","answer":"${paidKey()}","status":200,"text":""}""")
        settle()
        assertEquals(reply("a", "ok"), host.evaluated.last())
        advance(TapReceiverLink.AFTER_RESULT + 0.1)
        assertTrue(bt.peripheral.closed)
    }

    // MARK: away and back, the page gone

    @Test
    fun nothingIsOnTheAirWhileFoxyIsAwayAndANewReceiverGoesUpOnItsReturn() {
        onTheAir()
        val first = bt.peripheral.layout!!.service
        bridge.tapAway()
        assertTrue("let go: off the air and the GATT server closed", bt.peripheral.closed)
        assertFalse(bt.peripheral.onAir)
        host.evaluated.clear()
        bridge.tapBack()
        settle()
        assertEquals("a new receiver, a new service", 2, bt.opened.size)
        assertNotEquals(first, bt.peripheral.layout!!.service)
        assertTrue(bt.peripheral.onAir)
        assertEquals(listOf(tap("searching")), taps)
        // a stop while away means nothing comes back
        bridge.tapAway()
        send("""{"action":"tapReceiveStop","id":"s"}""")
        bridge.tapBack()
        settle()
        assertEquals(2, bt.opened.size)
    }

    @Test
    fun aPaymentCrossingAsFoxyGoesAwayKeepsItsLinkOffTheAir() {
        onTheAir()
        val payer = Payer()
        payer.linkUp()
        payer.pay("{}")
        bridge.tapAway()
        assertFalse(bt.peripheral.closed)
        assertFalse(bt.peripheral.onAir)
        send("""{"action":"inboxAnswer","id":"a","answer":"${paidKey()}","status":200,"text":""}""")
        settle()
        assertEquals(reply("a", "ok"), host.evaluated.last())
        bridge.tapBack()
        settle()
        assertEquals("the same receiver: it has had its payer, and is not put back on the air", 1, bt.opened.size)
        assertFalse(bt.peripheral.onAir)
    }

    @Test
    fun aPayerHoldingTheOfferAsFoxyGoesAwayKeepsTheirLinkAndIsAnsweredOnItsReturn() {
        // iOS lets tapReceiver go only on TAP again, stop and the page going (FoxyBridge+Tap.swift 73, 159-161, 320-328)
        onTheAir()
        val payer = Payer()
        payer.linkUp()
        assertNotNull("the payer holds M4", payer.offer)
        bridge.tapAway()
        assertFalse("the payer's link is kept", bt.peripheral.closed)
        assertFalse("but nothing is on the air", bt.peripheral.onAir)
        assertTrue(payer.central.isConnected)
        bridge.tapBack()
        settle()
        assertEquals("the same receiver", 1, bt.opened.size)
        assertTrue(payer.central.isConnected)
        val how = payer.pay("{\"proofs\":[{\"amount\":21}]}")
        send("""{"action":"inboxAnswer","id":"a","answer":"${paidKey()}","status":200,"text":""}""")
        settle()
        assertEquals(reply("a", "ok"), host.evaluated.last())
        assertEquals("{\"v\":2,\"ok\":true,\"code\":200}", (how[0] as TapPayerLink.Handover.Answered).text)
    }

    @Test
    fun thePageGoingStopsEverythingAtOnce() {
        onTheAir()
        val payer = Payer()
        payer.linkUp()
        payer.pay("{}")
        send("""{"action":"shakeStart","id":"sh"}""")
        val key = paidKey()
        bridge.pageGone()
        assertTrue("not waited for: nothing is left to write it down", bt.peripheral.closed)
        assertEquals(listOf(true, false), host.shakeSenses.toList())
        host.evaluated.clear()
        send("""{"action":"inboxAnswer","id":"a","answer":"$key","status":200,"text":""}""")
        assertEquals(listOf(reply("a", null, TapBridge.NO_PAYMENT_WAITING)), host.evaluated.toList())
    }

    // MARK: shake and turned

    @Test
    fun aShakeIsToldOnlyWhileArmedAndTheSensorIsListenedToOnlyThen() {
        send("""{"action":"shakeStart","id":"s1"}""")
        send("""{"action":"shakeStart","id":"s2"}""")
        assertEquals("asked for once", listOf(true), host.shakeSenses.toList())
        host.shake!!()
        assertEquals(tap("shake"), host.evaluated.last())
        send("""{"action":"shakeStop","id":"s3"}""")
        send("""{"action":"shakeStop","id":"s4"}""")
        assertEquals(listOf(true, false), host.shakeSenses.toList())
        assertNull(host.shake)
        assertEquals(listOf(reply("s1", "ok"), reply("s2", "ok"), tap("shake"), reply("s3", "ok"), reply("s4", "ok")), host.evaluated.toList())
    }

    @Test
    fun theTurnIsPushedAsIosPushesIt() {
        bridge.turned(true)
        bridge.turned(false)
        assertEquals(
            listOf(
                "window.FoxyWallet && window.FoxyWallet._turned && window.FoxyWallet._turned(true)",
                "window.FoxyWallet && window.FoxyWallet._turned && window.FoxyWallet._turned(false)",
            ),
            host.evaluated.toList(),
        )
    }

    // MARK: the push's shape

    @Test
    fun theTapPushIsIosPushTap() {
        assertEquals(
            "window.FoxyWallet && window.FoxyWallet._tap && window.FoxyWallet._tap({\"side\":\"receive\",\"stage\":\"code\",\"code\":\"0042\"})",
            TapBridge.tapPush("receive", TapStage.CODE, "0042"),
        )
        assertEquals(tap("nearby", ",\"edge\":\"left\""), TapBridge.tapPush("receive", TapStage.NEARBY, "left"))
        assertEquals(tap("receiving", ",\"pct\":37"), TapBridge.tapPush("receive", TapStage.RECEIVING, "37"))
        // what is not four digits, an edge or a percentage on its stage is left out
        assertEquals(tap("version"), TapBridge.tapPush("receive", TapStage.VERSION, "4"))
        assertEquals(tap("receiving"), TapBridge.tapPush("receive", TapStage.RECEIVING, "101"))
        assertEquals(tap("code"), TapBridge.tapPush("receive", TapStage.CODE, "12a4"))
        assertEquals(TapRules.push("receive", "unsupported"), TapBridge.tapPush("receive", TapStage.UNSUPPORTED, null))
    }

    @Test
    fun aLargePaymentSaysHowFarItHasGot() {
        onTheAir()
        val payer = Payer(mtu = 185)
        payer.linkUp()
        val body = "{\"proofs\":[" + (1..200).joinToString(",") { "{\"amount\":$it,\"secret\":\"${"x".repeat(40)}\"}" } + "]}"
        payer.pay(body)
        val pcts = taps.filter { it.contains("\"stage\":\"receiving\"") }
        assertTrue("receiving with pct: ${pcts.size}", pcts.isNotEmpty() && pcts.all { it.contains("\"pct\":") })
        assertNotNull(paidKey())
    }
}
