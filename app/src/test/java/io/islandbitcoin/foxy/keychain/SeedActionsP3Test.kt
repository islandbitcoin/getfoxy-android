package io.islandbitcoin.foxy.keychain

import io.islandbitcoin.foxy.FakeKeys
import io.islandbitcoin.foxy.TestSupport
import io.islandbitcoin.foxy.bridge.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The P3 seed actions on a real vault (fake keys): adopting typed words
 * (SeedActions.swift:517-563; review M2, M6), forgetting them, wiping
 * (SeedActions.swift:586-600) and the protection (S5). No reply holds a word
 * (S2).
 */
class SeedActionsP3Test {
    private val dir = TestSupport.tempDir("seed-p3")
    private val keys = FakeKeys()
    private val deviceKeys = FakeKeys()
    private val gate = SeedVaultProtectionTest.FakeGate()
    private val vault = SeedVault(dir, keys, deviceKeys = deviceKeys, gate = gate)
    private val candidates = SeedCandidates().also { c -> vault.onForget = { c.forgetAll(it) } }
    private val counters = CounterStore(dir)
    private val flags = Flags(dir)
    private val window = SeedMigrationWindow(flags)
    private val order = ArrayList<String>()
    private var deleteWorks = true
    private val env = SeedEnvironment(
        savedSeed = { vault.seedForSecrets() },
        seedPresent = { vault.isThere() },
        write = { words -> order.add("write"); vault.write(words) },
        counters = counters,
        candidates = candidates,
        wordlist = { TestSupport.wordlist },
        generate = { order.add("generate"); TestSupport.MADE },
        migrationWindowOpen = { window.isOpen },
        closeMigrationWindow = { window.close(it) },
        flags = flags,
        writeReplacing = { words, keep, confirm -> vault.write(words, replace = true, keepCandidate = keep, confirmReplace = confirm) },
        delete = { order.add("delete"); if (deleteWorks) vault.delete() else false },
        protection = { vault.protection() },
        setProtection = { vault.setProtection(it) },
        wordsForScreen = { vault.wordsForScreen() },
    )
    private val replies = ArrayList<SeedReply>()
    private val v00 = TestSupport.V00

    @After
    fun tearDown() {
        SeedRepliesTest.assertNoWords(replies)
        dir.deleteRecursively()
    }

    private fun run(reply: SeedReply): SeedReply {
        replies.add(reply)
        assertTrue("an answer or an error, never both: $reply", (reply.text == null) != (reply.error == null))
        return reply
    }

    @Suppress("UNCHECKED_CAST")
    private fun parsed(reply: SeedReply): Map<String, Any?> = Json.parse(reply.text!!) as Map<String, Any?>

    // MARK: seedAdopt

    @Test
    fun adoptingTheSavedSeedsOwnWordsChangesNothingButRaisesTheCounters() {
        assertNull(vault.write(TestSupport.SAVED))
        val id = candidates.add(TestSupport.SAVED)
        candidates.serve(id, v00, 0, 100)
        candidates.serve(id, v00, 1000, 500)
        var asked = 0
        val reply = run(SeedActions.adopt(id, env) { asked++; true })
        assertEquals(mapOf("adopted" to false, "same" to true), parsed(reply))
        assertEquals("the same words: nobody is asked", 0, asked)
        assertEquals("raised to 1000 short of the furthest served", 500L, counters.next(v00))
        assertEquals(SeedVault.Answer.Found(TestSupport.SAVED), vault.read())
    }

    @Test
    fun aDifferentSeedDeclinedIsNothingChanged() {
        assertNull(vault.write(TestSupport.SAVED))
        counters.reserve(v00, 0, 7)
        val id = candidates.add(TestSupport.TYPED_OTHER)
        val reply = run(SeedActions.adopt(id, env) { false })
        assertEquals("Nothing was changed.", reply.error)
        assertEquals(SeedVault.Answer.Found(TestSupport.SAVED), vault.read())
        assertEquals("the counters are where they were", 7L, counters.next(v00))
        assertFalse(dir.listFiles()!!.any { it.name.startsWith("foxy-counters.replaced.") })
    }

    @Test
    fun aDifferentSeedAcceptedReplacesItSetsTheCountersAsideAndRaisesThem() {
        assertNull(vault.write(TestSupport.SAVED))
        counters.reserve(v00, 0, 7)
        val id = candidates.add(TestSupport.TYPED_OTHER)
        candidates.serve(id, v00, 0, 1000)
        candidates.serve(id, v00, 1000, 1000)
        var asked = 0
        val reply = run(SeedActions.adopt(id, env) { asked++; true })
        assertEquals(mapOf("adopted" to true), parsed(reply))
        assertEquals(1, asked)
        assertEquals(SeedVault.Answer.Found(TestSupport.TYPED_OTHER), vault.read())
        assertTrue("the old counters were set aside", dir.listFiles()!!.any { it.name.startsWith("foxy-counters.replaced.") })
        assertEquals("and the new raised to 1000 short of the furthest served", 1000L, counters.next(v00))
        assertTrue("the adopted candidate stays for the scans still running with it (M6)", id in candidates)
        assertFalse("the migration window is closed", window.isOpen)
    }

