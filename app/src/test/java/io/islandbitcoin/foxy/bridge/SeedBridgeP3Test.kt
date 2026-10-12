package io.islandbitcoin.foxy.bridge

import io.islandbitcoin.foxy.FakeHost
import io.islandbitcoin.foxy.FakeKeys
import io.islandbitcoin.foxy.TestSupport
import io.islandbitcoin.foxy.keychain.CounterStore
import io.islandbitcoin.foxy.keychain.Flags
import io.islandbitcoin.foxy.keychain.SeedCandidates
import io.islandbitcoin.foxy.keychain.SeedEnvironment
import io.islandbitcoin.foxy.keychain.SeedMigrationWindow
import io.islandbitcoin.foxy.keychain.SeedRepliesTest
import io.islandbitcoin.foxy.keychain.SeedVault
import io.islandbitcoin.foxy.keychain.SeedVaultProtectionTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * The P3 seed actions through the bridge (NativeSeedBridge.swift:425-604): the
 * screens one at a time and the pause after a failed read (L1), a screen closed
 * from outside answered as closed (L4, S20), adopting and wiping behind a
 * native question and then the device owner (S5, M8), one seed change at a
 * time, and the protection's argument check.
 */
class SeedBridgeP3Test {
    private val dir = TestSupport.tempDir("seed-bridge-p3")
    private val keys = FakeKeys()
    private val gate = SeedVaultProtectionTest.FakeGate()
    private val vault = SeedVault(dir, keys, deviceKeys = FakeKeys(), gate = gate)
    private val candidates = SeedCandidates().also { c -> vault.onForget = { c.forgetAll(it) } }
    private val flags = Flags(dir)
    private val window = SeedMigrationWindow(flags)
    private val env = SeedEnvironment(
        savedSeed = { vault.seedForSecrets() }, seedPresent = { vault.isThere() }, write = { vault.write(it) },
        counters = CounterStore(dir), candidates = candidates, wordlist = { TestSupport.wordlist },
        generate = { TestSupport.MADE }, migrationWindowOpen = { window.isOpen }, closeMigrationWindow = { window.close(it) },
        flags = flags,
        writeReplacing = { w, keep, confirm -> vault.write(w, replace = true, keepCandidate = keep, confirmReplace = confirm) },
        delete = { vault.delete() }, protection = { vault.protection() }, setProtection = { vault.setProtection(it) },
        wordsForScreen = { vault.wordsForScreen() }, unlock = { r, c -> vault.unlock(r, c) },
    )
    private val host = FakeHost()
    private var now = 1_000_000L

    /** The seed executor: run at once, or held until [drain] (a change still waiting). */
    private var hold = false
    private val held = ArrayList<Runnable>()
    private val executor = Executor { if (hold) held.add(it) else it.run() }

    private fun drain() {
        hold = false
        while (held.isNotEmpty()) held.removeAt(0).run()
    }

    private val bridge = FoxyBridge(host, { env }, executor, false, clock = { now })

    @After
    fun tearDown() {
        SeedRepliesTest.assertNoWords(replies().flatMap { listOfNotNull(it.second, it.third) })
        dir.deleteRecursively()
    }

    private fun send(json: String) = bridge.onMessage(json, true, FoxyBridge.ORIGIN)

    private fun replies(): List<Triple<String?, String?, String?>> = host.evaluated.filter { it.contains("._scanResult(") }.map { js ->
        @Suppress("UNCHECKED_CAST")
        val list = Json.parse("[" + js.substringAfter("_scanResult(").removeSuffix(")") + "]") as List<String?>
        Triple(list[0], list[1], list[2])
    }

    private fun reply(id: String) = replies().single { it.first == id }

    @Suppress("UNCHECKED_CAST")
    private fun json(id: String): Map<String, Any?> = Json.parse(reply(id).second!!) as Map<String, Any?>

    // MARK: seedShow

