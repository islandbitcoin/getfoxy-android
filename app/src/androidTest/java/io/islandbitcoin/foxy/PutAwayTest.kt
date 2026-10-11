package io.islandbitcoin.foxy

import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.islandbitcoin.foxy.network.Route
import io.islandbitcoin.foxy.web.WebHost
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/**
 * S21 and the reply path's guard: Foxy put away shuts the door (nothing new may
 * leave), a return opens it and pushes `__foxyUptime` and `_resumed`, and a
 * reply that arrives after the WebView is gone does nothing. (No request can be
 * out in P1: nothing has a route.)
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
            assertTrue(Page.until(s, Page.BOOTED, 90_000))
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

    @Test
    fun aReplyAfterTheWebViewIsGoneDoesNothing() {
        val kept = AtomicReference<WebHost?>(null)
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            assertTrue(Page.until(s, Page.BOOTED, 90_000))
            s.onActivity { kept.set(it.host) }
        }
        val host = kept.get()!!
        val bridge = AtomicReference(host.bridge)
        // the activity and its WebView are gone; a late answer and a late push must not throw
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            host.evaluate("window.FoxyWallet && window.FoxyWallet._resumed(0)")
            bridge.get()?.resolve("late-1", "ok", null)
        }
        SystemClock.sleep(500)
        assertFalse(host.webView != null)
    }
}
