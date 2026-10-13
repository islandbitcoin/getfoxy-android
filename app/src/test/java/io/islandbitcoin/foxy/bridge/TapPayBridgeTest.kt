package io.islandbitcoin.foxy.bridge

import io.islandbitcoin.foxy.FakeHost
import io.islandbitcoin.foxy.TestSupport
import io.islandbitcoin.foxy.keychain.CounterStore
import io.islandbitcoin.foxy.keychain.Flags
import io.islandbitcoin.foxy.keychain.SeedCandidates
import io.islandbitcoin.foxy.keychain.SeedEnvironment
import io.islandbitcoin.foxy.keychain.SeedVault
import io.islandbitcoin.foxy.tap.AirReceiver
import io.islandbitcoin.foxy.tap.ChangeHandover
import io.islandbitcoin.foxy.tap.FakeBluetooth
import io.islandbitcoin.foxy.tap.FakeTapCentral
import io.islandbitcoin.foxy.tap.PayerAir
import io.islandbitcoin.foxy.tap.TapAir
import io.islandbitcoin.foxy.tap.TapBluetooth
import io.islandbitcoin.foxy.tap.TapStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * ENG-671: tap to pay's paying side through the bridge (FoxyBridge+Tap.swift
 * `handleTapPayStart`, `handleTapSend`, `handleTapQuote`, `handleTapChangeKept`,
 * `handleTapPayStop`, `endTapPay`, `tapWoke` at 8df0bb1), with the payer's GATT
 * client faked at the ATT level against Android's own receiver ([PayerAir]):
 * the listen answered with the offer and the four digits, the stages pushed as
 * `_tap({side:"pay",…})`, the payment and its M6, M10, M11 as `_tapTerms`, change
 * as `_tapChange` answered with `tapChangeKept` into M8, a listen started again
 * or stopped, Foxy away and back, and the page gone. And ENG-658: a phone whose
 * Bluetooth cannot listen is told so once and its `tapPayStart` is never
 * answered "Stopped.", so the page has nothing to ask again.
 */
class TapPayBridgeTest {
    private val dir = TestSupport.tempDir("tap-pay-bridge")
    private val env = SeedEnvironment(
        savedSeed = { SeedVault.SecretsSeed.Absent }, seedPresent = { false }, write = { null },
        counters = CounterStore(dir), candidates = SeedCandidates(), wordlist = { TestSupport.wordlist },
        generate = { TestSupport.MADE }, migrationWindowOpen = { false }, closeMigrationWindow = {}, flags = Flags(dir),
    )
    private val air = PayerAir()
    private val bt = FakeBluetooth(air.clock, air.pending).also { b -> b.centralFor = { p -> FakeTapCentral(air, p) } }
    private val host = FakeHost().apply { tapBluetooth = bt }
    private val bridge = FoxyBridge(host, { env }, Executor { it.run() }, isDebug = false)

    private fun send(json: String) = bridge.onMessage(json, true, FoxyBridge.ORIGIN)

    private fun reply(id: String, text: String?, error: String? = null) = FoxyBridge.replyScript(id, text, error)

    private fun payTap(stage: String, extra: String = "") =
        "window.FoxyWallet && window.FoxyWallet._tap && window.FoxyWallet._tap({\"side\":\"pay\",\"stage\":\"$stage\"$extra})"

    private val replies: List<String> get() = host.evaluated.filter { it.contains("._scanResult(") }
    private val central: FakeTapCentral get() = air.centrals.last()

    private fun receiver(rssi: Int = -30): AirReceiver = air.receiver("AA:01").also {
        it.rssi = rssi
        it.linkRssi = rssi
        it.onAir()
    }

    /** A listen that has the offer: returns its four digits. */
    private fun linked(r: AirReceiver = receiver()): String {
        send("""{"action":"tapPayStart","id":"p1"}""")
        air.hear(3.0)
        val code = r.events.first { it.first == TapStage.CODE }.second!!
        assertEquals(reply("p1", TapBridge.offerReply(TapAir.OFFER, code)), replies.last())
        return code
    }

    // MARK: the listen

