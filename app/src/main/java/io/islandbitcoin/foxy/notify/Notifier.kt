package io.islandbitcoin.foxy.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.islandbitcoin.foxy.R
import io.islandbitcoin.foxy.debug.Log

/**
 * Payment notifications (FoxyBridge.swift `notify`, ForegroundNotifications.swift):
 * one channel, "Payments", made with the application; native's fixed words,
 * titled "Foxy", never an amount; private on the lock screen, where the public
 * version says only "Foxy" (iOS's `hiddenPreviewsBodyPlaceholder`). No tap
 * action: iOS's has none. Shown only while Foxy runs: nothing wakes it.
 *
 * ENG-665: iOS shows it as a banner with a sound in the foreground
 * (`[.banner, .sound]`), so the channel is IMPORTANCE_HIGH, Android's heads-up.
 * Android keeps a channel's importance from the day it was made, whatever a
 * later app asks, so the high channel has a new id and the default-importance
 * one an earlier Foxy made is deleted. The small icon is the iOS app icon's
 * fox head in white (app/art/NOTICE.md), tinted the page's orange.
 */
object Notifier {
    const val CHANNEL = "payments_heads_up"
    const val IMPORTANCE = NotificationManager.IMPORTANCE_HIGH

    /** Channels an earlier Foxy made: "payments" was IMPORTANCE_DEFAULT (P3). */
    val RETIRED: List<String> = listOf("payments")

    /**
     * The new channel's importance: high, unless the person had turned the
     * old one off in Settings (IMPORTANCE_NONE), which carries over: a new id
     * is not a way round their no.
     */
    fun importanceFor(retiredImportances: Collection<Int>): Int =
        if (retiredImportances.any { it == NotificationManager.IMPORTANCE_NONE }) NotificationManager.IMPORTANCE_NONE else IMPORTANCE

    /** The channel, once per process (Application.onCreate), and the retired ones deleted. */
    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val retired = RETIRED.mapNotNull { manager.getNotificationChannel(it) }
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.notify_channel), importanceFor(retired.map { it.importance }))
        channel.description = context.getString(R.string.notify_channel_description)
        channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
        for (old in retired) manager.deleteNotificationChannel(old.id)
    }

    /** Whether Android lets Foxy post one now (API 33+ asks POST_NOTIFICATIONS). */
    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Posts `text` titled `title`, both native's. Not allowed: nothing is shown, and nothing is said to the page (iOS). */
    fun post(context: Context, title: String, text: String) {
        if (!allowed(context)) {
            Log.line("[foxy] notification not shown: Android has not allowed Foxy's notifications")
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val colour = context.getColor(R.color.accent)
        val public = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(colour)
            .setContentTitle(title)
            .build()
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(colour)
            .setContentTitle(title)
            .setContentText(text)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        try {
            manager.notify((System.nanoTime() and 0x7fffffff).toInt(), notification)
        } catch (e: SecurityException) {
            Log.line("[foxy] notification not shown: ${e.javaClass.simpleName}")
        }
    }
}
