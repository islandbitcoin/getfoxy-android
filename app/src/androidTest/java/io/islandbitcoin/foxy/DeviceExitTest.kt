package io.islandbitcoin.foxy

import android.app.Instrumentation
import android.content.DialogInterface
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.islandbitcoin.foxy.bridge.PromptQueue
import io.islandbitcoin.foxy.network.Route
import io.islandbitcoin.foxy.tor.Tor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The P2 device exit's opt-out leg (docs/DEVICE-TESTS.md): CONTINUE WITHOUT TOR
 * through the native dialog, then a mint and a melt over the open connection on
 * a REAL mint with REAL sats (Android is tested on real mints with real ecash).
 * The dialog is pressed here because only a person, or instrumentation, can
 * press it.
 *
 * It runs only when asked (`-e foxyExit true`; skipped otherwise), and then only
 * under the money rules (RealMoney, docs/DEVICE-TESTS.md "Real money"), checked
 * before anything is launched: the person's explicit go (`-e realMoney yes`), a
 * cap (`-e maxSats`, 100 or less unless the person adds `-e overDefaultCap yes`),
 * and the person's own invoice to melt (`-e invoice`), read and refused above
 * `maxSats` or without room under `mintSats` for the mint's fee reserve
 * (RealMoney.feeMarginSats: 64 minted pays an invoice of at most 62). The
 * mint (`-e mint`, default https://mint.minibits.cash/Bitcoin) makes an invoice for `mintSats` (default `maxSats`), printed in the
 * instrumentation output and in logcat (tag `foxy-exit`) for the person to pay
 * from their own wallet; the test waits `payTimeoutSeconds` (default 600) for
 * it and fails cleanly, having melted nothing, if it is not paid. No agent runs
 * this leg: the person and the architect do.
 *
 *   adb shell am instrument -w -e class io.islandbitcoin.foxy.DeviceExitTest -e foxyExit true \
 *     -e realMoney yes -e maxSats 64 -e invoice <an invoice of at most 62 sats from the person's own wallet> \
 *     io.islandbitcoin.foxy.test/androidx.test.runner.AndroidJUnitRunner
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

    /** A line in `am instrument`'s own output, and in logcat. */
    private fun say(line: String) {
        android.util.Log.i("foxy-exit", line)
        instrumentation.sendStatus(0, Bundle().apply { putString(Instrumentation.REPORT_KEY_STREAMRESULT, "\nfoxy-exit: $line\n") })
    }

    private fun unquoted(json: String?): String? = json?.let { JSONObject("{\"v\":$it}").optString("v", "") }

    @Test
    fun continueWithoutTorThenMintAndMelt() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("the device exit runs only when asked (-e foxyExit true)", args.getString("foxyExit") == "true")
        // the money rules first: a refusal here has launched nothing and sent nothing
        val leg = RealMoney.leg { args.getString(it) }
        Phone.assumeUnlocked()
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            assertTrue(Page.until(s, Page.STARTED, 90_000))
            instrumentation.runOnMainSync { Tor.park() }
            assertTrue("Tor off the network", waitFor(10_000) { !Tor.isRunning })
            Page.eval(s, "FoxyWallet.setUnprotected(true); 0")
            assertTrue("the dialog came up", waitFor(10_000) { PromptQueue.showing?.isShowing == true })
            instrumentation.runOnMainSync { PromptQueue.showing!!.getButton(DialogInterface.BUTTON_POSITIVE).performClick() }
            assertTrue("continuing without Tor", waitFor(10_000) { Route.unprotected })
            // off again, and Tor back, whatever happened to the money; a failed
            // step here never hides why the money leg stopped (Always, AlwaysTest)
            Always.run(
                { mintAndMelt(s, leg) },
                { Page.eval(s, "FoxyWallet.setUnprotected(false); 0") },
                { assertTrue("unprotected mode off again", waitFor(10_000) { !Route.unprotected }) },
                { instrumentation.runOnMainSync { Tor.resumed(0) } },
            )
        }
    }

    private fun mintAndMelt(s: ActivityScenario<MainActivity>, leg: RealMoney.Leg) {
        val quote = unquoted(Page.async(s, """
            var sleep = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };
            for (var t = 0; t < 120 && !FoxyWallet.connected; t++) await sleep(500);
            await FoxyWallet.connect(${JSONObject.quote(leg.mint)});
            var a = await FoxyWallet.invoice(${leg.mintSats}, 'foxy p2 exit, without Tor');
            return JSON.stringify({ bolt11: a.bolt11, hash: a.hash, sats: a.sats, mint: FoxyWallet.mintUrl });
        """.trimIndent(), 180_000))
        if (quote == null || quote.startsWith("threw ")) fail("the mint made no invoice: $quote")
        val q = JSONObject(quote!!)
        val bolt11 = q.getString("bolt11")
        val hash = q.getString("hash")
        assertEquals("the quote is for the leg's amount", leg.mintSats, q.getLong("sats"))
        // the mint's own invoice is read too: the person is never shown more than mintSats to pay
        assertEquals("the mint's invoice is for mintSats", leg.mintSats, RealMoney.invoiceSats(bolt11))
        say("PAY THIS from your own wallet: ${leg.mintSats} sats to ${q.optString("mint")}, within ${leg.payTimeoutSeconds} s:")
        say(bolt11)

        val deadline = SystemClock.elapsedRealtime() + leg.payTimeoutSeconds * 1000
        var claimed = false
        var last: String? = null
        while (!claimed && SystemClock.elapsedRealtime() < deadline) {
            last = unquoted(Page.async(s, """
                try { await FoxyWallet.claim(${JSONObject.quote(hash)}); return 'claimed'; }
                catch (e) { return 'not yet: ' + String((e && e.message) || e); }
            """.trimIndent(), 60_000))
            claimed = last == "claimed"
            if (!claimed) SystemClock.sleep(3_000)
        }
        if (!claimed) {
            say("not paid within ${leg.payTimeoutSeconds} s; nothing was melted (last: $last). A quote paid later is claimed by Foxy's sweep on its next connect to that mint.")
            fail("the mint quote was not paid within ${leg.payTimeoutSeconds} s; nothing was melted (last: $last)")
        }
        say("claimed ${leg.mintSats} sats; melting ${leg.invoiceSats} sats to the person's invoice")

        val result = unquoted(Page.async(s, """
            var paid = await FoxyWallet.pay(${JSONObject.quote(leg.invoice)});
            return JSON.stringify({ unprotected: FoxyWallet.privacy().unprotected, tor: FoxyWallet.privacy().tor, paid: !!paid });
        """.trimIndent(), 240_000))
        say("without Tor: $result")
        assertEquals("{\"unprotected\":true,\"tor\":\"connecting\",\"paid\":true}", result)
    }
}
