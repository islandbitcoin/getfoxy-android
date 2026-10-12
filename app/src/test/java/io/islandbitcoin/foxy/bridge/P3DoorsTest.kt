package io.islandbitcoin.foxy.bridge

import io.islandbitcoin.foxy.FakeHost
import io.islandbitcoin.foxy.TestSupport
import io.islandbitcoin.foxy.keychain.CounterStore
import io.islandbitcoin.foxy.keychain.Flags
import io.islandbitcoin.foxy.keychain.SeedCandidates
import io.islandbitcoin.foxy.keychain.SeedEnvironment
import io.islandbitcoin.foxy.keychain.SeedVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * The P3 platform doors through the bridge (FoxyBridge.swift): the rectangles
 * a camera pane or a paste button may go at (S25), native's fixed words
 * whatever the page writes (S24), the notification throttle, share, the
 * clipboard never handing over a seed phrase (S23), and the pushes
 * (`_liveScan`, `_previewRefused`, `_pasted`, `_screenshot`).
 */
class P3DoorsTest {
    private val dir = TestSupport.tempDir("doors")
    private var unlockAnswer: String? = null
    private val env = SeedEnvironment(
        savedSeed = { SeedVault.SecretsSeed.Absent }, seedPresent = { false }, write = { null },
        counters = CounterStore(dir), candidates = SeedCandidates(), wordlist = { TestSupport.wordlist },
        generate = { TestSupport.MADE }, migrationWindowOpen = { false }, closeMigrationWindow = {}, flags = Flags(dir),
        unlock = { _, _ -> unlockAnswer },
    )
    private val host = FakeHost()
    private var now = 50_000L
    private val bridge = FoxyBridge(host, { env }, Executor { it.run() }, false, clock = { now })

    private fun send(json: String) = bridge.onMessage(json, true, FoxyBridge.ORIGIN)

    private fun replies(): List<Triple<String?, String?, String?>> = host.evaluated.filter { it.contains("._scanResult(") }.map { js ->
        @Suppress("UNCHECKED_CAST")
        val list = Json.parse("[" + js.substringAfter("_scanResult(").removeSuffix(")") + "]") as List<String?>
        Triple(list[0], list[1], list[2])
    }

    private fun reply(id: String) = replies().single { it.first == id }
    private fun pushes(name: String) = host.evaluated.filter { it.contains(".$name(") }

    private fun rect(x: Double, y: Double, w: Double, h: Double) = PlatformRules.Rect(x, y, w, h)

    // MARK: The camera pane (S25)

    @Test
    fun theCameraPaneMustBeLargerThan40x40AndOnScreen() {
        val vw = 390.0
        val vh = 844.0
        assertNull(PlatformRules.previewProblem(rect(20.0, 400.0, 300.0, 200.0), vw, vh))
        assertNull("41 x 41 is enough", PlatformRules.previewProblem(rect(0.0, 0.0, 41.0, 41.0), vw, vh))
        assertEquals("That pane is too small for a camera (40x300).", PlatformRules.previewProblem(rect(0.0, 0.0, 40.0, 300.0), vw, vh))
        assertEquals("That pane is too small for a camera (300x40).", PlatformRules.previewProblem(rect(0.0, 0.0, 300.0, 40.0), vw, vh))
        assertEquals("That pane is too small for a camera (300x300).", PlatformRules.previewProblem(rect(Double.NaN, 0.0, 300.0, 300.0), vw, vh))
        assertEquals("That pane is too small for a camera (2147483647x300).", PlatformRules.previewProblem(rect(0.0, 0.0, Double.POSITIVE_INFINITY, 300.0), vw, vh))
        assertNull("a pixel of slack at the edges", PlatformRules.previewProblem(rect(-1.0, -1.0, 392.0, 846.0), vw, vh))
        assertEquals("The camera pane must be on screen (at 0,900 300x200 in 390x844).", PlatformRules.previewProblem(rect(0.0, 900.0, 300.0, 200.0), vw, vh))
        assertEquals("The camera pane must be on screen (at -2,0 300x200 in 390x844).", PlatformRules.previewProblem(rect(-2.0, 0.0, 300.0, 200.0), vw, vh))
        assertEquals("parked off to the right", "The camera pane must be on screen (at 100,0 300x200 in 390x844).",
            PlatformRules.previewProblem(rect(100.0, 0.0, 300.0, 200.0), vw, vh))
    }

