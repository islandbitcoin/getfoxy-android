package io.islandbitcoin.foxy.flashcard

import android.nfc.Tag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ReaderModeRadio on a fake reader mode (ENG-668, review of PR #15): reader mode going
 * off at a session's end is what starts CardTapIntake's quiet window, so a card left on
 * the phone after a read does not open the Flashcard screen again. `off` runs once per
 * stop that ended a look, and never for a stop with nothing looking.
 */
class ReaderModeRadioTest {
    private class FakeReader : ReaderModeRadio.ReaderMode {
        override var isEnabled = true
        var enables = 0
        var disables = 0
        var refuseEnable = false
        var refuseDisable = false

        override fun enable(found: (Tag) -> Unit) {
            if (refuseEnable) throw IllegalStateException("not in front")
            enables++
        }

        override fun disable() {
            disables++
            if (refuseDisable) throw IllegalStateException("already off")
        }
    }

    private val reader = FakeReader()
    private var usable = true
    private var offs = 0
    private val radio = ReaderModeRadio(reader, { usable }) { offs++ }

    @Test
    fun aStopThatEndsALookTurnsReaderModeOffAndSaysSoOnce() {
        assertTrue(radio.start {})
        assertEquals(1, reader.enables)
        radio.stop()
        assertEquals(1, reader.disables)
        assertEquals(1, offs)
        radio.stop()
        assertEquals("a second stop, nothing looking: nothing said", 1, offs)
        assertEquals(1, reader.disables)
    }

    @Test
    fun eachLookEndedIsSaidOnce() {
        repeat(3) {
            assertTrue(radio.start {})
            radio.stop()
        }
        assertEquals(3, offs)
    }

    @Test
    fun aStopWithNothingLookingSaysNothing() {
        radio.stop()
        assertEquals(0, offs)
        assertEquals(0, reader.disables)
    }

    @Test
    fun aLookThatNeverStartedIsNeverSaidToEnd() {
        usable = false
        assertFalse("the activity is going", radio.start {})
        radio.stop()
        usable = true
        reader.refuseEnable = true
        assertFalse("not in front", radio.start {})
        radio.stop()
        reader.refuseEnable = false
        reader.isEnabled = false
        assertFalse("NFC switched off", radio.start {})
        radio.stop()
        assertEquals(0, offs)
    }

    /** Android had already ended reader mode (a pause): the look still ended, and is said to. */
    @Test
    fun readerModeAlreadyOffStillEndsTheLook() {
        assertTrue(radio.start {})
        reader.refuseDisable = true
        radio.stop()
        assertEquals(1, offs)
    }

    @Test
    fun aPhoneWithNoNfcNeverStarts() {
        var said = 0
        val none = ReaderModeRadio(null, { true }) { said++ }
        assertFalse(none.available())
        assertFalse(none.start {})
        none.stop()
        assertEquals(0, said)
    }

    /** A resume puts reader mode back on for a look still going; it is not a stop. */
    @Test
    fun aResumeIsNotAStop() {
        assertTrue(radio.start {})
        radio.resumed()
        assertEquals(2, reader.enables)
        assertEquals(0, offs)
        radio.stop()
        assertEquals(1, offs)
    }
}
