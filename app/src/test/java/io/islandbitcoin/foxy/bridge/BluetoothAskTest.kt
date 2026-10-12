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
 * ENG-656: the first-install Bluetooth screen's APPROVE posts `bluetoothAsk`,
 * sets `asking`, and leaves only when native calls
 * `window.__foxyBluetoothAnswer(state)` (foxy-tor-gate.js). Main 570d049
 * answered "Unknown action" and pushed nothing, so the screen never closed.
 * Until BLE tap to pay (ENG-650) Android pushes "unsupported" and then answers
 * it, in iOS's order (FoxyBridge+Tap.swift `handleBluetoothAsk`), and touches
 * no other door.
 */
class BluetoothAskTest {
    private val dir = TestSupport.tempDir("bluetooth")
    private val env = SeedEnvironment(
        savedSeed = { SeedVault.SecretsSeed.Absent }, seedPresent = { false }, write = { null },
        counters = CounterStore(dir), candidates = SeedCandidates(), wordlist = { TestSupport.wordlist },
        generate = { TestSupport.MADE }, migrationWindowOpen = { false }, closeMigrationWindow = {}, flags = Flags(dir),
    )

    private val push = "window.__foxyBluetoothAnswer && window.__foxyBluetoothAnswer(\"unsupported\")"

    private fun reply(id: String) = FoxyBridge.replyScript(id, "unsupported", null)

    @Test
    fun approvePushesTheAnswerAndThenRepliesUnsupported() {
        val host = FakeHost()
        val bridge = FoxyBridge(host, { env }, Executor { it.run() }, isDebug = false)
        bridge.onMessage("""{"id":"bt-1760000000000","action":"bluetoothAsk"}""", true, FoxyBridge.ORIGIN)
        // the push first, then the reply: exactly these two, and nothing "Unknown"
        assertEquals(listOf(push, reply("bt-1760000000000")), host.evaluated.toList())
        assertEquals(PlatformBridge.bluetoothAnswer(PlatformBridge.BLUETOOTH_UNSUPPORTED), push)
        // no other door: no prompt, no settings page, no haptic, nothing kept awake
        assertTrue(host.prompts.isEmpty())
        assertEquals(0, host.settingsOpened)
        assertTrue(host.haptics.isEmpty())
        assertEquals(null, host.awake)
    }

    @Test
    fun theAnswerIsOneTheContractListsAndBothEntriesAreP3() {
        val entries = TestSupport.contractEntries()
        val ask = entries.single { it["name"] == "bluetoothAsk" }
        val global = entries.single { it["name"] == "__foxyBluetoothAnswer" }
        assertEquals("P3", ask["phase"])
        assertEquals("P3", global["phase"])
        assertEquals("global", global["kind"])
        assertEquals("window.__foxyBluetoothAnswer(state)", global["target"])
        @Suppress("UNCHECKED_CAST")
        val values = (ask["reply"] as Map<String, Any?>)["values"] as List<String>
        assertTrue(PlatformBridge.BLUETOOTH_UNSUPPORTED in values)
        assertTrue("bluetoothAsk" in FoxyBridge.P3_ACTIONS)
        assertFalse("served by its handler, not a stub", "bluetoothAsk" in ContractStubs.p1.keys)
    }

    @Test
    fun everyAskIsAnsweredAgainSoASecondScreenIsNeverStuck() {
        // the page clears the global as it leaves; a later launch (FOXY_SHOW_WARNING) asks afresh with a new id
        val host = FakeHost()
        val bridge = FoxyBridge(host, { env }, Executor { it.run() }, isDebug = false)
        bridge.onMessage("""{"id":"bt-1","action":"bluetoothAsk"}""", true, FoxyBridge.ORIGIN)
        bridge.onMessage("""{"id":"bt-2","action":"bluetoothAsk"}""", true, FoxyBridge.ORIGIN)
        assertEquals(listOf(push, reply("bt-1"), push, reply("bt-2")), host.evaluated.toList())
    }

    @Test
    fun bothGoThroughTheMainLooperInOrderAndNothingRunsBeforeIt() {
        // a host whose main looper is a queue: nothing is evaluated on the bridge's thread
        val queued = ArrayDeque<Runnable>()
        val evaluated = mutableListOf<String>()
        val host = object : PageHost {
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
        val bridge = FoxyBridge(host, { env }, Executor { it.run() }, isDebug = false)
        bridge.onMessage("""{"id":"bt-q","action":"bluetoothAsk"}""", true, FoxyBridge.ORIGIN)
        assertEquals("nothing evaluated off the main thread", emptyList<String>(), evaluated)
        while (queued.isNotEmpty()) queued.removeFirst().run()
        assertEquals(listOf(push, reply("bt-q")), evaluated)
    }

    @Test
    fun aSubframeOrAnotherOriginGetsNoAnswerAndNoPush() {
        val host = FakeHost()
        val bridge = FoxyBridge(host, { env }, Executor { it.run() }, isDebug = false)
        bridge.onMessage("""{"id":"bt-f","action":"bluetoothAsk"}""", false, FoxyBridge.ORIGIN)
        bridge.onMessage("""{"id":"bt-o","action":"bluetoothAsk"}""", true, "https://example.com")
        assertEquals(emptyList<String>(), host.evaluated.toList())
    }
}
