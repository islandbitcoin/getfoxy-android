package io.islandbitcoin.foxy

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.islandbitcoin.foxy.seed.SeedScreenHost
import io.islandbitcoin.foxy.seed.ShowWordsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/**
 * S19 and S20 on the phone: a seed screen's window is FLAG_SECURE like the
 * page's, and Foxy put away (HOME) finishes it, answered as its back button
 * answers, so no words are left on screen over the PIN lock on the way back.
 * Words that are no one's (a BIP-39 test vector).
 */
@RunWith(AndroidJUnit4::class)
class SeedScreenStopTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return true
            SystemClock.sleep(100)
        }
        return false
    }

    @Test
    fun backgroundingFinishesTheWordsScreen() {
        Phone.assumeUnlocked()
        val words = "legal winner thank year wave sausage worth useful legal winner thank yellow".split(" ")
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            val answer = AtomicReference<Triple<Boolean, Boolean, Boolean>?>(null)
            s.onActivity { a ->
                assertTrue(SeedScreenHost.showWords(a, SeedScreenHost.Show(words, false, true) { v, d, r -> answer.set(Triple(v, d, r)) }))
            }
            assertTrue("the screen opened", waitFor(5_000) { SeedScreenHost.shown is ShowWordsActivity })
            val screen = SeedScreenHost.shown!!
            instrumentation.runOnMainSync {
                assertTrue("FLAG_SECURE", screen.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
            assertNull("no answer while it is up", answer.get())
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            assertTrue("put away: the screen finished", waitFor(10_000) { screen.isDestroyed })
            assertTrue("and answered", waitFor(5_000) { answer.get() != null })
            assertEquals("as closed: not verified, nothing asked, nothing revealed", Triple(false, false, false), answer.get())
            assertNull(SeedScreenHost.shown)
        }
    }

    @Test
    fun closingFromOutsideAnswersAScreenNotYetOpen() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            val answer = AtomicReference<String?>("unanswered")
            s.onActivity { a ->
                assertTrue(SeedScreenHost.enterWords(a, SeedScreenHost.Enter(listOf("abandon")) { answer.set(it) }))
                // the seed changed (or the page went) before the screen opened
                SeedScreenHost.closeAll()
            }
            assertTrue(waitFor(5_000) { answer.get() == null })
            SystemClock.sleep(1_000)
            assertNotNull("the screen that opened late found nothing to show and closed", s)
            assertTrue(waitFor(5_000) { SeedScreenHost.shown == null })
        }
    }
}
