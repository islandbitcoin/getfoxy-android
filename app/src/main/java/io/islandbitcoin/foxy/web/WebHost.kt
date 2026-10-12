package io.islandbitcoin.foxy.web

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.islandbitcoin.foxy.BuildConfig
import io.islandbitcoin.foxy.FoxyServices
import io.islandbitcoin.foxy.bridge.FoxyBridge
import io.islandbitcoin.foxy.bridge.PageHost
import io.islandbitcoin.foxy.bridge.PlatformRules
import io.islandbitcoin.foxy.bridge.Prompt
import io.islandbitcoin.foxy.debug.Log
import io.islandbitcoin.foxy.notify.Notifier
import io.islandbitcoin.foxy.scanner.LivePreview
import io.islandbitcoin.foxy.scanner.ScannerActivity
import io.islandbitcoin.foxy.seed.SeedScreenHost
import io.islandbitcoin.foxy.tor.Tor
import java.io.ByteArrayInputStream

/**
 * The WebView around core/web (Foxy/FoxyWebView.swift), with four walls between
 * the page and the network (docs/ROUTE.md §4, S8):
 *
 * 1. only the asset loader answers: `shouldInterceptRequest` serves
 *    `https://appassets.androidplatform.net/assets/web/…` from the APK and a
 *    403 with an empty body for every other URL, never null;
 * 2. `setBlockNetworkLoads(true)`;
 * 3. a dead-end proxy, 127.0.0.1:9 with no direct fallback and no implicit
 *    loopback bypass, for whatever the engine starts that is never intercepted;
 * 4. no navigation, no new windows, no WebRTC, no file or content access, no
 *    geolocation, no permissions, Safe Browsing and metrics off.
 *
 * In P1 the app had no INTERNET permission at all; P2 adds it for Tor and
 * Route, and the four walls are what keeps the page off it (NetBlockTest runs
 * with INTERNET granted).
 */
