package io.islandbitcoin.foxy

import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.islandbitcoin.foxy.bridge.FoxyBridge
import io.islandbitcoin.foxy.debug.FieldLog
import io.islandbitcoin.foxy.network.Route
import io.islandbitcoin.foxy.tor.Tor
import io.islandbitcoin.foxy.web.WebHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * S21 and the reply path's guard: Foxy put away shuts the door (nothing new may
 * leave), a return opens it and pushes `__foxyUptime` and `_resumed`, and a
 * reply that arrives after the WebView is gone does nothing. With Tor running,
 * putting away also parks Tor (off the network) and coming back sets it up
 * again; a melt out at that moment is waited for (MoneyInFlightTest).
 */
@RunWith(AndroidJUnit4::class)
class PutAwayTest {
    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return true
            SystemClock.sleep(100)
        }
        return false
    }

    @Test
    fun puttingAwayShutsTheDoorAndComingBackOpensIt() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            assertTrue(Page.until(s, Page.STARTED, 90_000))
            Route.openDoor()
            Page.eval(s, "window.__foxyUptime = undefined; 0")
            s.moveToState(Lifecycle.State.CREATED)
            // ProcessLifecycleOwner says stop 0.7 s later; 0.4 s more, the page is asked, then the door shuts
            assertTrue("the door shut while Foxy was away", waitFor(10_000) { Route.shut() })
            // coming back needs the activity in front, which a locked phone does not allow
            Phone.assumeUnlocked()
            s.moveToState(Lifecycle.State.RESUMED)
            assertTrue("the door opened on the return", waitFor(5_000) { !Route.shut() })
            assertTrue("the page heard the return", Page.until(s, "typeof window.__foxyUptime === 'object'", 5_000))
        }
    }

    /**
     * Back finishing the activity (API 28-30): onDestroy removes PutAway's
     * observer before ProcessLifecycleOwner's stop, 700 ms later, so PutAway
     * never runs. Tor must still come off the network (docs/ROUTE.md "The door
     * and backgrounding", S21), not keep its guard connections for as long as
     * the cached process lives.
     */
    @Test
    fun finishingTheActivityParksTorAlthoughPutAwayNeverRuns() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { s ->
                assertTrue(Page.until(s, Page.STARTED, 90_000))
                instrumentation.runOnMainSync { if (Tor.parked) Tor.resumed(0) else Tor.restart("the test asked") }
                assumeTrue("Tor did not connect in 3 minutes: is the phone online?", waitFor(180_000) { Tor.isRunning })
                s.onActivity { it.finish() }
                // past ProcessLifecycleOwner's 700 ms hold, which no longer has an observer to tell
                SystemClock.sleep(1_500)
                val parked = AtomicBoolean(false)
                instrumentation.runOnMainSync { parked.set(Tor.parked) }
                assertTrue("Tor is parked once the activity finished", parked.get())
                assertTrue("Tor is off the network", waitFor(5_000) { !Tor.isRunning })
            }
        } finally {
            instrumentation.runOnMainSync { Tor.resumed(0) }
            Route.openDoor()
        }
    }

    @Test
    fun aReplyAfterTheWebViewIsGoneDoesNothing() {
        val kept = AtomicReference<WebHost?>(null)
        val bridgeRef = AtomicReference<FoxyBridge?>(null)
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            assertTrue(Page.until(s, Page.STARTED, 90_000))
            // the bridge as it was while the page was up: WebHost.destroy() nulls its own field
            s.onActivity {
                kept.set(it.host)
                bridgeRef.set(it.host!!.bridge)
            }
        }
        val host = kept.get()!!
        val bridge = bridgeRef.get()
        assertNotNull("the page had a bridge", bridge)
        assertNull("the WebView is gone", host.webView)
        FieldLog.clear()
        // the activity and its WebView are gone; a late answer and a late push must not throw
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val thrown = AtomicReference<Throwable?>(null)
        instrumentation.runOnMainSync {
            try {
                bridge!!.resolve("late-1", "ok", null)
                host.evaluate("window.FoxyWallet && window.FoxyWallet._resumed(0)")
            } catch (t: Throwable) {
                thrown.set(t)
            }
        }
        // resolve posts its evaluate to the main looper: let it run
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {}
        assertNull("nothing threw", thrown.get())
        assertNull(host.webView)
        val dropped = FieldLog.lines().count { it.text == WebHost.DROPPED_LINE }
        assertEquals("the late reply and the late push were both dropped by the guard, not sent to a WebView", 2, dropped)
    }
}
