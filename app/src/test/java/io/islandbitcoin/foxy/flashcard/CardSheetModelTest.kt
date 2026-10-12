package io.islandbitcoin.foxy.flashcard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Foxy's card sheet shows in each state (flashcard/CardSheetModel.kt), as
 * iOS's CoreNFC sheet shows it: "Ready to Scan" and the page's line with
 * Cancel while it looks; the line as it changes; a tick and the page's word,
 * or the reason in red, with no Cancel, before it goes by itself.
 */
class CardSheetModelTest {
    @Test
    fun lookingIsReadyToScanWithThePagesLineAndCancel() {
        val m = CardSheetModel.looking("Tap behind the phone.")
        assertEquals("Ready to Scan", m.title)
        assertEquals("Tap behind the phone.", m.line)
        assertEquals(CardSheetModel.Mark.LOOKING, m.mark)
        assertTrue(m.cancellable)
        assertNull("it waits for the next word", m.holdMs)
        assertEquals("Cancel", CardSheetModel.CANCEL)
    }

    @Test
    fun theLineIsAlwaysOneShortLine() {
        val m = CardSheetModel.looking("one\ntwo\u0007" + "x".repeat(300))
        assertEquals(120, m.line.length)
        assertFalse(m.line.contains("\n"))
        assertEquals("Keepthe card there", m.saying("Keep\nthe card there").line)
    }

    @Test
    fun theLineChangesWhileItLooksAndTheTitleStays() {
        val m = CardSheetModel.looking("Tap").saying(CardGate.SCANNING)
        assertEquals("Reading the card", m.line)
        assertEquals("Ready to Scan", m.title)
        assertTrue(m.cancellable)
    }

    @Test
    fun finishedIsATickAndTheWordThenItGoes() {
        val done = CardSheetModel.looking("Tap").finished("Done.")
        assertEquals(CardSheetModel.Mark.DONE, done.mark)
        assertEquals("Done.", done.line)
        assertFalse(done.cancellable)
        assertEquals(CardSheetModel.FINISH_MS, done.holdMs)
        assertEquals("with no word, the line it had", "Reading the card", CardSheetModel.looking("Tap").saying(CardGate.SCANNING).finished("").line)
        assertEquals("nothing more is said on a sheet that has finished", done, done.saying("later"))
    }

    @Test
    fun failedIsTheReasonInRedThenItGoes() {
        val failed = CardSheetModel.looking("Tap").failed("Card taken away too soon.")
        assertEquals(CardSheetModel.Mark.FAILED, failed.mark)
        assertEquals("Card taken away too soon.", failed.line)
        assertFalse(failed.cancellable)
        assertEquals(CardSheetModel.FAIL_MS, failed.holdMs)
        assertTrue("a reason stays longer than a tick", CardSheetModel.FAIL_MS > CardSheetModel.FINISH_MS)
        assertEquals(failed, failed.saying("later"))
    }
}
