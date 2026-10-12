package io.islandbitcoin.foxy

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/**
 * ENG-665 on the phone: MainActivity's splash cover (FoxyWebView.swift
 * `addSplash`/`hideSplash`). The intro's still is an ImageView over the web
 * view, the activity's last child and drawn above every sibling, including a
 * view the web host adds later (the paste button, the live pane); the page's
 * `covered` takes it down, and it is out of the view tree once the 350 ms
 * fade is over. SplashCoverTest has the latch on the JVM.
 *
 * Skipped, saying why, when the page said `covered` before the test could
 * look (the cover is already down then, and there is nothing to see).
 */
@RunWith(AndroidJUnit4::class)
class SplashCoverViewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun theStillIsOverEverythingUntilThePageSaysCovered() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            val image = AtomicReference<ImageView?>()
            val container = AtomicReference<FrameLayout?>()
            var up = false
            s.onActivity { a ->
                up = a.coverUp
                image.set(a.coverView)
                container.set(a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as FrameLayout)
            }
            assumeTrue("the page said covered before the test could look; the cover is down already", up)
            val cover = image.get()
            assertNotNull("the cover is up, so its view is there", cover)
            val frame = container.get()!!

            s.onActivity { a ->
                assertSame("the cover is in the activity's container", frame, cover!!.parent)
                assertSame("added after the web host's views: the container's last child", cover, frame.getChildAt(frame.childCount - 1))
                assertEquals(ImageView.ScaleType.CENTER_CROP, cover.scaleType)
                assertNotNull("the still was read from the APK's assets", cover.drawable)
                // a view the web host adds later (PasteButton, LivePreview) comes after the cover in the tree
                val later = View(a)
                frame.addView(later)
                for (i in 0 until frame.childCount) {
                    val sibling = frame.getChildAt(i)
                    if (sibling === cover) continue
                    assertTrue("the cover (z ${cover.z}) is drawn above ${sibling.javaClass.simpleName} (z ${sibling.z})", cover.z > sibling.z)
                }
                frame.removeView(later)
                // the page says covered
                a.host!!.covered()
            }

            val end = SystemClock.elapsedRealtime() + SplashCover.FADE_MS + SLACK_MS
            var gone = false
            while (SystemClock.elapsedRealtime() < end) {
                instrumentation.waitForIdleSync()
                val parent = AtomicReference<Any?>()
                s.onActivity { parent.set(cover!!.parent) }
                if (parent.get() == null) {
                    gone = true
                    break
                }
                SystemClock.sleep(50)
            }
            assertTrue("the cover is out of the view tree within ${SplashCover.FADE_MS} ms and ${SLACK_MS} ms slack of `covered`", gone)
            s.onActivity { a ->
                assertFalse("down, and a second `covered` is no news", a.coverUp)
                assertNull(a.coverView)
                a.host!!.covered()
            }
        }
    }

    private companion object {
        const val SLACK_MS = 1_000L
    }
}
