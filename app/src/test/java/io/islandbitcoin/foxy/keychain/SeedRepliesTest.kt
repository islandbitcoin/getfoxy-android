package io.islandbitcoin.foxy.keychain

import io.islandbitcoin.foxy.FakeHost
import io.islandbitcoin.foxy.FakeKeys
import io.islandbitcoin.foxy.TestSupport
import io.islandbitcoin.foxy.bridge.FoxyBridge
import io.islandbitcoin.foxy.bridge.Json
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.Executor

/**
 * S2: the words never reach the page. Every P1 action, run through the bridge on
 * a real vault (fake key) holding known words, in every state, answers nothing
 * that holds one of the words or the seed's bytes (ported from the iOS
 * `tests/run.js` `wordsAsked` and SeedActionsTests' L12 check).
 */
class SeedRepliesTest {
    companion object {
        private val phrases = listOf(TestSupport.SAVED, TestSupport.MADE, TestSupport.TYPED_OTHER, TestSupport.ABANDON_ABOUT)

        fun assertNoWords(texts: List<String>) {
            val words = phrases.flatMap { it.split(" ") }.toSet()
            val seeds = phrases.map { NUT13.seed(it).hex() }
            for (text in texts) {
                val lower = text.lowercase()
                val tokens = lower.split(Regex("[^a-z]+")).toSet()
                for (w in words) if (w in tokens) fail("a reply holds the word \"$w\": $text")
                for (s in seeds) {
                    if (lower.contains(s) || lower.contains(s.take(32)) || lower.contains(s.takeLast(32))) fail("a reply holds seed bytes: $text")
                }
            }
        }

        @JvmName("assertNoWordsInReplies")
        fun assertNoWords(replies: List<SeedReply>) = assertNoWords(replies.flatMap { listOfNotNull(it.text, it.error) })
    }

    @Test
    fun noReplyOfAnyActionHoldsTheWords() {
        val dir = TestSupport.tempDir("replies")
        val keys = FakeKeys()
        val vault = SeedVault(dir, keys)
        val flags = Flags(dir)
        val window = SeedMigrationWindow(flags)
        val env = SeedEnvironment(
            savedSeed = { vault.seedForSecrets() }, seedPresent = { vault.isThere() }, write = { vault.write(it) },
            counters = CounterStore(dir), candidates = SeedCandidates(), wordlist = { TestSupport.wordlist },
            generate = { BIP39.generate(it) }, migrationWindowOpen = { window.isOpen }, closeMigrationWindow = { window.close(it) },
            flags = flags,
        )
        val host = FakeHost()
        val bridge = FoxyBridge(host, { env }, Executor { it.run() }, false)
        fun send(json: String) = bridge.onMessage(json, true, FoxyBridge.ORIGIN)
        val v00 = TestSupport.V00
        val all = listOf(
            """{"action":"seedStatus","id":"a"}""", """{"action":"seedStatus","id":"b","quiet":true}""",
            """{"action":"seedCreate","id":"c"}""",
            """{"action":"seedMigrate","id":"d","words":"${TestSupport.SAVED}"}""",
            """{"action":"seedMigrate","id":"e","words":${TestSupport.write(TestSupport.ABANDON_ABOUT.split(" "))}}""",
            """{"action":"seedMigrate","id":"f","words":"${TestSupport.SAVED} zoo"}""",
            """{"action":"countersImport","id":"g","counters":{"$v00":3}}""",
            """{"action":"counterSnapshot","id":"h"}""",
            """{"action":"counterReserve","id":"i","keysetId":"$v00","count":5}""",
            """{"action":"counterReserveAt","id":"j","keysetId":"$v00","start":10,"count":5}""",
            """{"action":"counterAdvance","id":"k","keysetId":"$v00","next":20}""",
            """{"action":"restoreSecrets","id":"l","keysetId":"$v00","start":0,"count":50}""",
            """{"action":"restoreSecrets","id":"m","keysetId":"$v00","start":0,"count":5,"candidate":"feed"}""",
            """{"action":"p2pkReserve","id":"n","count":4}""",
            """{"action":"p2pkPubkeys","id":"o","start":0,"count":10}""",
            """{"action":"p2pkKey","id":"p","index":2}""",
            """{"action":"seedShow","id":"q","verify":true}""",
            """{"action":"seedEnter","id":"r"}""",
        )
        // a fresh vault, the same with a seed made on the phone, and one that failed
        for (pass in 0 until 3) {
            if (pass == 2) keys.failGet = true
            for (m in all) send(m)
        }
        assertTrue(host.evaluated.size >= all.size * 3)
        val texts = host.evaluated.flatMap { js ->
            val args = js.substringAfter("_scanResult(").removeSuffix(")")
            @Suppress("UNCHECKED_CAST")
            (Json.parse("[$args]") as List<String?>).drop(1).filterNotNull()
        }
        assertTrue("secrets were answered, and checked", texts.any { it.contains("\"secrets\":[\"") })
        assertNoWords(texts)
        dir.deleteRecursively()
    }
}
