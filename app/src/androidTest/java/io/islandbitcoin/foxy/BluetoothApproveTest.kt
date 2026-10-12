package io.islandbitcoin.foxy

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ENG-656 on a phone: what the first-install Bluetooth screen's APPROVE posts
 * (`{ id: 'bt-…', action: 'bluetoothAsk' }`, foxy-tor-gate.js) reaches native
 * through the real shim and comes back twice, in iOS's order: the gate's
 * `window.__foxyBluetoothAnswer("unsupported")`, which is what closes the
 * screen, and then the reply on the action's own id. The page's own handler is
 * set aside for the test and put back, so a Bluetooth screen up on a fresh
 * install is left as it was.
 *
 * The second case is the screen itself, as a fresh install meets it: the
 * gate's own `#ftg-bt` (foxy-tor-gate.js `showBluetooth`), reached the way the
 * person reaches it, PROCEED on the warning and then APPROVE, with nothing of
 * the gate's set aside. It closes, and `foxy.bt.asked` says why. The launch
 * asks for the warning again (`FOXY_SHOW_WARNING`, debug builds), so the
 * phone's own wallet and keys are kept; the two keys PROCEED and APPROVE write
 * are put back as they were.
 */
@RunWith(AndroidJUnit4::class)
class BluetoothApproveTest {
    @Test
    fun approveIsPushedTheAnswerAndThenRepliedUnsupported() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            assertTrue("the page boots", Page.until(s, Page.STARTED, 90_000))
            val result = Page.async(s, """
                var heard = [];
                var page = window.__foxyBluetoothAnswer;
                window.__foxyBluetoothAnswer = function (state) { heard.push('push ' + state); };
                var real = window.FoxyWallet._scanResult;
                window.FoxyWallet._scanResult = function (id, text, error) {
                    if (id === 'bt-device') heard.push('reply ' + text + ' ' + error);
                    return real.apply(this, arguments);
                };
                window.webkit.messageHandlers.foxy.postMessage({ id: 'bt-device', action: 'bluetoothAsk' });
                await new Promise(function (ok) { setTimeout(ok, 3000); });
                window.FoxyWallet._scanResult = real;
                window.__foxyBluetoothAnswer = page;
                return heard.join(', ');
            """.trimIndent(), 15_000)
            assertEquals("\"push unsupported, reply unsupported null\"", result)
        }
    }

    @Test
    fun approveOnTheRealScreenClosesIt() {
        // the debug launch that asks for the warning again (WebHost: window.FOXY_SHOW_WARNING), which also
        // makes the Bluetooth screen due after it (foxy-tor-gate.js bluetoothDue), as on a fresh install
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra("FOXY_SHOW_WARNING", true)
        ActivityScenario.launch<MainActivity>(intent).use { s ->
            assertTrue("the page boots", Page.until(s, Page.STARTED, 90_000))
            // what this phone had, put back at the end: PROCEED and APPROVE both write theirs
            val before = Page.eval(s, "JSON.stringify([localStorage.getItem('$WARNED'), localStorage.getItem('$ASKED')])")
            try {
                /* A phone with a wallet on file opens home first and the gate's
                 * screen, which puts the warning up, is never built; the banner's
                 * tap (leaveOffline) builds it, as on a first launch. */
                if (!Page.until(s, "document.getElementById('ftg-warn-go')", 5_000)) {
                    Page.eval(s, "window.FoxyGate.leaveOffline(); 0")
                }
                assertTrue("the warning is up", Page.until(s, "document.getElementById('ftg-warn-go')", 30_000))
                Page.eval(s, "document.getElementById('ftg-warn-go').click(); 0")
                assertTrue("the Bluetooth screen follows it", Page.until(s, "document.getElementById('ftg-bt-go')", 10_000))
                Page.eval(s, "document.getElementById('ftg-bt-go').click(); 0")
                assertTrue(
                    "APPROVE closes the Bluetooth screen",
                    Page.until(
                        s,
                        "!document.getElementById('ftg-bt') && localStorage.getItem('$ASKED') === 'approved, iOS said unsupported'",
                        10_000,
                    ),
                )
            } finally {
                if (before != null) Page.eval(
                    s,
                    "(function (v) { [['$WARNED', v[0]], ['$ASKED', v[1]]].forEach(function (kv) {" +
                        " if (kv[1] === null) localStorage.removeItem(kv[0]); else localStorage.setItem(kv[0], kv[1]); }); })(JSON.parse($before)); 0",
                )
            }
        }
    }

    private companion object {
        const val WARNED = "foxy.warned"
        const val ASKED = "foxy.bt.asked"
    }
}
