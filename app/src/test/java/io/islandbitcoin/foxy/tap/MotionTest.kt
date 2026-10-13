package io.islandbitcoin.foxy.tap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ENG-670: shake to TAP and the screen turned, from the sensors' readings
 * (FoxyWebView.swift `motionEnded`, `deviceTurned`).
 */
class MotionTest {
    private val g = ShakeDetector.GRAVITY.toFloat()

    @Test
    fun twoJoltsCloseTogetherAreAShakeAndThenItIsQuiet() {
        var shakes = 0
        val d = ShakeDetector { shakes++ }
        // still, in a hand, face up: one g
        for (t in 0L until 2_000L step 20) d.reading(0f, 0f, g, t)
        assertEquals(0, shakes)
        d.reading(3f * g, 0f, g, 2_000)
        assertEquals("one jolt is not a shake", 0, shakes)
        d.reading(-3f * g, 0f, g, 2_250)
        assertEquals(1, shakes)
        // the rest of the same shake, inside the quiet, is not a second one
        d.reading(3f * g, 0f, g, 2_400)
        d.reading(-3f * g, 0f, g, 2_600)
        assertEquals(1, shakes)
        // after it, a new shake counts
        d.reading(3f * g, 0f, g, 4_000)
        d.reading(-3f * g, 0f, g, 4_300)
        assertEquals(2, shakes)
    }

    @Test
    fun aKnockAPutDownAndJoltsFarApartAreNotAShake() {
        var shakes = 0
        val d = ShakeDetector { shakes++ }
        // two readings of one knock
        d.reading(0f, 0f, 3f * g, 1_000)
        d.reading(0f, 0f, 3f * g, 1_050)
        assertEquals(0, shakes)
        // a jolt, and the next one too late
        d.reading(3f * g, 0f, 0f, 5_000)
        d.reading(3f * g, 0f, 0f, 5_000 + ShakeDetector.WINDOW_MS + 1)
        assertEquals(0, shakes)
        // a walk: never more than half a g off
        for (t in 10_000L until 20_000L step 50) d.reading(0f, 0.4f * g, g * (if (t % 100 == 0L) 1.4f else 0.6f), t)
        assertEquals(0, shakes)
        // a phone dropped (near zero g) and caught once is not a shake either
        d.reading(0f, 0f, 0f, 30_000)
        d.reading(0f, 0f, 3.5f * g, 30_200)
        assertEquals(0, shakes)
    }

    @Test
    fun theTurnIsToldForPortraitAndUpsideDownOnly() {
        val told = ArrayList<Boolean>()
        val d = TurnDetector { told.add(it) }
        d.reading(5)
        assertEquals("the first reading is told", listOf(false), told)
        d.reading(10)
        d.reading(355)
        assertEquals("the same way up is not told again", listOf(false), told)
        d.reading(178)
        assertEquals(listOf(false, true), told)
        // flat on the table top-down, then landscape: nothing, and the page stays as it was
        d.reading(TurnDetector.FLAT)
        d.reading(90)
        assertEquals(listOf(false, true), told)
        // and upside down again after them is told again, as iOS's orientationDidChange is
        d.reading(185)
        assertEquals(listOf(false, true, true), told)
        // between bands: nothing changes, so a phone at a boundary does not flicker
        d.reading(45)
        d.reading(135)
        assertEquals(listOf(false, true, true), told)
        d.reading(30)
        assertEquals(listOf(false, true, true, false), told)
        d.forget()
        d.reading(0)
        assertEquals("told again after a return", listOf(false, true, true, false, false), told)
    }

    @Test
    fun theBands() {
        assertEquals(TurnDetector.Way.PORTRAIT, TurnDetector.way(0))
        assertEquals(TurnDetector.Way.PORTRAIT, TurnDetector.way(330))
        assertEquals(TurnDetector.Way.UPSIDE_DOWN, TurnDetector.way(150))
        assertEquals(TurnDetector.Way.UPSIDE_DOWN, TurnDetector.way(210))
        assertEquals(TurnDetector.Way.LANDSCAPE, TurnDetector.way(270))
        assertEquals(TurnDetector.Way.FLAT, TurnDetector.way(-1))
        assertNull(TurnDetector.way(40))
        assertNull(TurnDetector.way(230))
    }
}
