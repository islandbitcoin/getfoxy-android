package io.islandbitcoin.foxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The Play build (ENG-680, docs/RELEASE.md): read from what the release build itself
 * compiled and merged, not from the sources, that no debug-only path ships in it.
 *
 * The debug-only paths, each with where it lives:
 * - src/debug: SimCardLink (the card simulator's loopback socket), DebugConsole's logcat,
 *   the foxy:// deep-link filter (src/debug/AndroidManifest.xml);
 * - BuildConfig.DEBUG gates in main: WebView debugging (`setWebContentsDebuggingEnabled`),
 *   the foxy:// link (MainActivity.receiveDeepLink, WebHost.deepLink), FOXY_DEBUG and the
 *   FOXY_SHOW_WARNING extra (WebHost.create), the FoxyNoTor and FoxyForceTransport extras
 *   (MainActivity.onCreate), the FoxyCardSim extra (WebHost, through CardSim.ENABLED),
 *   the seed thread's self-check (FoxyServices), the bridge's `isDebug` answer;
 * - the tests' hooks in main (`internal var x: ((…) -> Unit)? = null`): set by tests only.
 *
 * The release classes are read with the JDK's own javap (the JVM running the tests).
 * Gradle compiles them, and merges both manifests, before the debug unit tests.
 */
class ReleaseBuildTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private fun dir(property: String): File {
        val f = File(System.getProperty(property) ?: error("the $property path is not set"))
        assertTrue("$property is built before the unit tests ($f)", f.exists())
        return f
    }

    // the version app/build.gradle.kts sets (defaultConfig), handed over by the test task
    private val versionCode by lazy { System.getProperty("foxy.versionCode")?.takeIf { it != "null" } ?: error("foxy.versionCode is not set") }
    private val versionName by lazy { System.getProperty("foxy.versionName")?.takeIf { it != "null" } ?: error("foxy.versionName is not set") }

    private val kotlinClasses by lazy { dir("foxy.releaseClasses") }
    private val javaClasses by lazy { dir("foxy.releaseJavaClasses") }

    private fun manifest(property: String): Element = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }.newDocumentBuilder().parse(dir(property)).documentElement

    private fun usesSdk(m: Element): Element = m.getElementsByTagName("uses-sdk").item(0) as Element

    /** One class's javap -c -p, split by member: the member's header line to its code's lines. */
    class Disassembly(val className: String, val members: Map<String, List<String>>)

    private fun javap(vararg args: String): String {
        val tool = File(System.getProperty("java.home"), "bin/javap")
        assertTrue("javap is in the JDK running the tests ($tool)", tool.canExecute())
        val out = File.createTempFile("javap", ".txt").apply { deleteOnExit() }
        val p = ProcessBuilder(listOf(tool.path) + args).redirectErrorStream(true).redirectOutput(out).start()
        assertTrue("javap finished", p.waitFor(5, TimeUnit.MINUTES))
        val text = out.readText()
        out.delete()
        assertEquals("javap: $text".take(2000), 0, p.exitValue())
        return text
    }

    private val release: List<Disassembly> by lazy {
        val files = kotlinClasses.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.map { it.path }.toList() +
            javaClasses.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.map { it.path }.toList()
        assertTrue("the release build has Foxy's classes (${files.size})", files.size > 100)
        val out = mutableListOf<Disassembly>()
        files.chunked(200).forEach { chunk -> out += parse(javap("-c", "-p", *chunk.toTypedArray())) }
        out
    }

    private fun parse(text: String): List<Disassembly> {
        val classes = mutableListOf<Disassembly>()
        var name: String? = null
        var members = linkedMapOf<String, MutableList<String>>()
        var member: MutableList<String>? = null
        val declaration = Regex("""^(?:[a-z]+ )*(?:class|interface|enum) ([\w.$]+)""")
        for (line in text.lines()) {
            val d = declaration.find(line)
            when {
                d != null && !line.startsWith(" ") -> {
                    name?.let { classes += Disassembly(it, members) }
                    name = d.groupValues[1]
                    members = linkedMapOf()
                    member = null
                }
                line.startsWith("  ") && !line.startsWith("   ") -> member = mutableListOf<String>().also { members[line.trim()] = it }
                line.startsWith("    ") -> member?.add(line.trim())
            }
        }
        name?.let { classes += Disassembly(it, members) }
        return classes
    }

    private fun foxy(): List<Disassembly> = release.filter { it.className.startsWith("io.islandbitcoin.foxy.") }

    @Test
    fun theReleaseCarriesTheBuildFilesVersionWithNoDebugFlag() {
        val text = javap("-constants", "-cp", javaClasses.path, "io.islandbitcoin.foxy.BuildConfig")
        assertTrue(text, text.contains("public static final boolean DEBUG = false;"))
        assertTrue(text, text.contains("public static final java.lang.String BUILD_TYPE = \"release\";"))
        assertTrue(text, text.contains("public static final int VERSION_CODE = $versionCode;"))
        assertTrue(text, text.contains("public static final java.lang.String VERSION_NAME = \"$versionName\";"))
        assertTrue(text, text.contains("public static final java.lang.String APPLICATION_ID = \"io.islandbitcoin.foxy\";"))
    }

    @Test
    fun releaseRunsFromApi30AndDebugFrom28() {
        val release = manifest("foxy.releaseManifest")
        val debug = manifest("foxy.mergedManifest")
        assertEquals("release: Android 11 and later until ENG-654", "30", usesSdk(release).getAttributeNS(android, "minSdkVersion"))
        assertEquals("debug keeps API 28 for the ENG-654 work", "28", usesSdk(debug).getAttributeNS(android, "minSdkVersion"))
        for (m in listOf(release, debug)) {
            assertEquals("36", usesSdk(m).getAttributeNS(android, "targetSdkVersion"))
            assertEquals(versionCode, m.getAttributeNS(android, "versionCode"))
            assertEquals(versionName, m.getAttributeNS(android, "versionName"))
            assertEquals("io.islandbitcoin.foxy", m.getAttribute("package"))
        }
    }

    @Test
    fun theReleaseManifestIsNotDebuggableAndHasNoFoxyLink() {
        val release = manifest("foxy.releaseManifest")
        val app = release.getElementsByTagName("application").item(0) as Element
        assertFalse("a release is not debuggable", app.getAttributeNS(android, "debuggable") == "true")
        val data = release.getElementsByTagName("data")
        for (i in 0 until data.length) {
            assertFalse("no foxy:// link in a release", (data.item(i) as Element).getAttributeNS(android, "scheme").equals("foxy", true))
        }
        // and the debug one does have it, so the check above reads the right attribute
        val debug = manifest("foxy.mergedManifest").getElementsByTagName("data")
        assertTrue((0 until debug.length).any { (debug.item(it) as Element).getAttributeNS(android, "scheme") == "foxy" })
    }

    @Test
    fun noClassOfTheDebugSourceSetIsInTheRelease() {
        val debugSrc = dir("foxy.debugSrc")
        val releaseSrc = dir("foxy.releaseSrc")
        val debugOnly = debugSrc.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(debugSrc).path.removeSuffix(".kt") }
            .filter { !releaseSrc.resolve("$it.kt").exists() }.toList()
        assertTrue("SimCardLink is debug only", debugOnly.any { it.endsWith("flashcard/SimCardLink") })
        val names = release.map { it.className.replace('.', '/') }
        for (path in debugOnly) {
            assertFalse("$path is not in a release", names.any { it == path || it.startsWith("$path$") || it.startsWith("${path}Kt") })
        }
        val sim = javap("-constants", "-cp", kotlinClasses.path, "io.islandbitcoin.foxy.flashcard.CardSim", "io.islandbitcoin.foxy.debug.DebugConsole")
        assertEquals("release's CardSim and DebugConsole are off: $sim", 2,
            Regex("""public static final boolean ENABLED = false;""").findAll(sim).count())
    }

    @Test
    fun foxysReleaseCodeNeverCallsLogcat() {
        for (c in foxy()) for ((member, code) in c.members) {
            assertFalse("${c.className}.$member calls android.util.Log", code.any { "android/util/Log." in it })
        }
    }

    @Test
    fun webViewDebuggingIsOffInEveryCall() {
        var calls = 0
        for (c in foxy()) for ((member, code) in c.members) {
            code.forEachIndexed { i, line ->
                if ("android/webkit/WebView.setWebContentsDebuggingEnabled" in line) {
                    calls++
                    assertTrue("${c.className}.$member: setWebContentsDebuggingEnabled(false), constant", code[i - 1].endsWith("iconst_0"))
                }
            }
        }
        assertEquals("one call, in WebHost.configure", 1, calls)
    }

    @Test
    fun noDebugExtraOrPageFlagIsReadInARelease() {
        // the extras' names and the page's debug flags anywhere in a string (the foxy: link's two doors are
        // theDeepLinkDoorsAreEmptyInARelease: "foxy:" itself is also the clipboard filter's, in every build)
        val debugStrings = listOf(
            Regex("""// String (FoxyNoTor|FoxyForceTransport|FoxyCardSim|FOXY_SHOW_WARNING)$"""),
            Regex("""// String .*window\.(FOXY_DEBUG|FOXY_SHOW_WARNING)"""),
        )
        for (c in foxy()) for ((member, code) in c.members) for (line in code) {
            for (s in debugStrings) assertFalse("${c.className}.$member loads a debug string: $line", s.containsMatchIn(line))
        }
    }

    /** A member that does nothing: no call but Kotlin's own parameter check, no field written, then return. */
    private fun assertDoesNothing(where: String, code: List<String>) {
        assertEquals("$where returns", "return", code.last().substringAfter(": "))
        for (line in code) {
            if ("invoke" in line) assertTrue("$where calls nothing: $line", "kotlin/jvm/internal/Intrinsics.checkNotNullParameter" in line)
            assertFalse("$where writes nothing: $line", Regex("""\bput(field|static)\b""").containsMatchIn(line))
        }
    }

    @Test
    fun theDeepLinkDoorsAreEmptyInARelease() {
        val main = foxy().single { it.className == "io.islandbitcoin.foxy.MainActivity" }
        assertDoesNothing("MainActivity.receiveDeepLink", main.members.entries.single { it.key.contains(" receiveDeepLink(android.content.Intent)") }.value)
        val host = foxy().single { it.className == "io.islandbitcoin.foxy.web.WebHost" }
        assertDoesNothing("WebHost.deepLink", host.members.entries.single { it.key.contains(" deepLink(java.lang.String)") }.value)
        val console = foxy().single { it.className == "io.islandbitcoin.foxy.debug.DebugConsole" }
        assertDoesNothing("DebugConsole.install", console.members.entries.single { it.key.contains(" install()") }.value)
    }

    @Test
    fun theTestsHooksAreNeverSetInARelease() {
        val main = dir("foxy.mainSrc")
        val hook = Regex("""internal var (\w+): \(\(?.*\)? -> Unit\)\? = null""")
        val hooks = main.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { f -> hook.findAll(f.readText()).map { it.groupValues[1] } }.toSet()
        assertTrue("the hooks are found: $hooks",
            hooks.containsAll(setOf("droppedScript", "afterRename", "afterWriteBeforeDelete", "afterSeal", "console")))
        for (name in hooks) {
            val setter = "set${name.replaceFirstChar { it.uppercase() }}\$app_release"
            for (c in foxy()) for ((member, code) in c.members) {
                val own = member.contains(" $setter(") || member.contains("<init>") || member.contains("static {}") ||
                    Regex("""^[\w ]*${Regex.escape(c.className)}\(""").containsMatchIn(member)
                for ((i, line) in code.withIndex()) {
                    assertFalse("${c.className}.$member sets the hook $name: $line", line.contains("Method ") && line.contains("$setter:"))
                    if (Regex("""put(field|static) .*// Field (\S+\.)?$name:""").containsMatchIn(line)) {
                        assertTrue("${c.className}.$member writes the hook $name: $line", own)
                        // a constructor only ever writes null into it
                        if (!member.contains(" $setter(")) assertEquals("${c.className}.$member: $name starts null", "aconst_null", code[i - 1].substringAfter(": "))
                    }
                }
            }
        }
    }
}
