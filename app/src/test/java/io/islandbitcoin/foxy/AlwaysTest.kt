package io.islandbitcoin.foxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** Always.run, which DeviceExitTest puts the phone back with after a real-money leg. */
class AlwaysTest {
    @Test
    fun everyStepRunsAndTheBodysFailureIsTheOneReported() {
        val ran = mutableListOf<String>()
        val money = AssertionError("the mint quote was not paid within 600 s; nothing was melted")
        val thrown = assertThrows(AssertionError::class.java) {
            Always.run(
                { ran += "body"; throw money },
                { ran += "unprotected off" },
                { ran += "off again?"; throw AssertionError("unprotected mode off again") },
                { ran += "Tor resumed" },
            )
        }
        // the money leg's own failure, not the clean-up's assertion
        assertSame(money, thrown)
        assertEquals(listOf("unprotected mode off again"), thrown.suppressed.map { it.message })
        // and Tor came back although a step before it failed
        assertEquals(listOf("body", "unprotected off", "off again?", "Tor resumed"), ran)
    }

    @Test
    fun aCleanUpFailureIsReportedWhenTheBodyPassed() {
        val ran = mutableListOf<String>()
        val thrown = assertThrows(AssertionError::class.java) {
            Always.run(
                { ran += "body" },
                { throw AssertionError("unprotected mode off again") },
                { ran += "Tor resumed"; throw IllegalStateException("second") },
            )
        }
        assertEquals("unprotected mode off again", thrown.message)
        assertEquals(listOf("second"), thrown.suppressed.map { it.message })
        assertEquals(listOf("body", "Tor resumed"), ran)
    }

    @Test
    fun nothingIsThrownWhenEverythingPasses() {
        var steps = 0
        Always.run({ steps++ }, { steps++ }, { steps++ })
        assertEquals(3, steps)
    }
}