    @Test
    fun theWordsGoToTheScreenAndOnlyTheAnswerToThePage() {
        assertNull(vault.write(TestSupport.SAVED))
        host.showWordsAnswer = Triple(true, false, true)
        send("""{"action":"seedShow","id":"s1","verify":true}""")
        assertEquals(listOf(TestSupport.SAVED.split(" ") to true), host.shownWords)
        assertEquals(mapOf("verified" to true, "delete" to false, "revealed" to true), json("s1"))
        host.showWordsAnswer = Triple(false, true, false)
        send("""{"action":"seedShow","id":"s2"}""")
        assertEquals("verify is false unless asked", false, host.shownWords[1].second)
        assertEquals(mapOf("verified" to false, "delete" to true, "revealed" to false), json("s2"))
    }

    private fun assertNull(value: Any?) = org.junit.Assert.assertNull(value)

    @Test
    fun noSeedNothingToShowItFromAndAFailedReadAreRefused() {
        send("""{"action":"seedShow","id":"a"}""")
        assertEquals(Triple("a", null, "no seed"), reply("a"))
        assertNull(vault.write(TestSupport.SAVED))
        host.showWordsAnswer = null
        send("""{"action":"seedShow","id":"b"}""")
        assertEquals(Triple("b", null, SeedBridge.NO_PRESENTER), reply("b"))
        host.showWordsAnswer = Triple(true, false, true)
        keys.failGet = true
        send("""{"action":"seedShow","id":"c"}""")
        assertEquals(Triple("c", null, "the seed could not be read"), reply("c"))
        keys.failGet = false
        // a script cannot put the prompt straight back up (L1)
        send("""{"action":"seedShow","id":"d"}""")
        assertEquals(Triple("d", null, SeedBridge.PAUSED), reply("d"))
        now += 9_999
        send("""{"action":"seedShow","id":"e"}""")
        assertEquals(Triple("e", null, SeedBridge.PAUSED), reply("e"))
        now += 1
        send("""{"action":"seedShow","id":"f"}""")
        assertEquals(mapOf("verified" to true, "delete" to false, "revealed" to true), json("f"))
    }

    @Test
    fun oneSeedScreenAtATime() {
        assertNull(vault.write(TestSupport.SAVED))
        host.holdScreens = true
        send("""{"action":"seedShow","id":"a"}""")
        send("""{"action":"seedShow","id":"b"}""")
        send("""{"action":"seedEnter","id":"c"}""")
        assertEquals(Triple("b", null, SeedBridge.BUSY_SCREEN), reply("b"))
        assertEquals(Triple("c", null, SeedBridge.BUSY_SCREEN), reply("c"))
        assertEquals(1, host.shownWords.size)
        host.answerScreens()
        assertEquals(mapOf("verified" to true, "delete" to false, "revealed" to true), json("a"))
        send("""{"action":"seedEnter","id":"d"}""")
        assertEquals("free again", 1, host.enterScreens)
    }

    @Test
    fun aScreenWaitingBehindAQuestionWaitsAndIsAnsweredWhenClosed() {
        assertNull(vault.write(TestSupport.SAVED))
        host.alertUp = true
        send("""{"action":"seedShow","id":"a"}""")
        assertTrue("not shown over a question", host.shownWords.isEmpty())
        assertEquals(listOf(io.islandbitcoin.foxy.seed.ScreenQueue.RETRY_MS), host.later.map { it.first })
        // Foxy put away (or the page gone, or the seed changed) before it showed
        bridge.letGo()
        assertEquals(mapOf("verified" to false), json("a"))
        assertEquals(1, host.screensClosed)
        assertEquals(1, host.previewStops)
        assertEquals(1, host.pasteStops)
        host.alertUp = false
        host.later.single().second.run()
        assertTrue("a cancelled screen never shows", host.shownWords.isEmpty())
    }

