package io.islandbitcoin.foxy.keychain

import io.islandbitcoin.foxy.FakeKeys
import io.islandbitcoin.foxy.TestSupport
import io.islandbitcoin.foxy.bridge.FoxyBridge
import io.islandbitcoin.foxy.FakeHost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executor

/**
 * SeedVault with a fake key provider (S4, S20): a read has three answers and
 * every failure is *failed*, never absent; on failed, seedStatus, seedCreate and
 * counterReserve answer "the seed could not be read" and nothing is written; a
 * write never goes over a different seed and is read back; the seed in memory
 * serves the work of leaving and nothing after. Ported from SeedVaultTests.swift
 * and NativeSeedTests.swift.
 */
class SeedVaultTest {
    private val dir = TestSupport.tempDir("vault")
    private val keys = FakeKeys()
    private var now = 1_000_000L
    private val vault = SeedVault(dir, keys) { now }
    private val words = TestSupport.SAVED
    private val other = TestSupport.ABANDON_ABOUT

    @After
    fun clean() {
        dir.setWritable(true)
        dir.deleteRecursively()
    }

    private fun failed(a: SeedVault.Answer) = a is SeedVault.Answer.Failed

    @Test
    fun anEmptyVaultIsAbsentAndAWrittenOneReadsBack() {
        assertEquals(SeedVault.Answer.Absent, vault.read())
        assertEquals(false, vault.isThere())
        assertNull(vault.write(words))
        assertEquals(SeedVault.Answer.Found(words), vault.read())
        assertEquals(true, vault.isThere())
        // a second vault on the same folder and key, as after a relaunch
        assertEquals(SeedVault.Answer.Found(words), SeedVault(dir, keys).read())
        // the file is not the words
        assertFalse(String(File(dir, SeedVault.FILE_NAME).readBytes(), Charsets.ISO_8859_1).contains("legal"))
        assertEquals("Found(<words>)", vault.read().toString())
    }

    @Test
    fun everyFailureIsFailedNeverAbsent() {
        assertNull(vault.write(words))
        val key = keys.key
        // a file without its key
        keys.key = null
        assertTrue(failed(vault.read()))
        // a key without its file
        keys.key = key
        val sealed = File(dir, SeedVault.FILE_NAME).readBytes()
        File(dir, SeedVault.FILE_NAME).delete()
        assertTrue(failed(vault.read()))
        File(dir, SeedVault.FILE_NAME).writeBytes(sealed)
        assertEquals(SeedVault.Answer.Found(words), vault.read())
        // a keystore that throws, on asking and on reading
        keys.failExists = true
        assertTrue(failed(vault.read()))
        assertEquals("the file is there: a seed is there, readable or not", true, vault.isThere())
        File(dir, SeedVault.FILE_NAME).delete()
        assertNull("no file, and a keystore that will not say: no answer", vault.isThere())
        File(dir, SeedVault.FILE_NAME).writeBytes(sealed)
        keys.failExists = false
        keys.failGet = true
        assertTrue(failed(vault.read()))
        keys.failGet = false
        // another key: the ciphertext does not authenticate (an AEAD failure)
        keys.key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertTrue(failed(vault.read()))
        keys.key = key
        // a ciphertext with one bit changed
        val flipped = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        File(dir, SeedVault.FILE_NAME).writeBytes(flipped)
        assertTrue(failed(vault.read()))
        // a file that is not one this build reads
        File(dir, SeedVault.FILE_NAME).writeBytes(byteArrayOf(9, 9, 9))
        assertTrue(failed(vault.read()))
    }

    @Test
    fun aFailedVaultIsNeverWrittenAndTheActionsSaySo() {
        assertNull(vault.write(words))
        keys.failGet = true
        val before = File(dir, SeedVault.FILE_NAME).readBytes()
        val refused = vault.write(other)!!
        assertTrue(refused, refused.startsWith("The keychain did not answer") && refused.endsWith("so nothing was written."))
        assertTrue("the file is as it was", before.contentEquals(File(dir, SeedVault.FILE_NAME).readBytes()))

        // through the bridge: seedStatus, seedCreate and counterReserve all refuse, and nothing is written
        val counters = CounterStore(File(dir, "c"))
        val env = SeedEnvironment(
            savedSeed = { vault.seedForSecrets() }, seedPresent = { vault.isThere() }, write = { vault.write(it) },
            counters = counters, candidates = SeedCandidates(), wordlist = { TestSupport.wordlist },
            generate = { TestSupport.MADE }, migrationWindowOpen = { true }, closeMigrationWindow = {}, flags = Flags(dir),
        )
        val host = FakeHost()
        val bridge = FoxyBridge(host, { env }, Executor { it.run() }, false)
        for (m in listOf("""{"action":"seedStatus","id":"1"}""", """{"action":"seedCreate","id":"2"}""",
            """{"action":"counterReserve","id":"3","keysetId":"${TestSupport.V00}","count":2}""")) {
            bridge.onMessage(m, true, FoxyBridge.ORIGIN)
        }
        assertEquals(
            listOf("1", "2", "3").map { """window.FoxyWallet && window.FoxyWallet._scanResult("$it", null, "the seed could not be read")""" },
            host.evaluated,
        )
        assertTrue("no counter was used up", counters.snapshot().isEmpty())
        assertTrue(before.contentEquals(File(dir, SeedVault.FILE_NAME).readBytes()))
        // quiet, an unreadable seed is still a seed that is there: the page must not make another
        host.evaluated.clear()
        bridge.onMessage("""{"action":"seedStatus","id":"4","quiet":true}""", true, FoxyBridge.ORIGIN)
        assertTrue(host.evaluated.single().contains("""_scanResult("4", "{\"exists\":true}", null)"""))
        // and with no file and a keystore that will not say, a refusal
        File(dir, SeedVault.FILE_NAME).delete()
        keys.failExists = true
        host.evaluated.clear()
        bridge.onMessage("""{"action":"seedStatus","id":"5","quiet":true}""", true, FoxyBridge.ORIGIN)
        assertEquals("""window.FoxyWallet && window.FoxyWallet._scanResult("5", null, "the seed could not be read")""", host.evaluated.single())
    }

