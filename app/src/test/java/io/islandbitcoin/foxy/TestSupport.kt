package io.islandbitcoin.foxy

import io.islandbitcoin.foxy.bridge.Json
import io.islandbitcoin.foxy.bridge.PageHost
import io.islandbitcoin.foxy.bridge.PlatformRules
import io.islandbitcoin.foxy.bridge.Prompt
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

/**
 * A page host that runs everything at once and keeps what was evaluated, and
 * what each P3 door was asked: the tests set how a door answers and read what
 * it was given.
 */
class FakeHost : PageHost {
    val evaluated = CopyOnWriteArrayList<String>()
    val haptics = CopyOnWriteArrayList<String>()
    var coveredCount = 0
    var awake: Boolean? = null

    // P3 doors: what they answer
    var page: Pair<Double, Double>? = 390.0 to 844.0
    var scanAnswer: Pair<String?, String?> = "cashuBscanned" to null
    var previewShows = true
    var pasteShows = true
    var clipboard = ""
    var shareRefusal: String? = null
    var biometricAnswer = "yes"
    var settingsRefusal: String? = null
    var urlRefusal: String? = null
    var isCaptured = false
    var promptAnswer: Boolean? = true
    var ownerApproves = true
    var alertUp = false
    /** null: the screen is not shown (nothing to present it from). */
    var showWordsAnswer: Triple<Boolean, Boolean, Boolean>? = Triple(true, false, true)
    /** The screen answers this phrase (null: closed); [enterWordsShown] false: not shown at all. */
    var enterWordsAnswer: String? = null
    var enterWordsShown = true
    /** Hold the seed screens' answers until [answerScreens] (a screen left up). */
    var holdScreens = false

    /** Tap to pay's Bluetooth (ENG-670): null is a phone with none Foxy can use. */
    var tapBluetooth: io.islandbitcoin.foxy.tap.TapBluetooth? = null

    /** Each time the accelerometer was asked for (true) or let go, and the shake it hands over while on. */
    val shakeSenses = CopyOnWriteArrayList<Boolean>()
    var shake: (() -> Unit)? = null

    // P3 doors: what they were asked
    val scans = CopyOnWriteArrayList<String>()
    val previews = CopyOnWriteArrayList<PlatformRules.Rect>()
    var liveCode: ((String) -> Unit)? = null
    var previewStops = 0
    val pastes = CopyOnWriteArrayList<PlatformRules.Rect>()
    var pasteTap: ((String) -> Unit)? = null
    var pasteStops = 0
    var clipboardReads = 0
    val copies = CopyOnWriteArrayList<Pair<String, Boolean>>()
    val shares = CopyOnWriteArrayList<String>()
    val notifications = CopyOnWriteArrayList<Pair<String, String>>()
    val biometrics = CopyOnWriteArrayList<Pair<String, Boolean>>()
    val urls = CopyOnWriteArrayList<String>()
    var settingsOpened = 0
    val prompts = CopyOnWriteArrayList<Prompt>()
    val ownerReasons = CopyOnWriteArrayList<String>()
    val shownWords = CopyOnWriteArrayList<Pair<List<String>, Boolean>>()
    var enterScreens = 0
    var screensClosed = 0
    val later = CopyOnWriteArrayList<Pair<Long, Runnable>>()
    /** A screen held up: how it answers when finished, and when closed from outside. */
    private class Held(val finish: () -> Unit, val close: () -> Unit)
    private val heldScreens = CopyOnWriteArrayList<Held>()

    /** Lets every seed screen held by [holdScreens] answer. */
    fun answerScreens() {
        val held = heldScreens.toList()
        heldScreens.clear()
        held.forEach { it.finish() }
    }

    /** Posts wait on [posted] until [runPosts], as on a main looper behind other work; off, they run at once. */
    var holdPosts = false
    val posted = java.util.ArrayDeque<Runnable>()

