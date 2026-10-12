package io.islandbitcoin.foxy.keychain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeviceAuth's API 28-29 screen-lock confirmation (ScreenLockConfirmation),
 * driven through a fake launcher and a stand-in for KeyguardManager's intent.
 * PutAway must hear the answer (confirmAnswered) with `confirming` already
 * false and before `done` reaches the seed executor: a no is a return like any
 * other, and the put-away it skipped has to happen.
 */
class ScreenLockConfirmationTest {
    private val keyguardIntent = "confirm-device-credential"

    /** What happened, in order. */
    private val events = mutableListOf<String>()

    private fun confirmation(): Pair<ScreenLockConfirmation<String>, () -> (Boolean) -> Unit> {
        val c = ScreenLockConfirmation<String>()
        var answer: ((Boolean) -> Unit)? = null
        c.start = { intent, cb ->
            assertSame("the keyguard's intent is what is started", keyguardIntent, intent)
            assertTrue("confirming is set before the activity starts", c.confirming)
            events += "started"
            answer = cb
            true
        }
        c.answered = { yes ->
            assertFalse("confirming is already false when PutAway hears the answer", c.confirming)
            events += "answered:$yes"
        }
        return c to { answer!! }
    }

    private fun run(yes: Boolean): DeviceAuth.Result? {
        val (c, answer) = confirmation()
        var result: DeviceAuth.Result? = null
        c.ask(keyguardIntent) { r ->
            events += "done:$r"
            result = r
        }
        assertTrue("waiting on the person", c.confirming)
        assertEquals(listOf("started"), events)
        answer()(yes)
        assertFalse(c.confirming)
        return result
    }

    @Test fun aYesIsToldToPutAwayBeforeDone() {
        assertEquals(DeviceAuth.Result.YES, run(yes = true))
        assertEquals(listOf("started", "answered:true", "done:YES"), events)
    }

    @Test fun aNoIsToldToPutAwayBeforeDone() {
        assertEquals(DeviceAuth.Result.NO, run(yes = false))
        assertEquals(listOf("started", "answered:false", "done:NO"), events)
    }

    @Test fun noKeyguardIntentIsUnavailableAndAsksNothing() {
        val (c, _) = confirmation()
        var result: DeviceAuth.Result? = null
        c.ask(null) { result = it }
        assertEquals(DeviceAuth.Result.UNAVAILABLE, result)
        assertFalse(c.confirming)
        assertTrue(events.isEmpty())
    }

    @Test fun noLauncherIsNo() {
        val c = ScreenLockConfirmation<String>()
        c.answered = { events += "answered:$it" }
        var result: DeviceAuth.Result? = null
        c.ask(keyguardIntent) { result = it }
        assertEquals(DeviceAuth.Result.NO, result)
        assertFalse(c.confirming)
        assertTrue("nothing was asked, so nothing is told to PutAway", events.isEmpty())
    }

    @Test fun aLauncherThatCannotStartIsNoAndClearsConfirming() {
        val c = ScreenLockConfirmation<String>()
        c.start = { _, _ -> false }
        var result: DeviceAuth.Result? = null
        c.ask(keyguardIntent) { result = it }
        assertEquals(DeviceAuth.Result.NO, result)
        assertFalse(c.confirming)
    }
}
