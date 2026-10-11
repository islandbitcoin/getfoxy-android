package io.islandbitcoin.foxy.web

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.HapticFeedbackConstants
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
import io.islandbitcoin.foxy.debug.Log
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
 * The app has no INTERNET permission in P1 either: a fifth wall.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebHost(
    private val activity: ComponentActivity,
    private val services: FoxyServices,
    private val container: ViewGroup,
    private val onCovered: () -> Unit,
) : PageHost {
    companion object {
        const val DOMAIN = "appassets.androidplatform.net"
        const val PAGE_URL = "https://$DOMAIN/assets/web/index.html"
        private const val SERVED_PREFIX = "/assets/web/"
        val ALLOWED_ORIGINS = setOf(FoxyBridge.ORIGIN)

        /** The WebView features P1 cannot run without. */
        val REQUIRED_FEATURES = listOf(
            WebViewFeature.WEB_MESSAGE_LISTENER,
            WebViewFeature.DOCUMENT_START_SCRIPT,
        )

        /** Whether a URL is one the loader may answer: the asset origin, the page's folder, nothing else. */
        fun serves(url: Uri): Boolean =
            url.scheme == "https" && url.host == DOMAIN && url.port == -1 &&
                (url.path ?: "").startsWith(SERVED_PREFIX) && !(url.path ?: "").contains("..")

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

    // MARK: PageHost

    override fun post(action: Runnable) {
        main.post(action)
    }

    override fun evaluate(js: String) {
        if (destroyed) return
        webView?.evaluateJavascript(js, null)
    }

    /** evaluateJavascript with its result, as JSON text; null once the WebView is gone. */
    fun read(js: String, answer: (String?) -> Unit) {
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
        val modern = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
        val effect = when (kind) {
            "success" -> if (modern) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            "error" -> if (modern) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
            "warning", "heavy", "long" -> HapticFeedbackConstants.LONG_PRESS
            "pulse" -> HapticFeedbackConstants.CLOCK_TICK
            "triple" -> {
                for (knock in 0 until 3) {
                    main.postDelayed({ webView?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }, 150L * knock)
                }
                return
            }
            else -> HapticFeedbackConstants.KEYBOARD_TAP
        }
        view.performHapticFeedback(effect)
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

        val b = FoxyBridge(this, services::environment, services.seedExecutor, BuildConfig.DEBUG)
        bridge = b
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
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(s, false)
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        view.webViewClient = Client()
        view.webChromeClient = Chrome()
    }

    /** 127.0.0.1:9, nothing listening, no direct fallback and no loopback bypass; the page loads once it is set. */
    @SuppressLint("RequiresFeature") // checked on the first line
    private fun setDeadEndProxy() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            Log.line("[foxy] webview: no proxy override on this phone; the other walls stand")
            proxyReady = true
            loadOnce()
            return
        }
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
    fun pushUptime() {
        val s = SystemClock.elapsedRealtime() / 1000.0
        evaluate("window.__foxyUptime = { s: $s, p: performance.now() }")
    }

    fun destroy() {
        destroyed = true
        webView?.let {
            container.removeView(it)
            it.destroy()
        }
        webView = null
        bridge = null
        pageLoaded = false
    }

    /** The page's process ended: drop what native held for it, and load it again in a new WebView. */
    private fun recreate() {
        Log.line("[foxy] webview: the page's process ended; loading it again")
        services.vault.forgetNativeSeed()
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
