package io.islandbitcoin.foxy.bridge

import io.islandbitcoin.foxy.FakeHost
import io.islandbitcoin.foxy.TestSupport
import io.islandbitcoin.foxy.keychain.CounterStore
import io.islandbitcoin.foxy.keychain.Flags
import io.islandbitcoin.foxy.keychain.SeedCandidates
import io.islandbitcoin.foxy.keychain.SeedEnvironment
import io.islandbitcoin.foxy.keychain.SeedVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * S15: the bridge serves exactly the P2 contract. Every name in the table is in
 * conformance/contract.json; the 18 P1 actions, the 7 P2 actions and the `p1`
 * stubs of the later phases' actions are all there; each stub answers the
 * contract's text or error byte for byte; with no Tor, each P2 action answers
 * as its stub said P1 would; a later phase's action with no stub is "Unknown
 * action: <name>"; `{username}`, `{}`, a non-object and `{haptic}` get no reply.
 */
class BridgeContractTest {
    private val host = FakeHost()
    private val dir = TestSupport.tempDir("contract")
    private val env = SeedEnvironment(
        savedSeed = { SeedVault.SecretsSeed.Absent },
        seedPresent = { false },
        write = { null },
        counters = CounterStore(dir),
        candidates = SeedCandidates(),
        wordlist = { TestSupport.wordlist },
        generate = { TestSupport.MADE },
        migrationWindowOpen = { false },
        closeMigrationWindow = {},
        flags = Flags(dir),
    )
    private val bridge = FoxyBridge(host, { env }, Executor { it.run() }, isDebug = false)
    private val entries = TestSupport.contractEntries()

    private fun send(json: String) = bridge.onMessage(json, true, FoxyBridge.ORIGIN)

    /** What the page would be told: the (id, text, error) of every _scanResult evaluated. */
    private fun replies(): List<Triple<String?, String?, String?>> = host.evaluated.filter { it.contains("._scanResult(") }.map { js ->
        val args = js.substringAfter("_scanResult(").removeSuffix(")")
        @Suppress("UNCHECKED_CAST")
        val list = Json.parse("[$args]") as List<String?>
        Triple(list[0], list[1], list[2])
    }

    @Test
    fun everyNameInTheTableIsAnActionOfTheContract() {
        val actions = entries.filter { it["kind"] == "action" }.map { it["name"] as String }.toSet()
        for (name in bridge.handlers.keys) assertTrue("$name is not in contract.json", name in actions)
    }

    private val served = setOf("P1", "P2")

    @Test
    fun theP1AndP2ActionsAndTheLaterStubsAreAllThere() {
        val p1 = entries.filter { it["kind"] == "action" && it["phase"] == "P1" }.map { it["name"] as String }.toSet()
        val p2 = entries.filter { it["kind"] == "action" && it["phase"] == "P2" }.map { it["name"] as String }.toSet()
        val stubs = entries.filter { it["kind"] == "action" && it["p1"] != null && it["phase"] !in served }.map { it["name"] as String }.toSet()
        assertEquals(18, p1.size)
        assertEquals(setOf("privacy", "torRetry", "unprotected", "mintRequest", "price", "candles", "hostsKnown"), p2)
        assertEquals(setOf("captured", "orbotRefresh", "orbotAccess", "seedProtection"), stubs)
        assertEquals(p1, FoxyBridge.P1_ACTIONS)
        assertEquals(p2, FoxyBridge.P2_ACTIONS)
        assertEquals(stubs, ContractStubs.p1.keys)
        assertEquals(p1 + p2 + stubs, bridge.handlers.keys)
    }

    @Test
    fun withNoTorEachP2ActionAnswersAsP1Did() {
        val p2 = entries.filter { it["kind"] == "action" && it["phase"] == "P2" }
        for ((n, e) in p2.withIndex()) {
            val name = e["name"] as String
            @Suppress("UNCHECKED_CAST")
            val p1 = e["p1"] as Map<String, Any?>
            host.evaluated.clear()
            send("""{"action":"$name","id":"n$n","on":true,"url":"https://mint.example.com","method":"GET","hosts":["a.b"]}""")
            val r = replies().single()
            assertEquals(name, "n$n", r.first)
            assertEquals(name, p1["error"], r.third)
            val want = p1["text"] as String? ?: continue
            val got = r.second!!
            // the snapshot is the same object, its keys written in another order
            if (want.startsWith("{")) assertEquals(name, Json.parse(want), Json.parse(got)) else assertEquals(name, want, got)
        }
    }

