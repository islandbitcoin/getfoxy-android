package io.islandbitcoin.foxy.notify

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ENG-665: "Payment received" is a heads-up, as iOS's foreground banner
 * (ForegroundNotifications.swift `[.banner, .sound]`). Android keeps the
 * importance a channel was made with, so an upgraded phone would keep the
 * old default-importance "payments" for ever: the high channel has an id of
 * its own and the old one is deleted, but a channel the person turned off
 * stays off.
 */
class NotifierTest {
    @Test
    fun theChannelIsHighImportanceUnderANewId() {
        assertEquals("heads-up", NotificationManager.IMPORTANCE_HIGH, Notifier.IMPORTANCE)
        assertEquals("payments_heads_up", Notifier.CHANNEL)
        assertTrue("the default-importance channel P3 made is retired", "payments" in Notifier.RETIRED)
        assertFalse("never its own retired id: it would be made and deleted at once", Notifier.CHANNEL in Notifier.RETIRED)
    }

    @Test
    fun aFreshInstallAndAnUpgradeGetHeadsUp() {
        assertEquals("nothing before it", NotificationManager.IMPORTANCE_HIGH, Notifier.importanceFor(emptyList()))
        assertEquals("the old channel at its default", NotificationManager.IMPORTANCE_HIGH,
            Notifier.importanceFor(listOf(NotificationManager.IMPORTANCE_DEFAULT)))
        assertEquals("the person lowered it, but did not turn it off", NotificationManager.IMPORTANCE_HIGH,
            Notifier.importanceFor(listOf(NotificationManager.IMPORTANCE_LOW)))
    }

    @Test
    fun aChannelThePersonTurnedOffStaysOff() {
        assertEquals(NotificationManager.IMPORTANCE_NONE, Notifier.importanceFor(listOf(NotificationManager.IMPORTANCE_NONE)))
        assertEquals(NotificationManager.IMPORTANCE_NONE,
            Notifier.importanceFor(listOf(NotificationManager.IMPORTANCE_DEFAULT, NotificationManager.IMPORTANCE_NONE)))
    }
}
