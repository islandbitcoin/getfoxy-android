package io.islandbitcoin.foxy

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ProcessLifecycleOwner
import io.islandbitcoin.foxy.network.Route
import io.islandbitcoin.foxy.tor.Tor
import io.islandbitcoin.foxy.tor.TorTransport
import io.islandbitcoin.foxy.debug.Log
import io.islandbitcoin.foxy.web.DeepLink
import io.islandbitcoin.foxy.web.PutAway
import io.islandbitcoin.foxy.web.WebHost

/**
 * The page's activity: portrait, FLAG_SECURE before anything is drawn (S19: the
 * Recents thumbnail is blank and capture is refused), Android's splash fading
 * into the intro's still, which stays over the page until it says `covered` or
 * 8 s pass (SplashCover, ENG-665), and the WebView host.
 *
 * P3: the permission and screen-lock launchers the doors use, registered here
 * because a launcher cannot be registered after STARTED; `_screenshot` from
 * Android 14's screen-capture callback while Foxy is in front; the clipboard's
 * 120 s clear looked at again whenever the window has focus (Android 10+ shows
 * an app the clipboard only then); the camera let go on every stop; and in
 * debug builds only, the `foxy://` deep link.
 */
class MainActivity : ComponentActivity(), WebHost.Permissions {
    companion object {
        const val SPLASH_BACKSTOP_MS = SplashCover.BACKSTOP_MS

        /** Debug builds only: start Tor on this transport (direct, obfs4, snowflake), for the device exit test. */
        const val FORCE_TRANSPORT = "FoxyForceTransport"

        /** Debug builds only: do not start Tor in this process (the page stays on SECURING YOUR CONNECTION). */
        const val NO_TOR = "FoxyNoTor"
    }

    private val main = Handler(Looper.getMainLooper())
    private var cover: SplashCover? = null

    /** The cover's view while it is up, and whether it is (SplashCoverViewTest). */
    internal var coverView: ImageView? = null
        private set
    internal val coverUp: Boolean get() = cover?.isUp == true

    /** Whether onCreate made a splash cover at all, up or down (SplashCoverViewTest). */
    internal val coverMade: Boolean get() = cover != null

