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
        val ops = listOf("mtu" to GattAnswer.MTU, "discover" to GattAnswer.DISCOVERY, "cccd" to GattAnswer.CCCD_WRITE, "write" to GattAnswer.WRITE)
        for ((n, a) in ops) q.add(n, a, start = { started.add(n); true }, failed = { error("$n failed") })
        assertEquals(listOf("mtu"), started)
        assertEquals("mtu", q.inFlight)
        assertEquals(3, q.queued)
        assertTrue(q.done(GattAnswer.MTU))
        assertEquals(listOf("mtu", "discover"), started)
        assertTrue(q.done(GattAnswer.DISCOVERY))
        assertTrue(q.done(GattAnswer.CCCD_WRITE))
        assertTrue(q.done(GattAnswer.WRITE))
        assertEquals(listOf("mtu", "discover", "cccd", "write"), started)
        assertNull(q.inFlight)
    }

    @Test
    fun oneAndroidWouldNotTakeIsAnsweredFailedAndTheNextStarts() {
        val clock = FakeClock()
        val q = TapGattQueue(clock)
        val failed = ArrayList<String>()
        val started = ArrayList<String>()
        q.add("refused", GattAnswer.WRITE, start = { false }, failed = { failed.add(it) })
        q.add("thrown", GattAnswer.READ, start = { throw SecurityException("no permission") }, failed = { failed.add(it) })
        q.add("next", GattAnswer.RSSI, start = { started.add("next"); true }, failed = { failed.add(it) })
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
        q.add("mtu", GattAnswer.MTU, start = { started.add("mtu"); true }, failed = { failed.add(it) }, stall = 2.0)
        q.add("write", GattAnswer.WRITE, start = { started.add("write"); true }, failed = { failed.add(it) })
        clock.advance(1.9)
        assertEquals(listOf("mtu"), started)
        clock.advance(0.2)
        assertEquals(listOf("mtu was never answered"), failed)
        assertEquals(listOf("mtu", "write"), started)
        clock.advance(TapGattQueue.STALL + 0.1)
        assertEquals(listOf("mtu was never answered", "write was never answered"), failed)
        // an answer that comes late is for nobody
        assertFalse(q.done(GattAnswer.WRITE))
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
        q.add("an MTU of 517", GattAnswer.MTU, start = { started.add("mtu"); true }, failed = {}, stall = 2.0)
        q.add("service discovery", GattAnswer.DISCOVERY, start = { started.add("discover"); true }, failed = { error("discovery failed") })
        q.add("notifications on", GattAnswer.CCCD_WRITE, start = { started.add("cccd"); true }, failed = { error("cccd failed") })
        clock.advance(2.1)
        assertEquals(listOf("mtu", "discover"), started)
        assertFalse("the late MTU answer is not the discovery's", q.done(GattAnswer.MTU))
        assertEquals("service discovery", q.inFlight)
        assertEquals("the CCCD write waits for the discovery", listOf("mtu", "discover"), started)
        assertTrue(said.any { it.contains("a late MTU answer") })
        assertTrue(q.done(GattAnswer.DISCOVERY))
        assertEquals(listOf("mtu", "discover", "cccd"), started)
        // and an answer with nothing in flight at all is for nobody
        assertTrue(q.done(GattAnswer.CCCD_WRITE))
        assertFalse(q.done(GattAnswer.CCCD_WRITE))
        assertNull(q.inFlight)
    }

    @Test
    fun aDroppedLateAnswerLeavesTheStallTimerOfTheOpInFlightRunning() {
        // the stall timer is all that ends an operation whose own answer never comes: a late
        // answer dropped must not cancel it, or the discovery would hang the queue forever
        val clock = FakeClock()
        val q = TapGattQueue(clock)
        val started = ArrayList<String>()
        val failed = ArrayList<String>()
        q.add("an MTU of 517", GattAnswer.MTU, start = { started.add("mtu"); true }, failed = {}, stall = 2.0)
        q.add("service discovery", GattAnswer.DISCOVERY, start = { started.add("discover"); true }, failed = { failed.add(it) })
        q.add("notifications on", GattAnswer.CCCD_WRITE, start = { started.add("cccd"); true }, failed = { failed.add(it) })
        clock.advance(2.1)
        assertFalse(q.done(GattAnswer.MTU))
        // the discovery's real answer never comes
        clock.advance(TapGattQueue.STALL - 0.2)
        assertEquals("not before its own stall", emptyList<String>(), failed)
        clock.advance(0.3)
        assertEquals(listOf("service discovery was never answered"), failed)
        assertEquals(listOf("mtu", "discover", "cccd"), started)
        assertEquals("notifications on", q.inFlight)
    }

    @Test
    fun everyAnswerEndsOnlyTheOperationThatAskedForIt() {
        // each callback against each kind of operation: only its own ends it
        for (asked in GattAnswer.values()) {
            for (came in GattAnswer.values()) {
                val q = TapGattQueue(FakeClock())
                q.add("op", asked, start = { true }, failed = {})
                assertEquals("$came answering $asked", came == asked, q.done(came))
                assertEquals(if (came == asked) null else "op", q.inFlight)
            }
        }
    }

    @Test
    fun closingAnswersEverythingLeftAsFailedAndTakesNothingMore() {
        val clock = FakeClock()
        val q = TapGattQueue(clock)
        val failed = ArrayList<String>()
        q.add("a", GattAnswer.WRITE, start = { true }, failed = { failed.add("a: $it") })
        q.add("b", GattAnswer.WRITE, start = { true }, failed = { failed.add("b: $it") })
        q.close("the link is closed")
        q.add("c", GattAnswer.WRITE, start = { error("started after close") }, failed = { failed.add("c: $it") })
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

    private class AndroidScanner(val clock: FakeClock, var takes: Boolean = true) {
        val starts = ArrayList<Double>()
        val stops = ArrayList<Double>()
        var lost = 0
        val budget = TapScanBudget()
        val cycle = TapScanCycle(
            clock,
            budget,
            start = { if (takes) starts.add(clock.now()); takes },
            stop = { stops.add(clock.now()) },
            lost = { lost++ },
        )
    }

    @Test
    fun anUnfilteredScanIsStartedAgainBeforeAndroidsThirtyMinuteTimeout() {
        // ENG-679 review: AOSP's ScanManager moves an unfiltered scan that has run
        // scan_timeout_millis (30 min) to opportunistic mode with no onScanFailed
        val clock = FakeClock()
        val a = AndroidScanner(clock)
        val t0 = clock.now()
        assertTrue(a.cycle.startScan())
        assertEquals(listOf(t0), a.starts)
        clock.advance(TapScanCycle.CYCLE - 1)
        assertEquals("nothing before the cycle", 1, a.starts.size)
        clock.advance(1.0)
        assertEquals(2, a.starts.size)
        assertEquals(1, a.stops.size)
        assertTrue("restarted before 30 minutes", a.starts[1] - t0 < 30 * 60.0)
        assertTrue(a.cycle.scanning)
        // an hour and a half in front: every run is under thirty minutes, and the budget is never exceeded
        clock.advance(90 * 60.0)
        for (i in 1 until a.starts.size) assertTrue(a.starts[i] - a.starts[i - 1] < 30 * 60.0)
        for (s in a.starts) assertTrue(a.starts.count { it >= s && it < s + TapScanBudget.WINDOW } <= TapScanBudget.MOST)
        assertEquals(0, a.lost)
    }

    @Test
    fun aRestartTheBudgetWouldRefuseWaitsForItAndAStoppedScanIsNotRestarted() {
        val clock = FakeClock()
        val a = AndroidScanner(clock)
        val t0 = clock.now()
        a.cycle.startScan()
        // five starts spent in the process just before the cycle comes round (the page re-listening)
        clock.advance(TapScanCycle.CYCLE - 5)
        repeat(TapScanBudget.MOST) { a.budget.started(clock.now()) }
        clock.advance(5.0)
        assertEquals("stopped at the cycle", listOf(t0 + TapScanCycle.CYCLE), a.stops)
        assertEquals("a sixth start would be dropped by Android: it waits", 1, a.starts.size)
        clock.advance(TapScanBudget.WINDOW - 5 - 0.1)
        assertEquals(1, a.starts.size)
        clock.advance(0.2)
        assertEquals(2, a.starts.size)
        assertEquals(t0 + TapScanCycle.CYCLE - 5 + TapScanBudget.WINDOW, a.starts[1], 1e-9)
        assertTrue(a.cycle.scanning)
        assertEquals(0, a.lost)
        // put away: no more starts, ever
        a.cycle.stopScan()
        assertFalse(a.cycle.scanning)
        clock.advance(3 * TapScanCycle.CYCLE)
        assertEquals(2, a.starts.size)
    }

    @Test
    fun putAwayWhileARestartWaitsForTheBudgetStartsNothing() {
        val clock = FakeClock()
        val a = AndroidScanner(clock)
        a.cycle.startScan()
        clock.advance(TapScanCycle.CYCLE - 1)
        repeat(TapScanBudget.MOST) { a.budget.started(clock.now()) }
        clock.advance(1.0)
        assertFalse(a.cycle.scanning)
        a.cycle.stopScan()
        clock.advance(TapScanBudget.WINDOW * 2)
        assertEquals(1, a.starts.size)
        assertEquals("not stopped twice", 1, a.stops.size)
    }

    @Test
    fun aRestartAndroidRefusesIsHandedToThePayer() {
        val clock = FakeClock()
        val a = AndroidScanner(clock)
        a.cycle.startScan()
        a.takes = false
        clock.advance(TapScanCycle.CYCLE)
        assertEquals(1, a.lost)
        assertFalse(a.cycle.scanning)
        assertTrue(a.cycle.wanted)
        // the payer starts it again (TapPayer.scanFailed), and it is counted from then
        a.takes = true
        assertTrue(a.cycle.startScan())
        assertEquals(2, a.starts.size)
    }

    @Test
    fun aScanAndroidSaysFailedIsNotCycled() {
        val clock = FakeClock()
        val a = AndroidScanner(clock)
        a.cycle.startScan()
        a.cycle.failed()
        clock.advance(2 * TapScanCycle.CYCLE)
        assertEquals("the payer restarts a failed scan, not the cycle", 1, a.starts.size)
        assertEquals(0, a.stops.size)
    }
}
