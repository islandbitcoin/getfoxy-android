package io.islandbitcoin.foxy.bridge

import io.islandbitcoin.foxy.TestSupport
import io.islandbitcoin.foxy.TestSupport.V00
import io.islandbitcoin.foxy.TestSupport.V01
import io.islandbitcoin.foxy.TestSupport.body
import io.islandbitcoin.foxy.bridge.SeedBridge.Check
import io.islandbitcoin.foxy.keychain.SeedActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seed actions' argument checks, before anything is read: ported from
 * NativeSeedTests.swift and CounterRangeCheckTests.swift. Bodies go through
 * JSON as the page's do, so a number is a Double and a boolean a Boolean.
 */
class SeedBridgeTest {
    private val bad = Check.Refuse("bad request")

    @Test
    fun counterReserveTakesZeroToAThousand() {
        for (count in listOf(0, 1, 1000)) {
            assertEquals(Check.Ok(SeedActions.CounterRequest(V00, 0, 0, count)), SeedBridge.counterReserveCheck(body("keysetId" to V00, "count" to count)))
        }
        assertEquals(Check.Ok(SeedActions.CounterRequest(V01, 1, 0, 2)), SeedBridge.counterReserveCheck(body("keysetId" to V01.uppercase(), "count" to 2.0)))
        for (count in listOf<Any?>(-1, 1001, 1.5, "3", true, null)) {
            assertEquals("$count", bad, SeedBridge.counterReserveCheck(body("keysetId" to V00, "count" to count)))
        }
        assertEquals(bad, SeedBridge.counterReserveCheck(body("count" to 1)))
        assertEquals(bad, SeedBridge.counterReserveCheck(body("keysetId" to "I2yN+iRYfkzT", "count" to 1)))
    }

    @Test
    fun restoreSecretsTakesACandidateOrNone() {
        val range = SeedActions.CounterRequest(V00, 0, 0, 5)
        val b = mapOf<String, Any?>("keysetId" to V00, "start" to 0, "count" to 5)
        assertEquals(Check.Ok(SeedActions.RestoreRequest(range, null)), SeedBridge.restoreSecretsCheck(body(TestSupport.write(b))))
        assertEquals(Check.Ok(SeedActions.RestoreRequest(range, null)), SeedBridge.restoreSecretsCheck(body(TestSupport.write(b + ("candidate" to null)))))
        assertEquals(Check.Ok(SeedActions.RestoreRequest(range, "0123456789abcdef0123456789abcdef")),
            SeedBridge.restoreSecretsCheck(body(TestSupport.write(b + ("candidate" to "0123456789abcdef0123456789abcdef")))))
        for (c in listOf<Any?>(5, "", "a".repeat(65), true, listOf("x"))) {
            assertEquals("$c", bad, SeedBridge.restoreSecretsCheck(body(TestSupport.write(b + ("candidate" to c)))))
        }
    }

    @Test
    fun counterAdvanceTakesASafeInteger() {
        for (next in listOf<Any>(0, 12, 9_007_199_254_740_991L)) assertTrue("$next", SeedBridge.counterAdvanceCheck(body("keysetId" to V00, "next" to next)) is Check.Ok)
        assertEquals(Check.Ok(SeedActions.AdvanceRequest(V00, 4)), SeedBridge.counterAdvanceCheck(body("keysetId" to V00.uppercase(), "next" to 4)))
        for (next in listOf<Any?>(-1, 9_007_199_254_740_992L, 1.5, "1", false, null)) {
            assertEquals("$next", bad, SeedBridge.counterAdvanceCheck(body("keysetId" to V00, "next" to next)))
        }
        assertEquals(bad, SeedBridge.counterAdvanceCheck(body("next" to 1)))
    }

    @Test
    fun countersImportRefusesTheWholeImportForOneBadEntry() {
        assertEquals("ids that differ only in case are one keyset, at the larger value", Check.Ok(mapOf(V00 to 9L, V01 to 0L)),
            SeedBridge.countersImportCheck(body("counters" to mapOf(V00 to 3, V00.uppercase() to 9, V01 to 0))))
        assertEquals(Check.Ok(emptyMap<String, Long>()), SeedBridge.countersImportCheck(body("counters" to emptyMap<String, Any>())))
        val bads = listOf<Any?>(
            null, "{}", listOf(1, 2),
            mapOf(V00 to -1), mapOf(V00 to 1.5), mapOf(V00 to "3"), mapOf(V00 to true), mapOf(V00 to 9_007_199_254_740_992L),
            mapOf("00zz" to 1), mapOf("I2yN+iRYfkzT" to 1), mapOf("0012" to 1), mapOf(V00 to 1, ("02" + "ab".repeat(32)) to 1),
        )
        for (c in bads) assertEquals("$c", bad, SeedBridge.countersImportCheck(body("counters" to c)))
        val many = (0 until 256).associate { "00%014x".format(it) to 1 }
        assertTrue(SeedBridge.countersImportCheck(body("counters" to many)) is Check.Ok)
        assertEquals("no more than the file holds", bad, SeedBridge.countersImportCheck(body("counters" to many + ("00%014x".format(256) to 1))))
    }

