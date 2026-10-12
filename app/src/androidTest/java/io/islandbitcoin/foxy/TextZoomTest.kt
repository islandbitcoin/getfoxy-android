package io.islandbitcoin.foxy

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.islandbitcoin.foxy.web.WebHost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * ENG-661: the page's text is laid out at 100 %, as WKWebView lays it out on
 * iOS, whatever the phone's font size. A WebView of the same activity left
 * alone starts at the font scale (130 at 1.3), which is what overflowed the
 * backup sheet's buttons on a Pixel 8 Pro at font_scale 1.3; Foxy's own is 100.
 *
 * The test sets the system font scale to 1.3 through the shell and puts the
 * phone's value back afterwards. It is skipped, saying why, when the scale
 * cannot be set or when a plain WebView does not follow it (then 100 would
 * prove nothing).
 */
@RunWith(AndroidJUnit4::class)
class TextZoomTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext
    private var before: String? = null

    private fun shell(command: String): String {
        val out = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes().toString(Charsets.UTF_8).trim() }
    }

    private fun waitForScale(scale: Float, timeoutMs: Long): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (abs(app.resources.configuration.fontScale - scale) < 0.01f) return true
            SystemClock.sleep(100)
        }
        return false
    }

    @Before
    fun largeText() {
        before = shell("settings get system font_scale")
        shell("settings put system font_scale $SCALE")
        assumeTrue("the font scale could not be set to $SCALE", waitForScale(SCALE, 5_000))
    }

    @After
    fun putTheScaleBack() {
        val was = before
        if (was == null || was == "null" || was.isEmpty()) shell("settings delete system font_scale")
        else shell("settings put system font_scale $was")
    }

    @Test
    fun thePageIsLaidOutAtOneHundredPercentWhateverTheFontScale() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            val plain = AtomicInteger(-1)
            val foxy = AtomicInteger(-1)
            s.onActivity { a ->
                // the control: a WebView nobody configured follows the phone's font scale
                val control = WebView(a)
                plain.set(control.settings.textZoom)
                control.destroy()
                foxy.set(a.host?.webView?.settings?.textZoom ?: -1)
            }
            assumeTrue("this phone's WebView does not follow the font scale (textZoom ${plain.get()}), so 100 would prove nothing",
                plain.get() == Math.round(SCALE * 100))
            assumeTrue("this phone's WebView lacks a feature Foxy needs; the page is not made", foxy.get() != -1)
            assertEquals("Foxy's WebView at font scale $SCALE", WebHost.PAGE_TEXT_ZOOM, foxy.get())
            assertEquals(100, WebHost.PAGE_TEXT_ZOOM)
        }
    }

    private companion object {
        const val SCALE = 1.3f
    }
}
