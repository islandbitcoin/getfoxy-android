package io.islandbitcoin.foxy.keychain

import io.islandbitcoin.foxy.TestSupport.ALIASES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** CounterRules: one counter per derivation path, and how far a counter may move (review H1, M1, L9). Ported from CounterRulesTests.swift. */
class CounterRulesTest {
    private val v01 = "01" + "ab".repeat(32)
    private val allowed = CounterRules.Move.ALLOWED
    private val tooFar = CounterRules.Move.TOO_FAR_AHEAD

    @Test
    fun theFourAliasIdsAreOneSlot() {
        for (id in ALIASES) {
            assertEquals(id, 864_559_728L, NUT13.derivationIndex(id))
            assertEquals(id, "00:864559728", CounterRules.slot(id))
            assertEquals(id, "00:864559728", CounterRules.slot(id.uppercase()))
        }
        assertNotEquals(CounterRules.slot("00b4cd27d8861a44"), CounterRules.slot(ALIASES[0]))
    }

    @Test
    fun a01KeysetIsItsOwnIdAndAnythingElseHasNoSlot() {
        assertEquals(v01, CounterRules.slot(v01))
        assertEquals(v01, CounterRules.slot(v01.uppercase()))
        assertNull(NUT13.derivationIndex(v01))
        for (bad in listOf("", "00", "I2yN+iRYfkzT", "02" + "ab".repeat(32), "009a1f293253e41g")) {
            assertNull(bad, CounterRules.slot(bad))
        }
    }

    @Test
    fun anAliasIsStoredUnderTheFirstIdOfItsSlot() {
        val stored = listOf(ALIASES[2], "00b4cd27d8861a44", v01)
        for (id in ALIASES) {
            assertEquals(id, ALIASES[2], CounterRules.storedKey(id, stored))
            assertEquals(ALIASES[2], CounterRules.storedKey(id.uppercase(), stored))
        }
        assertEquals("00b4cd27d8861a44", CounterRules.storedKey("00B4CD27D8861A44", stored))
        assertEquals("a new keyset is its own id", "00c0ffee00c0ffee", CounterRules.storedKey("00c0ffee00c0ffee", stored))
        assertEquals(ALIASES[0], CounterRules.storedKey(ALIASES[0], emptyList()))
        assertEquals(v01, CounterRules.storedKey(v01.uppercase(), stored))
    }

    @Test
    fun aFileWithAliasesIsMergedAtTheLargestValue() {
        val merged = CounterRules.merged(mapOf(ALIASES[0] to 40L, ALIASES[1] to 900L, ALIASES[3] to 7L, v01 to 3L, "00b4cd27d8861a44" to 12L))
        assertEquals(mapOf(ALIASES[1] to 900L, v01 to 3L, "00b4cd27d8861a44" to 12L), merged)
        assertEquals("a tie keeps the smaller id", mapOf(ALIASES[0] to 5L), CounterRules.merged(mapOf(ALIASES[2] to 5L, ALIASES[0] to 5L)))
        assertEquals(emptyMap<String, Long>(), CounterRules.merged(emptyMap()))
    }

    @Test
    fun advanceGoesAHundredPastTheCounter() {
        assertEquals(allowed, CounterRules.advance(0, 100, 0, 0))
        assertEquals(tooFar, CounterRules.advance(0, 101, 0, 0))
        assertEquals(allowed, CounterRules.advance(40, 140, 0, 1))
        assertEquals(tooFar, CounterRules.advance(40, 10_040, 0, 1))
        assertEquals("lower moves nothing", allowed, CounterRules.advance(40, 12, 0, 0))
        assertEquals(allowed, CounterRules.advance(40, 40, 0, 0))
    }

    @Test
    fun advanceGoesAsFarAsARestoreServed() {
        assertEquals(allowed, CounterRules.advance(40, 340, 340, 0))
        assertEquals(tooFar, CounterRules.advance(40, 341, 340, 0))
        assertEquals(allowed, CounterRules.advance(0, 20_000, 20_000, 1))
        assertEquals(allowed, CounterRules.advance(500, 600, 300, 1))
    }

    @Test
    fun advanceStopsAtTheVersionsCeiling() {
        val top00 = 1L shl 31
        val top01 = 1L shl 53
        assertEquals(top00, CounterRules.ceiling(0))
        assertEquals(top01, CounterRules.ceiling(1))
        assertEquals(allowed, CounterRules.advance(top00 - 50, top00, 0, 0))
        assertEquals(tooFar, CounterRules.advance(top00 - 50, top00 + 1, 0, 0))
        assertEquals("not even a restore's end moves a 00 counter past 2^31", tooFar, CounterRules.advance(0, top00 + 1, Long.MAX_VALUE, 0))
        assertEquals(allowed, CounterRules.advance(top01 - 1, top01, 0, 1))
        assertEquals(tooFar, CounterRules.advance(top01 - 1, top01 + 1, 0, 1))
        assertEquals(tooFar, CounterRules.advance(Long.MAX_VALUE - 10, Long.MAX_VALUE, 0, 1))
    }

    @Test
    fun reserveAtStartsAtMostAHundredPastTheCounter() {
        assertEquals(allowed, CounterRules.reserveAt(0, 100))
        assertEquals(tooFar, CounterRules.reserveAt(0, 101))
        assertEquals(allowed, CounterRules.reserveAt(1_000, 1_100))
        assertEquals(tooFar, CounterRules.reserveAt(1_000, 5_000))
        assertEquals(allowed, CounterRules.reserveAt(12, 12))
    }

    @Test
    fun importedValuesAreCutToTheCeiling() {
        assertEquals(1L shl 31, CounterRules.capped(9_007_199_254_740_991, 0))
        assertEquals(9_007_199_254_740_991, CounterRules.capped(9_007_199_254_740_991, 1))
        assertEquals(1L shl 53, CounterRules.capped(Long.MAX_VALUE, 1))
        assertEquals(44L, CounterRules.capped(44, 0))
        assertEquals(256, CounterRules.MOST_KEYSETS)
        assertEquals(100L, CounterRules.REACH)
    }
}
