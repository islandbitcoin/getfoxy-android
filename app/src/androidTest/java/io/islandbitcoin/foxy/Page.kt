package io.islandbitcoin.foxy

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Driving the real page in the real WebView from a test: evaluate, and wait for a condition. */
object Page {
    /** The script's result as JSON text ("null" when the page is not there). */
    fun eval(scenario: ActivityScenario<MainActivity>, js: String, timeoutMs: Long = 10_000): String? {
        val out = AtomicReference<String?>(null)
        val done = CountDownLatch(1)
        scenario.onActivity { activity ->
            val view = activity.host?.webView
            if (view == null) {
                done.countDown()
            } else {
                view.evaluateJavascript(js) {
                    out.set(it)
                    done.countDown()
                }
            }
        }
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
        return out.get()
    }

    /** Polls a boolean expression until it is true or the time is up. */
    fun until(scenario: ActivityScenario<MainActivity>, js: String, timeoutMs: Long): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (eval(scenario, "!!($js)") == "true") return true
            SystemClock.sleep(250)
        }
        return false
    }

    /** Runs an async function body and waits for what it returns, as a string. */
    fun async(scenario: ActivityScenario<MainActivity>, body: String, timeoutMs: Long): String? {
        eval(scenario, "window.__foxyTest = undefined; (async function () { $body })().then(function (r) { window.__foxyTest = String(r); }, function (e) { window.__foxyTest = 'threw ' + e; }); 0")
        if (!until(scenario, "typeof window.__foxyTest === 'string'", timeoutMs)) return null
        return eval(scenario, "window.__foxyTest")
    }

    const val BOOTED = "document.body && document.body.textContent.indexOf('CANNOT CONNECT TO TOR') >= 0 && document.body.textContent.indexOf('PROCEED OFFLINE') >= 0"
}