    @Test
    fun seedMigrateTakesOnlyABip39Phrase() {
        val list = TestSupport.wordlist
        val other = TestSupport.SAVED
        fun check(words: Any?) = SeedBridge.seedMigrateCheck(body("words" to words), list)
        assertEquals(Check.Ok(other), check(other))
        assertEquals(Check.Ok(TestSupport.ABANDON_ABOUT), check(TestSupport.ABANDON_ABOUT))
        assertEquals("the spacing around the words is not part of the phrase", Check.Ok(other), check("  " + other.replace(" ", "\n\t ") + " "))
        assertEquals("an array of words", Check.Ok(other), check(other.split(" ")))
        assertEquals("capitals folded", Check.Ok(other), check(other.uppercase()))
        assertEquals(Check.Ok(other), check(other.split(" ").map { " " + it.replaceFirstChar { c -> c.uppercase() } + " " }))
        val twentyFour = "abandon ".repeat(23) + "art"
        assertEquals(Check.Ok(twentyFour), check(twentyFour))
        val checksum = other.split(" ").toMutableList().also { it[11] = "year" }
        val bads = listOf<Any?>(
            null, 5, true, "", mapOf("words" to other),
            checksum.joinToString(" "),
            other.split(" ").dropLast(1).joinToString(" "),
            "$other abandon",
            "abandon ".repeat(11) + "abandon",
            "legal winner thank year wave sausage worth useful legal winner thank foxy",
            listOf(1, 2, 3), other.split(" ") + "abandon", List(25) { "abandon" },
            "abandon ".repeat(200),
        )
        for (w in bads) assertEquals("$w", bad, check(w))
    }

    // CounterRangeCheckTests: counterReserveAt and restoreSecrets take the same range

    private val good = mapOf<String, Any?>("action" to "restoreSecrets", "id" to "4", "keysetId" to "00b4cd27d8861a44", "start" to 0, "count" to 10)

    private fun range(b: Map<String, Any?>): Check<SeedActions.CounterRequest> {
        val parsed = body(TestSupport.write(b))
        val at = SeedBridge.counterReserveAtCheck(parsed)
        return when (val r = SeedBridge.restoreSecretsCheck(parsed)) {
            is Check.Ok -> {
                assertEquals(Check.Ok(r.value.range), at)
                Check.Ok(r.value.range)
            }
            is Check.Refuse -> {
                assertEquals(r, at)
                r
            }
        }
    }

    @Test
    fun aGoodRangeIsTaken() {
        assertEquals(Check.Ok(SeedActions.CounterRequest("00b4cd27d8861a44", 0, 0, 10)), range(good))
        val v01 = "015ba18a8adcd02e715a58358eb618da4a4b3791151a4bee5e968bb88406ccf76a"
        val edges = listOf(
            listOf("00b4cd27d8861a44", 0, 1, 0, 0L, 1), listOf("00b4cd27d8861a44", 0, 1000, 0, 0L, 1000),
            listOf("00b4cd27d8861a44", 0x7FFF_FFFFL, 1, 0, 0x7FFF_FFFFL, 1), listOf("00b4cd27d8861a44", 0x7FFF_FC18L, 1000, 0, 0x7FFF_FC18L, 1000),
            listOf(v01, 9_007_199_254_740_991L, 1, 1, 9_007_199_254_740_991L, 1), listOf(v01, (1L shl 40).toDouble(), 1000.0, 1, 1L shl 40, 1000),
        )
        for (e in edges) {
            val r = range(good + mapOf("keysetId" to e[0], "start" to e[1], "count" to e[2]))
            assertEquals("$e", Check.Ok(SeedActions.CounterRequest(e[0] as String, e[3] as Int, e[4] as Long, e[5] as Int)), r)
        }
        assertEquals(Check.Ok(SeedActions.CounterRequest("00b4cd27d8861a44", 0, 0, 10)), range(good + ("keysetId" to "00B4CD27D8861A44")))
    }

    @Test
    fun countsOutsideOneToAThousandAreRefused() {
        for (count in listOf<Any?>(0, 1001, -1, 1.5, "10", true, null, Double.NaN, 1e300)) {
            assertEquals("count $count", bad, range(good + ("count" to count)))
        }
        assertEquals(bad, range(good - "count"))
    }

