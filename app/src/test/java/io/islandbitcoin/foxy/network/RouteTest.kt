package io.islandbitcoin.foxy.network

import io.islandbitcoin.foxy.network.Route.Leaving
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Route in P1 (S9, S21): nothing is available, every start is refused with the
 * fixed text, the door shuts for 5 s at a time and opens by itself or on a
 * return (injected clock), and the putting-away rule. Ported from RouteTests.swift.
 */
class RouteTest {
    @After
    fun open() {
        Route.openDoor()
        Route.clock = { System.nanoTime() / 1_000_000 }
    }

    @Test
    fun nothingIsAvailableAndEveryRequestIsRefused() {
        assertFalse(Route.available)
        assertEquals("Foxy is not connected to Tor.", Route.REFUSAL)
        assertNull(Route.start("mint.minibits.cash"))
        assertEquals(0, Route.out)
        Route.shutDoor()
        assertNull(Route.start("mint.minibits.cash"))
        assertEquals("a request that was refused is not out", 0, Route.out)
    }

    @Test
    fun theDoorShutsForFiveSecondsAndOpensByItself() {
        assertEquals(5_000L, Route.SHUT_FOR_MS)
        assertFalse(Route.shut(100_000))
        Route.shutDoor(100_000)
        assertTrue(Route.shut(100_000))
        assertTrue(Route.shut(100_000 + Route.SHUT_FOR_MS - 100))
        assertFalse("nobody opened it: a missed return cannot leave it shut", Route.shut(100_000 + Route.SHUT_FOR_MS))
        Route.shutDoor(200_000)
        Route.openDoor()
        assertFalse(Route.shut(200_100))
        // with the injected clock
        var now = 1_000L
        Route.clock = { now }
        Route.shutDoor()
        assertTrue(Route.shut())
        now += 4_999
        assertTrue(Route.shut())
        now += 1
        assertFalse(Route.shut())
    }

    @Test
    fun aSwapAndItsTopUpAreWaitedFor() {
        assertEquals(Leaving.Wait, Route.leaving(1, false, 1, 400, 0))
        assertEquals(Leaving.Wait, Route.leaving(0, true, 0, 12_000, 0))
        assertEquals(Leaving.Wait, Route.leaving(1, false, 3, 19_000, 9_000))
    }

    @Test
    fun afterTwentySecondsWhateverIsOutIsLeft() {
        assertEquals(Leaving.Leave(0), Route.leaving(1, false, 1, 20_100, 0))
        assertEquals(Leaving.Leave(0), Route.leaving(0, true, 0, 20_100, 0))
        assertEquals(Leaving.Leave(0), Route.leaving(0, false, 2, 20_100, 1_000))
        assertEquals(Leaving.Leave(500), Route.leaving(0, false, 0, 20_100, 0))
    }

    @Test
    fun aRequestThatMovesNoMoneyIsWaitedForThreeSeconds() {
        assertEquals(Leaving.Wait, Route.leaving(0, false, 1, 400, 0))
        assertEquals(Leaving.Wait, Route.leaving(0, false, 2, 3_200, 2_900))
        assertEquals(Leaving.Leave(0), Route.leaving(0, false, 2, 3_600, 3_100))
        assertEquals(Leaving.Wait, Route.leaving(0, false, 1, 15_000, 500))
    }

    @Test
    fun withNothingOutItLeavesAfterHalfASecond() {
        assertEquals(Leaving.Leave(500), Route.leaving(0, false, 0, 400, 0))
        assertEquals(Leaving.Leave(500), Route.leaving(0, false, 0, 9_000, 3_500))
    }
}