    @Test
    fun eachStubAnswersTheContractsTextOrErrorExactly() {
        val stubs = entries.filter { it["kind"] == "action" && it["p1"] != null && it["phase"] !in served }
        for ((n, e) in stubs.withIndex()) {
            val name = e["name"] as String
            @Suppress("UNCHECKED_CAST")
            val p1 = e["p1"] as Map<String, Any?>
            host.evaluated.clear()
            send("""{"action":"$name","id":"s$n","on":true,"url":"https://mint.example","method":"GET","hosts":["a.b"]}""")
            assertEquals(name, listOf(Triple("s$n", p1["text"] as String?, p1["error"] as String?)), replies())
        }
    }

    @Test
    fun aLaterPhasesActionIsUnknown() {
        val later = entries.filter { it["kind"] == "action" && it["phase"] !in served && it["p1"] == null }.map { it["name"] as String }
        assertTrue(later.contains("seedShow"))
        for (name in later) {
            host.evaluated.clear()
            send("""{"action":"$name","id":"u1"}""")
            assertEquals(name, listOf(Triple("u1", null, "Unknown action: $name")), replies())
        }
        host.evaluated.clear()
        send("""{"action":"seedShow","id":"x","verify":true}""")
        assertEquals(listOf(Triple("x", null, "Unknown action: seedShow")), replies())
    }

    @Test
    fun usernameEmptyNonObjectsAndHapticsGetNoReply() {
        for (message in listOf("""{"username":"satoshi"}""", "{}", "[]", "\"seedStatus\"", "5", "null", "not json",
            """{"action":"seedStatus"}""", """{"id":"1"}""", """{"action":5,"id":"1"}""", """{"action":"seedStatus","id":1}""",
            """[{"action":"seedStatus","id":"1"}]""")) {
            host.evaluated.clear()
            send(message)
            assertEquals(message, emptyList<String>(), host.evaluated)
        }
        host.evaluated.clear()
        send("""{"haptic":"success","action":"seedStatus","id":"h1"}""")
        assertEquals("a haptic message runs no action", emptyList<String>(), host.evaluated)
        assertEquals(listOf("success"), host.haptics)
    }

    @Test
    fun theP1ActionsOfTheContractAnswer() {
        for ((n, action) in FoxyBridge.P1_ACTIONS.withIndex()) {
            host.evaluated.clear()
            send("""{"action":"$action","id":"p$n"}""")
            val r = replies()
            assertEquals(action, 1, r.size)
            assertEquals("p$n", r[0].first)
            assertTrue("$action: exactly one of text and error", (r[0].second == null) != (r[0].third == null))
            assertTrue("$action is not unknown", r[0].third?.startsWith("Unknown action") != true)
        }
    }

    @Test
    fun theSmallP1ActionsAnswerAsTheContractSays() {
        send("""{"action":"covered","id":"c1"}""")
        send("""{"action":"awake","id":"a1","on":true}""")
        send("""{"action":"isDebug","id":"d1"}""")
        send("""{"action":"log","id":"l1","text":"[foxy] hello"}""")
        send("""{"action":"fieldLogClear","id":"f1"}""")
        assertEquals(listOf(Triple("c1", "ok", null), Triple("a1", "ok", null), Triple("d1", "no", null), Triple("l1", "ok", null),
            Triple("f1", "ok", null)), replies())
        assertEquals(1, host.coveredCount)
        assertEquals(true, host.awake)
        host.evaluated.clear()
        send("""{"action":"fieldLog","id":"g1","bad":false}""")
        val text = replies().single().second!!
        assertTrue(text.startsWith("[") && text.contains("the log was cleared"))
    }
}
