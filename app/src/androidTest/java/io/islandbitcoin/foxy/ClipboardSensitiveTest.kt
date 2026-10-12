package io.islandbitcoin.foxy

import android.content.ClipboardManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.islandbitcoin.foxy.bridge.ClipboardFilter
import io.islandbitcoin.foxy.bridge.PlatformRules
import io.islandbitcoin.foxy.web.Clipboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * S23 on the phone: a sensitive copy carries IS_SENSITIVE and Foxy's label; it
 * is cleared once its 120 s are up (an injected clock) and only while it is
 * still Foxy's; and the paste button reads the clipboard in its tap and never
 * before (ENG-649 decision 2). Android 10+ lets an app see the clipboard only
 * with focus, so Foxy is in front throughout.
 */
@RunWith(AndroidJUnit4::class)
class ClipboardSensitiveTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return true
            SystemClock.sleep(100)
        }
        return false
    }

    @Test
    fun aSensitiveCopyIsMarkedAndClearedAfterItsTime() {
        Phone.assumeUnlocked()
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            assertTrue("Foxy has focus", waitFor(10_000) { var f = false; s.onActivity { f = it.hasWindowFocus() }; f })
            var now = 1_000L
            val clipboard = Clipboard(context) { now }
            val manager = context.getSystemService(ClipboardManager::class.java)
            instrumentation.runOnMainSync { clipboard.copy("cashuBsensitive", sensitive = true) }
            instrumentation.runOnMainSync {
                val description = manager.primaryClipDescription!!
                assertTrue("Foxy's label", ClipboardFilter.isFoxys(description.label))
                assertTrue("marked sensitive", description.extras!!.getBoolean(ClipboardFilter.SENSITIVE_EXTRA))
                assertTrue(clipboard.isFoxys())
                clipboard.clearIfExpired()
                assertEquals("not yet", "cashuBsensitive", manager.primaryClip!!.getItemAt(0).text.toString())
            }
            now += ClipboardFilter.EXPIRE_MS
            instrumentation.runOnMainSync {
                clipboard.clearIfExpired()
                val clip = manager.primaryClip
                assertTrue("cleared", clip == null || clip.itemCount == 0 || clip.getItemAt(0).text.isNullOrEmpty())
            }
            // a plain copy is Foxy's (the restore screen refuses it) but never expires
            instrumentation.runOnMainSync { clipboard.copy("hello", sensitive = false) }
            now += 10 * ClipboardFilter.EXPIRE_MS
            instrumentation.runOnMainSync {
                assertNull(manager.primaryClipDescription!!.extras?.get(ClipboardFilter.SENSITIVE_EXTRA))
                clipboard.clearIfExpired()
                assertEquals("hello", manager.primaryClip!!.getItemAt(0).text.toString())
                clipboard.clear()
            }
        }
    }

    @Test
    fun thePasteButtonReadsTheClipboardOnlyInItsTap() {
        Phone.assumeUnlocked()
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            assertTrue(Page.until(s, Page.STARTED, 90_000))
            val services = (context.applicationContext as FoxyApplication).services
            val pasted = AtomicInteger(0)
            var before = 0
            instrumentation.runOnMainSync { services.clipboard.copy("cashuBpaste", sensitive = false) }
            s.onActivity { a ->
                before = services.clipboard.reads
                val host = a.host!!
                assertTrue(host.pasteShow(PlatformRules.Rect(20.0, 600.0, 300.0, 60.0)) { text ->
                    assertEquals("cashuBpaste", text)
                    pasted.incrementAndGet()
                })
                assertEquals("placing the button reads nothing", before, services.clipboard.reads)
                assertTrue(host.paste!!.showing)
            }
            SystemClock.sleep(500)
            s.onActivity { a ->
                assertEquals("nor does the page, or time passing", before, services.clipboard.reads)
                assertTrue(a.host!!.paste!!.button!!.performClick())
                assertEquals(before + 1, services.clipboard.reads)
                assertEquals(1, pasted.get())
                a.host!!.pasteStop()
                assertFalse(a.host!!.paste!!.showing)
            }
            instrumentation.runOnMainSync { services.clipboard.clear() }
        }
    }
}
