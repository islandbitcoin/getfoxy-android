package io.islandbitcoin.foxy

import androidx.test.core.app.ActivityScenario
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
}
