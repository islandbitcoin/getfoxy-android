package io.islandbitcoin.foxy.tap

import kotlin.math.sqrt

/**
 * A shake, from the accelerometer (FoxyWebView.swift `motionEnded(.motionShake)`):
 * iOS decides what a shake is, the gesture of shake to undo; Android has no
 * such gesture, so this is one. Two jolts above [JOLT_G] (the force of the
 * phone less gravity, in g) within [WINDOW_MS], and then nothing for
 * [QUIET_MS]: a phone set down, tapped or carried does not make two in that
 * window, and one wave of the hand does. Readings are m/s², as SensorEvent's.
 */
class ShakeDetector(private val onShake: () -> Unit) {
    companion object {
        const val GRAVITY = 9.80665
        const val JOLT_G = 1.6
        const val WINDOW_MS = 600L
        const val JOLTS = 2
        const val QUIET_MS = 1_200L

        /** Readings closer together than this are one jolt, not two. */
        const val SAME_JOLT_MS = 120L
    }

    private var firstJolt = -1L
    private var lastJolt = -1L
    private var jolts = 0
    private var quietUntil = Long.MIN_VALUE

    fun reading(x: Float, y: Float, z: Float, atMs: Long) {
        if (atMs < quietUntil) return
        val g = sqrt((x * x + y * y + z * z).toDouble()) / GRAVITY
        // the force less the steady one g of gravity, whichever way the phone faces
        if (kotlin.math.abs(g - 1.0) < JOLT_G) return
        if (lastJolt >= 0 && atMs - lastJolt < SAME_JOLT_MS) return
        if (firstJolt < 0 || atMs - firstJolt > WINDOW_MS) {
            firstJolt = atMs
            jolts = 0
        }
        lastJolt = atMs
        jolts += 1
        if (jolts >= JOLTS) {
            firstJolt = -1
            lastJolt = -1
            jolts = 0
            quietUntil = atMs + QUIET_MS
            onShake()
        }
    }
}

/**
 * Which way up the phone is held (FoxyWebView.swift `deviceTurned`): the page is
 * told portrait or upside down each time the phone comes to one of the two, and
 * nothing for face up, face down or landscape, which leave it as it was. The
 * activity stays portrait; the page does the turn.
 *
 * Readings are OrientationEventListener's degrees (0 upright, 180 upside down,
 * -1 lying flat). Each band is 60° wide around its angle, and a reading between
 * bands changes nothing, so a phone held near a boundary does not flicker.
 */
class TurnDetector(private val onTurned: (upsideDown: Boolean) -> Unit) {
    enum class Way { PORTRAIT, UPSIDE_DOWN, LANDSCAPE, FLAT }

    companion object {
        const val FLAT = -1
        const val HALF_BAND = 30

        fun way(degrees: Int): Way? {
            if (degrees < 0) return Way.FLAT
            val d = degrees % 360
            return when {
                d <= HALF_BAND || d >= 360 - HALF_BAND -> Way.PORTRAIT
                d in 180 - HALF_BAND..180 + HALF_BAND -> Way.UPSIDE_DOWN
                d in 90 - HALF_BAND..90 + HALF_BAND || d in 270 - HALF_BAND..270 + HALF_BAND -> Way.LANDSCAPE
                else -> null
            }
        }
    }

    private var now: Way? = null

    fun reading(degrees: Int) {
        val way = way(degrees) ?: return
        if (way == now) return
        now = way
        // only the two that mean something here
        if (way == Way.PORTRAIT || way == Way.UPSIDE_DOWN) onTurned(way == Way.UPSIDE_DOWN)
    }

    /** Listening again (Foxy back in front): the next reading is told whatever it is. */
    fun forget() {
        now = null
    }
}
