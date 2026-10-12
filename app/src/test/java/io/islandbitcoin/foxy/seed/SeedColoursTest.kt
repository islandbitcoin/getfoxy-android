package io.islandbitcoin.foxy.seed

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * ENG-665: the seed screens draw SeedStyle's colours in code, and the native
 * dialogs and the notification's tint take the same colours from
 * res/values/colors.xml. Both are SeedScreens.swift `SeedStyle`; this keeps
 * the two copies one colour, so retuning one without the other fails here.
 */
class SeedColoursTest {
    private val res = File(System.getProperty("foxy.res") ?: "src/main/res")

    private fun colours(): Map<String, Int> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "values/colors.xml")).documentElement
        val nodes = root.getElementsByTagName("color")
        return (0 until nodes.length).map { nodes.item(it) as Element }.associate {
            it.getAttribute("name") to it.textContent.trim().removePrefix("#").toLong(16).toInt()
        }
    }

    private fun hex(argb: Int) = "#%08X".format(argb)

    @Test
    fun colorsXmlIsSeedStyle() {
        val c = colours()
        assertEquals("accent is SeedStyle.ACCENT", hex(SeedStyle.ACCENT), hex(c.getValue("accent")))
        assertEquals("danger is SeedStyle.RED", hex(SeedStyle.RED), hex(c.getValue("danger")))
        assertEquals("ink is SeedStyle.INK", hex(SeedStyle.INK), hex(c.getValue("ink")))
        assertEquals("dialog_background is SeedStyle.TILE", hex(SeedStyle.TILE), hex(c.getValue("dialog_background")))
        assertEquals("seed_background is SeedStyle.BACKGROUND", hex(SeedStyle.BACKGROUND), hex(c.getValue("seed_background")))
    }

    @Test
    fun theDerivedInksAreInkAtTheirAlpha() {
        val c = colours()
        val ink = SeedStyle.INK and 0xffffff
        assertEquals("ink_body is ink at the page's 62 %", hex((0x9E shl 24) or ink), hex(c.getValue("ink_body")))
        assertEquals("dialog_edge is ink at 12 %", hex((0x1F shl 24) or ink), hex(c.getValue("dialog_edge")))
    }

    @Test
    fun seedStyleIsIoss() {
        // SeedScreens.swift:321-324 (foxy-ios-ref 8df0bb1) SeedStyle, 0-255 per channel; TILE is Android's own (iOS has none)
        assertEquals("background 10, 10, 10", hex(0xFF0A0A0A.toInt()), hex(SeedStyle.BACKGROUND))
        assertEquals("ink 245, 241, 236", hex(0xFFF5F1EC.toInt()), hex(SeedStyle.INK))
        assertEquals("accent 242, 128, 46", hex(0xFFF2802E.toInt()), hex(SeedStyle.ACCENT))
        assertEquals("red 255, 92, 92", hex(0xFFFF5C5C.toInt()), hex(SeedStyle.RED))
    }
}