    @Test
    fun aDifferentSeedIsNeverWrittenOver() {
        assertNull(vault.write(words))
        assertEquals(SeedVault.DIFFERENT_SEED, vault.write(other))
        assertEquals(SeedVault.Answer.Found(words), vault.read())
        assertNull("the same words again are no change", vault.write(words))
    }

    /** The process dying: not an Exception, so nothing in seal() catches it. */
    private class Died : Error("the process died")

    /** FakeKeys, with a hook that runs once the key is made. */
    private class KeysThen(val inner: FakeKeys, val afterCreate: () -> Unit) : SeedKeys {
        override fun exists() = inner.exists()
        override fun get() = inner.get()
        override fun create(): javax.crypto.SecretKey = inner.create().also { afterCreate() }
        override fun delete() = inner.delete()
    }

    private val creating get() = File(dir, SeedVault.CREATING_NAME)

    @Test
    fun aFirstWriteCutOffBetweenItsKeyAndItsFileReadsAbsentAndCanBeMadeAgain() {
        // the person swipes Foxy away while the Keystore is making the seed's key
        val dying = SeedVault(dir, KeysThen(keys) { throw Died() }) { now }
        try {
            dying.write(words)
            org.junit.Assert.fail("the write should have died")
        } catch (_: Died) {}
        assertNotNull("the key was made", keys.key)
        assertFalse("and the seed file never was", File(dir, SeedVault.FILE_NAME).exists())
        assertTrue("the marker says a first write was under way", creating.exists())

        // the next launch: the orphan key goes and the vault is absent, not failed for good
        val relaunched = SeedVault(dir, keys) { now }
        assertEquals(SeedVault.Answer.Absent, relaunched.read())
        assertNull("the orphan key was removed", keys.key)
        assertFalse("and the marker with it", creating.exists())
        assertNull(relaunched.write(words))
        assertEquals(SeedVault.Answer.Found(words), relaunched.read())
        assertFalse("a write that finished leaves no marker", creating.exists())

        // a key without its file and without the marker is still failed (S4)
        File(dir, SeedVault.FILE_NAME).delete()
        assertTrue(failed(relaunched.read()))
        assertNotNull("and its key is left alone", keys.key)
    }

    @Test
    fun aMarkerBesideASavedSeedIsOnlyAMarker() {
        assertNull(vault.write(words))
        // a first write cut off after its rename, before the marker went
        creating.writeBytes(ByteArray(0))
        assertEquals(SeedVault.Answer.Found(words), vault.read())
        assertFalse(creating.exists())
        // so a later key-without-file is failed, not taken for a cut-off write
        File(dir, SeedVault.FILE_NAME).delete()
        assertTrue(failed(vault.read()))
    }

    @Test
    fun aCutOffWriteWhoseKeyWillNotGoIsFailedNotAbsent() {
        keys.create()
        creating.writeBytes(ByteArray(0))
        keys.failExists = true
        assertTrue(failed(vault.read()))
        assertTrue("the marker stays for the next read", creating.exists())
        keys.failExists = false
        assertEquals(SeedVault.Answer.Absent, vault.read())

        // seedStatus asks isThere first: it clears the cut-off write too, and answers no seed
        keys.create()
        creating.writeBytes(ByteArray(0))
        assertEquals(false, vault.isThere())
        assertNull(keys.key)
        assertFalse(creating.exists())
    }

    @Test
    fun aKeyMadeForAWriteWhoseFileCouldNotBeWrittenGoesWithIt() {
        // the key is made, then the folder refuses the seed file
        val v = SeedVault(dir, KeysThen(keys) { TestSupport.makeUnwritable(dir) }) { now }
        try {
            assertEquals("the keystore refused the write", v.write(words))
        } finally {
            dir.setWritable(true)
        }
        assertEquals("a key was made for the write", 1, keys.created)
        assertNull("and went with it", keys.key)
        assertEquals(SeedVault.Answer.Absent, vault.read())
        assertFalse(creating.exists())
    }

