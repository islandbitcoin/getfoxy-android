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

/** S14: only the asset origin's main frame is heard; and the reply literal cannot be broken out of. */
class DispatchTest {
    private val host = FakeHost()
    private val dir = TestSupport.tempDir("dispatch")
    private val bridge = FoxyBridge(host, {
        SeedEnvironment(
            savedSeed = { SeedVault.SecretsSeed.Absent }, seedPresent = { false }, write = { null },
            counters = CounterStore(dir), candidates = SeedCandidates(), wordlist = { null }, generate = { "" },
            migrationWindowOpen = { false }, closeMigrationWindow = {}, flags = Flags(dir),
        )
    }, Executor { it.run() }, isDebug = true)

    @Test
    fun onlyTheMainFrameOfTheAssetOriginIsAnswered() {
        assertTrue(FoxyBridge.answers(true, "https://appassets.androidplatform.net"))
        assertFalse(FoxyBridge.answers(false, "https://appassets.androidplatform.net"))
        for (origin in listOf("https://example.com", "http://appassets.androidplatform.net", "https://appassets.androidplatform.net.evil",
            "https://evil.appassets.androidplatform.net", "null", "", "file://", null)) {
            assertFalse("$origin", FoxyBridge.answers(true, origin))
        }
        val message = """{"action":"isDebug","id":"1"}"""
        bridge.onMessage(message, false, FoxyBridge.ORIGIN)
        bridge.onMessage(message, true, "https://example.com")
        bridge.onMessage(message, true, null)
        assertEquals("a frame or another origin gets nothing", emptyList<String>(), host.evaluated)
        bridge.onMessage(message, true, FoxyBridge.ORIGIN)
        assertEquals(listOf("""window.FoxyWallet && window.FoxyWallet._scanResult("1", "yes", null)"""), host.evaluated)
    }

    @Test
    fun aMessageTooLongIsDroppedUnread() {
        val long = """{"action":"log","id":"1","text":"${"x".repeat(FoxyBridge.MOST_MESSAGE_CHARS)}"}"""
        bridge.onMessage(long, true, FoxyBridge.ORIGIN)
        assertEquals(emptyList<String>(), host.evaluated)
    }

    @Test
    fun theLiteralSurvivesQuotesNewlinesSeparatorsAndScriptTags() {
        assertEquals("null", FoxyBridge.literal(null))
        val nasty = listOf(
            "plain", "", "a\"b", "back\\slash", "line\nbreak", "cr\rlf", "tab\there", "\u2028sep\u2029para",
            "</script><script>alert(1)</script>", "'); evil(); ('", "\u0000\u0001\u001f\u007f", "emoji 🦊", "<!-- -->",
        )
        for (s in nasty) {
            val lit = FoxyBridge.literal(s)
            assertTrue(lit.startsWith("\"") && lit.endsWith("\""))
            assertFalse("a raw line end in $lit", lit.contains('\n') || lit.contains('\r') || lit.contains('\u2028') || lit.contains('\u2029'))
            assertFalse("a raw < in $lit", lit.contains('<'))
            // it is a JSON string that reads back as the same text
            assertEquals(s, Json.parse(lit))
        }
        assertEquals(
            """window.FoxyWallet && window.FoxyWallet._scanResult("i\"d", null, "x\u2028y")""",
            FoxyBridge.replyScript("i\"d", null, "x\u2028y"),
        )
    }

    @Test
    fun dispatchOrderIsHapticThenActionThenNothing() {
        assertEquals(FoxyBridge.Dispatch.Haptic("success"), bridge.dispatch(mapOf("haptic" to "success", "action" to "seedStatus", "id" to "1")))
        assertEquals(FoxyBridge.Dispatch.Unknown("seedRead", "6"), bridge.dispatch(mapOf("action" to "seedRead", "id" to "6")))
        for (near in listOf("open", "seedWrite", "seedDelete", "seedSecrets", "", "SCAN", "seedstatus", "seedStatus ", "constructor", "__proto__", "toString")) {
            assertEquals(near, FoxyBridge.Dispatch.Unknown(near, "9"), bridge.dispatch(mapOf("action" to near, "id" to "9")))
        }
        assertEquals(FoxyBridge.Dispatch.Ignored, bridge.dispatch(mapOf("username" to "x")))
        assertEquals(FoxyBridge.Dispatch.Ignored, bridge.dispatch(listOf(mapOf("action" to "isDebug", "id" to "1"))))
        val run = bridge.dispatch(mapOf("action" to "isDebug", "id" to "7", "x" to 1.0))
        assertTrue(run is FoxyBridge.Dispatch.Run)
        assertEquals(1.0, (run as FoxyBridge.Dispatch.Run).body["x"])
    }
}
