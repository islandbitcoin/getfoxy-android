package io.islandbitcoin.foxy.bridge

import io.islandbitcoin.foxy.FakeHost
import io.islandbitcoin.foxy.TestSupport
import io.islandbitcoin.foxy.flashcard.InterimCardTime
import io.islandbitcoin.foxy.keychain.Bytes
import io.islandbitcoin.foxy.keychain.CounterStore
import io.islandbitcoin.foxy.keychain.Flags
import io.islandbitcoin.foxy.keychain.NUT13
import io.islandbitcoin.foxy.keychain.P256
import io.islandbitcoin.foxy.keychain.SeedCandidates
import io.islandbitcoin.foxy.keychain.SeedEnvironment
import io.islandbitcoin.foxy.keychain.SeedVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * ENG-666: `cardOwnerKey`, `cardOwnerSign` and `cardTime` through the bridge as the
 * page posts them (JSON, main frame, the asset origin), answered through
 * `_scanResult` as iOS answers them: the owner key of the iOS vectors for the
 * same words, a signature that verifies against it, "bad request" for anything
 * else before the seed is read, and the phone's clock signed by the interim
 * time key. The two seed actions run on the seed executor; the time on the
 * bridge's own thread, as FoxyBridge+Flashcard.swift's does.
 */
class CardBridgeTest {
    private val host = FakeHost()
    private val dir = TestSupport.tempDir("card-bridge")
    private var words: String? = TestSupport.ABANDON_ABOUT
    private var reads = 0
    private val queued = ArrayList<Runnable>()
    private val env = SeedEnvironment(
        savedSeed = {
            reads++
            words?.let { SeedVault.SecretsSeed.Found(NUT13.seed(it)) } ?: SeedVault.SecretsSeed.Absent
        },
        seedPresent = { words != null },
        write = { null },
        counters = CounterStore(dir),
        candidates = SeedCandidates(),
        wordlist = { TestSupport.wordlist },
        generate = { TestSupport.MADE },
        migrationWindowOpen = { false },
        closeMigrationWindow = {},
        flags = Flags(dir),
    )
    private var now = 1_800_000_000_250L
    /** The seed executor: held, so a test can see what ran on it and what did not. */
    private val bridge = FoxyBridge(host, { env }, Executor { queued.add(it) }, isDebug = false, wallClock = { now })

    private val cardKey = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
    private val nonce = "000102030405060708090a0b0c0d0e0f"

    @Suppress("UNCHECKED_CAST")
    private val vectors: List<Map<String, Any?>> by lazy {
        (TestSupport.fixture("card-owner-vectors.json") as Map<String, Any?>)["cases"] as List<Map<String, Any?>>
    }

    private fun send(json: String) = bridge.onMessage(json, true, FoxyBridge.ORIGIN)

    private fun drain() {
        while (queued.isNotEmpty()) queued.removeAt(0).run()
    }

    private fun replies(): List<Triple<String?, String?, String?>> = host.evaluated.filter { it.contains("._scanResult(") }.map { js ->
        @Suppress("UNCHECKED_CAST")
        val list = Json.parse("[" + js.substringAfter("_scanResult(").removeSuffix(")") + "]") as List<String?>
        Triple(list[0], list[1], list[2])
    }

    @Suppress("UNCHECKED_CAST")
    private fun parsed(text: String?): Map<String, Any?> = Json.parse(text!!) as Map<String, Any?>

    @Test
    fun theOwnerKeyIsTheIPhonesForTheSameWords() {
        for (c in vectors) {
            val phrase = c["mnemonic"] as? String ?: continue
            words = phrase
            host.evaluated.clear()
            send("""{"action":"cardOwnerKey","id":"k","key":"${(c["cardKey"] as String).uppercase()}"}""")
            assertEquals("on the seed executor", emptyList<Any>(), replies())
            drain()
            val r = replies().single()
            assertEquals("k", r.first)
            assertNull(r.third)
            assertEquals("{\"pub\":\"${c["pub"]}\"}", r.second)
        }
    }

