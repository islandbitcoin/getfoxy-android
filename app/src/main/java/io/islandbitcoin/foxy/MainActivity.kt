package io.islandbitcoin.foxy

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ProcessLifecycleOwner
import io.islandbitcoin.foxy.network.Route
import io.islandbitcoin.foxy.tor.Tor
import io.islandbitcoin.foxy.tor.TorTransport
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

        /** Debug builds only: start Tor on this transport (direct, obfs4, snowflake), for the device exit test. */
        const val FORCE_TRANSPORT = "FoxyForceTransport"

        /** Debug builds only: do not start Tor in this process (the page stays on SECURING YOUR CONNECTION). */
        const val NO_TOR = "FoxyNoTor"
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

        // native questions are asked on this activity; Tor starts as iOS starts it, with the web view
        services.inFront(this)
        val forced = if (BuildConfig.DEBUG) TorTransport.named(intent?.getStringExtra(FORCE_TRANSPORT)) else null
        Tor.install(this, release = !BuildConfig.DEBUG, forced = forced)
        // debug builds can be told not to start Tor, for the leak test's CONTINUE WITHOUT TOR run
        if (!(BuildConfig.DEBUG && intent?.getBooleanExtra(NO_TOR, false) == true)) {
            // a finished activity parked Tor (onDestroy) and no PutAway return will follow: set it up again here
            if (Tor.parked) Tor.resumed(0) else Tor.start()
        }
    }

    override fun onDestroy() {
        (application as FoxyApplication).services.gone(this)
        putAway?.let {
            it.cancel()
            ProcessLifecycleOwner.get().lifecycle.removeObserver(it)
        }
        putAway = null
        main.removeCallbacksAndMessages(null)
        host?.destroy()
        host = null
        // Back finishing the activity (API 28-30): ProcessLifecycleOwner's stop comes 700 ms later, after
        // the PutAway observer was removed above, so PutAway never parks Tor. The page is gone, so there
        // is no work of leaving to wait for: shut the door and take Tor off the network now
        // (docs/ROUTE.md "The door and backgrounding", S21). After the page went, so the park's
        // _privacy push has no page to go to (WebHost.destroy clears Tor.onChange). A rotation is
        // not a leaving.
        if (isFinishing && !isChangingConfigurations) {
            Route.shutDoor()
            Tor.park()
        }
        super.onDestroy()
    }
}
