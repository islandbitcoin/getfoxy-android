package io.islandbitcoin.foxy.web

import android.view.HapticFeedbackConstants
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `{haptic: kind}` (BRIDGE.md `haptic`; FoxyBridge.swift `fireHaptic`): every
 * kind the contract lists maps to its View constant, on API 30+ and before, and
 * an unknown kind is a light tap. The constants are android.jar's own,
 * compiled in.
 */
class HapticMappingTest {
    @Test
    fun theConstantsAreAndroids() {
        assertEquals(HapticFeedbackConstants.LONG_PRESS, Haptics.LONG_PRESS)
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, Haptics.VIRTUAL_KEY)
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, Haptics.KEYBOARD_TAP)
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, Haptics.CLOCK_TICK)
        assertEquals(HapticFeedbackConstants.CONTEXT_CLICK, Haptics.CONTEXT_CLICK)
        assertEquals(HapticFeedbackConstants.CONFIRM, Haptics.CONFIRM)
        assertEquals(HapticFeedbackConstants.REJECT, Haptics.REJECT)
    }

    @Test
    fun everyContractKindMapsToItsConstant() {
        val modern = mapOf(
            "success" to Haptics.Effect.Once(HapticFeedbackConstants.CONFIRM),
            "error" to Haptics.Effect.Once(HapticFeedbackConstants.REJECT),
            "warning" to Haptics.Effect.Once(HapticFeedbackConstants.LONG_PRESS),
            "heavy" to Haptics.Effect.Once(HapticFeedbackConstants.LONG_PRESS),
            "long" to Haptics.Effect.Once(HapticFeedbackConstants.LONG_PRESS),
            "pulse" to Haptics.Effect.Once(HapticFeedbackConstants.CLOCK_TICK),
            "triple" to Haptics.Effect.Thrice(HapticFeedbackConstants.CONTEXT_CLICK),
        )
        for ((kind, effect) in modern) assertEquals(kind, effect, Haptics.effect(kind, 37))
        assertEquals(Haptics.Effect.Once(HapticFeedbackConstants.VIRTUAL_KEY), Haptics.effect("success", 29))
        assertEquals(Haptics.Effect.Once(HapticFeedbackConstants.LONG_PRESS), Haptics.effect("error", 28))
        assertEquals(150L, Haptics.TRIPLE_GAP_MS)
    }

    @Test
    fun anUnknownKindIsALightTap() {
        for (kind in listOf("tap", "light", "", "SUCCESS", "<script>")) {
            assertEquals(kind, Haptics.Effect.Once(HapticFeedbackConstants.KEYBOARD_TAP), Haptics.effect(kind, 37))
        }
    }
}
