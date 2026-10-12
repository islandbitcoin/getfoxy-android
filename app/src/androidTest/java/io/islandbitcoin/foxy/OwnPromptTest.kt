package io.islandbitcoin.foxy

import android.app.KeyguardManager
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.islandbitcoin.foxy.bridge.Json
import io.islandbitcoin.foxy.debug.FieldLog
import io.islandbitcoin.foxy.keychain.SeedVault
import io.islandbitcoin.foxy.network.Route
import io.islandbitcoin.foxy.seed.SeedScreenHost
import io.islandbitcoin.foxy.seed.ShowWordsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Callable

/**
 * API 28-29 only: the screen-lock confirmation is Settings' activity, in
 * another process, over Foxy, so MainActivity stops and ProcessLifecycleOwner
 * says stop 700 ms later. That is Foxy's own question, not a put-away: the
 * vault's epoch does not move, the door stays open, and a seedShow of a
 * `device` seed opens SHOW WORDS once the screen lock is given (it used to
 * answer {"verified":false} without a screen). Taking the guard off asks again,
 * even within the 10 s an earlier confirmation opened the time-bound key for.
 *
 * Needs the emulator's PIN, given as `-e foxyPin 1234` (set with
 * `adb shell locksettings set-pin 1234`); skipped without it. It types the PIN
 * with `input`, as a person would.
 */
@RunWith(AndroidJUnit4::class)
class OwnPromptTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val services get() = (context.applicationContext as FoxyApplication).services

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return true
            SystemClock.sleep(100)
        }
        return false
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).close()
    }

    /** Waits for Foxy's own confirmation to be up (and Foxy stopped under it), then gives the PIN. */
    private fun givePin(pin: String) {
        assertTrue("the screen-lock confirmation came up", waitFor(10_000) { services.auth.confirming })
        // past ProcessLifecycleOwner's 700 ms: the stop that used to put Foxy away has come
        SystemClock.sleep(2_500)
        shell("input text $pin")
        SystemClock.sleep(300)
        shell("input keyevent 66")
        assertTrue("the confirmation answered", waitFor(10_000) { !services.auth.confirming })
    }

    private fun <T> onSeedThread(work: () -> T): java.util.concurrent.Future<T> = services.seedExecutor.submit(Callable { work() })

    private fun post(s: ActivityScenario<MainActivity>, message: String) {
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

    /** API 28-29, the emulator's PIN, an unlocked phone with a screen lock; the PIN, or the test is skipped. */
    private fun pinOrSkip(): String {
        assumeTrue("API 28-29: the screen lock is an activity of its own only there", Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
        val pin = InstrumentationRegistry.getArguments().getString("foxyPin")
        assumeTrue("give the emulator's PIN with -e foxyPin", !pin.isNullOrEmpty())
        Phone.assumeUnlocked()
        assumeTrue("a screen lock", context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        return pin!!
    }

    /** A seed behind the screen lock (asked for), and the 10 s that confirmation opened the key for gone by. */
    private fun guardOn(pin: String) {
        if (onSeedThread { services.vault.isThere() }.get() != true) {
            assertNull(onSeedThread { services.vault.write(WORDS) }.get())
        }
        val on = onSeedThread { services.vault.setProtection(SeedVault.DEVICE) }
        givePin(pin)
        assertNull(on.get())
        assertEquals(SeedVault.DEVICE, services.vault.protection())
        SystemClock.sleep(11_000)
    }

    private fun guardOffIfOn(pin: String) {
        if (services.vault.protection() != SeedVault.DEVICE) return
        val off = onSeedThread { services.vault.setProtection(SeedVault.NONE) }
        runCatching { givePin(pin) }
        runCatching { off.get() }
    }

    /**
     * The vault and the door under the confirmation, without the page (an
     * emulator image's WebView may be too old to load it): the seed read under
     * the prompt is kept (the PIN lock's remembered yes), the epoch does not
     * move, the door stays open, and PutAway says it put nothing away. Then
     * taking the guard off within 10 s of that yes still asks (fresh).
     */
    @Test
    fun theConfirmationIsNotAPutAwayAndTheGuardOffAlwaysAsks() {
        val pin = pinOrSkip()
        ActivityScenario.launch(MainActivity::class.java).use { _ ->
            Route.openDoor()
            try {
                guardOn(pin)
                onSeedThread { services.vault.forgetNativeSeed() }.get()
                val epoch = services.vault.epochNow
                FieldLog.clear()
                val read = onSeedThread { services.vault.seedForSecrets() }
                givePin(pin)
                assertTrue(read.get() is SeedVault.SecretsSeed.Found)
                assertEquals("the confirmation was not a put-away: the epoch did not move", epoch, services.vault.epochNow)
                assertTrue("the seed read under it is kept", onSeedThread { services.vault.kept() != null }.get())
                assertFalse("the door stayed open", Route.shut())
                assertTrue("PutAway was told it was Foxy's own prompt",
                    FieldLog.lines().any { it.text.contains("the screen lock's confirmation is over Foxy") })

                // within 10 s of that yes: taking the guard off asks again
                val off = onSeedThread { services.vault.setProtection(SeedVault.NONE) }
                givePin(pin)
                assertNull(off.get())
                assertEquals(SeedVault.NONE, services.vault.protection())
            } finally {
                guardOffIfOn(pin)
            }
        }
    }

    /** SHOW WORDS of a `device` seed opens once the screen lock is given (it used to answer verified:false, no screen). */
    @Test
    fun seedShowUnderTheScreenLockOpensTheWords() {
        val pin = pinOrSkip()
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            var loaded = false
            s.onActivity { loaded = it.host?.webView != null }
            assumeTrue("this image's WebView cannot load the page", loaded && Page.until(s, Page.STARTED, 90_000))
            Route.openDoor()
            try {
                guardOn(pin)
                val epoch = services.vault.epochNow
                post(s, "{ action: 'seedShow', id: 'show-1', verify: false }")
                givePin(pin)
                assertEquals(epoch, services.vault.epochNow)
                assertFalse(Route.shut())
                assertTrue("SHOW WORDS opened", waitFor(10_000) { SeedScreenHost.shown is ShowWordsActivity })
                instrumentation.runOnMainSync { SeedScreenHost.closeAll() }
                assertEquals(null, answer(s, "show-1")[1])
            } finally {
                guardOffIfOn(pin)
            }
        }
    }

    private companion object {
        const val WORDS = "legal winner thank year wave sausage worth useful legal winner thank yellow"
    }
}
