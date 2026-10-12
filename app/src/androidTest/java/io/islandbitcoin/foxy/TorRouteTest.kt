package io.islandbitcoin.foxy

import android.content.DialogInterface
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.islandbitcoin.foxy.bridge.Json
import io.islandbitcoin.foxy.bridge.PromptQueue
import io.islandbitcoin.foxy.network.Route
import io.islandbitcoin.foxy.tor.Tor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * P2 on a phone, through the real page, bridge, dialogs and Tor:
 *
 * - S11: CONTINUE WITHOUT TOR shows a native dialog; its Stay protected answers
 *   `unprotected: false` and its Continue sets it, for this process only;
 * - S13: a mintRequest to a host Foxy has not used shows the Allow dialog, and
 *   Don't Allow answers "You did not allow Foxy to contact …" with nothing sent;
 *   the approvals are sealed in `noBackupFilesDir`;
 * - S9: Tor taken off the network mid-request fails that request, and the next
 *   is refused with "Foxy is not connected to Tor." (needs the phone online).
 */
@RunWith(AndroidJUnit4::class)
class TorRouteTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @After
    fun restore() {
        Route.unprotected = false
        instrumentation.runOnMainSync { Tor.resumed(0) }
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return true
            SystemClock.sleep(100)
        }
        return false
    }

    /** Posts a message from the page and keeps its answer, as [text, error] JSON, in window.__answers[id]. */
    private fun post(s: ActivityScenario<MainActivity>, id: String, message: String) {
        Page.eval(s, """
            (function () {
              window.__answers = window.__answers || {};
              if (!window.__hooked) {
                window.__hooked = true;
                var real = window.FoxyWallet._scanResult;
                window.FoxyWallet._scanResult = function (i, t, e) {
                  window.__answers[i] = JSON.stringify([t, e]);
                  return real.apply(this, arguments);
                };
              }
              window.webkit.messageHandlers.foxy.postMessage($message);
              return 0;
            })()
        """.trimIndent())
    }

    @Suppress("UNCHECKED_CAST")
    private fun answer(s: ActivityScenario<MainActivity>, id: String, timeoutMs: Long = 30_000): List<String?> {
        assertTrue("an answer for $id", Page.until(s, "window.__answers && typeof window.__answers['$id'] === 'string'", timeoutMs))
        val json = Json.parse(Page.eval(s, "window.__answers['$id']")!!) as String
        return Json.parse(json) as List<String?>
    }

    private fun press(which: Int, title: String) {
        assertTrue("the dialog \"$title\" came up", waitFor(10_000) { PromptQueue.showing?.isShowing == true })
        instrumentation.runOnMainSync {
            val d = PromptQueue.showing!!
            d.getButton(which).performClick()
        }
    }

    private fun parked(s: ActivityScenario<MainActivity>) {
        instrumentation.runOnMainSync { Tor.park() }
        assertTrue("Tor off the network", waitFor(10_000) { !Tor.isRunning })
    }

    @Test
    fun continueWithoutTorIsTheDialogsAndStayProtectedAnswersNo() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            assertTrue(Page.until(s, Page.STARTED, 90_000))
            parked(s)
            post(s, "u1", """{ action: 'unprotected', id: 'u1', on: true }""")
            press(DialogInterface.BUTTON_NEGATIVE, "Continue without Tor?")
            val (text, error) = answer(s, "u1")
            assertEquals(null, error)
            assertEquals(false, (Json.parse(text!!) as Map<*, *>)["unprotected"])
            assertFalse(Route.unprotected)
            // and its yes, which only the dialog can give
            post(s, "u2", """{ action: 'unprotected', id: 'u2', on: true }""")
            press(DialogInterface.BUTTON_POSITIVE, "Continue without Tor?")
            assertEquals(true, (Json.parse(answer(s, "u2")[0]!!) as Map<*, *>)["unprotected"])
            assertTrue(Route.unprotected)
            // off again needs nothing
            post(s, "u3", """{ action: 'unprotected', id: 'u3', on: false }""")
            assertEquals(false, (Json.parse(answer(s, "u3")[0]!!) as Map<*, *>)["unprotected"])
            assertFalse(Route.unprotected)
        }
    }

    @Test
    fun aHostFoxyHasNotUsedIsAskedAboutAndDontAllowSendsNothing() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            assertTrue(Page.until(s, Page.STARTED, 90_000))
            parked(s)
            // a route, so that the request reaches the approval: the person's yes, set here
            Route.unprotected = true
            val out = Route.out
            post(s, "h1", """{ action: 'mintRequest', id: 'h1', url: 'https://mint.not-approved.example/v1/info', method: 'GET' }""")
            press(DialogInterface.BUTTON_NEGATIVE, "Allow Foxy to contact mint.not-approved.example?")
            assertEquals(listOf(null, "You did not allow Foxy to contact mint.not-approved.example."), answer(s, "h1"))
            assertEquals("nothing was sent", out, Route.out)
        }
    }

    @Test
    fun theApprovalsAreSealedInNoBackupFilesDir() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            // the approvals' key is setUnlockedDeviceRequired, as the seed's: nothing is written on a locked phone
            Phone.assumeUnlocked()
            assertTrue(Page.until(s, Page.STARTED, 90_000))
            val context = instrumentation.targetContext
            // the page carries its hosts over once, as it boots (hostsKnown)
            val carried = File(context.noBackupFilesDir, "foxy-hosts-carried.v1.bin")
            assertTrue("the carried hosts are in noBackupFilesDir", waitFor(30_000) { carried.exists() })
            assertFalse("and nowhere that backs up", File(context.filesDir, carried.name).exists())
            val bytes = carried.readBytes()
            assertEquals("sealed (format 1), not text", 1, bytes[0].toInt())
            assertTrue("a format byte, a 12-byte IV and a 16-byte tag at least", bytes.size >= 1 + 12 + 16)
        }
    }

    @Test
    fun torLeavingTheNetworkMidRequestFailsItAndRefusesTheNext() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            assertTrue(Page.until(s, Page.STARTED, 90_000))
            instrumentation.runOnMainSync { Tor.restart("the test asked") }
            assumeTrue("Tor did not connect in 3 minutes: is the phone online?", waitFor(180_000) { Tor.isRunning })
            // the route works: a request over Tor is answered by the mint
            post(s, "t0", """{ action: 'mintRequest', id: 't0', url: 'https://testnut.cashu.space/v1/info', method: 'GET' }""")
            val ok = answer(s, "t0", 120_000)
            assertTrue("answered over Tor: ${ok[1]}", ok[0]?.startsWith("200\n") == true)
            val before = Route.out
            post(s, "t1", """{ action: 'mintRequest', id: 't1', url: 'https://testnut.cashu.space/v1/info', method: 'GET' }""")
            assertTrue("the request is out", waitFor(10_000) { Route.out > before })
            instrumentation.runOnMainSync { Tor.park() }
            val first = answer(s, "t1", 90_000)
            assertNotNull("the request in flight failed", first[1])
            post(s, "t2", """{ action: 'mintRequest', id: 't2', url: 'https://testnut.cashu.space/v1/info', method: 'GET' }""")
            assertEquals(listOf(null, "Foxy is not connected to Tor."), answer(s, "t2"))
        }
    }
}
