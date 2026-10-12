package io.islandbitcoin.foxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * ENG-665: the app's art is the iOS art (app/art/NOTICE.md, tools/make-art.py),
 * cut to Android's rules: the adaptive icon's layers and their safe zones, a
 * small icon that is white on clear, the splash icon pixel for pixel from the
 * intro's first frame and inside the circle Android 12+ shows, the Play Store
 * icon, the licence kept; and the themes that use them.
 */
class ArtTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private val res = File(System.getProperty("foxy.res") ?: "src/main/res")
    private val main = res.parentFile
    private val app = main.parentFile.parentFile
    private val core = File(System.getProperty("foxy.core") ?: "../core")
    private val densities = mapOf("mdpi" to 1.0, "hdpi" to 1.5, "xhdpi" to 2.0, "xxhdpi" to 3.0, "xxxhdpi" to 4.0)

    private fun parse(file: File): Element = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(file).documentElement

    private fun image(file: File): Png {
        assertTrue("$file exists", file.isFile)
        return Png.read(file)
    }

    private fun alpha(argb: Int) = argb ushr 24
    private fun rgb(argb: Int) = argb and 0xffffff

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun styles(): Map<String, Element> {
        val nodes = parse(File(res, "values/themes.xml")).getElementsByTagName("style")
        return (0 until nodes.length).map { nodes.item(it) as Element }.associateBy { it.getAttribute("name") }
    }

    private fun items(style: Element): Map<String, String> {
        val nodes = style.getElementsByTagName("item")
        return (0 until nodes.length).map { nodes.item(it) as Element }.associate { it.getAttribute("name") to it.textContent.trim() }
    }

    // MARK: The launcher icon

    @Test
    fun theAdaptiveIconIsTheIosFoxWithAThemedLayer() {
        val icon = parse(File(res, "mipmap-anydpi/ic_launcher.xml"))
        fun layer(name: String) = (icon.getElementsByTagName(name).item(0) as Element).getAttributeNS(android, "drawable")
        assertEquals("@drawable/ic_launcher_background", layer("background"))
        assertEquals("@mipmap/ic_launcher_foreground", layer("foreground"))
        assertEquals("the themed icon (Android 13+) is the same white fox", "@mipmap/ic_launcher_foreground", layer("monochrome"))
        assertFalse("the placeholder vector fox is gone", File(res, "drawable/ic_launcher_foreground.xml").exists())
        val background = parse(File(res, "drawable/ic_launcher_background.xml"))
        assertEquals("black, as the iOS icon's ground", "@color/splash",
            (background.getElementsByTagName("solid").item(0) as Element).getAttributeNS(android, "color"))
    }

    @Test
    fun theForegroundIsAt108DpInEveryDensityWhiteOnClear() {
        for ((name, d) in densities) {
            val png = image(File(res, "mipmap-$name/ic_launcher_foreground.png"))
            val side = (108 * d).roundToInt()
            assertEquals("$name width", side, png.width)
            assertEquals("$name height", side, png.height)
            var drawn = 0
            for (y in 0 until side) for (x in 0 until side) {
                val p = png.getRGB(x, y)
                if (alpha(p) == 0) continue
                drawn++
                assertEquals("$name ($x,$y) is white: the fox's lines are clear, so they show the black ground or the theme's", 0xffffff, rgb(p))
            }
            assertTrue("$name draws the fox", drawn > side * side / 5)
        }
    }

    @Test
    fun theFoxKeepsToTheSafeZoneAndItsNeckRunsOffTheBottom() {
        val png = image(File(res, "mipmap-xxxhdpi/ic_launcher_foreground.png"))
        val px = png.width / 108.0
        val centre = png.width / 2.0
        // above the neck, everything drawn is inside the 66 dp circle every mask keeps
        // (the face's lower edge may reach the 72 dp the masks show, but not past it)
        for (y in 0 until (80 * px).toInt()) for (x in 0 until png.width) {
            if (alpha(png.getRGB(x, y)) < 128) continue
            val r = hypot(x + 0.5 - centre, y + 0.5 - centre) / px
            assertTrue("($x,$y) is ${"%.1f".format(r)} dp from the centre", r <= 33.5)
        }
        for (y in (90 * px).toInt() until png.height) {
            assertEquals("the neck's middle at row $y is solid", 255, alpha(png.getRGB(png.width / 2, y)))
        }
        assertEquals("nothing outside the art's 64 dp across", 0, alpha(png.getRGB((10 * px).toInt(), png.height - 1)))
    }

    @Test
    fun thePlayStoreIconIs512AndOpaque() {
        val png = image(File(main, "ic_launcher-playstore.png"))
        assertEquals(512, png.width)
        assertEquals(512, png.height)
        for (y in 0 until 512 step 7) for (x in 0 until 512 step 7) assertEquals("($x,$y)", 255, alpha(png.getRGB(x, y)))
    }

    // MARK: The notification's small icon

    @Test
    fun theSmallIconIsAWhiteFoxHeadAt24Dp() {
        assertFalse("the placeholder bolt is gone", File(res, "drawable/ic_notify.xml").exists())
        for ((name, d) in densities) {
            val png = image(File(res, "drawable-$name/ic_notify.png"))
            val side = (24 * d).roundToInt()
            assertEquals("$name width", side, png.width)
            assertEquals("$name height", side, png.height)
            var drawn = 0
            var clear = 0
            for (y in 0 until side) for (x in 0 until side) {
                val p = png.getRGB(x, y)
                if (alpha(p) == 0) {
                    clear++
                    continue
                }
                drawn++
                assertEquals("$name ($x,$y): Android draws a small icon by its alpha alone, so it is white", 0xffffff, rgb(p))
            }
            assertTrue("$name has a shape, not a square", drawn > side * side / 4 && clear > side * side / 4)
        }
    }

    // MARK: The splash

    @Test
    fun theSplashIconIsTheStillsSleepingFoxPixelForPixel() {
        val still = image(File(core, "web/foxy-splash-still.png"))
        val fox = image(File(res, "drawable-nodpi/splash_fox.png"))
        assertEquals(600, fox.width)
        assertEquals(600, fox.height)
        val top = 352
        for (y in 0 until 600) for (x in 0 until 600) {
            assertEquals("($x,$y)", rgb(still.getRGB(x, top + y)), rgb(fox.getRGB(x, y)))
            assertEquals("($x,$y) is opaque", 255, alpha(fox.getRGB(x, y)))
        }
        // the rest of the still is black: what was cut away is the splash's own ground
        for (y in 0 until still.height) for (x in 0 until still.width) {
            if (y in top until top + 600) continue
            val p = rgb(still.getRGB(x, y))
            assertTrue("($x,$y) of the still outside the cut is black", (p shr 16) < 48 && (p shr 8 and 0xff) < 48 && (p and 0xff) < 48)
        }
    }

    @Test
    fun theSleepingFoxFitsTheMiddleTwoThirdsAndroidShows() {
        // Android 12+ shows an icon with no icon background inside a circle two thirds of its side across
        val fox = image(File(res, "drawable-nodpi/splash_fox.png"))
        val c = fox.width / 2.0
        var lit = 0
        for (y in 0 until fox.height) for (x in 0 until fox.width) {
            val p = rgb(fox.getRGB(x, y))
            if ((p shr 16) < 48) continue
            lit++
            assertTrue("($x,$y) is inside the circle", hypot(x + 0.5 - c, y + 0.5 - c) <= fox.width / 3.0)
        }
        assertTrue("the fox is there", lit > 20_000)
    }

    @Test
    fun theStartingThemeUsesTheFoxOnBlackAndTheCoverReadsTheStill() {
        val starting = items(styles().getValue("Theme.Foxy.Starting"))
        assertEquals("@drawable/splash_fox", starting["windowSplashScreenAnimatedIcon"])
        assertEquals("@color/splash", starting["windowSplashScreenBackground"])
        assertFalse("no icon background: the fox's own black is the splash's", "windowSplashScreenIconBackgroundColor" in starting)
        assertEquals("@style/Theme.Foxy", starting["postSplashScreenTheme"])
        assertEquals("@color/splash", items(styles().getValue("Theme.Foxy"))["android:windowBackground"])
        assertTrue("the cover's still is the page's, in the APK's assets/web/", File(core, SplashCover.STILL).isFile)
        val colors = parse(File(res, "values/colors.xml")).getElementsByTagName("color")
        val splash = (0 until colors.length).map { colors.item(it) as Element }.single { it.getAttribute("name") == "splash" }
        assertEquals("black, iOS's splashGreen and the intro's ground", "#FF000000", splash.textContent.trim())
    }

    // MARK: Native dialogs

    @Test
    fun theDialogThemeIsThePagesCardWithHeavySpacedOrangeButtons() {
        val all = styles()
        val dialog = all.getValue("Theme.Foxy.Dialog")
        assertEquals("android:Theme.Material.Dialog.Alert", dialog.getAttribute("parent"))
        val d = items(dialog)
        assertEquals("@drawable/dialog_background", d["android:windowBackground"])
        assertEquals("@color/accent", d["android:colorAccent"])
        assertEquals("@color/ink", d["android:textColorPrimary"])
        val button = items(all.getValue(d.getValue("android:buttonBarButtonStyle").removePrefix("@style/")))
        assertEquals("800", button["android:textFontWeight"])
        assertEquals("@color/accent", button["android:textColor"])
        assertEquals("true", button["android:textAllCaps"])
        assertEquals("SeedType.BUTTON's kern over the dialog's size", 1.2f / 16f,
            button.getValue("android:letterSpacing").toFloat(), 0.001f)
        assertEquals("16sp", button["android:textSize"])
        val title = items(all.getValue(items(all.getValue(d.getValue("android:windowTitleStyle").removePrefix("@style/")))
            .getValue("android:textAppearance").removePrefix("@style/")))
        assertEquals("800", title["android:textFontWeight"])
        assertEquals("SeedType.TITLE's kern over the dialog's size", 1.4f / 20f, title.getValue("android:letterSpacing").toFloat(), 0.001f)
        assertEquals("20sp", title["android:textSize"])
        val card = parse(File(res, "drawable/dialog_background.xml"))
        assertEquals("@color/dialog_background", (card.getElementsByTagName("solid").item(0) as Element).getAttributeNS(android, "color"))
        assertEquals("20dp", (card.getElementsByTagName("corners").item(0) as Element).getAttributeNS(android, "radius"))
    }

    // MARK: The licence

    @Test
    fun theNoticeNamesEverySourceAndKeepsTheUpstreamLicence() {
        val notice = File(app, "art/NOTICE.md").readText()
        val licence = File(core, "UPSTREAM-LICENSE").readText().trim()
        assertTrue("the MIT licence, word for word", notice.contains(licence))
        assertTrue(notice.contains("Foxy/Assets.xcassets/AppIcon.appiconset/foxy app icon.png"))
        assertTrue(notice.contains("Web/foxy-splash-still.png"))
        assertTrue("the still's sha256 is iOS's (Web/VENDOR.md)",
            notice.contains("d21b711786d81aa20cb79b3aa823178230eb66b71fceb7478da12c6af6626144"))
        assertEquals("and core/web's still is that file", "d21b711786d81aa20cb79b3aa823178230eb66b71fceb7478da12c6af6626144",
            sha256(File(core, "web/foxy-splash-still.png")))
        val pin = File(core, "PIN").readLines().single { it.startsWith("commit=") }.removePrefix("commit=")
        assertTrue("the commit the art was cut at is core/PIN's", notice.contains(pin))
    }
}
