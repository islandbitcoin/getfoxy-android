package io.islandbitcoin.foxy.bridge

import io.islandbitcoin.foxy.FakeHost
import io.islandbitcoin.foxy.TestSupport
import io.islandbitcoin.foxy.keychain.CounterStore
import io.islandbitcoin.foxy.keychain.Flags
import io.islandbitcoin.foxy.keychain.SeedCandidates
import io.islandbitcoin.foxy.keychain.SeedEnvironment
import io.islandbitcoin.foxy.keychain.SeedVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * ENG-656: the receive screen asks `tapReceiveStart` by itself (auto-arm), and
 * main 570d049 answered "Unknown action", which the page took as a refusal: the
 * TAP card said UNKNOWN ACTION: TAPRECEIVESTART and the page asked again every
 * 1.2 s. Android now answers what iOS answers on an iPhone whose Bluetooth is
 * `unsupported` (FoxyBridge+Tap.swift `handleTapReceiveStart`, TapLink.swift
 * `cbState`): "ok", then `_tap({side:"receive",stage:"unsupported"})` when the
 * receiver is made; "ok" alone while one is held; iOS's refusal for a payload
 * it would not put on the air; `tapReceiveStop` "ok". No other door is touched.
 */
class TapReceiveTest {
    private val dir = TestSupport.tempDir("tap-receive")
    private val env = SeedEnvironment(
        savedSeed = { SeedVault.SecretsSeed.Absent }, seedPresent = { false }, write = { null },
        counters = CounterStore(dir), candidates = SeedCandidates(), wordlist = { TestSupport.wordlist },
        generate = { TestSupport.MADE }, migrationWindowOpen = { false }, closeMigrationWindow = {}, flags = Flags(dir),
    )
    private val host = FakeHost()
    private val bridge = FoxyBridge(host, { env }, Executor { it.run() }, isDebug = false)

    private val push = "window.FoxyWallet && window.FoxyWallet._tap && window.FoxyWallet._tap({\"side\":\"receive\",\"stage\":\"unsupported\"})"
    private fun reply(id: String, text: String?, error: String? = null) = FoxyBridge.replyScript(id, text, error)
    private fun send(json: String) = bridge.onMessage(json, true, FoxyBridge.ORIGIN)

    private val invoice = "lnbc80n1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypq"
    private val request = "creqApWF0YWRpYTF1aXRjcmVxdWVzdGEx"
    private fun offer(vararg pairs: Pair<String, Any>): String = Json.write(mapOf("v" to 2) + pairs.toMap())

    @Test
    fun theScreenArmingItselfIsAnsweredOkAndThenTold_unsupported() {
        // what auto-arm posts on the amount screen: the empty "on the air, offer coming" payload, early
        send("""{"action":"tapReceiveStart","id":"b1","payload":"","early":true}""")
        // the reply first, then the push: exactly these two, and nothing "Unknown"
        assertEquals(listOf(reply("b1", "ok"), push), host.evaluated.toList())
        assertEquals(TapRules.push("receive", TapRules.UNSUPPORTED), push)
        assertTrue(host.evaluated.none { it.contains("Unknown action") })
        // no other door: no prompt, no settings page, no haptic, nothing kept awake
        assertTrue(host.prompts.isEmpty())
        assertEquals(0, host.settingsOpened)
        assertTrue(host.haptics.isEmpty())
        assertEquals(null, host.awake)
    }

    @Test
    fun aHeldReceiverIsAnsweredOkAloneUntilItIsLetGo() {
        send("""{"action":"tapReceiveStart","id":"b1","payload":"","early":true}""")
        // the offer arriving on the invoice screen: iOS's `update` and `arm` on the receiver it has, no new state
        send("""{"action":"tapReceiveStart","id":"b2","payload":${Json.quote(offer("inv" to invoice))},"early":false}""")
        send("""{"action":"tapReceiveStop","id":"b3"}""")
        // the next screen makes a new receiver, which says its state again
        send("""{"action":"tapReceiveStart","id":"b4","payload":"","early":true}""")
        assertEquals(listOf(reply("b1", "ok"), push, reply("b2", "ok"), reply("b3", "ok"), reply("b4", "ok"), push), host.evaluated.toList())
    }