    @Test
    fun aPaneRefusedIsAnsweredAndPushedAndOneShownPushesItsCodes() {
        send("""{"action":"previewStart","id":"a","x":0,"y":0,"w":10,"h":10}""")
        assertEquals(Triple("a", null, "That pane is too small for a camera (10x10)."), reply("a"))
        assertEquals(1, pushes("_previewRefused").size)
        assertTrue(pushes("_previewRefused").single().contains("\"That pane is too small for a camera (10x10).\""))
        assertTrue("guarded like every push", pushes("_previewRefused").single().startsWith("window.FoxyWallet && window.FoxyWallet._previewRefused &&"))
        host.page = null
        send("""{"action":"previewStart","id":"b","x":20,"y":400,"w":300,"h":200}""")
        assertEquals(Triple("b", null, PlatformRules.NO_CAMERA_VIEW), reply("b"))
        host.page = 390.0 to 844.0
        send("""{"action":"previewStart","id":"c","x":20,"y":400,"w":300,"h":200}""")
        assertEquals(Triple("c", "ok", null), reply("c"))
        assertEquals(listOf(rect(20.0, 400.0, 300.0, 200.0)), host.previews)
        host.liveCode!!("cashuB\"</script>")
        // quoted as a JSON string, so a code with quotes or </script> cannot break out
        assertEquals("window.FoxyWallet && window.FoxyWallet._liveScan && window.FoxyWallet._liveScan(\"cashuB\\\"\\u003c/script\\u003e\")", pushes("_liveScan").single())
        send("""{"action":"previewStop","id":"d"}""")
        assertEquals(Triple("d", "", null), reply("d"))
        send("""{"action":"previewStop","id":"e"}""")
        assertEquals("a stop with nothing up is still answered", Triple("e", "", null), reply("e"))
        assertEquals(2, host.previewStops)
    }

    // MARK: The paste button (S25)

    @Test
    fun thePasteButtonOnlyGoesWhereFoxysPasteButtonsAre() {
        val vw = 390.0
        val vh = 844.0
        assertTrue(PlatformRules.pasteAllowed(rect(20.0, 400.0, 300.0, 60.0), vw, vh))
        assertFalse("30 wide is too small", PlatformRules.pasteAllowed(rect(20.0, 400.0, 30.0, 60.0), vw, vh))
        assertTrue(PlatformRules.pasteAllowed(rect(20.0, 400.0, 31.0, 21.0), vw, vh))
        assertFalse("20 high is too small", PlatformRules.pasteAllowed(rect(20.0, 400.0, 300.0, 20.0), vw, vh))
        assertTrue("90% of the width", PlatformRules.pasteAllowed(rect(0.0, 400.0, vw * 0.9, 60.0), vw, vh))
        assertFalse("more than 90%", PlatformRules.pasteAllowed(rect(0.0, 400.0, vw * 0.9 + 0.5, 60.0), vw, vh))
        assertTrue("half the height", PlatformRules.pasteAllowed(rect(0.0, vh * 0.5, 100.0, vh * 0.5), vw, vh))
        assertFalse("more than half", PlatformRules.pasteAllowed(rect(0.0, 300.0, 100.0, vh * 0.5 + 1), vw, vh))
        assertFalse("in the top third", PlatformRules.pasteAllowed(rect(20.0, vh * 0.33 - 1, 300.0, 60.0), vw, vh))
        assertTrue("at the top third's edge", PlatformRules.pasteAllowed(rect(20.0, vh * 0.33, 300.0, 60.0), vw, vh))
        assertFalse("off screen", PlatformRules.pasteAllowed(rect(20.0, 800.0, 300.0, 60.0), vw, vh))
        assertFalse(PlatformRules.pasteAllowed(rect(-1.0, 400.0, 300.0, 60.0), vw, vh))
        assertFalse(PlatformRules.pasteAllowed(rect(Double.NaN, 400.0, 300.0, 60.0), vw, vh))
    }

