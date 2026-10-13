import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.UnrecoverableKeyException

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

/* The upload key (ENG-680): read from outside the repository at build time, never
 * from a file in it. FOXY_UPLOAD_STORE_FILE (the keystore's path) and
 * FOXY_UPLOAD_KEY_ALIAS come from the environment; the passwords from
 * FOXY_UPLOAD_STORE_PASSWORD and FOXY_UPLOAD_KEY_PASSWORD or, for either that is
 * unset, the macOS Keychain's generic password `foxy-android-upload`
 * (`security find-generic-password -s foxy-android-upload -w`), and only when a
 * release is signed (signsARelease) or :app:verifyReleaseSigning runs. Without the path and the
 * alias the release builds unsigned, with a warning, and verifyReleaseSigning
 * fails. FOXY_SECURITY_TOOL replaces /usr/bin/security (tools/release-signing.test.sh
 * points it at a stub, so the test never reads the Keychain). */
class UploadKey(val storeFile: String, val alias: String)

val uploadKey: UploadKey? = run {
    val store = providers.environmentVariable("FOXY_UPLOAD_STORE_FILE").orNull?.takeIf { it.isNotBlank() }
    val alias = providers.environmentVariable("FOXY_UPLOAD_KEY_ALIAS").orNull?.takeIf { it.isNotBlank() }
    if (store != null && alias != null) UploadKey(store, alias) else null
}
val uploadKeyMissing = "the release is not signed: set FOXY_UPLOAD_STORE_FILE (the upload keystore's path, outside " +
    "this repository) and FOXY_UPLOAD_KEY_ALIAS, and the passwords in FOXY_UPLOAD_STORE_PASSWORD and " +
    "FOXY_UPLOAD_KEY_PASSWORD or the macOS Keychain item foxy-android-upload (docs/RELEASE.md)"

/* Whether this build was asked for a signed release: AGP reads a signing config's passwords
 * when the build is configured, so they are read only for a build that names a task that
 * packages, signs or installs the release (or assemble/bundle/build, which include it).
 * Any other build, a debug one or the unit tests, never reads a password or the Keychain. */
val signsARelease: Boolean = gradle.startParameter.taskNames.any {
    Regex("(assemble|bundle|package|sign|install)Release.*|assemble|bundle|build").matches(it.substringAfterLast(':'))
}

/** The Keychain's password for the upload key, read once and only when asked for; null when there is none. */
val keychainPassword: String? by lazy {
    val tool = providers.environmentVariable("FOXY_SECURITY_TOOL").orNull?.takeIf { it.isNotBlank() } ?: "/usr/bin/security"
    if (!File(tool).canExecute()) return@lazy null
    val found = providers.exec {
        commandLine(tool, "find-generic-password", "-s", "foxy-android-upload", "-w")
        isIgnoreExitValue = true
    }
    if (found.result.get().exitValue != 0) null
    else found.standardOutput.asText.get().removeSuffix("\n").takeIf { it.isNotEmpty() }
}

/** A password of the upload key's: its environment variable, else the Keychain; a clear failure when neither has one. */
fun uploadPassword(variable: String): String =
    providers.environmentVariable(variable).orNull?.takeIf { it.isNotEmpty() }
        ?: keychainPassword
        ?: throw GradleException("$variable is not set and the macOS Keychain has no foxy-android-upload password: $uploadKeyMissing")