    @Test
    fun thePageGoingLetsTheReceiverGo() {
        send("""{"action":"tapReceiveStart","id":"b1","payload":""}""")
        // WebHost.destroy (the page's process ended, or the activity finished): FoxyBridge.swift `pageGone` → `stopTap`
        bridge.pageGone()
        send("""{"action":"tapReceiveStart","id":"b2","payload":""}""")
        assertEquals(listOf(reply("b1", "ok"), push, reply("b2", "ok"), push), host.evaluated.toList())
    }

    @Test
    fun puttingFoxyAwayKeepsTheReceiverAsIosDoes() {
        send("""{"action":"tapReceiveStart","id":"b1","payload":""}""")
        // PutAway → WebHost.letGo: iOS leaves `tapReceiver` alone in the background, so nothing is said again
        bridge.letGo()
        send("""{"action":"tapReceiveStart","id":"b2","payload":""}""")
        assertEquals(listOf(reply("b1", "ok"), push, reply("b2", "ok")), host.evaluated.toList())
    }

    @Test
    fun aPayloadIosWouldNotPutOnTheAirIsRefusedWithItsWordsAndNoPush() {
        val refused = listOf(
            "not an offer", "{}", """{"v":1,"inv":"$invoice"}""", """{"v":2}""", """{"v":true,"inv":"$invoice"}""",
            offer("inv" to "lnbc1short"), offer("inv" to invoice.uppercase().replaceFirst("L", "l")), offer("req" to "creq-too-short"),
            offer("inv" to invoice, "req" to "notarequestatallbutlong"), offer("inv" to 5),
            offer("inv" to "lnbc1" + "q".repeat(TapRules.MOST_INVOICE_BYTES)), offer("req" to "creqA" + "a".repeat(TapRules.MOST_PAYLOAD_BYTES)),
        )
        for ((n, payload) in refused.withIndex()) {
            host.evaluated.clear()
            send("""{"action":"tapReceiveStart","id":"r$n","payload":${Json.quote(payload)}}""")
            assertEquals(payload, listOf(reply("r$n", null, TapRules.NOT_PAYABLE)), host.evaluated.toList())
        }
        // and a refusal makes no receiver: the next good one is told its state
        host.evaluated.clear()
        send("""{"action":"tapReceiveStart","id":"g","payload":${Json.quote(offer("inv" to invoice, "req" to request))}}""")
        assertEquals(listOf(reply("g", "ok"), push), host.evaluated.toList())
    }

    @Test
    fun theOffersTheShapesAllow() {
        assertTrue(TapRules.isPayload(offer("inv" to invoice)))
        assertTrue(TapRules.isPayload(offer("req" to request)))
        assertTrue(TapRules.isPayload(offer("inv" to invoice, "req" to request, "up" to true, "ask" to 1)))
        assertTrue("one case throughout", TapRules.isPayload(offer("inv" to invoice.uppercase())))
        // a half that is not a string counts as missing, as `as? String` reads it
        assertTrue(TapRules.isPayload(offer("inv" to 5, "req" to request)))
        assertTrue("v written as 2.0 is the number two", TapRules.isPayload("""{"v":2.0,"inv":"$invoice"}"""))
        assertFalse(TapRules.isPayload(offer("inv" to invoice.replaceFirst("lnbc80", "lnBc80"))))
        assertFalse(TapRules.isPayload("[" + offer("inv" to invoice) + "]"))
    }

    /** FoxyTests/TapProtocolTests.swift:8 and :41-80, one assertion at a time, with the same strings. */
    private val iosInvoice = "lnbc10n1pn9xyzpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpusp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9qrsgq"

    @Test
    fun iosTestOnlyAWellFormedOfferPasses() {
        val req = "creqApGF0gaNhdGVub3N0cmFheDQ3YWJjZGVmMTIzNDU2Nzg5MGFiY2RlZjEyMzQ1Njc4OTBhYmNk"
        assertTrue(TapRules.isPayload("{\"v\":2,\"inv\":\"$iosInvoice\"}"))
        assertTrue(TapRules.isPayload("{\"v\":2,\"req\":\"$req\"}"))
        assertTrue(TapRules.isPayload("{\"v\":2,\"inv\":\"$iosInvoice\",\"req\":\"$req\"}"))
        // neither half
        assertFalse(TapRules.isPayload("{\"v\":2}"))
        // padded, which is what cashu-ts actually produces (TapProtocol.swift:67-76: a stricter pattern refused every padded offer)
        assertTrue(TapRules.isPayload("{\"v\":2,\"req\":\"$req=\"}"))
        assertTrue(TapRules.isPayload("{\"v\":2,\"req\":\"$req==\"}"))
        assertTrue(TapRules.isRequest(req + "="))
        // a half that is not what it claims
        assertFalse(TapRules.isPayload("{\"v\":2,\"inv\":\"bc1qxy2kgdygjrsqtzq2n0\"}"))
        assertFalse(TapRules.isPayload("{\"v\":2,\"req\":\"cashuBo2F0gaJhaUgA\"}"))
        // not this protocol, or not JSON at all
        assertFalse(TapRules.isPayload("{\"v\":3,\"inv\":\"$iosInvoice\"}"))
        assertFalse(TapRules.isPayload(iosInvoice))
        assertFalse(TapRules.isPayload("{"))
        assertFalse(TapRules.isPayload("{\"v\":2,\"inv\":\"lnbc10n1" + "q".repeat(5000) + "\"}"))
    }