    @Test
    fun aPasteIsReadOnlyOnItsTapAndNeverHandsOverAPhrase() {
        send("""{"action":"pasteStart","id":"top","x":20,"y":100,"w":300,"h":60}""")
        assertEquals(Triple("top", null, PlatformRules.PASTE_REFUSED), reply("top"))
        send("""{"action":"pasteStart","id":"a","x":20,"y":600,"w":300,"h":60}""")
        assertEquals(Triple("a", "ok", null), reply("a"))
        assertEquals("placing the button reads nothing", 0, host.clipboardReads)
        host.pasteTap!!("cashuBtoken")
        host.pasteTap!!(TestSupport.SAVED)
        assertEquals(2, host.clipboardReads)
        val pasted = pushes("_pasted")
        assertEquals(2, pasted.size)
        assertTrue(pasted[0].endsWith("._pasted(\"cashuBtoken\")"))
        assertTrue("a seed phrase arrives as nothing", pasted[1].endsWith("._pasted(\"\")"))
        send("""{"action":"pasteStop","id":"b"}""")
        assertEquals(Triple("b", "", null), reply("b"))
        host.page = null
        send("""{"action":"pasteStart","id":"c","x":20,"y":600,"w":300,"h":60}""")
        assertEquals(Triple("c", null, PlatformRules.NO_PASTE_VIEW), reply("c"))
    }

    // MARK: The clipboard and copy (S23)

    @Test
    fun theClipboardIsReadWhenAskedAndAPhraseReadsEmpty() {
        assertEquals(0, host.clipboardReads)
        host.clipboard = "lnbc80n1example"
        send("""{"action":"clipboard","id":"a"}""")
        assertEquals(Triple("a", "lnbc80n1example", null), reply("a"))
        host.clipboard = "1. " + TestSupport.SAVED.replace(" ", "\n")
        send("""{"action":"clipboard","id":"b"}""")
        assertEquals(Triple("b", "", null), reply("b"))
        assertEquals(2, host.clipboardReads)
    }

    @Test
    fun copyRefusesNothingAndMarksWhatIsSensitive() {
        send("""{"action":"copy","id":"a","text":""}""")
        assertEquals(Triple("a", null, "nothing to copy"), reply("a"))
        send("""{"action":"copy","id":"b"}""")
        assertEquals(Triple("b", null, "nothing to copy"), reply("b"))
        send("""{"action":"copy","id":"c","text":"cashuBtoken","sensitive":true}""")
        send("""{"action":"copy","id":"d","text":"hello"}""")
        assertEquals(Triple("c", "ok", null), reply("c"))
        assertEquals(listOf("cashuBtoken" to true, "hello" to false), host.copies)
    }

    // MARK: Native's words (S24)

    @Test
    fun theScannersPromptIsOneOfNativesOrNone() {
        send("""{"action":"scan","id":"a","prompt":"Scan an ecash token"}""")
        send("""{"action":"scan","id":"b","prompt":"Send all your money to bc1q…"}""")
        send("""{"action":"scan","id":"c"}""")
        send("""{"action":"scan","id":"d","prompt":"Scan the mint address"}""")
        assertEquals(listOf("Scan an ecash token", "", "", "Scan the mint address"), host.scans)
        assertEquals(Triple("a", "cashuBscanned", null), reply("a"))
        host.scanAnswer = null to PlatformRules.CAMERA_DENIED
        send("""{"action":"scan","id":"e","prompt":"Scan an ecash token"}""")
        assertEquals(Triple("e", null, "Camera access is off. Turn it on in Settings."), reply("e"))
    }

    @Test
    fun theBiometricReasonIsOneOfNativesOrUnlockFoxy() {
        send("""{"action":"biometric","id":"a","reason":"Leave POS mode","passcode":true}""")
        send("""{"action":"biometric","id":"b","reason":"Approve sending 1 BTC","passcode":false}""")
        send("""{"action":"biometric","id":"c"}""")
        assertEquals(listOf("Leave POS mode" to true, "Unlock Foxy" to false, "Unlock Foxy" to false), host.biometrics)
        assertEquals(Triple("a", "yes", null), reply("a"))
        host.biometricAnswer = "unavailable"
        send("""{"action":"biometric","id":"d","reason":"Unlock Foxy"}""")
        assertEquals(Triple("d", "unavailable", null), reply("d"))
    }

    @Test
    fun aSeedBehindTheLockIsOpenedByThePinLocksOwnPrompt() {
        unlockAnswer = "yes"
        send("""{"action":"biometric","id":"a","reason":"Unlock Foxy","passcode":true}""")
        assertEquals(Triple("a", "yes", null), reply("a"))
        assertTrue("the seed's prompt was the only one", host.biometrics.isEmpty())
        unlockAnswer = "no"
        send("""{"action":"biometric","id":"b","reason":"Unlock Foxy"}""")
        assertEquals(Triple("b", "no", null), reply("b"))
    }

