import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/* The page that ships is core/web, byte for byte (tools/verify-core.sh). Its
 * hash is sha256(core/SHA256SUMS), and core/PIN records the same value as
 * page-hash: the build refuses to go on if the two disagree, and hands the
 * value to the app as BuildConfig.PAGE_HASH (window.__foxyPageHash). */
val coreDir = rootProject.file("core")
val pageHash: String = run {
    val pin = coreDir.resolve("PIN").readLines()
        .firstOrNull { it.startsWith("page-hash=") }?.removePrefix("page-hash=")?.trim()
        ?: throw GradleException("core/PIN has no page-hash line")
    val sums = MessageDigest.getInstance("SHA-256").digest(coreDir.resolve("SHA256SUMS").readBytes())
        .joinToString("") { "%02x".format(it) }
    if (pin != sums) throw GradleException("core/PIN page-hash $pin is not sha256(core/SHA256SUMS) $sums")
    if (!Regex("^[0-9a-f]{64}$").matches(pin)) throw GradleException("core/PIN page-hash is not 64 lowercase hex")
    pin
}

/* The relay directory seed (tor/TorSeed.kt): iOS's tor-microdescs.xz, unpacked
 * and checked against app/tor-seed/PIN when the APK is built (gradle/tor-seed.gradle.kts).
 * The app is handed the unpacked file's pin, which it checks again before Tor
 * reads the file. */
apply(from = rootProject.file("gradle/tor-seed.gradle.kts"))
val torSeedPlainSha256: String = file("tor-seed/PIN").readLines()
    .firstOrNull { it.startsWith("plain-sha256=") }?.removePrefix("plain-sha256=")?.trim()
    ?.takeIf { Regex("^[0-9a-f]{64}$").matches(it) }
    ?: throw GradleException("app/tor-seed/PIN has no plain-sha256 of 64 lowercase hex")
val torSeedAssets = layout.buildDirectory.dir("generated/torseed")

/* The later phases' stubs: the answer this host gives to each action of a phase
 * it does not serve yet that the page asks before anything else (docs/BRIDGE.md
 * §2, "P1 answers"; the contract's `p1` answers). The app serves P1 and P2, so
 * only the stubs of P3 and LATER actions are emitted. Generated from
 * conformance/contract.json, never retyped, so the Kotlin table cannot drift
 * from the contract the conformance suite checks. */
val contractFile = rootProject.file("conformance/contract.json")
val generatedStubs = layout.buildDirectory.dir("generated/source/contract/kotlin")

abstract class GenerateContractStubs : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val contract: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        @Suppress("UNCHECKED_CAST")
        val entries = groovy.json.JsonSlurper().parse(contract.get().asFile) as List<Map<String, Any?>>
        fun lit(value: Any?): String {
            if (value == null) return "null"
            val s = value as String
            val out = StringBuilder("\"")
            for (c in s) {
                when (c) {
                    '\\' -> out.append("\\\\")
                    '"' -> out.append("\\\"")
                    '$' -> out.append("\\$")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    else -> if (c.code < 0x20 || c.code > 0x7e) out.append("\\u%04x".format(c.code)) else out.append(c)
                }
            }
            return out.append('"').toString()
        }
        val served = setOf("P1", "P2")
        val stubs = entries.filter { it["kind"] == "action" && it["p1"] != null && it["phase"] !in served }
            .sortedBy { it["name"] as String }
        val text = buildString {
            appendLine("// Generated from conformance/contract.json by :app:generateContractStubs. Do not edit.")
            appendLine("package io.islandbitcoin.foxy.bridge")
            appendLine()
            appendLine("/** The `p1` answers of conformance/contract.json for the phases after P2: what this host says to a later phase's action. */")
            appendLine("internal object ContractStubs {")
            appendLine("    class Stub(val text: String?, val error: String?)")
            appendLine()
            appendLine("    val p1: Map<String, Stub> = linkedMapOf(")
            for (e in stubs) {
                @Suppress("UNCHECKED_CAST")
                val p1 = e["p1"] as Map<String, Any?>
                appendLine("        ${lit(e["name"])} to Stub(${lit(p1["text"])}, ${lit(p1["error"])}),")
            }
            appendLine("    )")
            appendLine("}")
        }
        val dir = outputDir.get().asFile.resolve("io/islandbitcoin/foxy/bridge")
        dir.mkdirs()
        dir.resolve("ContractStubs.kt").writeText(text)
    }
}