android {
    namespace = "io.islandbitcoin.foxy"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        // The Play app is bound to io.islandbitcoin.getfoxy (ENG-680, docs/RELEASE.md); the
        // Kotlin namespace and source packages stay io.islandbitcoin.foxy, as iOS's bundle id does.
        applicationId = "io.islandbitcoin.getfoxy"
        minSdk = 28
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PAGE_HASH", "\"$pageHash\"")
        buildConfigField("String", "TOR_SEED_PLAIN_SHA256", "\"$torSeedPlainSha256\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        // The upload key (ENG-680, docs/RELEASE.md): its path and alias from the environment,
        // only when both are given; its passwords only for a build that signs a release
        // (signsARelease), so no other build reads a secret.
        if (uploadKey != null) {
            create("upload") {
                storeFile = file(uploadKey.storeFile)
                keyAlias = uploadKey.alias
                if (signsARelease) {
                    storePassword = uploadPassword("FOXY_UPLOAD_STORE_PASSWORD")
                    keyPassword = uploadPassword("FOXY_UPLOAD_KEY_PASSWORD")
                }
            }
        }
    }

    buildTypes {
        // The Play internal-testing build (ENG-680, docs/RELEASE.md). Signed with the
        // upload key when FOXY_UPLOAD_STORE_FILE and FOXY_UPLOAD_KEY_ALIAS are set
        // (Play App Signing re-signs it for the store), unsigned otherwise, so CI
        // keeps compiling it. minSdk 30 is set per variant (androidComponents below).
        release {
            // R8 is off for 0.1.0: the page's bridge is reached from the WebView by
            // name, secp256k1-kmp and Tink load JNI and classes by reflection, and no
            // minified build has been through the device suite. Foxy's own release
            // classes call no android.util.Log (ReleaseBuildTest); a dependency's own
            // logcat lines are not stripped until R8 is on (docs/RELEASE.md "R8").
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload")
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
            // ReleaseBuildTest: the version the builds must carry, from defaultConfig above, so a bump needs no test edit
            it.systemProperty("foxy.versionCode", defaultConfig.versionCode.toString())
            it.systemProperty("foxy.versionName", defaultConfig.versionName.toString())
            it.systemProperty("foxy.applicationId", defaultConfig.applicationId.toString())
            // ReleaseBuildTest: the release build's own classes, and the source sets that say what is debug only
            it.systemProperty("foxy.releaseClasses", layout.buildDirectory.dir("tmp/kotlin-classes/release").get().asFile.absolutePath)
            it.systemProperty("foxy.releaseJavaClasses",
                layout.buildDirectory.dir("intermediates/javac/release/compileReleaseJavaWithJavac/classes").get().asFile.absolutePath)
            it.systemProperty("foxy.mainSrc", file("src/main/java").absolutePath)
            it.systemProperty("foxy.debugSrc", file("src/debug/java").absolutePath)
            it.systemProperty("foxy.releaseSrc", file("src/release/java").absolutePath)
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

androidComponents {
    // Release runs on Android 11 and later until ENG-654 proves the API 28-29 screen-lock
    // confirmation on a phone; debug builds keep 28 for that work (ReleaseBuildTest reads both).
    beforeVariants(selector().withBuildType("release")) { it.minSdk = 30 }
}

/* Fails unless the upload key is there and opens: the keystore at FOXY_UPLOAD_STORE_FILE,
 * its store password, the alias's private key and its key password. Prints the alias and
 * the certificate's SHA-256 (public: Play Console shows it as the upload certificate), never
 * a password. */
tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Fails clearly unless the release upload key is configured and opens (docs/RELEASE.md)."
    doLast {
        val key = uploadKey ?: throw GradleException(uploadKeyMissing)
        val store = file(key.storeFile)
        if (!store.isFile) throw GradleException("FOXY_UPLOAD_STORE_FILE names no file: ${store.path}")
        val keystore = try {
            KeyStore.getInstance(store, uploadPassword("FOXY_UPLOAD_STORE_PASSWORD").toCharArray())
        } catch (e: IOException) {
            throw GradleException("the upload keystore does not open with its store password (${e.javaClass.simpleName})")
        }
        if (!keystore.isKeyEntry(key.alias)) throw GradleException("the upload keystore has no key under the alias ${key.alias}")
        try {
            keystore.getKey(key.alias, uploadPassword("FOXY_UPLOAD_KEY_PASSWORD").toCharArray())
                ?: throw GradleException("the upload keystore has no private key under the alias ${key.alias}")
        } catch (e: UnrecoverableKeyException) {
            throw GradleException("the upload key does not open with its key password")
        }
        val cert = keystore.getCertificate(key.alias)
        val sha256 = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }
        println("upload key: alias ${key.alias}, certificate SHA-256 $sha256")
    }
}

// A release built without the upload key says so where it is built.
tasks.matching { it.name == "bundleRelease" || it.name == "assembleRelease" }.configureEach {
    doLast { if (uploadKey == null) logger.warn("WARNING: UNSIGNED $name: $uploadKeyMissing") }
}

tasks.named("preBuild") { dependsOn(generateContractStubs, "unpackTorSeed") }
afterEvaluate {
    // ManifestTest reads the merged manifests, so the debug and release manifests are merged before the debug
    // unit tests: the release one must register no deep-link scheme (BRIDGE.md `deepLink`).
    // ReleaseBuildTest reads the release build's own classes, so they are compiled first too.
    tasks.named("testDebugUnitTest") {
        dependsOn("processDebugMainManifest", "processReleaseMainManifest", "compileReleaseKotlin", "compileReleaseJavaWithJavac")
    }
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
    // tap to pay's X25519 (tap/TapCrypto.kt): the platform has XDH only from API 33, minSdk is 28
    implementation(libs.tink.android)

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
