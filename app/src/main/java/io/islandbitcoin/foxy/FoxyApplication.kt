package io.islandbitcoin.foxy

import android.app.Application
import android.os.Build
import io.islandbitcoin.foxy.debug.DebugConsole
import io.islandbitcoin.foxy.debug.FieldLog
import io.islandbitcoin.foxy.notify.Notifier

/** Starts the field log, then the services the activity hands the page. */
class FoxyApplication : Application() {
    lateinit var services: FoxyServices
        private set

    override fun onCreate() {
        super.onCreate()
        DebugConsole.install()
        FieldLog.begin(
            noBackupFilesDir,
            "[foxy] Foxy ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) on Android ${Build.VERSION.RELEASE}",
        )
        services = FoxyServices(this)
        // the one notification channel, made with the process (P3)
        Notifier.createChannel(this)
    }
}
