package io.islandbitcoin.foxy.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a scanner does with each code read (QRScannerViewController.swift and
 * LiveScanner.swift): a plain code at once, an animated one when whole, the live
 * pane's repeats held back for 3 s; and the close-focus zoom.
 */
class ScanSessionTest {
    @Test
    fun aPlainCodeIsHandedOnAtOnce() {
        val s = ScanSession({ 0L }, live = false)
        assertEquals(ScanSession.Next.Hand("cashuBabc"), s.read("cashuBabc"))
        assertEquals("the full-screen scanner answers once and closes; it repeats nothing itself", ScanSession.Next.Hand("cashuBabc"), s.read("cashuBabc"))
        assertEquals(ScanSession.Next.Nothing, s.read(""))
    }

    @Test
    fun theLivePaneHandsTheSameCodeOnAgainOnlyAfter3s() {
        var now = 1_000L
        val s = ScanSession({ now }, live = true)
        assertEquals(ScanSession.Next.Hand("lnbc1"), s.read("lnbc1"))
        now += 2_999
        assertEquals(ScanSession.Next.Nothing, s.read("lnbc1"))
        assertEquals("another code at once", ScanSession.Next.Hand("lnbc2"), s.read("lnbc2"))
        now += 3_000
        assertEquals(ScanSession.Next.Hand("lnbc1"), s.read("lnbc1"))
    }

    @Test
    fun anAnimatedCodeIsGatheredAndHandedOnWhole() {
        val c = AnimatedQRReaderTest.cases.first { it.seqLength == 5 }
        val s = ScanSession({ 0L }, live = true)
        val steps = c.frames.take(5).map { s.read(it) }
        assertEquals(ScanSession.Next.Progress(20), steps[0])
        assertEquals(ScanSession.Next.Progress(80), steps[3])
        assertEquals(ScanSession.Next.Hand(c.token), steps[4])
        assertEquals("the loop still on screen is the same code, not a new one", ScanSession.Next.Nothing, s.read(c.frames[6]))
        s.reset()
        assertTrue(s.read("ur:bytes/1-3/zzzz") is ScanSession.Next.Rejected)
    }

    @Test
    fun closeFocusZoomsOnlyWhereTheLensCannotFocusClose() {
        val half = Math.toRadians(35.0)
        // the code must be at (25 / 0.25) / tan(35°) = 142.8 mm
        assertEquals(1.0, closeFocus(100.0, half, 10.0), 0.0)
        assertEquals(200.0 / 142.81480067, closeFocus(200.0, half, 10.0), 1e-6)
        assertEquals("never past the camera's own", 1.2, closeFocus(1000.0, half, 1.2), 0.0)
        assertEquals("a camera that does not say", 1.0, closeFocus(-1.0, half, 10.0), 0.0)
        assertEquals(1.0, closeFocus(200.0, 0.0, 10.0), 0.0)
        assertEquals(1.0, closeFocus(Double.NaN, half, 10.0), 0.0)
    }
}