    @Test
    fun aSignatureVerifiesAgainstThatKeyAndAnythingElseIsABadRequestBeforeTheSeedIsRead() {
        val case = vectors[0]
        val pub = Bytes.fromHex(case["pub"] as String)!!
        for ((label, value) in listOf("change-pin" to "31323334", "set-limit" to "000186a0", "load" to "", "reset" to "")) {
            host.evaluated.clear()
            send("""{"action":"cardOwnerSign","id":"s","key":"$cardKey","label":"$label","nonce":"$nonce","value":"$value"}""")
            drain()
            val r = replies().single()
            assertNull(r.third)
            val sig = parsed(r.second)["sig"] as String
            val message = "FoxyCard/$label".toByteArray(Charsets.UTF_8) + Bytes.fromHex(nonce)!! + Bytes.fromHex(value)!!
            assertTrue(label, P256.verify(pub, message, Bytes.fromHex(sig)!!))
        }
        val before = reads
        val refused = listOf(
            """{"action":"cardOwnerSign","id":"b","key":"$cardKey","label":"lock","nonce":"$nonce","value":""}""",
            """{"action":"cardOwnerSign","id":"b","key":"$cardKey","label":"tag","nonce":"$nonce","value":"01"}""",
            """{"action":"cardOwnerSign","id":"b","key":"$cardKey","label":"time","nonce":"$nonce","value":"6b49d200"}""",
            """{"action":"cardOwnerSign","id":"b","key":"$cardKey","label":"load","nonce":"$nonce","value":"00"}""",
            """{"action":"cardOwnerSign","id":"b","key":"$cardKey","label":"load","nonce":"0001","value":""}""",
            """{"action":"cardOwnerSign","id":"b","key":"$cardKey","label":"load","nonce":"$nonce"}""",
            """{"action":"cardOwnerKey","id":"b","key":"04${cardKey.drop(2)}"}""",
            """{"action":"cardOwnerKey","id":"b"}""",
        )
        for (json in refused) {
            host.evaluated.clear()
            send(json)
            assertEquals("refused at once, on the bridge's thread: $json", 0, queued.size)
            assertEquals(json, listOf(Triple("b", null, "bad request")), replies())
        }
        assertEquals("the seed was not read", before, reads)
    }

    @Test
    fun withNoSeedBothAreRefused() {
        words = null
        send("""{"action":"cardOwnerKey","id":"1","key":"$cardKey"}""")
        send("""{"action":"cardOwnerSign","id":"2","key":"$cardKey","label":"load","nonce":"$nonce","value":""}""")
        drain()
        assertEquals(listOf(Triple("1", null, "no seed"), Triple("2", null, "no seed")), replies())
    }

    @Test
    fun cardTimeIsThePhonesClockSignedByTheInterimKey() {
        send("""{"action":"cardTime","id":"t","anything":"is ignored"}""")
        assertEquals("needs no seed and no executor", 0, queued.size)
        val r = replies().single()
        assertEquals("t", r.first)
        assertNull(r.third)
        val obj = parsed(r.second)
        assertEquals(setOf("sig", "time"), obj.keys)
        assertEquals(1_800_000_000.0, obj["time"])
        assertTrue(r.second!!.endsWith(",\"time\":1800000000}"))
        assertTrue(P256.verify(Bytes.fromHex(InterimCardTime.PUBLIC_KEY_HEX)!!, InterimCardTime.message(1_800_000_000), Bytes.fromHex(obj["sig"] as String)!!))
        assertEquals("the seed was not read", 0, reads)
        // a clock no card can be told
        for (clock in listOf(-1L, 4_294_967_296_000L)) {
            now = clock
            host.evaluated.clear()
            send("""{"action":"cardTime","id":"u"}""")
            assertEquals(listOf(Triple("u", null, "The clock on this phone is not a time a card can be told.")), replies())
        }
    }

    @Test
    fun aSubframeOrAnotherOriginIsNotAnswered() {
        bridge.onMessage("""{"action":"cardTime","id":"x"}""", false, FoxyBridge.ORIGIN)
        bridge.onMessage("""{"action":"cardOwnerKey","id":"y","key":"$cardKey"}""", true, "https://evil.example")
        drain()
        assertEquals(emptyList<Any>(), replies())
        assertEquals(0, reads)
    }
}
