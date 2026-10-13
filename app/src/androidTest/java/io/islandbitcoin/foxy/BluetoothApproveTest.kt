package io.islandbitcoin.foxy

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.islandbitcoin.foxy.tap.BluetoothAnswer
import io.islandbitcoin.foxy.tap.TapBluetooth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ENG-656 on a phone: what the first-install Bluetooth screen's APPROVE posts
 * (`{ id: 'bt-…', action: 'bluetoothAsk' }`, foxy-tor-gate.js) reaches native
 * through the real shim and comes back twice, in iOS's order: the gate's
 * `window.__foxyBluetoothAnswer(state)`, which is what closes the screen, and
 * then the reply on the action's own id. The page's own handler is set aside
 * for the test and put back, so a Bluetooth screen up on a fresh install is
 * left as it was.
 *
 * ENG-670: the state is the phone's, as `BluetoothAnswer` reads it, not a fixed
 * "unsupported": BLUETOOTH_ADVERTISE and BLUETOOTH_CONNECT are granted to the
 * app before each test (API 31+, so the system's question does not come up),
 * and the answer must be the one this phone's adapter gives, `allowed` with
 * Bluetooth on, `off` with it off, `unsupported` on a phone that cannot
 * advertise. Run it once with Bluetooth on and once with it off.
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
    @Before
    fun grantTheTwo() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val app = ApplicationProvider.getApplicationContext<Context>().packageName
        for (p in BluetoothAnswer.permissions(Build.VERSION.SDK_INT)) ui.grantRuntimePermission(app, p)
    }

    /** What this phone should answer, read from its adapter as the app reads it. */
    @SuppressLint("MissingPermission") // granted above; getState and the advertiser need nothing more
    private fun expected(): String {
        val adapter = ApplicationProvider.getApplicationContext<Context>().getSystemService(BluetoothManager::class.java)?.adapter
            ?: return BluetoothAnswer.UNSUPPORTED
        val power = when (adapter.state) {
            BluetoothAdapter.STATE_ON -> TapBluetooth.Power.ON
            BluetoothAdapter.STATE_TURNING_ON -> TapBluetooth.Power.TURNING
            else -> TapBluetooth.Power.OFF
        }
        val advertises = power == TapBluetooth.Power.ON && adapter.isMultipleAdvertisementSupported && adapter.bluetoothLeAdvertiser != null
        return BluetoothAnswer.answer(true, true, power, advertises) ?: BluetoothAnswer.UNKNOWN
    }

    @Test
    fun approveIsPushedTheAnswerAndThenRepliedWithIt() {
        val state = expected()
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
            assertEquals("\"push $state, reply $state null\"", result)
        }
    }

    @Test
    fun approveOnTheRealScreenClosesIt() {
        // the debug launch that asks for the warning again (WebHost: window.FOXY_SHOW_WARNING), which also
        // makes the Bluetooth screen due after it (foxy-tor-gate.js bluetoothDue), as on a fresh install
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra("FOXY_SHOW_WARNING", true)
        val state = expected()
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
                        "!document.getElementById('ftg-bt') && localStorage.getItem('$ASKED') === 'approved, iOS said $state'",
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
