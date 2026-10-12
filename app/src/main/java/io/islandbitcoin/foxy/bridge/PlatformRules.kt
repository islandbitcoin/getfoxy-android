package io.islandbitcoin.foxy.bridge

/**
 * The P3 doors' rules, apart from Android so the JVM tests hold them
 * (FoxyBridge.swift; SECURITY-INVARIANTS.md S24, S25): the rectangles the page
 * may put a camera pane or a paste button at, the fixed words native says
 * (the scanner's prompt, the biometric reason, the notifications), the four
 * company sites, and the notification throttle. Nothing the page writes is ever
 * shown or opened: it picks from these lists, or gets the default, or a refusal.
 */
object PlatformRules {
    /** A rectangle in the page's CSS pixels, as the page measured it. */
    data class Rect(val x: Double, val y: Double, val w: Double, val h: Double) {
        val finite: Boolean get() = x.isFinite() && y.isFinite() && w.isFinite() && h.isFinite()
    }

    /** `{x, y, w, h}` from a message; a missing or non-number field is 0, as iOS reads it. */
    fun rect(body: Map<String, Any?>): Rect = Rect(
        body["x"] as? Double ?: 0.0,
        body["y"] as? Double ?: 0.0,
        body["w"] as? Double ?: 0.0,
        body["h"] as? Double ?: 0.0,
    )

    // MARK: The camera pane (previewStart)

    const val NO_CAMERA_VIEW = "Nothing to show the camera in."

    /**
     * Why a camera pane may not go at `r` in a page `viewW` × `viewH`, or null:
     * finite, larger than 40 × 40, and on screen, inside the page with a pixel
     * of slack (FoxyBridge.swift:1099-1141; audit I7: a pane parked out of sight
     * ran the camera with only the system indicator to show it).
     */
    fun previewProblem(r: Rect, viewW: Double, viewH: Double): String? {
        if (!r.finite || r.w <= 40 || r.h <= 40) return "That pane is too small for a camera (${r.w.toInt()}x${r.h.toInt()})."
        val inside = r.x >= -1 && r.y >= -1 && r.x + r.w <= viewW + 1 && r.y + r.h <= viewH + 1
        if (!inside) {
            return "The camera pane must be on screen (at ${r.x.toInt()},${r.y.toInt()} " +
                "${r.w.toInt()}x${r.h.toInt()} in ${viewW.toInt()}x${viewH.toInt()})."
        }
        return null
    }

    // MARK: The paste button (pasteStart)

    const val NO_PASTE_VIEW = "Nothing to attach the button to."
    const val PASTE_REFUSED = "That is not a place a paste button goes."

    /**
     * Whether a paste button may go at `r`: larger than 30 × 20, at most 90% of
     * the page's width and half its height, inside it, and in its lower two
     * thirds, where Foxy's paste affordances are. Anything that could run script
     * in the page could otherwise park it over CONFIRM SEND and have a tap drop
     * the clipboard into the page (FoxyBridge.swift:1163-1237).
     */
    fun pasteAllowed(r: Rect, viewW: Double, viewH: Double): Boolean =
        r.w > 30 && r.h > 20 &&
            r.w <= viewW * 0.9 && r.h <= viewH * 0.5 &&
            r.x >= 0 && r.y >= 0 && r.x + r.w <= viewW && r.y + r.h <= viewH &&
            r.y >= viewH * 0.33

    // MARK: Fixed words

    /** The scanner's wording, from this list or none (FoxyBridge.swift:1083). */
    val SCANNER_PROMPTS = setOf("Scan an ecash token", "Scan the mint address")

    fun scannerPrompt(asked: Any?): String {
        val text = asked as? String ?: ""
        return if (text in SCANNER_PROMPTS) text else ""
    }

    /** The biometric prompt's reason: one of these, never the page's own words (FoxyBridge.swift:826). */
    val BIOMETRIC_REASONS = listOf("Unlock Foxy", "Leave POS mode")

    fun biometricReason(asked: Any?): String {
        val text = asked as? String ?: ""
        return if (text in BIOMETRIC_REASONS) text else "Unlock Foxy"
    }

    /** A notification's title, always. */
    const val NOTIFY_TITLE = "Foxy"
    const val UNKNOWN_NOTIFICATION = "unknown notification kind"

    /** The words of a notification of `kind`, or null for a kind native does not know (a fake "you received 0.05 BTC" is a lure). */
    fun notificationText(kind: Any?): String? = when (kind) {
        "sent" -> "Payment sent"
        "received" -> "Payment received"
        else -> null
    }

    /** At most one notification every 3 s: more than 3 s since the last one shown (FoxyBridge.swift:1271-1276). */
    const val NOTIFY_EVERY_MS = 3_000L

    /** The throttle, on a monotonic clock in ms. Main thread. */
    class NotifyThrottle(private val clock: () -> Long) {
        private var last: Long? = null

        /** True, and the time noted, when a notification may be shown now. */
        fun allow(): Boolean {
            val now = clock()
            val before = last
            if (before != null && now - before <= NOTIFY_EVERY_MS) return false
            last = now
            return true
        }
    }

    /**
     * The GET DOLLARS screen's four web addresses, and no others: the page says
     * which by name, so nothing it holds can become a URL Foxy opens
     * (FoxyBridge.swift:1042-1047). Opened in the phone's browser, outside Foxy
     * and outside Tor.
     */
    val COMPANY_SITES = mapOf(
        "cashapp" to "https://cash.app",
        "strike" to "https://strike.me",
        "river" to "https://river.com",
        "flash" to "https://getflash.io",
    )

    const val NOT_A_COMPANY = "Not one of the listed sites."
    const val BROWSER_WOULD_NOT_OPEN = "The browser would not open."
    const val NO_SETTINGS = "No settings page to open."
    const val SETTINGS_WOULD_NOT_OPEN = "Settings would not open."

    fun companySite(which: Any?): String? = (which as? String)?.let { COMPANY_SITES[it] }

    // MARK: Share

    const val NOTHING_TO_SHARE = "Nothing to share."
    const val ALREADY_OPEN = "Something else is already open."

    // MARK: Scanner

    const val NO_SCANNER_VIEW = "Nothing to present the scanner from."
    const val NO_CAMERA = "No camera available."
    const val CAMERA_DENIED = "Camera access is off. Turn it on in Settings."
    const val SCAN_CANCELLED = "Cancelled."
}
