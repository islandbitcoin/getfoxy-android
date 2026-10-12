package io.islandbitcoin.foxy

import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.islandbitcoin.foxy.bridge.PlatformRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The live camera pane on the phone: laid over the page it binds the camera,
 * and the activity stopping (Foxy put away) lets it go at once, so the camera
 * light never stays on in the background; a stop with nothing up does nothing.
 */
@RunWith(AndroidJUnit4::class)
class CameraUnbindTest {
    @get:Rule
    val camera: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.CAMERA)

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return true
            SystemClock.sleep(100)
        }
        return false
    }

    @Test
    fun noUseCaseIsBoundAfterTheActivityStops() {
        Phone.assumeUnlocked()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("a phone with a camera", context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            assertTrue(Page.until(s, Page.STARTED, 90_000))
            s.onActivity { a -> assertTrue(a.host!!.previewShow(PlatformRules.Rect(20.0, 400.0, 300.0, 200.0)) {}) }
            assertTrue("the camera is bound under the pane", waitFor(10_000) { var b = false; s.onActivity { b = it.host!!.live!!.bound }; b })
            s.moveToState(Lifecycle.State.CREATED)
            var bound = true
            var showing = true
            s.onActivity { a ->
                bound = a.host!!.live!!.bound
                showing = a.host!!.live!!.showing
            }
            assertFalse("no use case bound after onStop", bound)
            assertFalse(showing)
            s.onActivity { a ->
                a.host!!.previewStop()
                a.host!!.previewStop()
                assertFalse(a.host!!.live!!.bound)
            }
        }
    }
}
