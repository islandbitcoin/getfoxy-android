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
 */
object Notifier {
    const val CHANNEL = "payments"

    /** The channel, once per process (Application.onCreate). */
    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.notify_channel), NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = context.getString(R.string.notify_channel_description)
        channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
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
        val public = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .build()
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
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
