package io.islandbitcoin.foxy

import android.content.DialogInterface
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.islandbitcoin.foxy.bridge.PromptQueue
import io.islandbitcoin.foxy.network.Route
import io.islandbitcoin.foxy.tor.Tor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The P2 device exit's opt-out leg (docs/DEVICE-TESTS.md): CONTINUE WITHOUT TOR
 * through the native dialog, then a mint and a melt on testnut.cashu.space (fake
 * money) over the open connection. The dialog is pressed here because only a
 * person, or instrumentation, can press it. It moves (fake) money and leaves the
 * phone without Tor, so it runs only when asked, with a BOLT11 invoice of 8 sats
 * for testnut's FakeWallet to "pay" (its own invoices are paid as they are made,
 * so the melt needs one from elsewhere; any validly signed invoice will do):
 *
 *   adb shell am instrument -w -e class io.islandbitcoin.foxy.DeviceExitTest -e foxyExit true \
 *     -e invoice lnbc80n1... io.islandbitcoin.foxy.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class DeviceExitTest {
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
    fun continueWithoutTorThenMintAndMelt() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("the device exit runs only when asked (-e foxyExit true)", args.getString("foxyExit") == "true")
        val invoice = args.getString("invoice") ?: ""
        assertTrue("an 8-sat BOLT11 invoice to melt (-e invoice …)", invoice.matches(Regex("^lnbc80n1[02-9ac-hj-np-z]+$")))
        Phone.assumeUnlocked()
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            assertTrue(Page.until(s, Page.STARTED, 90_000))
            instrumentation.runOnMainSync { Tor.park() }
            assertTrue("Tor off the network", waitFor(10_000) { !Tor.isRunning })
            Page.eval(s, "FoxyWallet.setUnprotected(true); 0")
            assertTrue("the dialog came up", waitFor(10_000) { PromptQueue.showing?.isShowing == true })
            instrumentation.runOnMainSync { PromptQueue.showing!!.getButton(DialogInterface.BUTTON_POSITIVE).performClick() }
            assertTrue("continuing without Tor", waitFor(10_000) { Route.unprotected })
            val result = Page.async(s, """
                var sleep = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };
                await FoxyWallet.connect('https://testnut.cashu.space');
                var a = await FoxyWallet.invoice(64, 'foxy p2 exit, without Tor');
                var claimed = null;
                for (var i = 0; i < 20 && !claimed; i++) { try { claimed = await FoxyWallet.claim(a.hash); } catch (e) { await sleep(1500); } }
                var paid = await FoxyWallet.pay('$invoice');
                return JSON.stringify({ unprotected: FoxyWallet.privacy().unprotected, tor: FoxyWallet.privacy().tor, claimed: !!claimed, paid: !!paid });
            """.trimIndent(), 240_000)
            android.util.Log.i("foxy-exit", "without Tor: $result")
            assertEquals("\"{\\\"unprotected\\\":true,\\\"tor\\\":\\\"connecting\\\",\\\"claimed\\\":true,\\\"paid\\\":true}\"", result)
            // and off again, and Tor back
            Page.eval(s, "FoxyWallet.setUnprotected(false); 0")
            assertTrue(waitFor(10_000) { !Route.unprotected })
            instrumentation.runOnMainSync { Tor.resumed(0) }
        }
    }
}
