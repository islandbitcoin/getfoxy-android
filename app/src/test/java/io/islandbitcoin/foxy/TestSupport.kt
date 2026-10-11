package io.islandbitcoin.foxy

import io.islandbitcoin.foxy.bridge.Json
import io.islandbitcoin.foxy.bridge.PageHost
import io.islandbitcoin.foxy.keychain.BIP39
import io.islandbitcoin.foxy.keychain.SeedKeys
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Paths, fixtures and fakes the JVM tests share. */
object TestSupport {
    /** conformance/contract.json, handed to the tests by Gradle (app/build.gradle.kts). */
    val contract: File get() = File(System.getProperty("foxy.contract") ?: "../conformance/contract.json")
    val core: File get() = File(System.getProperty("foxy.core") ?: "../core")

    val wordlist: List<String> by lazy {
        BIP39.wordlist(File("src/main/assets/bip39-english.txt").readBytes()) ?: error("the wordlist is not the pinned list")
    }

    fun fixture(name: String): Any? {
        val stream = TestSupport::class.java.getResourceAsStream("/fixtures/$name") ?: error("no fixture $name")
        return Json.parse(stream.readBytes().toString(Charsets.UTF_8))
    }

    @Suppress("UNCHECKED_CAST")
    fun contractEntries(): List<Map<String, Any?>> = Json.parse(contract.readText()) as List<Map<String, Any?>>

    fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    /**
     * Makes a folder unwritable, for the tests of a write that fails. Root writes
     * anyway (a container's build), and then the test cannot be made: it is
     * skipped with that reason rather than passing on nothing.
     */
    fun makeUnwritable(dir: File) {
        org.junit.Assert.assertTrue(dir.setWritable(false))
        val probe = File(dir, ".probe")
        val writable = try {
            probe.createNewFile().also { probe.delete() }
        } catch (e: java.io.IOException) {
            false
        }
        if (writable) dir.setWritable(true)
        org.junit.Assume.assumeFalse("this user writes into read-only folders (root?)", writable)
    }

    /** A message body as the page sends it: through JSON, so numbers are Doubles and booleans Booleans. */
    @Suppress("UNCHECKED_CAST")
    fun body(json: String): Map<String, Any?> = Json.parse(json) as Map<String, Any?>

    /** A body from Kotlin values, through JSON text as the bridge receives it. */
    fun body(vararg pairs: Pair<String, Any?>): Map<String, Any?> = body(write(mapOf(*pairs)))

    /** Like [Json.write], but numbers of every kind and nested values as given (for building messages). */
    fun write(value: Any?): String = when (value) {
        null -> "null"
        is String -> Json.quote(value)
        is Boolean -> value.toString()
        is Double -> if (value.isNaN()) "null" else if (value == Math.floor(value) && !value.isInfinite() && Math.abs(value) < 1e15) value.toLong().toString() else value.toString()
        is Number -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { Json.quote(it.key as String) + ":" + write(it.value) }
        is List<*> -> value.joinToString(",", "[", "]") { write(it) }
        else -> error("not a value")
    }

    const val SAVED = "legal winner thank year wave sausage worth useful legal winner thank yellow"
    const val MADE = "letter advice cage absurd amount doctor acoustic avoid letter advice cage above"
    const val TYPED_OTHER = "ozone drill grab fiber curtain grace pudding thank cruise elder eight picnic"
    const val ABANDON_ABOUT = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    const val HALF = "half depart obvious quality work element tank gorilla view sugar picture humble"
    const val V00 = "009a1f293253e41e"
    val V01 = "01" + "ab".repeat(32)

    /** Four `00` ids NUT-13 derives with the same index, 864559728 (review H1). */
    val ALIASES = listOf("009a1f293253e41e", "0000000033882270", "009a1f29b253e41d", "009a1f28b253e41f")
}

/** A page host that runs everything at once and keeps what was evaluated. */
class FakeHost : PageHost {
    val evaluated = CopyOnWriteArrayList<String>()
    val haptics = CopyOnWriteArrayList<String>()
    var coveredCount = 0
    var awake: Boolean? = null

    override fun post(action: Runnable) = action.run()
    override fun evaluate(js: String) {
        evaluated.add(js)
    }
    override fun covered() {
        coveredCount++
    }
    override fun keepAwake(on: Boolean) {
        awake = on
    }
    override fun haptic(kind: String) {
        haptics.add(kind)
    }
}

/** The Keystore as SeedVault's rules see it, in memory, with every failure it can have. */
class FakeKeys : SeedKeys {
    var key: SecretKey? = null
    var failExists = false
    var failGet = false
    var failCreate = false
    var created = 0

    override fun exists(): Boolean {
        if (failExists) throw java.security.KeyStoreException("the keystore did not answer")
        return key != null
    }

    override fun get(): SecretKey? {
        if (failGet) throw java.security.UnrecoverableKeyException("the key could not be read")
        return key
    }

    override fun create(): SecretKey {
        if (failCreate) throw java.security.ProviderException("the keystore would not make a key")
        val k = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        key = k
        created++
        return k
    }

    override fun delete() {
        key = null
    }
}