    @Test
    fun aScreenUpWhenFoxyLeavesIsClosedWithItsBackButtonsAnswer() {
        assertNull(vault.write(TestSupport.SAVED))
        host.holdScreens = true
        send("""{"action":"seedShow","id":"a"}""")
        send("""{"action":"seedEnter","id":"x"}""")
        bridge.seedChanged()
        assertEquals(mapOf("verified" to false, "delete" to false, "revealed" to false), json("a"))
        assertEquals("busy while it was up", Triple("x", null, SeedBridge.BUSY_SCREEN), reply("x"))
        host.holdScreens = false
        host.enterWordsAnswer = null
        send("""{"action":"seedEnter","id":"e"}""")
        assertEquals(Triple("e", null, "cancelled"), reply("e"))
    }

    // MARK: seedEnter

    @Test
    fun typedWordsAreKeptUnderAnIdAndNeverAnswered() {
        host.enterWordsAnswer = TestSupport.TYPED_OTHER
        send("""{"action":"seedEnter","id":"a"}""")
        val id = json("a")["candidate"] as String
        assertTrue(id, Regex("^[0-9a-f]{32}$").matches(id))
        assertEquals(TestSupport.TYPED_OTHER, candidates.phrase(id))
        send("""{"action":"seedEnter","id":"b"}""")
        send("""{"action":"seedEnter","id":"c"}""")
        assertEquals("two at most, refused before the screen", Triple("c", null, "too many candidates"), reply("c"))
        assertEquals(2, host.enterScreens)
        send("""{"action":"seedCandidateForget","id":"d","candidate":"$id"}""")
        assertEquals(mapOf("forgotten" to true), json("d"))
        host.enterWordsAnswer = "legal winner thank year wave sausage worth useful legal winner thank year"
        send("""{"action":"seedEnter","id":"e"}""")
        assertEquals("checked again before it is kept", Triple("e", null, "the words could not be read"), reply("e"))
        host.enterWordsShown = false
        send("""{"action":"seedEnter","id":"f"}""")
        assertEquals(Triple("f", null, SeedBridge.NO_PRESENTER), reply("f"))
        for ((n, bad) in listOf("\"\"", "5", "\"${"a".repeat(65)}\"", "null").withIndex()) {
            send("""{"action":"seedCandidateForget","id":"g$n","candidate":$bad}""")
            assertEquals(Triple("g$n", null, "bad request"), reply("g$n"))
        }
    }

    // MARK: seedAdopt

    private fun typed(): String {
        host.enterWordsAnswer = TestSupport.TYPED_OTHER
        send("""{"action":"seedEnter","id":"typed"}""")
        val id = json("typed")["candidate"] as String
        host.evaluated.clear()
        return id
    }

    @Test
    fun replacingTheSeedNeedsTheQuestionsYesAndThenTheDeviceOwner() {
        assertNull(vault.write(TestSupport.SAVED))
        val id = typed()
        host.promptAnswer = false
        send("""{"action":"seedAdopt","id":"a","candidate":"$id"}""")
        assertEquals(Triple("a", null, "Nothing was changed."), reply("a"))
        assertEquals(SeedBridge.REPLACE_TITLE, host.prompts.single().title)
        assertEquals("Replace", host.prompts.single().yes)
        assertTrue(host.prompts.single().destructive)
        assertTrue("no to the question: the owner is not asked", host.ownerReasons.isEmpty())

        host.promptAnswer = true
        host.ownerApproves = false
        send("""{"action":"seedAdopt","id":"b","candidate":"$id"}""")
        assertEquals(Triple("b", null, "Nothing was changed."), reply("b"))
        assertEquals(listOf(SeedBridge.REPLACE_REASON), host.ownerReasons)
        assertEquals(SeedVault.Answer.Found(TestSupport.SAVED), vault.read())

        host.ownerApproves = true
        send("""{"action":"seedAdopt","id":"c","candidate":"$id"}""")
        assertEquals(mapOf("adopted" to true), json("c"))
        assertEquals(SeedVault.Answer.Found(TestSupport.TYPED_OTHER), vault.read())
    }