    private val cameraAsked = ArrayList<(Boolean) -> Unit>()
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val waiting = cameraAsked.toList()
        cameraAsked.clear()
        waiting.forEach { it(granted) }
    }
    private val notificationsAsked = ArrayList<(Boolean) -> Unit>()
    private val notificationsLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val waiting = notificationsAsked.toList()
        notificationsAsked.clear()
        waiting.forEach { it(granted) }
    }
    private var confirmed: ((Boolean) -> Unit)? = null
    private val confirmLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val done = confirmed
        confirmed = null
        done?.invoke(result.resultCode == RESULT_OK)
    }

    /** Android 14+: a screenshot was taken while Foxy was in front (FLAG_SECURE made it blank): the page hears `_screenshot`. */
    private val screenshotTaken: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        Activity.ScreenCaptureCallback { host?.bridge?.pushScreenshot() }
    } else {
        null
    }
    var host: WebHost? = null
        private set
    private var putAway: PutAway? = null
    private var confirmAnswered: ((Boolean) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        // Android's own splash goes with the first frame, fading onto the cover below (SplashCover)
        splash.setOnExitAnimationListener { provider ->
            provider.view.animate().alpha(0f).setDuration(SplashCover.SYSTEM_FADE_MS).withEndAction { provider.remove() }
        }

        val container = FrameLayout(this)
        container.setBackgroundColor(getColor(R.color.page))
        setContentView(container)

        // the seed executor is FoxyServices', made with the process and so before the listener
        val services = (application as FoxyApplication).services
        val webHost = WebHost(this, services, container, { main.post { cover?.down() } }, this)
        host = webHost
        webHost.create()
        cover = addCover(container)
        main.postDelayed({ cover?.down() }, SPLASH_BACKSTOP_MS)

        // Settings' screen-lock confirmation (API 28-29) stops this activity: Foxy's own question, not a put-away
        val away = PutAway({ host }, services.vault, ownPrompt = { services.auth.confirming })
        putAway = away
        // only a yes proves the stop was Foxy's own question; a no or a cancel is a return, and the PIN lock comes back
        val answered: (Boolean) -> Unit = { away.promptAnswered(it) }
        confirmAnswered = answered
        services.auth.confirmAnswered = answered
        ProcessLifecycleOwner.get().lifecycle.addObserver(away)

        // native questions are asked on this activity; Tor starts as iOS starts it, with the web view
        services.inFront(this)
        services.auth.confirmCredential = { intent, done ->
            if (confirmed != null || isFinishing) {
                false
            } else {
                confirmed = done
                confirmLauncher.launch(intent)
                true
            }
        }
        receiveDeepLink(intent)
        val forced = if (BuildConfig.DEBUG) TorTransport.named(intent?.getStringExtra(FORCE_TRANSPORT)) else null
        Tor.install(this, release = !BuildConfig.DEBUG, forced = forced)
        // debug builds can be told not to start Tor, for the leak test's CONTINUE WITHOUT TOR run
        if (!(BuildConfig.DEBUG && intent?.getBooleanExtra(NO_TOR, false) == true)) {
            // a finished activity parked Tor (onDestroy) and no PutAway return will follow: set it up again here
            if (Tor.parked) Tor.resumed(0) else Tor.start()
        }
    }

    /** The intro's still over the web view, at its size, until SplashCover takes it down; touches go through to the page, as iOS's. */
    private fun addCover(container: FrameLayout): SplashCover {
        val image = ImageView(this)
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        image.setBackgroundColor(getColor(R.color.splash))
        image.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        // above whatever the web host adds later (the live pane, the paste button), and casting no shadow
        image.outlineProvider = null
        image.translationZ = 1_000f
        try {
            assets.open(SplashCover.STILL).use { image.setImageBitmap(BitmapFactory.decodeStream(it)) }
        } catch (e: java.io.IOException) {
            // the page ships byte for byte (AssetsMatchPinTest); black, as iOS's splash colour, if it ever does not
            Log.line("[foxy] splash still not read: ${e.javaClass.simpleName}")
        }
        container.addView(image, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        coverView = image
        return SplashCover { fadeMs ->
            image.animate().alpha(0f).setDuration(fadeMs).withEndAction {
                container.removeView(image)
                coverView = null
            }
        }
    }

    // MARK: WebHost.Permissions

    override fun camera(done: (Boolean) -> Unit) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) return done(true)
        if (isFinishing || isDestroyed) return done(false)
        cameraAsked.add(done)
        if (cameraAsked.size == 1) cameraLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun notifications(done: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            return done(true)
        }
        if (isFinishing || isDestroyed) return done(false)
        notificationsAsked.add(done)
        if (notificationsAsked.size == 1) notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // MARK: Lifecycle (P3)

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerScreenCaptureCallback(mainExecutor, screenshotTaken as Activity.ScreenCaptureCallback)
        }
    }

    override fun onResume() {
        super.onResume()
        host?.resumed()
    }

    override fun onStop() {
        // the camera light never stays on behind anything else, Foxy's own screen-lock confirmation included:
        // stopping it touches no seed, screen or door, so it is not skipped for that confirmation (PutAway's away is)
        host?.stopCamera()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            unregisterScreenCaptureCallback(screenshotTaken as Activity.ScreenCaptureCallback)
        }
        super.onStop()
    }

    /** Android 10+ lets an app see the clipboard only with focus: a sensitive copy whose 120 s ran out while away is cleared now (S23). */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) (application as FoxyApplication).services.clipboard.clearIfExpired()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveDeepLink(intent)
    }

    /**
     * DEBUG BUILDS ONLY (FoxyWebView.swift:492-531): `foxy://receive?amt=50&unit=sat`,
     * rebuilt from a whitelist into the page's hash. A release build registers
     * no scheme (ManifestTest) and reads no link here.
     *
     * `foxy://card` is a card tap said by hand (FoxyWebView.swift:497-508). iOS
     * tells the page `_cardTapped`; Android does not deliver card taps yet
     * (ENG-668), so it is logged and nothing reaches the page.
     */
    private fun receiveDeepLink(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        if (intent?.action != Intent.ACTION_VIEW) return
        val link = intent.dataString ?: return
        when (val read = DeepLink.read(link) { Log.line("[deeplink] ignored parameter: ${it.take(32)}") }) {
            null -> Log.line("[deeplink] no such screen")
            DeepLink.Link.CardTap -> Log.line("[deeplink] a card tap: not passed to the page, card taps are not delivered yet (ENG-668)")
            is DeepLink.Link.Screen -> host?.deepLink(read.hash)
        }
    }

    override fun onDestroy() {
        val services = (application as FoxyApplication).services
        services.gone(this)
        if (services.frontActivity == null) services.auth.confirmCredential = null
        // a confirmation whose answer can no longer come back here is a no (and no longer Foxy's own prompt)
        confirmed?.let {
            confirmed = null
            it(false)
        }
        if (services.auth.confirmAnswered === confirmAnswered) services.auth.confirmAnswered = null
        confirmAnswered = null
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