    /** Runs what is queued, and what that queues, in order. */
    fun runPosts() {
        while (true) posted.pollFirst()?.run() ?: return
    }

    override fun post(action: Runnable) {
        if (holdPosts) posted.addLast(action) else action.run()
    }
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

    override fun later(ms: Long, action: Runnable) {
        later.add(ms to action)
    }

    override fun pageSize() = page

    override fun scan(prompt: String, done: (String?, String?) -> Unit) {
        scans.add(prompt)
        done(scanAnswer.first, scanAnswer.second)
    }

    override fun previewShow(rect: PlatformRules.Rect, onCode: (String) -> Unit): Boolean {
        if (!previewShows) return false
        previews.add(rect)
        liveCode = onCode
        return true
    }

    override fun previewStop() {
        previewStops++
        liveCode = null
    }

    override fun pasteShow(rect: PlatformRules.Rect, onPaste: (String) -> Unit): Boolean {
        if (!pasteShows) return false
        pastes.add(rect)
        pasteTap = { text ->
            clipboardReads++
            onPaste(text)
        }
        return true
    }

    override fun pasteStop() {
        pasteStops++
        pasteTap = null
    }

    override fun clipboardText(): String {
        clipboardReads++
        return clipboard
    }

    override fun copy(text: String, sensitive: Boolean) {
        copies.add(text to sensitive)
    }

    override fun share(text: String): String? {
        if (shareRefusal == null) shares.add(text)
        return shareRefusal
    }

    override fun notify(title: String, text: String) {
        notifications.add(title to text)
    }

    override fun biometric(reason: String, passcode: Boolean, done: (String) -> Unit) {
        biometrics.add(reason to passcode)
        done(biometricAnswer)
    }

    override fun openSettings(): String? {
        if (settingsRefusal == null) settingsOpened++
        return settingsRefusal
    }

    override fun openUrl(url: String): String? {
        if (urlRefusal == null) urls.add(url)
        return urlRefusal
    }

    override fun captured() = isCaptured

    override fun ask(prompt: Prompt, done: (Boolean) -> Unit): () -> Unit {
        prompts.add(prompt)
        val answer = promptAnswer
        if (answer != null) done(answer)
        return { if (answer == null) done(false) }
    }

    override fun alertShowing() = alertUp

    /** ENG-667: the card link each `cardBegin` is handed; null is a phone that cannot read a card. */
    var cardLinks: () -> io.islandbitcoin.foxy.flashcard.CardLink? = { null }
    var cardLinksMade = 0

    override fun cardLink(): io.islandbitcoin.foxy.flashcard.CardLink? {
        cardLinksMade++
        return cardLinks()
    }

    override fun deviceOwnerApproves(reason: String): Boolean {
        ownerReasons.add(reason)
        return ownerApproves
    }

    override fun showWords(words: List<String>, verify: Boolean, done: (Boolean, Boolean, Boolean) -> Unit): Boolean {
        val answer = showWordsAnswer ?: return false
        shownWords.add(words to verify)
        if (holdScreens) heldScreens.add(Held({ done(answer.first, answer.second, answer.third) }, { done(false, false, false) })) else done(answer.first, answer.second, answer.third)
        return true
    }

    override fun enterWords(wordlist: List<String>, done: (String?) -> Unit): Boolean {
        if (!enterWordsShown) return false
        enterScreens++
        val phrase = enterWordsAnswer
        if (holdScreens) heldScreens.add(Held({ done(phrase) }, { done(null) })) else done(phrase)
        return true
    }

    override fun bluetooth() = tapBluetooth

    override fun shakeSense(on: Boolean, onShake: () -> Unit) {
        shakeSenses.add(on)
        shake = if (on) onShake else null
    }

    override fun closeSeedScreen() {
        screensClosed++
        val held = heldScreens.toList()
        heldScreens.clear()
        held.forEach { it.close() }
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