    @Test
    fun theListenIsAnsweredWithTheOfferAndTheFourDigitsAndTheStagesArePushed() {
        val r = receiver()
        send("""{"action":"tapPayStart","id":"p1"}""")
        air.settle()
        assertTrue("no answer while listening", replies.isEmpty())
        assertTrue(central.scanning)
        air.hear(3.0)
        val code = r.events.first { it.first == TapStage.CODE }.second!!
        val answer = reply("p1", "{\"payload\":${Json.quote(TapAir.OFFER)},\"code\":\"$code\"}")
        assertEquals(listOf(answer), replies)
        // the offer is JSON the page reads: {payload, code}
        val o = Json.parse(TapBridge.offerReply(TapAir.OFFER, code)) as Map<*, *>
        assertEquals(TapAir.OFFER, o["payload"])
        assertEquals(code, o["code"])
        assertTrue(payTap("connecting") in host.evaluated)
        assertTrue(payTap("linked") in host.evaluated)
        assertTrue("connecting before linked", host.evaluated.indexOf(payTap("connecting")) < host.evaluated.indexOf(payTap("linked")))
    }

    @Test
    fun nearbyCarriesTheEdgeOfThisScreen() {
        val r = receiver(rssi = -45)
        send("""{"action":"tapPayStart","id":"p1"}""")
        air.hear(4.0)
        assertTrue(payTap("nearby", ",\"edge\":\"left\"") in host.evaluated)
        assertTrue(replies.isEmpty())
        // TAP TO PAY on the toast: answered at once; the listen takes that receiver, and answers its own call
        send("""{"action":"tapPayStart","id":"p2","now":true}""")
        assertEquals("answered at once", listOf(reply("p2", "ok")), replies)
        air.hear(2.0)
        val code = r.events.first { it.first == TapStage.CODE }.second!!
        assertEquals(reply("p1", TapBridge.offerReply(TapAir.OFFER, code)), replies.last())
    }

    @Test
    fun startingAgainAnswersTheWaitingListenAndStopAnswersItStopped() {
        send("""{"action":"tapPayStart","id":"p1"}""")
        air.settle()
        send("""{"action":"tapPayStart","id":"p2"}""")
        air.settle()
        assertEquals(listOf(reply("p1", null, "Started again.")), replies)
        assertTrue("the first listen let go", air.centrals.first().closed)
        send("""{"action":"tapPayStop","id":"s1"}""")
        air.settle()
        assertEquals(listOf(reply("p1", null, "Started again."), reply("p2", null, "Stopped."), reply("s1", "ok")), replies)
        assertTrue(air.centrals.all { it.closed && !it.scanning })
        // a stop with nothing listening is still ok
        send("""{"action":"tapPayStop","id":"s2"}""")
        air.settle()
        assertEquals(reply("s2", "ok"), replies.last())
        // and TAP TO PAY with nothing listening is ok and does nothing
        send("""{"action":"tapPayStart","id":"n1","now":true}""")
        air.settle()
        assertEquals(reply("n1", "ok"), replies.last())
    }

    /** ENG-658: no Bluetooth to listen with is a stage, once, and never a "Stopped." for the page to ask again after. */
    @Test
    fun aPhoneThatCannotListenSaysWhyOnceAndTheListenIsNeverEndedForIt() {
        val setups = listOf<Pair<String, (FakeHost, FakeBluetooth) -> Unit>>(
            "unsupported" to { h, _ -> h.tapBluetooth = null },
            "unsupported" to { _, b -> b.scans = false },
            "off" to { _, b -> b.powerNow = TapBluetooth.Power.OFF },
            "denied" to { _, b ->
                b.scanGranted = false
                b.grantScanOnAsk = false
            },
        )
        for ((stage, setup) in setups) {
            val a = PayerAir()
            val b = FakeBluetooth(a.clock, a.pending).also { it.centralFor = { p -> FakeTapCentral(a, p) } }
            val h = FakeHost().apply { tapBluetooth = b }
            setup(h, b)
            val br = FoxyBridge(h, { env }, Executor { it.run() }, isDebug = false)
            br.onMessage("""{"action":"tapPayStart","id":"p1"}""", true, FoxyBridge.ORIGIN)
            a.clock.advance(60.0) { a.settle() }
            assertEquals(stage, listOf(payTap(stage)), h.evaluated.filter { it.contains("._tap(") })
            assertTrue("$stage: the listen is not answered, so the page does not ask again", h.evaluated.none { it.contains("._scanResult(") })
        }
    }

    @Test
    fun bluetoothTurnedBackOnMidListenFindsTheReceiver() {
        val r = receiver()
        bt.powerNow = TapBluetooth.Power.OFF
        send("""{"action":"tapPayStart","id":"p1"}""")
        air.settle()
        assertEquals(listOf(payTap("off")), host.evaluated.filter { it.contains("._tap(") })
        bt.setPower(TapBluetooth.Power.ON)
        air.hear(3.0)
        val code = r.events.first { it.first == TapStage.CODE }.second!!
        assertEquals(listOf(reply("p1", TapBridge.offerReply(TapAir.OFFER, code))), replies)
    }

