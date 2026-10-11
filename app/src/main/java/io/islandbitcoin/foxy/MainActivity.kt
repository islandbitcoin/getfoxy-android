package io.islandbitcoin.foxy

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ProcessLifecycleOwner
import io.islandbitcoin.foxy.web.PutAway
import io.islandbitcoin.foxy.web.WebHost

/**
 * The one activity: portrait, FLAG_SECURE before anything is drawn (S19: the
 * Recents thumbnail is blank and capture is refused), the splash held until the
 * page says `covered` or 8 s pass, and the WebView host.
 */
class MainActivity : ComponentActivity() {
    companion object {
        const val SPLASH_BACKSTOP_MS = 8_000L
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var splashDone = false
    var host: WebHost? = null
        private set
    private var putAway: PutAway? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        splash.setKeepOnScreenCondition { !splashDone }
        main.postDelayed({ splashDone = true }, SPLASH_BACKSTOP_MS)

        val container = FrameLayout(this)
        container.setBackgroundColor(getColor(R.color.page))
        setContentView(container)

        // the seed executor is FoxyServices', made with the process and so before the listener
        val services = (application as FoxyApplication).services
        val webHost = WebHost(this, services, container) { splashDone = true }
        host = webHost
        webHost.create()

        val away = PutAway({ host }, services.vault)
        putAway = away
        ProcessLifecycleOwner.get().lifecycle.addObserver(away)
    }

    override fun onDestroy() {
        putAway?.let {
            it.cancel()
            ProcessLifecycleOwner.get().lifecycle.removeObserver(it)
        }
        putAway = null
        main.removeCallbacksAndMessages(null)
        host?.destroy()
        host = null
        super.onDestroy()
    }
}
