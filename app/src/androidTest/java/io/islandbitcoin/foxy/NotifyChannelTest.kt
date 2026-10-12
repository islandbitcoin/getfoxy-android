package io.islandbitcoin.foxy

import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.islandbitcoin.foxy.notify.Notifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ENG-665 on the phone: the payments channel is IMPORTANCE_HIGH (heads-up),
 * and a phone that still has the default-importance "payments" an earlier
 * Foxy made (an upgrade) loses it when the app starts. Android keeps a
 * channel's importance once made, which is why the id changed.
 */
@RunWith(AndroidJUnit4::class)
class NotifyChannelTest {
    private val context = ApplicationProvider.getApplicationContext<FoxyApplication>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test
    fun theChannelIsHeadsUp() {
        Notifier.createChannel(context)
        val channel: NotificationChannel? = manager.getNotificationChannel(Notifier.CHANNEL)
        assertNotNull(channel)
        assertEquals("heads-up, as iOS's banner (turned off in Settings by the person, this fails: turn it back on)",
            NotificationManager.IMPORTANCE_HIGH, channel!!.importance)
    }

    @Test
    fun anUpgradedPhoneLosesTheOldChannel() {
        // what P3 made, at its default importance
        manager.createNotificationChannel(NotificationChannel("payments", "Payments", NotificationManager.IMPORTANCE_DEFAULT))
        assertNotNull(manager.getNotificationChannel("payments"))
        Notifier.createChannel(context)
        assertNull("the retired channel is deleted", manager.getNotificationChannel("payments"))
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(Notifier.CHANNEL).importance)
    }
}
