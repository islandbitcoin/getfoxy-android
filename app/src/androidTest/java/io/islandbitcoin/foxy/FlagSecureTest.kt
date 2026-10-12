package io.islandbitcoin.foxy

import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** S19: the window is FLAG_SECURE, so Recents shows no wallet and capture is refused. */
@RunWith(AndroidJUnit4::class)
class FlagSecureTest {
    @Test
    fun theWindowIsSecure() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            s.onActivity { a ->
                assertTrue(a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
        }
    }
}
