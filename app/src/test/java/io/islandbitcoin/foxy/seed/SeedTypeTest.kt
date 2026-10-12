package io.islandbitcoin.foxy.seed

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ENG-665: the seed screens' type is iOS's (SeedScreens.swift `SeedStyle`,
 * `spaced`, `chip`, `wordCell`, `veil`): the same sizes, UIFont's weights as
 * CSS weights, and a kern in points as Android's spacing in ems.
 */
class SeedTypeTest {
    private fun check(name: String, type: SeedType, size: Float, weight: Int, kern: Float) {
        assertEquals("$name size", size, type.sizeSp, 0f)
        assertEquals("$name weight", weight, type.weight)
        assertEquals("$name kern", kern, type.kern, 0f)
        assertEquals("$name spacing is the kern over the size", kern / size, type.letterSpacing, 1e-6f)
    }

    @Test
    fun theWeightsAreUiFonts() {
        assertEquals("UIFont.Weight.heavy", 800, SeedType.HEAVY)
        assertEquals("UIFont.Weight.bold", 700, SeedType.BOLD)
        assertEquals("UIFont.Weight.semibold", 600, SeedType.SEMIBOLD)
    }

    @Test
    fun everyStyleIsIoss() {
        check("title: spaced(size: 22, kern: 1.4)", SeedType.TITLE, 22f, 800, 1.4f)
        check("primary button: spaced(size: 18, kern: 1.2)", SeedType.BUTTON, 18f, 800, 1.2f)
        check("body: 18 semibold", SeedType.body(), 18f, 600, 0f)
        check("a body of its own size", SeedType.body(16f), 16f, 600, 0f)
        check("native bar: 15 semibold", SeedType.NATIVE_BAR, 15f, 600, 0f)
        check("chip word: 18 bold", SeedType.CHIP_WORD, 18f, 700, 0f)
        check("chip number: 14 heavy", SeedType.CHIP_NUMBER, 14f, 800, 0f)
        check("cell number: 14 bold", SeedType.CELL_NUMBER, 14f, 700, 0f)
        check("cell word: 20 bold", SeedType.CELL_WORD, 20f, 700, 0f)
        check("TAP TO REVEAL: spaced(size: 16, kern: 0.7)", SeedType.REVEAL, 16f, 800, 0.7f)
        check("HOLD TO DELETE ALL DATA: spaced(size: 15, kern: 1)", SeedType.DELETE, 15f, 800, 1f)
        check("suggestion: 17 semibold", SeedType.SUGGESTION, 17f, 600, 0f)
    }

    @Test
    fun theSpacingIsWhatIosDrawsAtThatSize() {
        // 1.4 pt between letters at 22 pt is 0.0636 em: wider than the 0.06 the screens used before
        assertEquals(0.0636f, SeedType.TITLE.letterSpacing, 0.0001f)
        assertEquals(0.0667f, SeedType.BUTTON.letterSpacing, 0.0001f)
        assertEquals(0.0438f, SeedType.REVEAL.letterSpacing, 0.0001f)
    }
}