    @Test
    fun startsOutsideTheKeysetsCountersAreRefused() {
        val v01 = "01" + "cd".repeat(32)
        val refused = listOf<Triple<String, Any?, Any>>(
            Triple("00b4cd27d8861a44", -1, 1), Triple("00b4cd27d8861a44", 0x8000_0000L, 1), Triple("00b4cd27d8861a44", 0x7FFF_FFFFL, 2),
            Triple("00b4cd27d8861a44", 1L shl 40, 1), Triple(v01, 9_007_199_254_740_992L, 1), Triple(v01, 9_007_199_254_740_991L, 2),
            Triple("00b4cd27d8861a44", 0.5, 1), Triple("00b4cd27d8861a44", "0", 1), Triple("00b4cd27d8861a44", false, 1), Triple("00b4cd27d8861a44", null, 1),
        )
        for ((id, start, count) in refused) assertEquals("$id $start", bad, range(good + mapOf("keysetId" to id, "start" to start, "count" to count)))
    }

    @Test
    fun keysetIdsThatAreNotHexVersion00Or01AreRefused() {
        val ids = listOf<Any?>(null, 5, "", "00b4cd27d8861a4", "00b4cd27d8861a445", "01b4cd27d8861a44", "00" + "ab".repeat(32),
            "02" + "ab".repeat(32), "I2yN+iRYfkzT", "00b4cd27d8861a4z", "00b4cd27 8861a44", "００b4cd27d8861a44")
        for (id in ids) assertEquals("$id", bad, range(good + ("keysetId" to id)))
    }

    @Test
    fun aLockKeyReservationTakesZeroToSixtyFour() {
        for (count in listOf(0, 1, 64)) assertEquals(Check.Ok(count), SeedBridge.p2pkReserveCheck(body("count" to count)))
        assertEquals(Check.Ok(2), SeedBridge.p2pkReserveCheck(body("count" to 2.0)))
        for (count in listOf<Any?>(-1, 65, 1000, 1.5, "3", true, null)) assertEquals("$count", bad, SeedBridge.p2pkReserveCheck(body("count" to count)))
        assertEquals(bad, SeedBridge.p2pkReserveCheck(body()))
    }

    @Test
    fun aScanTakesUpToThreeHundredAndStopsAtTwentyThousand() {
        assertEquals(Check.Ok(SeedActions.P2PKRange(0, 300)), SeedBridge.p2pkPubkeysCheck(body("start" to 0, "count" to 300)))
        assertEquals(Check.Ok(SeedActions.P2PKRange(19_700, 300)), SeedBridge.p2pkPubkeysCheck(body("start" to 19_700, "count" to 300)))
        assertEquals(Check.Ok(SeedActions.P2PKRange(19_999, 1)), SeedBridge.p2pkPubkeysCheck(body("start" to 19_999, "count" to 1)))
        val refused = listOf<Pair<Any?, Any>>(19_701 to 300, 20_000 to 1, 0 to 301, 0 to 0, -1 to 1, 0.5 to 1, "0" to 1, false to 1, null to 1, (1L shl 40) to 1)
        for ((start, count) in refused) assertEquals("$start + $count", bad, SeedBridge.p2pkPubkeysCheck(body("start" to start, "count" to count)))
        assertEquals(bad, SeedBridge.p2pkPubkeysCheck(body("start" to 0)))
    }

    @Test
    fun aLockKeyIndexIsANormalBip32Child() {
        for (index in listOf(0L, 1L, 20_000L, 0x7FFF_FFFFL)) assertEquals(Check.Ok(index), SeedBridge.p2pkKeyCheck(body("index" to index)))
        for (index in listOf<Any?>(-1, 0x8000_0000L, 1L shl 40, 1.5, "7", true, null)) assertEquals("$index", bad, SeedBridge.p2pkKeyCheck(body("index" to index)))
        assertEquals(bad, SeedBridge.p2pkKeyCheck(body()))
    }

    @Test
    fun exactIntegersAreWholeNumbersJavaScriptHoldsAndNothingElse() {
        assertEquals(5L, SeedBridge.exactInteger(5.0))
        assertEquals(-9_007_199_254_740_991L, SeedBridge.exactInteger(-9_007_199_254_740_991.0))
        for (v in listOf<Any?>(1.5, Double.NaN, Double.POSITIVE_INFINITY, 9_007_199_254_740_992.0, true, "5", 5L, 5, null)) {
            assertEquals("$v", null, SeedBridge.exactInteger(v))
        }
    }
}