    // MARK: over the held link

    @Test
    fun thePaymentGoesOverTheLinkAndTheReceiversM6IsTheAnswer() {
        val r = receiver()
        linked(r)
        send("""{"action":"tapSend","id":"s1","body":"{\"proofs\":[{\"amount\":64}]}"}""")
        air.hear(1.0)
        assertEquals(listOf("{\"proofs\":[{\"amount\":64}]}"), r.payments)
        val m6 = replies.last()
        assertTrue(m6, m6.startsWith("window.FoxyWallet && window.FoxyWallet._scanResult(\"s1\", "))
        val text = Json.parse("[" + m6.substringAfter("._scanResult(").removeSuffix(")") + "]") as List<*>
        val said = Json.parse(text[1] as String) as Map<*, *>
        assertEquals(true, said["ok"])
        assertNull(text[2])
    }

    @Test
    fun aLargePaymentSaysHowFarItHasGot() {
        val r = receiver()
        linked(r)
        val body = "{\"proofs\":[" + (1..900).joinToString(",") { "{\"amount\":$it,\"secret\":\"${"s".repeat(50)}\"}" } + "]}"
        send("""{"action":"tapSend","id":"s1","body":${Json.quote(body)}}""")
        air.hear(90.0)
        assertEquals(listOf(body), r.payments)
        val sending = host.evaluated.filter { it.contains("\"side\":\"pay\",\"stage\":\"sending\"") }
        assertTrue(sending.size >= 3)
        assertTrue("with a percentage", sending.all { Regex("\"pct\":[0-9]+").containsMatchIn(it) })
    }

    @Test
    fun aPaymentWithNothingToHandOverOrNoLinkIsRefusedAndALinkLostIsSaid() {
        send("""{"action":"tapSend","id":"s0","body":""}""")
        send("""{"action":"tapSend","id":"s1","body":"x"}""")
        air.settle()
        assertEquals(listOf(reply("s0", null, "There is nothing to hand over."), reply("s1", null, "That phone is no longer connected.")), replies)
        val r = receiver()
        linked(r)
        r.paid = { _, _ -> }
        send("""{"action":"tapSend","id":"s2","body":"{\"proofs\":[]}"}""")
        air.hear(1.0)
        central.drop("AA:01")
        air.settle()
        assertEquals(reply("s2", null, "That phone went away before it said what became of the payment."), replies.last())
    }

    @Test
    fun theQuoteTheTermsAndTheChangeKept() {
        val r = receiver()
        linked(r)
        // M10
        send("""{"action":"tapQuote","id":"q1","body":"{\"rate\":2}"}""")
        air.hear(0.5)
        assertEquals(reply("q1", "ok"), replies.last())
        assertEquals(listOf("{\"rate\":2}"), r.quotes)
        // M11 reaches the page as _tapTerms
        assertTrue(r.receiver.sendTerms("{\"v\":2,\"req\":\"creqAterms0123456789abcdef\"}"))
        air.hear(0.5)
        assertEquals(PlatformBridge.push("_tapTerms", "{\"v\":2,\"req\":\"creqAterms0123456789abcdef\"}"), host.evaluated.last())
        // change: _tapChange, then the page's tapChangeKept is what goes back as M8
        r.paid = { _, a ->
            r.receiver.expectChange()
            a(200, "")
        }
        send("""{"action":"tapSend","id":"s1","body":"{\"proofs\":[{\"amount\":9}]}"}""")
        air.hear(1.0)
        var word: ChangeHandover.Word? = null
        assertTrue(r.receiver.handOverChange("cashuBchange") { word = it })
        air.hear(1.0)
        assertEquals(PlatformBridge.push("_tapChange", "cashuBchange"), host.evaluated.last())
        assertNull("nothing goes back until the page says it kept it", word)
        send("""{"action":"tapChangeKept","id":"k1","kept":true}""")
        air.hear(1.0)
        assertEquals(reply("k1", "ok"), replies.last())
        assertEquals(ChangeHandover.Word.KEPT, word)
        // and a second word has nothing waiting
        send("""{"action":"tapChangeKept","id":"k2","kept":true}""")
        air.settle()
        assertEquals(reply("k2", null, "No change is waiting to be kept."), replies.last())
    }