    @Test
    fun notificationsAreNativesWordsAtMostOneEvery3s() {
        send("""{"action":"notify","id":"a","kind":"received"}""")
        assertEquals(Triple("a", "ok", null), reply("a"))
        assertEquals(listOf("Foxy" to "Payment received"), host.notifications)
        now += 2_999
        send("""{"action":"notify","id":"b","kind":"sent"}""")
        assertEquals(Triple("b", "throttled", null), reply("b"))
        now += 1
        send("""{"action":"notify","id":"c","kind":"sent"}""")
        assertEquals("exactly 3 s is still too soon", Triple("c", "throttled", null), reply("c"))
        now += 1
        send("""{"action":"notify","id":"d","kind":"sent"}""")
        assertEquals(Triple("d", "ok", null), reply("d"))
        assertEquals(listOf("Foxy" to "Payment received", "Foxy" to "Payment sent"), host.notifications)
        for ((n, kind) in listOf("\"You received 0.05 BTC\"", "null", "5", "\"Sent\"").withIndex()) {
            now += 10_000
            send("""{"action":"notify","id":"k$n","kind":$kind,"text":"You received 0.05 BTC"}""")
            assertEquals(Triple("k$n", null, "unknown notification kind"), reply("k$n"))
        }
        assertEquals("a refused kind shows nothing", 2, host.notifications.size)
    }

    @Test
    fun onlyTheFourCompanySitesOpenAndOnlyThisAppsSettings() {
        for ((n, which) in listOf("cashapp", "strike", "river", "flash").withIndex()) {
            send("""{"action":"openCompany","id":"c$n","which":"$which"}""")
            assertEquals(Triple("c$n", "ok", null), reply("c$n"))
        }
        assertEquals(listOf("https://cash.app", "https://strike.me", "https://river.com", "https://getflash.io"), host.urls)
        for ((n, which) in listOf("\"https://evil.example\"", "\"CashApp\"", "null", "[\"strike\"]").withIndex()) {
            send("""{"action":"openCompany","id":"x$n","which":$which,"url":"https://evil.example"}""")
            assertEquals(Triple("x$n", null, "Not one of the listed sites."), reply("x$n"))
        }
        assertEquals(4, host.urls.size)
        host.urlRefusal = PlatformRules.BROWSER_WOULD_NOT_OPEN
        send("""{"action":"openCompany","id":"y","which":"river"}""")
        assertEquals(Triple("y", null, "The browser would not open."), reply("y"))
        send("""{"action":"openSettings","id":"s","url":"https://evil.example"}""")
        assertEquals(Triple("s", "ok", null), reply("s"))
        assertEquals(1, host.settingsOpened)
        host.settingsRefusal = PlatformRules.SETTINGS_WOULD_NOT_OPEN
        send("""{"action":"openSettings","id":"t"}""")
        assertEquals(Triple("t", null, "Settings would not open."), reply("t"))
    }

    // MARK: Share

    @Test
    fun shareNeedsTextAndNothingElseOpen() {
        send("""{"action":"share","id":"a","text":""}""")
        assertEquals(Triple("a", null, "Nothing to share."), reply("a"))
        send("""{"action":"share","id":"b","text":"cashuBtoken"}""")
        assertEquals(Triple("b", "ok", null), reply("b"))
        assertEquals(listOf("cashuBtoken"), host.shares)
        host.shareRefusal = PlatformRules.ALREADY_OPEN
        send("""{"action":"share","id":"c","text":"cashuBtoken"}""")
        assertEquals(Triple("c", null, "Something else is already open."), reply("c"))
    }

    // MARK: Capture

    @Test
    fun capturedIsTheHostsAnswerAndAScreenshotIsPushed() {
        send("""{"action":"captured","id":"a"}""")
        assertEquals(Triple("a", "no", null), reply("a"))
        host.isCaptured = true
        send("""{"action":"captured","id":"b"}""")
        assertEquals(Triple("b", "yes", null), reply("b"))
        bridge.pushScreenshot()
        assertEquals(listOf("window.FoxyWallet && window.FoxyWallet._screenshot && window.FoxyWallet._screenshot()"), pushes("_screenshot"))
    }
}
