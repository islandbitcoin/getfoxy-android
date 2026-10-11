package io.islandbitcoin.foxy

import android.app.KeyguardManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeFalse

/** What the phone the tests run on is doing, read through the app's own context. */
object Phone {
    val locked: Boolean
        get() = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(KeyguardManager::class.java).isDeviceLocked

    /**
     * The seed's key is `setUnlockedDeviceRequired`: on a locked phone it cannot
     * be used, which is the point. Tests that need it say so and are skipped,
     * with this reason, rather than passing on a phone that proved nothing.
     */
    fun assumeUnlocked() = assumeFalse("the phone is locked: unlock it and run again", locked)
}