@SuppressLint("SetJavaScriptEnabled")
class WebHost(
    private val activity: ComponentActivity,
    private val services: FoxyServices,
    private val container: ViewGroup,
    private val onCovered: () -> Unit,
    private val permissions: Permissions = Permissions.NONE,
) : PageHost, PutAway.Page {
    /**
     * The runtime permissions the doors ask, through MainActivity's launchers
     * (registered in onCreate: a launcher cannot be registered after STARTED).
     */
    interface Permissions {
        /** CAMERA, asked if not granted; `done(granted)`. */
        fun camera(done: (Boolean) -> Unit)

        /** POST_NOTIFICATIONS (API 33+), asked if not granted; `done(granted)`. */
        fun notifications(done: (Boolean) -> Unit)

        companion object {
            val NONE = object : Permissions {
                override fun camera(done: (Boolean) -> Unit) = done(false)
                override fun notifications(done: (Boolean) -> Unit) = done(false)
            }
        }
    }

    companion object {
        const val DOMAIN = "appassets.androidplatform.net"
        const val PAGE_URL = "https://$DOMAIN/assets/web/index.html"
        private const val SERVED_PREFIX = "/assets/web/"
        val ALLOWED_ORIGINS = setOf(FoxyBridge.ORIGIN)

        /** What a reply or push for a page that is gone leaves in the field log, instead of reaching a WebView. */
        const val DROPPED_LINE = "[foxy] webview: a script for a page that is gone was dropped"

        /** The WebView features P1 cannot run without. */
        val REQUIRED_FEATURES = listOf(
            WebViewFeature.WEB_MESSAGE_LISTENER,
            WebViewFeature.DOCUMENT_START_SCRIPT,
            // the dead-end proxy is a required wall (ROUTE.md §4), not one to do without
            WebViewFeature.PROXY_OVERRIDE,
        )

        /** Whether a URL is one the loader may answer: the asset origin, the page's folder, nothing else. */
        fun serves(url: Uri): Boolean =
            url.scheme == "https" && url.host == DOMAIN && url.port == -1 &&
                (url.path ?: "").startsWith(SERVED_PREFIX) && !(url.path ?: "").contains("..")

        /**
         * The page's text zoom, whatever the phone's font size (ENG-661).
         * Android's WebView scales page text by the system font scale
         * (`textZoom` starts at fontScale × 100, 130 at 1.3); WKWebView does
         * not apply Dynamic Type to the page, so the pinned page's fixed-size
         * buttons were only ever laid out at 100 % and overflow at 130 %.
         * The accessibility cost is the same as on iOS (docs/ANDROID-MAPPING.md).
         */
        const val PAGE_TEXT_ZOOM = 100

        fun refusal(): WebResourceResponse =
            WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }

    private val main = Handler(Looper.getMainLooper())
    private val loader = WebViewAssetLoader.Builder()
        .setDomain(DOMAIN)
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(activity))
        .build()

    var webView: WebView? = null
        private set
    var bridge: FoxyBridge? = null
        private set
    @Volatile private var destroyed = false

    /** Whether the proxy wall is up: the page is not loaded until it is. */
    private var proxyReady = false
    private var pageLoaded = false
    private var pageFinished = false

    /** The camera pane and the paste button over the page (P3); made with the WebView. */
    var live: LivePreview? = null
        private set
    var paste: PasteButton? = null
        private set

    /** The share sheet is up: a second share is refused until Foxy is in front again. */
    private var sharing = false

    /** DEBUG builds only: a deep link's hash, waiting for a loaded page. */
    private var pendingDeepLink: String? = null

    // MARK: PageHost

    override fun post(action: Runnable) {
        main.post(action)
    }

    /**
     * Told each script the guard below drops, for an instrumented test to
     * count only the drops it caused (ENG-652: a late reply to the page's own
     * request can land in the same moment). Null in the app.
     */
    @Volatile internal var droppedScript: ((String) -> Unit)? = null

    override fun evaluate(js: String) {
        if (destroyed) {
            Log.line(DROPPED_LINE)
            droppedScript?.invoke(js)
            return
        }
        webView?.evaluateJavascript(js, null)
    }

    /** evaluateJavascript with its result, as JSON text; null once the WebView is gone. */
    override fun read(js: String, answer: (String?) -> Unit) {
        val view = webView
        if (destroyed || view == null) return answer(null)
        view.evaluateJavascript(js) { answer(it) }
    }

    override fun covered() = onCovered()

    override fun keepAwake(on: Boolean) {
        if (destroyed) return
        if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.line("[foxy] screen kept awake: ${if (on) "yes" else "no"}")
    }

    override fun haptic(kind: String) {
        val view = webView ?: return
        when (val effect = Haptics.effect(kind, android.os.Build.VERSION.SDK_INT)) {
            is Haptics.Effect.Once -> view.performHapticFeedback(effect.constant)
            is Haptics.Effect.Thrice -> for (knock in 0 until 3) {
                main.postDelayed({ webView?.performHapticFeedback(effect.constant) }, Haptics.TRIPLE_GAP_MS * knock)
            }
        }
    }

    // MARK: PageHost, the platform's doors (P3)

    override fun later(ms: Long, action: Runnable) {
        main.postDelayed(action, ms)
    }

    private val density: Float get() = activity.resources.displayMetrics.density

    override fun pageSize(): Pair<Double, Double>? {
        val view = webView ?: return null
        if (destroyed || view.width == 0 || view.height == 0) return null
        return view.width / density.toDouble() to view.height / density.toDouble()
    }

    /** A page rectangle (CSS px) in the container's pixels: left, top, width, height. */
    private fun pixels(rect: PlatformRules.Rect): IntArray {
        val view = webView
        val left = (view?.left ?: 0) + Math.round(rect.x * density).toInt()
        val top = (view?.top ?: 0) + Math.round(rect.y * density).toInt()
        return intArrayOf(left, top, Math.round(rect.w * density).toInt(), Math.round(rect.h * density).toInt())
    }

    private fun usable(): Boolean = !destroyed && !activity.isFinishing && !activity.isDestroyed

    override fun scan(prompt: String, done: (String?, String?) -> Unit) {
        if (!usable()) return done(null, PlatformRules.NO_SCANNER_VIEW)
        permissions.camera { granted ->
            when {
                !granted -> done(null, PlatformRules.CAMERA_DENIED)
                !usable() -> done(null, PlatformRules.NO_SCANNER_VIEW)
                !ScannerActivity.start(activity, prompt, done) -> done(null, PlatformRules.NO_SCANNER_VIEW)
            }
        }
    }

    override fun previewShow(rect: PlatformRules.Rect, onCode: (String) -> Unit): Boolean {
        val pane = live ?: return false
        if (!usable()) return false
        val px = pixels(rect)
        pane.show(px[0], px[1], px[2], px[3], onCode)
        if (activity.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissions.camera { granted -> if (granted) live?.permissionGranted() }
        }
        return true
    }

    override fun previewStop() {
        live?.stop()
    }

    override fun pasteShow(rect: PlatformRules.Rect, onPaste: (String) -> Unit): Boolean {
        val button = paste ?: return false
        if (!usable()) return false
        val px = pixels(rect)
        button.show(px[0], px[1], px[2], px[3], onPaste)
        return true
    }

    override fun pasteStop() {
        paste?.remove()
    }

    override fun clipboardText(): String = services.clipboard.read()

    override fun copy(text: String, sensitive: Boolean) = services.clipboard.copy(text, sensitive)

    /**
     * The share sheet, plain text only (FoxyBridge.swift `presentShare`): no
     * URL item and no preview for the chooser to fetch. Its own Copy has no
     * callback on Android, so a token copied there is not marked or cleared
     * (S23, ENG-649 decision 3).
     */
    override fun share(text: String): String? {
        if (!usable()) return PlatformRules.NOTHING_TO_SHARE
        if (sharing || SeedScreenHost.shown != null || ScannerActivity.waiting) return PlatformRules.ALREADY_OPEN
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        sharing = true
        return try {
            activity.startActivity(Intent.createChooser(send, null))
            Log.line("[foxy] presenting share sheet, ${text.length} characters")
            null
        } catch (e: RuntimeException) {
            sharing = false
            PlatformRules.ALREADY_OPEN
        }
    }

    /** Foxy is in front again: whatever the share sheet did, it is gone. */
    fun resumed() {
        sharing = false
    }

    override fun notify(title: String, text: String) {
        if (Notifier.allowed(activity)) return Notifier.post(activity, title, text)
        // asked on the first notification; refused, nothing is shown and the page was already told ok
        permissions.notifications { granted -> if (granted) Notifier.post(activity, title, text) }
    }

    override fun biometric(reason: String, passcode: Boolean, done: (String) -> Unit) = services.auth.biometric(reason, passcode, done)

    /** This app's own page in Settings, and nothing else (a camera or notification permission refused once). */
    override fun openSettings(): String? {
        if (!usable()) return PlatformRules.NO_SETTINGS
        return try {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)))
            null
        } catch (e: ActivityNotFoundException) {
            PlatformRules.SETTINGS_WOULD_NOT_OPEN
        }
    }

    /** One of the four company sites, in the phone's browser, outside Foxy and outside Tor; never a URL from the page. */
    override fun openUrl(url: String): String? {
        if (url !in PlatformRules.COMPANY_SITES.values) return PlatformRules.NOT_A_COMPANY
        if (!usable()) return PlatformRules.BROWSER_WOULD_NOT_OPEN
        return try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
            null
        } catch (e: ActivityNotFoundException) {
            PlatformRules.BROWSER_WOULD_NOT_OPEN
        }
    }

    override fun ask(prompt: Prompt, done: (Boolean) -> Unit): () -> Unit {
        val ticket = services.prompts.ask(prompt, done)
        return { ticket.cancel() }
    }

    override fun alertShowing(): Boolean = services.prompts.isShowing

    override fun deviceOwnerApproves(reason: String): Boolean = services.auth.ownerApproves(reason)

    override fun showWords(words: List<String>, verify: Boolean, done: (Boolean, Boolean, Boolean) -> Unit): Boolean {
        if (!usable()) return false
        return SeedScreenHost.showWords(activity, SeedScreenHost.Show(words, verify, services.auth.passcodeSet, done))
    }

    override fun enterWords(wordlist: List<String>, done: (String?) -> Unit): Boolean {
        if (!usable()) return false
        return SeedScreenHost.enterWords(activity, SeedScreenHost.Enter(wordlist, done))
    }

    override fun closeSeedScreen() = SeedScreenHost.closeAll()

    /** PutAway: no camera, paste button or seed screen is left for a page put away (FoxyWebView.swift:999-1132). */
    override fun letGo() {
        bridge?.letGo()
    }

    /** The activity stopped: the camera is let go now, whatever PutAway does later. */
    fun stopCamera() {
        live?.stop()
    }

    // MARK: Deep links (debug builds only)

    /** A debug deep link's hash ([DeepLink.hash]), handed to the page once it has loaded. */
    fun deepLink(hash: String) {
        if (!BuildConfig.DEBUG) return
        pendingDeepLink = hash
        flushDeepLink()
    }

    private fun flushDeepLink() {
        val hash = pendingDeepLink ?: return
        if (!pageFinished || destroyed) return
        pendingDeepLink = null
        Log.line("[deeplink] #$hash")
        evaluate(DeepLink.script(hash))
    }

    // MARK: Building it

    /** Every WebView feature P1 needs is there. Without them the page is not loaded at all. */
    fun supported(): Boolean = REQUIRED_FEATURES.all { WebViewFeature.isFeatureSupported(it) }

    /**
     * The order is the contract's: the bridge (and the seed executor behind it,
     * made by FoxyServices) before the listener, the listener and every
     * document-start script before the one loadUrl. The features they need are
     * checked first ([supported]); without them nothing is loaded.
     */
    @SuppressLint("RequiresFeature")
    fun create() {
        destroyed = false
        if (!supported()) {
            Log.line("[foxy] this phone's WebView lacks a feature Foxy needs; the page is not loaded")
            return
        }
        services.vault.pageWillLoad()
        val view = WebView(activity)
        webView = view
        view.setBackgroundColor(activity.getColor(io.islandbitcoin.foxy.R.color.page))
        view.isHapticFeedbackEnabled = true
        container.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        configure(view)
        live = LivePreview(activity, container, view)
        paste = PasteButton(activity, container, view, services.clipboard)

        val b = FoxyBridge(this, services::environment, services.seedExecutor, BuildConfig.DEBUG,
            services.net { ms, r -> main.postDelayed(r, ms) })
        bridge = b
        // every change of Tor's or of the network is pushed to the page (_privacy)
        Tor.onChange = { bridge?.pushPrivacy() }
        // a seed written or deleted closes every seed screen, so none shows words from before (L4)
        services.vault.onChanged = { main.post { bridge?.seedChanged() } }
        WebViewCompat.addWebMessageListener(view, Scripts.NATIVE_OBJECT, ALLOWED_ORIGINS) { _, message, sourceOrigin, isMainFrame, _ ->
            b.onMessage(message.data, isMainFrame, sourceOrigin.toString().trimEnd('/'))
        }
        // the shim first, then the rest, as iOS orders its user scripts
        WebViewCompat.addDocumentStartJavaScript(view, Scripts.shim, ALLOWED_ORIGINS)
        // every frame, whatever its origin: a blank iframe must not get WebRTC back
        WebViewCompat.addDocumentStartJavaScript(view, Scripts.noWebRTC, setOf("*"))
        WebViewCompat.addDocumentStartJavaScript(view, Scripts.noCallout, ALLOWED_ORIGINS)
        WebViewCompat.addDocumentStartJavaScript(view, Scripts.clock24(DateFormat.is24HourFormat(activity)), ALLOWED_ORIGINS)
        if (BuildConfig.DEBUG) {
            WebViewCompat.addDocumentStartJavaScript(view, Scripts.foxyDebug, ALLOWED_ORIGINS)
            if (activity.intent?.getBooleanExtra("FOXY_SHOW_WARNING", false) == true) {
                WebViewCompat.addDocumentStartJavaScript(view, Scripts.showWarning, ALLOWED_ORIGINS)
            }
        } else {
            WebViewCompat.addDocumentStartJavaScript(view, Scripts.consoleSilence, ALLOWED_ORIGINS)
        }
        WebViewCompat.addDocumentStartJavaScript(view, Scripts.pageHash(BuildConfig.PAGE_HASH), ALLOWED_ORIGINS)

        setDeadEndProxy()
    }

    @SuppressLint("RequiresFeature")
    private fun configure(view: WebView) {
        val s = view.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.blockNetworkLoads = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setGeolocationEnabled(false)
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.mediaPlaybackRequiresUserGesture = false
        // the page lays out as on iOS, not at the phone's font scale (ENG-661)
        s.textZoom = PAGE_TEXT_ZOOM
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(s, false)
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        view.webViewClient = Client()
        view.webChromeClient = Chrome()
    }

    /** 127.0.0.1:9, nothing listening, no direct fallback and no loopback bypass; the page loads once it is set. */
    @SuppressLint("RequiresFeature") // PROXY_OVERRIDE is in REQUIRED_FEATURES, checked by create()
    private fun setDeadEndProxy() {
        val config = ProxyConfig.Builder()
            .addProxyRule("127.0.0.1:9")
            .removeImplicitRules()
            .build()
        ProxyController.getInstance().setProxyOverride(config, { it.run() }) {
            main.post {
                proxyReady = true
                loadOnce()
            }
        }
    }

    private fun loadOnce() {
        if (pageLoaded || !proxyReady || destroyed) return
        pageLoaded = true
        services.migrationWindow.noteLaunch(java.io.File(activity.dataDir, "app_webview"))
        webView?.loadUrl(PAGE_URL)
    }

    /** Seconds since the phone started, which moving the clock does not change (the PIN back-off clock). */
    override val moneyInFlight: Int get() = bridge?.moneyInFlight ?: 0

    override fun pushUptime() {
        val s = SystemClock.elapsedRealtime() / 1000.0
        evaluate("window.__foxyUptime = { s: $s, p: performance.now() }")
    }

    /**
     * The page goes: the seed kept for it goes with it (S20), whether or not
     * ProcessLifecycleOwner ever says stop (a Back that finishes the activity
     * inside its 700 ms hold never does).
     */
    fun destroy() {
        // no camera pane, paste button, seed screen or tap receiver of the page's is left over what comes next (I7)
        bridge?.pageGone()
        live?.destroy()
        live = null
        paste?.remove()
        paste = null
        destroyed = true
        Tor.onChange = null
        services.vault.onChanged = {}
        services.vault.forgetNativeSeed()
        webView?.let {
            container.removeView(it)
            it.destroy()
        }
        webView = null
        bridge = null
        pageLoaded = false
        pageFinished = false
    }

    /** The page's process ended: drop what native held for it, and load it again in a new WebView. */
    private fun recreate() {
        Log.line("[foxy] webview: the page's process ended; loading it again")
        destroy()
        create()
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
            val url = request.url
            if (serves(url)) {
                loader.shouldInterceptRequest(url)?.let { return it }
                return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }
            Log.line("[foxy] webview: a request outside the page refused (${url.scheme}://${url.host})")
            return refusal()
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

        override fun onPageFinished(view: WebView, url: String) {
            pushUptime()
            pageFinished = true
            flushDeepLink()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            main.post { recreate() }
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) = request.deny()

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) =
            callback.invoke(origin, false, false)

        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean = false
    }
}