    @Test
    fun changeTheReceiversPageCouldNotKeepIsNotAcknowledged() {
        val r = receiver()
        linked(r)
        r.paid = { _, a ->
            r.receiver.expectChange()
            a(200, "")
        }
        send("""{"action":"tapSend","id":"s1","body":"{\"proofs\":[{\"amount\":9}]}"}""")
        air.hear(1.0)
        var word: ChangeHandover.Word? = null
        r.receiver.handOverChange("cashuBchange") { word = it }
        air.hear(1.0)
        send("""{"action":"tapChangeKept","id":"k1","kept":false}""")
        air.hear(8.0)
        assertEquals(reply("k1", "ok"), replies.last())
        assertEquals("no M8: the receiver's own wait ends it", ChangeHandover.Word.NOT_HANDED, word)
    }

    @Test
    fun aQuoteWithNoLinkIsRefused() {
        send("""{"action":"tapQuote","id":"q1","body":"{}"}""")
        air.settle()
        assertEquals(listOf(reply("q1", null, "That phone is no longer connected.")), replies)
    }

    // MARK: Foxy away and back, and the page gone

    @Test
    fun nothingScansWhileFoxyIsAwayAndTheListenGoesOnWhenItIsBack() {
        val r = receiver(rssi = -70)
        send("""{"action":"tapPayStart","id":"p1"}""")
        air.hear(1.0)
        assertTrue(central.scanning)
        bridge.tapAway()
        assertFalse("nothing scans while Foxy is put away", central.scanning)
        r.rssi = -30
        r.linkRssi = -30
        air.hear(5.0)
        assertTrue("nothing heard while away", central.connects.isEmpty())
        assertTrue(replies.isEmpty())
        bridge.tapBack()
        assertTrue(central.scanning)
        air.hear(3.0)
        val code = r.events.first { it.first == TapStage.CODE }.second!!
        assertEquals(listOf(reply("p1", TapBridge.offerReply(TapAir.OFFER, code))), replies)
    }

    @Test
    fun aPaymentHandedOverIsAskedAgainWhenFoxyComesBack() {
        val r = receiver()
        linked(r)
        r.paid = { _, a -> a(200, "") }
        send("""{"action":"tapSend","id":"s1","body":"{\"proofs\":[{\"amount\":5}]}"}""")
        bridge.tapAway()
        central.deaf.add("AA:01")
        air.hear(7.0)
        assertTrue("the answer was missed", replies.none { it.contains("\"s1\"") })
        central.deaf.clear()
        bridge.tapBack()
        air.hear(1.0)
        assertTrue(replies.last().contains("._scanResult(\"s1\", "))
    }

    @Test
    fun thePageGoingEndsTheListenStopped() {
        receiver(rssi = -70)
        send("""{"action":"tapPayStart","id":"p1"}""")
        air.settle()
        bridge.pageGone()
        air.settle()
        assertEquals(listOf(reply("p1", null, "Stopped.")), replies)
        assertTrue(central.closed)
    }

    // MARK: the first-install question covers the payer

    @Test
    fun approveAsksWhenOnlyThePayersPermissionsAreMissing() {
        bt.scanGranted = false
        send("""{"id":"bt-1","action":"bluetoothAsk"}""")
        air.settle()
        assertEquals("one question for both sides, as iOS's one authorization", 1, bt.asks)
        assertTrue(bt.scanGranted)
        assertEquals(reply("bt-1", "allowed"), host.evaluated.last())
        // granted: no second question, from APPROVE or from the listen
        send("""{"id":"bt-2","action":"bluetoothAsk"}""")
        send("""{"action":"tapPayStart","id":"p1"}""")
        air.settle()
        assertEquals(1, bt.asks)
        assertEquals(0, bt.scanAsks)
    }

    /**
     * API 28-30 (BluetoothAnswer.pays): the payer does not run, so APPROVE never asks for it,
     * and there is no location dialog, which neither the page nor iOS's flow explains. The
     * listen says `unsupported` once and is not answered (ENG-658).
     */
    @Test
    fun belowApi31ApproveAsksNothingForThePayerAndTheListenIsUnsupported() {
        bt.pays = false
        bt.scanGranted = false
        send("""{"id":"bt-1","action":"bluetoothAsk"}""")
        air.settle()
        assertEquals("the receiver's permissions are granted; the payer's are not asked", 0, bt.asks)
        assertEquals(reply("bt-1", "allowed"), host.evaluated.last())
        send("""{"action":"tapPayStart","id":"p1"}""")
        air.settle()
        assertEquals(0, bt.scanAsks)
        assertEquals(payTap("unsupported"), host.evaluated.last())
        assertTrue("no scanner was made", air.centrals.isEmpty())
    }
}