    @Test
    fun aWriteThatFailsLeavesNoKeyBehind() {
        TestSupport.makeUnwritable(dir)
        try {
            assertEquals("the keystore refused the write", vault.write(words))
        } finally {
            dir.setWritable(true)
        }
        assertNull("a key made for the write went with it, so the vault is absent, not failed", keys.key)
        assertEquals(SeedVault.Answer.Absent, vault.read())
        keys.failCreate = true
        assertEquals("the keystore refused the write", vault.write(words))
        assertEquals(SeedVault.Answer.Absent, vault.read())
    }

    @Test
    fun theWriteRules() {
        assertEquals(SeedVault.WriteStep.Refuse("The keychain did not answer (x), so nothing was written."),
            SeedVault.writeStep(SeedVault.Answer.Failed("x"), words, true))
        assertEquals(SeedVault.WriteStep.Refuse(SeedVault.DIFFERENT_SEED), SeedVault.writeStep(SeedVault.Answer.Found(other), words, false))
        assertEquals(SeedVault.WriteStep.AskToReplace, SeedVault.writeStep(SeedVault.Answer.Found(other), words, true))
        assertEquals(SeedVault.WriteStep.Write, SeedVault.writeStep(SeedVault.Answer.Found(words), words, false))
        assertEquals(SeedVault.WriteStep.Write, SeedVault.writeStep(SeedVault.Answer.Absent, words, false))
    }

    @Test
    fun aWriteWhoseReadBackFailedIsASuccessIfTheWordsAreThere() {
        var reads = 0
        assertNull(SeedVault.writeOutcome(null, words) { reads++; SeedVault.Answer.Absent })
        assertEquals("no problem, no second read", 0, reads)
        assertNull(SeedVault.writeOutcome("it did not read back", words) { reads++; SeedVault.Answer.Found(words) })
        assertEquals(1, reads)
        assertEquals("it did not read back", SeedVault.writeOutcome("it did not read back", words) { SeedVault.Answer.Found(other) })
        assertEquals("x", SeedVault.writeOutcome("x", words) { SeedVault.Answer.Failed("y") })
    }

    @Test
    fun theSeedIsKeptForTheVisitAndDroppedOnEveryWrite() {
        assertNull(vault.write(words))
        val first = vault.seedForSecrets() as SeedVault.SecretsSeed.Found
        keys.failGet = true
        // kept: no second read
        assertTrue((vault.seedForSecrets() as SeedVault.SecretsSeed.Found).seed.sameBytes(first.seed))
        keys.failGet = false
        vault.write(words)
        keys.failGet = true
        assertTrue("a write drops the kept seed, so the next action reads again", vault.seedForSecrets() is SeedVault.SecretsSeed.Failed)
    }

    @Test
    fun aSeedPutAwayServesTheWorkOfLeavingAndNothingAfter() {
        val seed = NUT13.seed(words)
        val at = 5_000_000L
        fun visit() {
            vault.forgetNativeSeed()
            vault.keep(seed, vault.epochNow)
        }
        assertTrue(SeedVault.leavingCovers(at, at))
        assertTrue(SeedVault.leavingCovers(at, at + SeedVault.LEAVING_MS - 1))
        assertFalse(SeedVault.leavingCovers(at, at + SeedVault.LEAVING_MS))
        assertFalse("a clock that went backwards covers nothing", SeedVault.leavingCovers(at, at - 1))
        assertTrue(SeedVault.LEAVING_MS <= 30_000)

        visit()
        assertTrue(vault.kept(at)!!.sameBytes(seed))
        vault.putAway(at)
        assertTrue("for the work of leaving", vault.kept(at + 5_000)!!.sameBytes(seed))
        assertNull("out of time", vault.kept(at + SeedVault.LEAVING_MS))
        assertNull("dropped, not waiting for an earlier time", vault.kept(at + 1))

        visit()
        vault.putAway(at)
        vault.endLeaving()
        assertNull(vault.kept(at + 1))

        // a read that began before Foxy left does not put a seed back
        visit()
        val before = vault.epochNow
        vault.putAway(at)
        vault.endLeaving()
        vault.keep(seed, before)
        assertNull("its epoch has passed", vault.kept(at))

        // a visit that never read the seed leaves with none
        vault.forgetNativeSeed()
        vault.putAway(at)
        assertNull(vault.kept(at))

        for ((name, forget) in listOf<Pair<String, () -> Unit>>("forgetNativeSeed" to vault::forgetNativeSeed, "pageWillLoad" to vault::pageWillLoad)) {
            visit()
            vault.putAway(at)
            forget()
            assertNull(name, vault.kept(at + 1))
        }
        assertNotNull(seed)
    }
}