    @Test
    fun iosTestOnlyInvoicesPass() {
        assertTrue(TapRules.isInvoice(iosInvoice))
        assertTrue(TapRules.isInvoice(iosInvoice.uppercase()))
        assertFalse(TapRules.isInvoice("cashuBo2F0gaJhaUgA"))
        assertFalse(TapRules.isInvoice("bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"))
        assertFalse(TapRules.isInvoice("lnbc10n1" + "q".repeat(3000)))
        assertFalse(TapRules.isInvoice("lnbc10n1pn9<script>"))
        assertFalse(TapRules.isInvoice("lnbc10n1pN9xyz" + "q".repeat(40)))
    }

    @Test
    fun bothGoThroughTheMainLooperReplyFirstAndNothingRunsBeforeIt() {
        val queued = ArrayDeque<Runnable>()
        val evaluated = mutableListOf<String>()
        val looper = object : PageHost {
            override fun post(action: Runnable) {
                queued.addLast(action)
            }
            override fun evaluate(js: String) {
                evaluated.add(js)
            }
            override fun covered() {}
            override fun keepAwake(on: Boolean) {}
            override fun haptic(kind: String) {}
        }
        val queuedBridge = FoxyBridge(looper, { env }, Executor { it.run() }, isDebug = false)
        queuedBridge.onMessage("""{"action":"tapReceiveStart","id":"q","payload":""}""", true, FoxyBridge.ORIGIN)
        assertEquals("nothing evaluated off the main thread", emptyList<String>(), evaluated)
        while (queued.isNotEmpty()) queued.removeFirst().run()
        assertEquals(listOf(reply("q", "ok"), push), evaluated)
    }

    @Test
    fun aSubframeOrAnotherOriginGetsNoAnswerAndNoPush() {
        bridge.onMessage("""{"action":"tapReceiveStart","id":"f","payload":""}""", false, FoxyBridge.ORIGIN)
        bridge.onMessage("""{"action":"tapReceiveStart","id":"o","payload":""}""", true, "https://example.com")
        bridge.onMessage("""{"action":"tapReceiveStop","id":"s"}""", true, "https://example.com")
        assertEquals(emptyList<String>(), host.evaluated.toList())
    }

    @Test
    fun theContractHasThemP3AndThePushIsTheContractsShape() {
        val entries = TestSupport.contractEntries()
        for (name in listOf("tapReceiveStart", "tapReceiveStop", "_tap")) {
            assertEquals(name, "P3", entries.single { it["name"] == name }["phase"])
        }
        val tap = entries.single { it["name"] == "_tap" }
        assertEquals("push", tap["kind"])
        assertTrue((tap["behaviour"] as String).contains("unsupported"))
        @Suppress("UNCHECKED_CAST")
        val errors = (entries.single { it["name"] == "tapReceiveStart" }["reply"] as Map<String, Any?>)["errors"] as List<String>
        assertTrue(TapRules.NOT_PAYABLE in errors)
        assertTrue("tapReceiveStart" in FoxyBridge.P3_ACTIONS && "tapReceiveStop" in FoxyBridge.P3_ACTIONS)
        // the payer's search is P3 since ENG-671 (TapPayBridgeTest), and keeps its stub for P1 and P2 hosts
        assertEquals("P3", entries.single { it["name"] == "tapPayStart" }["phase"])
        assertTrue("tapPayStart" in ContractStubs.p1.keys)
        assertTrue("tapPayStart" in FoxyBridge.P3_ACTIONS)
    }
}
