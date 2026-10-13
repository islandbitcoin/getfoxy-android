package io.islandbitcoin.foxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * S1 on the merged manifest the debug APK ships: no backup, no device transfer,
 * every domain excluded from both, the permissions P3 needs and no other,
 * tor-android's service bound and never exported or in the foreground (audit
 * finding 4), Safe Browsing and WebView metrics off, every activity portrait
 * and only the page's and the card's door reachable from outside; and the release manifest
 * registers no deep-link scheme at all (BRIDGE.md `deepLink`), only the card's
 * one address, https://getfoxy.io/card, from the tag dispatch and as a link (ENG-668),
 * on CardTapActivity, never on MainActivity (review of PR #15: a second page).
 */
class ManifestTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private fun parse(file: File) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(file).documentElement

    private val merged: Element by lazy { manifest("foxy.mergedManifest") }
    private val release: Element by lazy { manifest("foxy.releaseManifest") }

    private fun manifest(property: String): Element {
        val file = File(System.getProperty(property) ?: error("the $property path is not set"))
        assertTrue("the merged manifest is built before the unit tests ($file)", file.exists())
        return parse(file)
    }

    private fun elements(root: Element, name: String): List<Element> {
        val nodes = root.getElementsByTagName(name)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test
    fun nothingLeavesInABackupOrATransfer() {
        val app = elements(merged, "application").single()
        assertEquals("false", app.getAttributeNS(android, "allowBackup"))
        assertEquals("false", app.getAttributeNS(android, "fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", app.getAttributeNS(android, "dataExtractionRules"))
    }

    @Test
    fun theRulesExcludeEveryDomainForBackupAndTransfer() {
        val rules = parse(File(System.getProperty("foxy.res") ?: "src/main/res", "xml/data_extraction_rules.xml"))
        val domains = setOf("root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val s = elements(rules, section).single()
            assertEquals("$section has no include", 0, s.getElementsByTagName("include").length)
            val excluded = elements(s, "exclude").map { it.getAttribute("domain") to it.getAttribute("path") }
            assertEquals(section, domains, excluded.map { it.first }.toSet())
            assertTrue("$section excludes each whole domain", excluded.all { it.second == "." })
        }
    }

    @Test
    fun thePermissionsP3AndTheCardNeedAndNoOther() {
        val permissions = elements(merged, "uses-permission").map { it.getAttributeNS(android, "name") }.toSet()
        assertEquals(
            "INTERNET (Tor and Route), ACCESS_NETWORK_STATE (NetworkKind), and P3's CAMERA (the scanner), " +
                "POST_NOTIFICATIONS (payments), USE_BIOMETRIC (the PIN lock, the seed's device protection) and " +
                "DETECT_SCREEN_CAPTURE (_screenshot), NFC (a card session, ENG-667), and nothing else: $permissions",
            setOf(
                "android.permission.INTERNET",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.CAMERA",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.USE_BIOMETRIC",
                "android.permission.DETECT_SCREEN_CAPTURE",
                "android.permission.NFC",
                "io.islandbitcoin.foxy.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            ),
            permissions,
        )
        assertFalse("never a foreground service", permissions.any { it.contains("FOREGROUND_SERVICE") })
        assertTrue("no permission of its own is declared beyond the receiver one", elements(merged, "permission").all {
            it.getAttributeNS(android, "name") == "io.islandbitcoin.foxy.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        })
        val camera = elements(merged, "uses-feature").filter { it.getAttributeNS(android, "name").startsWith("android.hardware.camera") }
        assertTrue("a camera is used where there is one, never required", camera.isNotEmpty() && camera.all { it.getAttributeNS(android, "required") == "false" })
        val nfc = elements(merged, "uses-feature").filter { it.getAttributeNS(android, "name") == "android.hardware.nfc" }
        assertEquals("NFC is used where there is one, never required: a phone without it installs Foxy", 1, nfc.size)
        assertEquals("false", nfc.single().getAttributeNS(android, "required"))
    }

    /**
     * ENG-667: a card is read only in a session the page opened, in reader mode
     * (flashcard/ReaderModeRadio.kt). No activity takes a card's technology or a
     * bare tag from Android's dispatch, in either build. ENG-668: the one
     * exception is NDEF, on the card's door only (CardTapActivity, which routes
     * it, CardTapRoute), and only for the card's own address (CardAddress), no
     * prefix, pattern or MIME type that would take anything more.
     */
    @Test
    fun noActivityIsHandedACardByTheSystemButThePageItsAddress() {
        for (manifest in listOf(merged, release)) {
            val actions = elements(manifest, "action").map { it.getAttributeNS(android, "name") }
            for (dispatch in listOf("android.nfc.action.TECH_DISCOVERED", "android.nfc.action.TAG_DISCOVERED")) {
                assertFalse("$dispatch is not filtered for", dispatch in actions)
            }
            assertEquals("no tech list for TECH_DISCOVERED", 0,
                elements(manifest, "meta-data").count { it.getAttributeNS(android, "name") == "android.nfc.action.TECH_DISCOVERED" })
            val ndef = filters(manifest).filter { f -> actionsOf(f).contains("android.nfc.action.NDEF_DISCOVERED") }
            assertEquals("one NDEF filter", 1, ndef.size)
            val f = ndef.single()
            assertEquals("on the card's door, never the page's activity", door, (f.parentNode as Element).getAttributeNS(android, "name"))
            assertEquals("the filter takes NDEF and nothing else", listOf("android.nfc.action.NDEF_DISCOVERED"), actionsOf(f))
            assertEquals(listOf("android.intent.category.DEFAULT"), categoriesOf(f))
            assertTheCardsAddressOnly(f)
        }
    }

    /**
     * ENG-668: the card's address as a link, from a browser or an NFC stack that offers
     * it as one. Not verified (autoVerify false) until getfoxy.io publishes an
     * assetlinks.json for the release key; the same exact address, in both builds.
     */
    @Test
    fun theCardsAddressIsALinkToThePageUnverified() {
        for (manifest in listOf(merged, release)) {
            val links = filters(manifest).filter { f -> dataOf(f).any { it.getAttributeNS(android, "scheme") == "https" } }
                .filter { f -> actionsOf(f).contains("android.intent.action.VIEW") }
            assertEquals("one https link filter", 1, links.size)
            val f = links.single()
            assertEquals(door, (f.parentNode as Element).getAttributeNS(android, "name"))
            assertEquals(listOf("android.intent.action.VIEW"), actionsOf(f))
            assertEquals(setOf("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE"), categoriesOf(f).toSet())
            assertEquals("not an App Link until getfoxy.io's assetlinks.json names the release key", "false", f.getAttributeNS(android, "autoVerify"))
            assertTheCardsAddressOnly(f)
        }
    }

    private val door = "io.islandbitcoin.foxy.CardTapActivity"

    /**
     * Review of PR #15: a card's address never starts MainActivity, which singleTop
     * would duplicate under a seed screen or the scanner. Its door draws nothing,
     * keeps no history or Recents entry, and has a task of its own, so being started
     * never by itself brings Foxy's task, and whatever is over the page, forward.
     */
    @Test
    fun theCardsDoorDrawsNothingAndKeepsNoPlace() {
        for (manifest in listOf(merged, release)) {
            val a = elements(manifest, "activity").single { it.getAttributeNS(android, "name") == door }
            assertEquals("true", a.getAttributeNS(android, "exported"))
            assertEquals("@android:style/Theme.NoDisplay", a.getAttributeNS(android, "theme"))
            assertEquals("a task of its own", "", a.getAttributeNS(android, "taskAffinity"))
            assertTrue("taskAffinity is set, empty", a.hasAttributeNS(android, "taskAffinity"))
            assertEquals("true", a.getAttributeNS(android, "excludeFromRecents"))
            assertEquals("true", a.getAttributeNS(android, "noHistory"))
            val page = elements(manifest, "activity").single { it.getAttributeNS(android, "name") == "io.islandbitcoin.foxy.MainActivity" }
            val pageData = elements(page, "data").map { it.getAttributeNS(android, "scheme") }
            assertFalse("the page's activity takes no https address", "https" in pageData)
            assertFalse("nor the tag dispatch", elements(page, "action").any { it.getAttributeNS(android, "name").startsWith("android.nfc.") })
        }
    }

    private fun filters(root: Element): List<Element> = elements(root, "intent-filter")
    private fun actionsOf(f: Element) = elements(f, "action").map { it.getAttributeNS(android, "name") }
    private fun categoriesOf(f: Element) = elements(f, "category").map { it.getAttributeNS(android, "name") }
    private fun dataOf(f: Element) = elements(f, "data")

    /** https, getfoxy.io and the path /card exactly: no port, prefix, pattern or MIME type. */
    private fun assertTheCardsAddressOnly(f: Element) {
        val data = dataOf(f).single()
        assertEquals("https", data.getAttributeNS(android, "scheme"))
        assertEquals("getfoxy.io", data.getAttributeNS(android, "host"))
        assertEquals("/card", data.getAttributeNS(android, "path"))
        for (wide in listOf("port", "pathPrefix", "pathPattern", "pathAdvancedPattern", "pathSuffix", "mimeType", "ssp", "sspPrefix", "sspPattern")) {
            assertEquals("no $wide", "", data.getAttributeNS(android, wide))
        }
    }

    @Test
    fun torsServiceIsBoundOnlyNeverExportedOrInTheForeground() {
        val services = elements(merged, "service")
        val tor = services.single { it.getAttributeNS(android, "name") == "org.torproject.jni.TorService" }
        assertFalse("exported", tor.getAttributeNS(android, "exported") == "true")
        assertEquals("no intent filter: nothing outside Foxy can reach it", 0, tor.getElementsByTagName("intent-filter").length)
        assertEquals("not a foreground service", "", tor.getAttributeNS(android, "foregroundServiceType"))
        for (s in services) {
            assertFalse("${s.getAttributeNS(android, "name")} is exported", s.getAttributeNS(android, "exported") == "true")
        }
        assertEquals("no receiver, provider or job of the app's own", 0,
            elements(merged, "receiver").count { it.getAttributeNS(android, "name").startsWith("io.islandbitcoin") })
    }

    @Test
    fun safeBrowsingAndMetricsAreOffAndTheNetworkPolicyIsSet() {
        val meta = elements(merged, "meta-data").associate { it.getAttributeNS(android, "name") to it.getAttributeNS(android, "value") }
        assertEquals("false", meta["android.webkit.WebView.EnableSafeBrowsing"])
        assertEquals("true", meta["android.webkit.WebView.MetricsOptOut"])
        val app = elements(merged, "application").single()
        assertEquals("@xml/network_security_config", app.getAttributeNS(android, "networkSecurityConfig"))
        val config = parse(File(System.getProperty("foxy.res") ?: "src/main/res", "xml/network_security_config.xml"))
        val base = elements(config, "base-config").single()
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))
        assertEquals(listOf("system"), elements(base, "certificates").map { it.getAttribute("src") })
        val onion = elements(config, "domain-config").single()
        assertEquals("true", onion.getAttribute("cleartextTrafficPermitted"))
        assertEquals("onion", elements(onion, "domain").single().textContent.trim())
    }

    @Test
    fun everyActivityIsPortraitAndOnlyThePagesIsReachable() {
        val all = elements(merged, "activity").filter { it.getAttributeNS(android, "name").startsWith("io.islandbitcoin.foxy") }
        // the card's door draws nothing and has no orientation of its own (theCardsDoorDrawsNothingAndKeepsNoPlace)
        assertEquals(1, all.count { it.getAttributeNS(android, "name") == door })
        val activities = all.filter { it.getAttributeNS(android, "name") != door }
        assertEquals(
            setOf(
                "io.islandbitcoin.foxy.MainActivity",
                "io.islandbitcoin.foxy.scanner.ScannerActivity",
                "io.islandbitcoin.foxy.seed.ShowWordsActivity",
                "io.islandbitcoin.foxy.seed.EnterWordsActivity",
            ),
            activities.map { it.getAttributeNS(android, "name") }.toSet(),
        )
        for (a in activities) assertEquals(a.getAttributeNS(android, "name"), "portrait", a.getAttributeNS(android, "screenOrientation"))
        val page = activities.single { it.getAttributeNS(android, "name") == "io.islandbitcoin.foxy.MainActivity" }
        assertEquals("adjustResize", page.getAttributeNS(android, "windowSoftInputMode"))
        for (a in activities - page) {
            assertEquals("${a.getAttributeNS(android, "name")} is Foxy's own, never another app's to open", "false", a.getAttributeNS(android, "exported"))
            assertEquals(0, a.getElementsByTagName("intent-filter").length)
        }
    }

    private fun schemes(root: Element): List<String> = elements(root, "data").map { it.getAttributeNS(android, "scheme") }.filter { it.isNotEmpty() }

    @Test
    fun theReleaseBuildRegistersNoSchemeButTheCardsAddress() {
        assertEquals("a release Foxy has no deep link to be reached through: https for the card's address, twice (ENG-668), and nothing else",
            listOf("https", "https"), schemes(release))
        val page = elements(release, "activity").single { it.getAttributeNS(android, "name") == "io.islandbitcoin.foxy.MainActivity" }
        assertEquals("the page's activity: the launcher and nothing else", listOf("android.intent.action.MAIN"),
            elements(page, "action").map { it.getAttributeNS(android, "name") })
        val cardDoor = elements(release, "activity").single { it.getAttributeNS(android, "name") == door }
        assertEquals("the card's address, from the tag and as a link, at its door",
            listOf("android.nfc.action.NDEF_DISCOVERED", "android.intent.action.VIEW"),
            elements(cardDoor, "action").map { it.getAttributeNS(android, "name") })
        assertEquals("a tap handed to the page on top of its task reaches that page (onNewIntent), not a second one", "singleTop", page.getAttributeNS(android, "launchMode"))
    }

    @Test
    fun theDebugBuildRegistersFoxyOnlyBesideTheCardsAddress() {
        assertEquals(listOf("foxy", "https", "https"), schemes(merged).sorted())
        val page = elements(merged, "activity").single { it.getAttributeNS(android, "name") == "io.islandbitcoin.foxy.MainActivity" }
        assertEquals("a link reaches the page that is up, not a second one", "singleTop", page.getAttributeNS(android, "launchMode"))
        val foxy = filters(merged).single { f -> dataOf(f).any { it.getAttributeNS(android, "scheme") == "foxy" } }
        assertEquals("foxy:// is a link and nothing else", listOf("android.intent.action.VIEW"), actionsOf(foxy))
    }

    @Test
    fun theLauncherIconIsTheAdaptiveFoxAndThePageStartsOnTheSplash() {
        // ENG-665: the iOS fox (ArtTest), as the launcher's icon and its round one
        val app = elements(merged, "application").single()
        assertEquals("@mipmap/ic_launcher", app.getAttributeNS(android, "icon"))
        assertEquals("@mipmap/ic_launcher", app.getAttributeNS(android, "roundIcon"))
        val page = elements(merged, "activity").single { it.getAttributeNS(android, "name") == "io.islandbitcoin.foxy.MainActivity" }
        assertEquals("@style/Theme.Foxy.Starting", page.getAttributeNS(android, "theme"))
    }
}
