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
 * every domain excluded from both, INTERNET and ACCESS_NETWORK_STATE and no
 * other permission, tor-android's service bound and never exported or in the
 * foreground (audit finding 4), Safe Browsing and WebView metrics off, the one
 * activity portrait.
 */
class ManifestTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private fun parse(file: File) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(file).documentElement

    private val merged: Element by lazy {
        val file = File(System.getProperty("foxy.mergedManifest") ?: error("the merged manifest path is not set"))
        assertTrue("the merged manifest is built before the unit tests ($file)", file.exists())
        parse(file)
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
    fun internetAndNetworkStateAndNoOtherPermission() {
        val permissions = elements(merged, "uses-permission").map { it.getAttributeNS(android, "name") }.toSet()
        assertEquals(
            "P2 asks INTERNET (Tor and Route) and ACCESS_NETWORK_STATE (NetworkKind), and nothing else: $permissions",
            setOf(
                "android.permission.INTERNET",
                "android.permission.ACCESS_NETWORK_STATE",
                "io.islandbitcoin.foxy.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            ),
            permissions,
        )
        assertFalse("never a foreground service", permissions.any { it.contains("FOREGROUND_SERVICE") })
        assertTrue("no permission of its own is declared beyond the receiver one", elements(merged, "permission").all {
            it.getAttributeNS(android, "name") == "io.islandbitcoin.foxy.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
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
    fun oneActivityPortrait() {
        val activities = elements(merged, "activity").filter { it.getAttributeNS(android, "name").startsWith("io.islandbitcoin.foxy") }
        assertEquals(1, activities.size)
        assertEquals("portrait", activities[0].getAttributeNS(android, "screenOrientation"))
        assertEquals("adjustResize", activities[0].getAttributeNS(android, "windowSoftInputMode"))
    }
}