    @Test
    fun aQuestionLeftUnansweredIsANoAfter120s() {
        assertEquals(120_000L, SeedBridge.SEED_ANSWER_WAIT_MS)
        assertTrue(SeedBridge.noPasscodeNote(SeedBridge.REPLACE_MESSAGE, "replaced", false)
            .endsWith("This phone has no screen lock, so nothing else will be asked before the seed is replaced."))
        assertEquals(SeedBridge.DELETE_MESSAGE, SeedBridge.noPasscodeNote(SeedBridge.DELETE_MESSAGE, "deleted", true))
        assertTrue("the alert says who the danger is", SeedBridge.REPLACE_MESSAGE.contains("Whoever gave you these words"))
    }

    @Test
    fun adoptChecksItsCandidateFirst() {
        for ((n, bad) in listOf("\"\"", "7", "true", "\"${"f".repeat(65)}\"").withIndex()) {
            send("""{"action":"seedAdopt","id":"a$n","candidate":$bad}""")
            assertEquals(Triple("a$n", null, "bad request"), reply("a$n"))
        }
        send("""{"action":"seedAdopt","id":"u","candidate":"0123456789abcdef0123456789abcdef"}""")
        assertEquals(Triple("u", null, "unknown candidate"), reply("u"))
        assertTrue(host.prompts.isEmpty())
    }

    @Test
    fun oneSeedChangeAtATime() {
        assertNull(vault.write(TestSupport.SAVED))
        val id = typed()
        hold = true
        send("""{"action":"seedAdopt","id":"a","candidate":"$id"}""")
        send("""{"action":"seedWipe","id":"b"}""")
        send("""{"action":"seedCreate","id":"c"}""")
        assertEquals(Triple("b", null, SeedBridge.BUSY), reply("b"))
        assertEquals(Triple("c", null, SeedBridge.BUSY), reply("c"))
        drain()
        assertEquals(mapOf("adopted" to true), json("a"))
    }

    // MARK: seedWipe

    @Test
    fun aWipeNeedsBothYesesAndAlwaysMakesANewSeed() {
        assertNull(vault.write(TestSupport.SAVED))
        host.promptAnswer = false
        send("""{"action":"seedWipe","id":"a"}""")
        assertEquals(Triple("a", null, "Nothing was erased."), reply("a"))
        assertEquals(SeedBridge.DELETE_TITLE, host.prompts.single().title)
        assertEquals("Delete", host.prompts.single().yes)
        assertTrue(host.ownerReasons.isEmpty())
        host.promptAnswer = true
        host.ownerApproves = false
        send("""{"action":"seedWipe","id":"b"}""")
        assertEquals(Triple("b", null, "Nothing was erased."), reply("b"))
        assertEquals(SeedVault.Answer.Found(TestSupport.SAVED), vault.read())
        host.ownerApproves = true
        send("""{"action":"seedWipe","id":"c"}""")
        assertEquals(mapOf("wiped" to true, "created" to true), json("c"))
        assertEquals(listOf(SeedBridge.DELETE_REASON, SeedBridge.DELETE_REASON), host.ownerReasons)
        assertEquals(SeedVault.Answer.Found(TestSupport.MADE), vault.read())
    }

    // MARK: The protection

    @Test
    fun theProtectionsArgumentIsDeviceOrNone() {
        send("""{"action":"seedProtection","id":"p"}""")
        assertEquals(mapOf("mode" to "none", "passcode" to true), json("p"))
        for ((n, bad) in listOf("\"faceid\"", "\"\"", "1", "null", "\"DEVICE\"").withIndex()) {
            send("""{"action":"seedProtect","id":"m$n","mode":$bad}""")
            assertEquals(Triple("m$n", null, "bad request"), reply("m$n"))
        }
        assertNull(vault.write(TestSupport.SAVED))
        send("""{"action":"seedProtect","id":"d","mode":"device"}""")
        assertEquals(mapOf("mode" to "device"), json("d"))
        send("""{"action":"seedProtect","id":"n","mode":"none"}""")
        assertEquals(mapOf("mode" to "none"), json("n"))
        assertFalse(gate.asked.isEmpty())
    }
}
