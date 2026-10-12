package io.islandbitcoin.foxy

/**
 * The launch, handed over as iOS hands it (ENG-665; FoxyWebView.swift
 * `addSplash`/`hideSplash`, the launch image `FoxySplash`, project.yml:89-91).
 *
 * On iOS the launch screen is the intro's first frame, full screen; a view of
 * the same still covers the web view until the page says `covered`, then fades
 * out in 0.35 s over the page's intro layer, whose background is that still,
 * `center/cover` (Web/foxy-tor-gate.js `introPlay`), so nothing moves.
 *
 * Android 12+ draws a splash of its own first, and it can hold only an icon:
 * the sleeping fox, cut pixel for pixel from that still (`splash_fox`). It goes
 * with Foxy's first frame, fading out in [SYSTEM_FADE_MS] onto the cover: the
 * still (the page's own [STILL], so the two cannot differ) scaled as CSS
 * `cover` scales it (ImageView CENTER_CROP: the larger of the two ratios,
 * centred) over the web view at the web view's size. The cover comes down as
 * iOS's does, at the page's first `covered` or the [BACKSTOP_MS] backstop,
 * whichever is first; anything after that is no news.
 *
 * The view is MainActivity's; this is the part the JVM tests run.
 */
class SplashCover(private val takeDown: (fadeMs: Long) -> Unit) {
    companion object {
        /** The intro's first frame, core/web/foxy-splash-still.png, in the APK's assets. */
        const val STILL = "web/foxy-splash-still.png"

        /** iOS's `hideSplash` fade. */
        const val FADE_MS = 350L

        /** Android's own splash onto the cover. */
        const val SYSTEM_FADE_MS = 200L

        /** The page says `covered` before this (docs/BRIDGE.md `covered`); if it does not, the cover comes down anyway. */
        const val BACKSTOP_MS = 8_000L
    }

    var isUp = true
        private set

    /** The page has the screen covered, or the backstop ran out: the first takes the cover down. Main thread. */
    fun down() {
        if (!isUp) return
        isUp = false
        takeDown(FADE_MS)
    }
}
