package io.islandbitcoin.foxy.web

/**
 * The page's `{haptic: kind}` as a View haptic constant (FoxyBridge.swift
 * `fireHaptic`; BRIDGE.md `haptic`): success, error, warning, pulse, long,
 * heavy and triple, anything else a light tap. Apart from the View so the JVM
 * tests hold the mapping; the values are android.view.HapticFeedbackConstants'
 * (the same numbers, written out because the test JVM has no android.jar
 * bodies). `warning`, `heavy` and `long` share LONG_PRESS: Android's View
 * haptics have no duration, and a Vibrator rework is out of scope (ENG-649
 * decision 6).
 */
object Haptics {
    const val LONG_PRESS = 0
    const val VIRTUAL_KEY = 1
    const val KEYBOARD_TAP = 3
    const val CLOCK_TICK = 4
    const val CONTEXT_CLICK = 6

    /** API 30 */
    const val CONFIRM = 16

    /** API 30 */
    const val REJECT = 17

    /** Three knocks 150 ms apart, for a card payment whose change is coming. */
    const val TRIPLE_GAP_MS = 150L

    sealed class Effect {
        data class Once(val constant: Int) : Effect()

        /** `constant`, three times, [TRIPLE_GAP_MS] apart. */
        data class Thrice(val constant: Int) : Effect()
    }

    /** The one-knock constant for `kind` on this phone (the native screens' own buzzes). */
    fun constant(kind: String): Int = when (val e = effect(kind, android.os.Build.VERSION.SDK_INT)) {
        is Effect.Once -> e.constant
        is Effect.Thrice -> e.constant
    }

    fun effect(kind: String, sdk: Int): Effect {
        val modern = sdk >= 30
        return when (kind) {
            "success" -> Effect.Once(if (modern) CONFIRM else VIRTUAL_KEY)
            "error" -> Effect.Once(if (modern) REJECT else LONG_PRESS)
            "warning", "heavy", "long" -> Effect.Once(LONG_PRESS)
            "pulse" -> Effect.Once(CLOCK_TICK)
            "triple" -> Effect.Thrice(CONTEXT_CLICK)
            else -> Effect.Once(KEYBOARD_TAP)
        }
    }
}