val generateContractStubs = tasks.register<GenerateContractStubs>("generateContractStubs") {
    contract.set(contractFile)
    outputDir.set(generatedStubs)
}

android {
    namespace = "io.islandbitcoin.foxy"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "io.islandbitcoin.foxy"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PAGE_HASH", "\"$pageHash\"")
        buildConfigField("String", "TOR_SEED_PLAIN_SHA256", "\"$torSeedPlainSha256\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        // P1 ships debug builds only. Release is configured so its rules (R8
        // stripping android.util.Log, no logcat, console silence) are in the
        // tree, but it has no signing config and CI does not build it.
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    sourceSets {
        getByName("main") {
            // core/web is served from the APK at assets/web/ (docs/ROUTE.md §4),
            // with core/PIN and core/SHA256SUMS beside it, which the loader never serves;
            // and the unpacked relay directory seed at assets/tor/ (gradle/tor-seed.gradle.kts).
            assets.srcDirs("../core", "src/main/assets", torSeedAssets)
            kotlin.srcDir(generatedStubs)
        }
        // the instrumented QR test reads the iOS animated-QR fixture the JVM tests read (fixtures/README.md)
        getByName("androidTest") {
            assets.srcDirs("src/test/resources/fixtures")
            // RealMoney, the real-money rules DeviceExitTest applies, and Always, its clean-up; the JVM tests test them (RealMoneyTest, AlwaysTest)
            kotlin.srcDir("src/sharedTest/java")
        }
        getByName("test") {
            kotlin.srcDir("src/sharedTest/java")
        }
    }

    androidResources {
        // AAPT's default pattern drops some names silently; every pinned file must ship,
        // so only dot-files are left out (core/ has none). AssetsMatchPinTest checks it.
        ignoreAssetsPattern = "!.*"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            it.systemProperty("foxy.contract", contractFile.absolutePath)
            it.systemProperty("foxy.core", coreDir.absolutePath)
            it.systemProperty("foxy.mergedManifest",
                layout.buildDirectory.file("intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml")
                    .get().asFile.absolutePath)
            it.systemProperty("foxy.releaseManifest",
                layout.buildDirectory.file("intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml")
                    .get().asFile.absolutePath)
            it.systemProperty("foxy.res", file("src/main/res").absolutePath)
            it.systemProperty("foxy.torSeed", torSeedAssets.get().asFile.resolve("tor/cached-microdescs").absolutePath)
            // the card simulator's port (tools/cardsim-test.sh): CardSimEndToEndTest drives the real applet there, and is skipped without it
            it.systemProperty("foxy.cardsimPort", providers.gradleProperty("foxy.cardsimPort").getOrElse(""))
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        // The pins are chosen by hand (released three days or more, compatible with
        // compileSdk 36) and listed in the PR; lint asking the network for newer
        // ones would make the check depend on the day it runs.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

tasks.named("preBuild") { dependsOn(generateContractStubs, "unpackTorSeed") }
afterEvaluate {
    // ManifestTest reads the merged manifests, so the debug and release manifests are merged before the debug
    // unit tests: the release one must register no deep-link scheme (BRIDGE.md `deepLink`).
    tasks.named("testDebugUnitTest") { dependsOn("processDebugMainManifest", "processReleaseMainManifest") }
}

dependencies {
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.secp256k1.jni.android)
    // Tor in this process (Guardian Project), its control library, the obfs4 and
    // Snowflake transports, and the one HTTP client (network/, docs/ROUTE.md §5)
    implementation(libs.tor.android)
    implementation(libs.jtorctl)
    implementation(libs.iptproxy)
    implementation(libs.okhttp)
    // the scanner (P3): CameraX and zxing-cpp, the one QR decoder (docs/ANDROID-MAPPING.md)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.zxing.cpp)

    testImplementation(libs.junit)
    testImplementation(libs.secp256k1.jni.jvm)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // draws the QR codes the instrumented decode test hands zxing-cpp; never in the app
    androidTestImplementation(libs.zxing.core)
}
