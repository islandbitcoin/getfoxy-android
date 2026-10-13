package io.islandbitcoin.foxy.tap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ENG-671: Android's one GATT operation at a time ([TapGattQueue]) and its five
 * scans in thirty seconds ([TapScanBudget]).
 */
class TapGattQueueTest {
    @Test
    fun oneOperationAtATimeTheNextOnlyWhenTheLastIsAnswered() {
        val clock = FakeClock()
        val q = TapGattQueue(clock)
        val started = ArrayList<String>()
        for (n in listOf("mtu", "discover", "cccd", "write")) q.add(n, start = { started.add(n); true }, failed = { error("$n failed") })
        assertEquals(listOf("mtu"), started)
        assertEquals("mtu", q.inFlight)
        assertEquals(3, q.queued)
        q.done()
        assertEquals(listOf("mtu", "discover"), started)
        q.done()
        q.done()
        q.done()
        assertEquals(listOf("mtu", "discover", "cccd", "write"), started)
        assertNull(q.inFlight)
    }

    @Test
    fun oneAndroidWouldNotTakeIsAnsweredFailedAndTheNextStarts() {
        val clock = FakeClock()
        val q = TapGattQueue(clock)
        val failed = ArrayList<String>()
        val started = ArrayList<String>()
        q.add("refused", start = { false }, failed = { failed.add(it) })
        q.add("thrown", start = { throw SecurityException("no permission") }, failed = { failed.add(it) })
        q.add("next", start = { started.add("next"); true }, failed = { failed.add(it) })
        assertEquals("never inside the call", emptyList<String>(), failed)
        assertEquals(listOf("next"), started)
        while (clock.runDue()) Unit
        assertEquals(listOf("Android would not take refused", "Android would not take thrown"), failed)
    }

    @Test
    fun anOperationNeverAnsweredFailsAfterItsStallAndTheQueueGoesOn() {
        val clock = FakeClock()
        val q = TapGattQueue(clock)
        val failed = ArrayList<String>()
        val started = ArrayList<String>()
        q.add("mtu", start = { started.add("mtu"); true }, failed = { failed.add(it) }, stall = 2.0)
        q.add("write", start = { started.add("write"); true }, failed = { failed.add(it) })
        clock.advance(1.9)
        assertEquals(listOf("mtu"), started)
        clock.advance(0.2)
        assertEquals(listOf("mtu was never answered"), failed)
        assertEquals(listOf("mtu", "write"), started)
        clock.advance(TapGattQueue.STALL + 0.1)
        assertEquals(listOf("mtu was never answered", "write was never answered"), failed)
        // an answer that comes late is for nobody
        q.done()
        assertNull(q.inFlight)
    }

    @Test
    fun aLateMtuAnswerDoesNotEndTheDiscoveryInFlight() {
        // ENG-679, the Pixel 8 Pro's log: the MTU given up on after 2 s, discovery started, and
        // onMtuChanged 0.3 s later, in the middle of it; it ended the discovery, so the next
        // operation would have gone to Android while discovery was still outstanding
        val clock = FakeClock()
        val said = ArrayList<String>()
        val q = TapGattQueue(clock, say = { said.add(it) })
        val started = ArrayList<String>()
        q.add("an MTU of 517", start = { started.add("mtu"); true }, failed = {}, stall = 2.0, answer = "MTU")
        q.add("service discovery", start = { started.add("discover"); true }, failed = { error("discovery failed") }, answer = "service discovery")
        q.add("notifications on", start = { started.add("cccd"); true }, failed = { error("cccd failed") }, answer = "CCCD write")
        clock.advance(2.1)
        assertEquals(listOf("mtu", "discover"), started)
        assertFalse("the late MTU answer is not the discovery's", q.done("MTU"))
        assertEquals("service discovery", q.inFlight)
        assertEquals("the CCCD write waits for the discovery", listOf("mtu", "discover"), started)
        assertTrue(said.any { it.contains("a late MTU answer") })
        assertTrue(q.done("service discovery"))
        assertEquals(listOf("mtu", "discover", "cccd"), started)
        // and an answer with nothing in flight at all is for nobody
        assertTrue(q.done("CCCD write"))
        assertFalse(q.done("CCCD write"))
        assertNull(q.inFlight)
    }

    @Test
    fun closingAnswersEverythingLeftAsFailedAndTakesNothingMore() {
        val clock = FakeClock()
        val q = TapGattQueue(clock)
        val failed = ArrayList<String>()
        q.add("a", start = { true }, failed = { failed.add("a: $it") })
        q.add("b", start = { true }, failed = { failed.add("b: $it") })
        q.close("the link is closed")
        q.add("c", start = { error("started after close") }, failed = { failed.add("c: $it") })
        while (clock.runDue()) Unit
        assertEquals(listOf("a: the link is closed", "b: the link is closed", "c: the link is closed"), failed)
        clock.advance(30.0)
        assertEquals(3, failed.size)
    }

    @Test
    fun fiveScansInThirtySecondsAndTheSixthWaitsForTheFirstToAgeOut() {
        val b = TapScanBudget()
        for (t in listOf(0.0, 1.0, 2.0, 3.0, 4.0)) {
            assertEquals(0.0, b.wait(t), 0.0)
            b.started(t)
        }
        assertEquals("the sixth would be dropped by Android", 25.0, b.wait(5.0), 1e-9)
        assertEquals(0.0, b.wait(30.0), 0.0)
        b.started(30.0)
        assertTrue(b.wait(30.5) > 0)
        assertEquals(0.0, b.wait(31.0), 0.0)
    }
}
