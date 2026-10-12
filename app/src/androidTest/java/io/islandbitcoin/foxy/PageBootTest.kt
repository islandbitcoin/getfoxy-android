package io.islandbitcoin.foxy

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.islandbitcoin.foxy.debug.FieldLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The unmodified page, from the APK, boots to the Tor gate (S26, S16, S17,
 * S15): the shim is there, the page hash is core/PIN's, no action the page asks
 * is unknown, navigation and new windows go nowhere, and WebRTC is gone. In P2
 * Tor starts with the page, so the gate is connecting, connected or failed,
 * whichever the phone's network makes it; the claims are the same in each.
 *
 * This is also the device check of ANDROID-MAPPING decision 2: the page loads
 * through the asset loader with setBlockNetworkLoads(true) and the dead-end proxy on.
 */
@RunWith(AndroidJUnit4::class)
class PageBootTest {
    private fun pinPageHash(): String {
        val pin = InstrumentationRegistry.getInstrumentation().targetContext.assets.open("PIN").bufferedReader().readText()
        return pin.lines().first { it.startsWith("page-hash=") }.removePrefix("page-hash=").trim()
    }

    @Test
    fun theUnmodifiedPageBootsToTheTorGateAndAsksNothingUnknown() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            assertTrue("the page boots to the Tor gate or past it", Page.until(s, Page.STARTED, 90_000))

            // the shim, locked, before the page's scripts: the page's bridge calls reached native and were answered
            assertEquals("true", Page.eval(s, "(function () { var d = Object.getOwnPropertyDescriptor(window, 'webkit');" +
                " return !!d && d.configurable === false && d.writable === false && typeof window.webkit.messageHandlers.foxy.postMessage === 'function'; })()"))
            // the page hash (S26)
            assertEquals("\"${BuildConfig.PAGE_HASH}\"", Page.eval(s, "window.__foxyPageHash"))
            assertEquals(pinPageHash(), BuildConfig.PAGE_HASH)
            // the debug flags and the clock
            assertEquals(BuildConfig.DEBUG.toString(), Page.eval(s, "window.FOXY_DEBUG === true"))
            assertTrue(Page.eval(s, "typeof window.__foxyClock24") == "\"boolean\"")
            // S17: no WebRTC, here or in a fresh blank frame
            assertEquals("\"undefined\"", Page.eval(s, "typeof RTCPeerConnection"))
            assertEquals("\"undefined\"", Page.eval(s, "(function () { var f = document.createElement('iframe'); document.body.appendChild(f);" +
                " var t = f.contentWindow ? typeof f.contentWindow.RTCPeerConnection : 'undefined'; f.remove(); return t; })()"))
            // reached without contentWindow, straight after the append, and one frame further down
            assertEquals("\"undefined undefined\"", Page.eval(s, "(function () { var f = document.createElement('iframe'); document.body.appendChild(f);" +
                " var a = typeof window[window.length - 1].RTCPeerConnection;" +
                " var d = window[window.length - 1].document; var g = d.createElement('iframe'); d.body.appendChild(g);" +
                " var b = typeof window[window.length - 1][0].RTCPeerConnection; f.remove(); return a + ' ' + b; })()"))

            // nothing the page asked on boot was an unknown action (the P1 and P2 tables and the later stubs cover the boot)
            val unknown = FieldLog.lines().filter { it.text.contains("Unknown action") }
            assertTrue("unknown actions on boot: ${unknown.map { it.text }}", unknown.isEmpty())

            // S16: navigation and new windows go nowhere
            val href = Page.eval(s, "location.href")
            assertEquals("\"https://appassets.androidplatform.net/assets/web/index.html\"", href)
            Page.eval(s, "setTimeout(function () { location.href = 'https://example.com/'; }, 0); 0")
            Page.eval(s, "setTimeout(function () { window.__opened = String(window.open('https://example.com/')); }, 0); 0")
            Thread.sleep(3_000)
            assertEquals(href, Page.eval(s, "location.href"))
            assertEquals("\"null\"", Page.eval(s, "window.__opened"))
            assertTrue("still the page that booted", Page.until(s, Page.STARTED, 5_000))
        }
    }

    @Test
    fun aSubframeIsNotAnsweredAndTheMainFrameIs() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            assertTrue(Page.until(s, Page.STARTED, 90_000))
            val result = Page.async(s, """
                var heard = {};
                var real = window.FoxyWallet._scanResult;
                window.FoxyWallet._scanResult = function (id) { heard[id] = true; return real.apply(this, arguments); };
                // a subframe of the page, posting as the page would
                var f = document.createElement('iframe');
                f.srcdoc = '<p>frame</p>';
                document.body.appendChild(f);
                await new Promise(function (ok) { f.onload = ok; setTimeout(ok, 2000); });
                var w = f.contentWindow;
                var offered = !!(w && w.foxyNative);
                if (offered) w.foxyNative.postMessage(JSON.stringify({ action: 'seedStatus', id: 'frame-1', quiet: true }));
                // and the main frame, which must be answered (the control)
                window.webkit.messageHandlers.foxy.postMessage({ action: 'isDebug', id: 'main-1' });
                await new Promise(function (ok) { setTimeout(ok, 3000); });
                f.remove();
                window.FoxyWallet._scanResult = real;
                return (heard['frame-1'] ? 'frame answered' : 'frame not answered') + ', ' + (heard['main-1'] ? 'main answered' : 'main not answered');
            """.trimIndent(), 15_000)
            assertEquals("\"frame not answered, main answered\"", result)
        }
    }
}