    @Test
    fun adoptingWithNoSeedSavedAsksNobody() {
        val id = candidates.add(TestSupport.TYPED_OTHER)
        var asked = 0
        assertEquals(mapOf("adopted" to true), parsed(run(SeedActions.adopt(id, env) { asked++; false })))
        assertEquals(0, asked)
        assertEquals(SeedVault.Answer.Found(TestSupport.TYPED_OTHER), vault.read())
    }

    @Test
    fun anUnknownCandidateOrAnUnreadableVaultIsRefused() {
        assertEquals("unknown candidate", run(SeedActions.adopt("0123456789abcdef0123456789abcdef", env) { true }).error)
        assertNull(vault.write(TestSupport.SAVED))
        val id = candidates.add(TestSupport.TYPED_OTHER)
        keys.failGet = true
        assertEquals("the seed could not be read", run(SeedActions.adopt(id, env) { true }).error)
        keys.failGet = false
        assertEquals(SeedVault.Answer.Found(TestSupport.SAVED), vault.read())
    }

    @Test
    fun forgettingACandidateIsAlwaysYes() {
        val id = candidates.add(TestSupport.TYPED_OTHER)
        assertEquals(mapOf("forgotten" to true), parsed(run(SeedActions.candidateForget(id, env))))
        assertFalse(id in candidates)
        assertEquals(mapOf("forgotten" to true), parsed(run(SeedActions.candidateForget(id, env))))
    }

    // MARK: seedWipe

    @Test
    fun aWipeDeletesThenSetsAsideThenMakesANewSeedInThatOrder() {
        assertNull(vault.write(TestSupport.SAVED))
        counters.reserve(v00, 0, 9)
        val id = candidates.add(TestSupport.TYPED_OTHER)
        order.clear()
        val reply = run(SeedActions.wipe(env))
        assertEquals(mapOf("wiped" to true, "created" to true), parsed(reply))
        assertEquals(listOf("delete", "generate", "write"), order)
        assertEquals(SeedVault.Answer.Found(TestSupport.MADE), vault.read())
        assertEquals("the counters start from zero", 0L, counters.next(v00))
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("foxy-counters.replaced.") })
        assertFalse("every candidate dropped", id in candidates)
    }

    @Test
    fun aSeedThatWillNotGoStopsTheWipeBeforeAnythingMoves() {
        assertNull(vault.write(TestSupport.SAVED))
        counters.reserve(v00, 0, 9)
        deleteWorks = false
        order.clear()
        assertEquals("the seed could not be deleted", run(SeedActions.wipe(env)).error)
        assertEquals(listOf("delete"), order)
        assertEquals(9L, counters.next(v00))
        assertEquals(SeedVault.Answer.Found(TestSupport.SAVED), vault.read())
    }

    @Test
    fun aWipeUnderDeviceMakesTheNewSeedBehindTheLock() {
        assertNull(vault.write(TestSupport.SAVED))
        assertNull(vault.setProtection(SeedVault.DEVICE))
        assertEquals(mapOf("wiped" to true, "created" to true), parsed(run(SeedActions.wipe(env))))
        assertTrue(File(dir, SeedVault.DEVICE_FILE_NAME).exists())
        assertFalse(File(dir, SeedVault.FILE_NAME).exists())
        assertEquals(SeedVault.Answer.Found(TestSupport.MADE), vault.read())
    }

    // MARK: The protection

    @Test
    fun theProtectionIsAnsweredAndMoved() {
        assertEquals(mapOf("mode" to "none", "passcode" to true), parsed(run(SeedActions.protection(env))))
        env.passcodeSet = { false }
        assertEquals(mapOf("mode" to "none", "passcode" to false), parsed(run(SeedActions.protection(env))))
        env.passcodeSet = { true }
        assertNull(vault.write(TestSupport.SAVED))
        assertEquals(mapOf("mode" to "device"), parsed(run(SeedActions.setProtection(env, "device"))))
        assertEquals(mapOf("mode" to "device", "passcode" to true), parsed(run(SeedActions.protection(env))))
        gate.answer = "no"
        assertEquals(SeedVault.DECLINED, run(SeedActions.setProtection(env, "none")).error)
        gate.answer = "yes"
        assertEquals(mapOf("mode" to "none"), parsed(run(SeedActions.setProtection(env, "none"))))
        assertNotNull(keys.key)
        assertNull(deviceKeys.key)
    }

    @Test
    fun theWordsForTheScreenAreTheSavedWords() {
        assertEquals(SeedVault.Answer.Absent, env.wordsForScreen())
        assertNull(vault.write(TestSupport.SAVED))
        assertEquals(SeedVault.Answer.Found(TestSupport.SAVED), env.wordsForScreen())
        assertNull(vault.setProtection(SeedVault.DEVICE))
        gate.asked.clear()
        assertEquals(SeedVault.Answer.Found(TestSupport.SAVED), env.wordsForScreen())
        assertEquals("showing the words always asks, with its own reason", listOf(SeedVault.SHOW_REASON to true), gate.asked)
    }
}
