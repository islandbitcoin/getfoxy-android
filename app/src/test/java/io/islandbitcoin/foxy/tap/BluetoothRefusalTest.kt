package io.islandbitcoin.foxy.tap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What is kept of the system's Bluetooth question: a refusal, never the asking (AndroidTapBluetooth's preferences). */
class BluetoothRefusalTest {
    private var granted = false
    private val store = MemoryRefusal()
    private val refusal = BluetoothRefusal(store) { granted }

    @Test
    fun aRefusalIsKeptAndReadUntilGranted() {
        refusal.answered(false)
        assertTrue(refusal.refused())
        granted = true
        assertFalse(refusal.refused())
        assertFalse("seen granted, the refusal is dropped", store.value)
        granted = false
        assertFalse("taken back later is asked afresh", refusal.refused())
    }

    @Test
    fun aQuestionNobodyAnsweredKeepsNothing() {
        refusal.answered(null)
        assertFalse(refusal.refused())
        refusal.answered(false)
        refusal.answered(null)
        assertTrue("an unanswered question does not wipe an earlier refusal", refusal.refused())
    }

    @Test
    fun aGrantKeepsNoRefusal() {
        granted = true
        refusal.answered(true)
        assertFalse(store.value)
        granted = false
        assertFalse(refusal.refused())
    }
}
