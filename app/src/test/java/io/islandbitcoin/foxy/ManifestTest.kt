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
 * and only the page's reachable from outside; and the release manifest
 * registers no deep-link scheme at all (BRIDGE.md `deepLink`).
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
    fun thePermissionsP3NeedsAndNoOther() {
        val permissions = elements(merged, "uses-permission").map { it.getAttributeNS(android, "name") }.toSet()
        assertEquals(
            "INTERNET (Tor and Route), ACCESS_NETWORK_STATE (NetworkKind), and P3's CAMERA (the scanner), " +
                "POST_NOTIFICATIONS (payments), USE_BIOMETRIC (the PIN lock, the seed's device protection) and " +
                "DETECT_SCREEN_CAPTURE (_screenshot), ENG-670's BLUETOOTH_ADVERTISE and BLUETOOTH_CONNECT (tap to pay's " +
                "receiver; BLUETOOTH and BLUETOOTH_ADMIN before API 31), and nothing else: $permissions",
            setOf(
                "android.permission.INTERNET",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.CAMERA",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.USE_BIOMETRIC",
                "android.permission.DETECT_SCREEN_CAPTURE",
                "android.permission.BLUETOOTH_ADVERTISE",
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.BLUETOOTH",
                "android.permission.BLUETOOTH_ADMIN",
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
    }

    /**
     * ENG-670: the receiver advertises and serves and never scans, so no
     * BLUETOOTH_SCAN and no location, on any API; the two runtime permissions
     * from API 31 with no ceiling, the install-time pair only up to API 30, and
     * Bluetooth LE never required (a phone without it answers "unsupported").
     */
    @Test
    fun bluetoothIsTheReceiversTwoAndTheLegacyPairOnlyBefore31() {
        val uses = elements(merged, "uses-permission").associateBy { it.getAttributeNS(android, "name") }
        for (name in listOf("android.permission.BLUETOOTH_ADVERTISE", "android.permission.BLUETOOTH_CONNECT")) {
            assertEquals("$name holds on every API from 31", "", uses.getValue(name).getAttributeNS(android, "maxSdkVersion"))
        }
        for (name in listOf("android.permission.BLUETOOTH", "android.permission.BLUETOOTH_ADMIN")) {
            assertEquals("$name only where it is the permission (API 28-30)", "30", uses.getValue(name).getAttributeNS(android, "maxSdkVersion"))
        }
        val never = listOf(
            "android.permission.BLUETOOTH_SCAN", "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION", "android.permission.BLUETOOTH_PRIVILEGED",
        )
        for (name in never) assertFalse("$name is never asked: the receiver does not scan", name in uses)
        assertEquals("nothing is declared for a Bluetooth permission's sake but these", 0,
            elements(merged, "uses-permission-sdk-23").size)
        val le = elements(merged, "uses-feature").single { it.getAttributeNS(android, "name") == "android.hardware.bluetooth_le" }
        assertEquals("Bluetooth LE is used where there is one, never required", "false", le.getAttributeNS(android, "required"))
        assertFalse("nor plain Bluetooth required", elements(merged, "uses-feature").any {
            it.getAttributeNS(android, "name") == "android.hardware.bluetooth" && it.getAttributeNS(android, "required") != "false"
        })
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
        val activities = elements(merged, "activity").filter { it.getAttributeNS(android, "name").startsWith("io.islandbitcoin.foxy") }
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
    fun theReleaseBuildRegistersNoScheme() {
        assertEquals("a release Foxy has no deep link to be reached through", emptyList<String>(), schemes(release))
        val page = elements(release, "activity").single { it.getAttributeNS(android, "name") == "io.islandbitcoin.foxy.MainActivity" }
        assertEquals("the launcher only", listOf("android.intent.action.MAIN"),
            elements(page, "action").map { it.getAttributeNS(android, "name") })
    }

    @Test
    fun theDebugBuildRegistersFoxyOnly() {
        assertEquals(listOf("foxy"), schemes(merged))
        val page = elements(merged, "activity").single { it.getAttributeNS(android, "name") == "io.islandbitcoin.foxy.MainActivity" }
        assertEquals("a link reaches the page that is up, not a second one", "singleTop", page.getAttributeNS(android, "launchMode"))
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
