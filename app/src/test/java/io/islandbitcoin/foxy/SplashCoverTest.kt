package io.islandbitcoin.foxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ENG-665: the launch handover (FoxyWebView.swift `addSplash`/`hideSplash`):
 * the intro's still covers the page until the first `covered` or the 8 s
 * backstop, and fades as iOS's fades; later ones are no news.
 */
class SplashCoverTest {
    private val fades = mutableListOf<Long>()
    private val cover = SplashCover { fades.add(it) }

    @Test
    fun theFirstCoveredTakesItDownWithIosFade() {
        assertTrue(cover.isUp)
        assertEquals(emptyList<Long>(), fades)
        cover.down()
        assertFalse(cover.isUp)
        assertEquals("hideSplash's 0.35 s", listOf(350L), fades)
    }

    @Test
    fun aSecondCoveredOrTheBackstopAfterItIsNoNews() {
        cover.down()
        cover.down()
        cover.down()
        assertEquals(listOf(SplashCover.FADE_MS), fades)
    }

    @Test
    fun theBackstopIsTheEightSecondsTheBridgeNames() {
        assertEquals(8_000L, SplashCover.BACKSTOP_MS)
        assertEquals(SplashCover.BACKSTOP_MS, MainActivity.SPLASH_BACKSTOP_MS)
        assertTrue("Android's splash is quicker than the cover's own fade", SplashCover.SYSTEM_FADE_MS < SplashCover.FADE_MS)
    }

    @Test
    fun theStillIsThePagesOwnFirstFrame() {
        // the APK's assets/ are core/ (app/build.gradle.kts), so web/<file> is core/web/<file>
        val core = File(System.getProperty("foxy.core") ?: "../core")
        assertEquals("web/foxy-splash-still.png", SplashCover.STILL)
        assertTrue(File(core, SplashCover.STILL).isFile)
        val gate = File(core, "web/foxy-tor-gate.js").readText()
        assertTrue("the intro layer's background is the same still, centred and covering",
            gate.contains("background:#000 url(foxy-splash-still.png) ' +\n      'center/cover no-repeat"))
    }
}
