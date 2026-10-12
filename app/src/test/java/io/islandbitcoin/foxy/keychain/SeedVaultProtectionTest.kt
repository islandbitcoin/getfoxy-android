package io.islandbitcoin.foxy.keychain

import io.islandbitcoin.foxy.FakeKeys
import io.islandbitcoin.foxy.TestSupport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import javax.crypto.Cipher

/**
 * The seed's two protections (S3, S5; SeedStore.swift:521-604): `none` and
 * `device`, the moves between them written, read back and only then the old
 * copy deleted, the protection recorded put back on a failure, and the
 * `device` key asked for through [SeedGate] for every use. Two FakeKeys stand
 * for the two Keystore keys.
 */
class SeedVaultProtectionTest {
    /** The person, as the gate hears them: yes, no, or nothing to ask with. */
    class FakeGate : SeedGate {
        var answer = "yes"
        val asked = ArrayList<Pair<String, Boolean>>()

        /** The reasons asked with `fresh`: never let through on an earlier yes. */
        val fresh = ArrayList<String>()
        override fun open(reason: String, credential: Boolean, fresh: Boolean, cipher: () -> Cipher): SeedGate.Answer {
            asked.add(reason to credential)
            if (fresh) this.fresh.add(reason)
            return when (answer) {
                "yes" -> SeedGate.Answer.Opened(cipher())
                "no" -> SeedGate.Answer.No
                else -> SeedGate.Answer.Unavailable
            }
        }
    }

    /** The process dying: not an Exception, so nothing in the vault catches it. */
    private class Died : Error("the process died")

    private val dir = TestSupport.tempDir("protection")
    private val plainKeys = FakeKeys()
    private val deviceKeys = FakeKeys()
    private val gate = FakeGate()
    private var passcode = true
    private var changed = 0
    private val words = TestSupport.SAVED
    private val other = TestSupport.TYPED_OTHER

    private fun vault() = SeedVault(dir, plainKeys, deviceKeys = deviceKeys, gate = gate, passcodeSet = { passcode }).also {
        it.onChanged = { changed++ }
    }

    private val vault = vault()
    private val plainFile get() = File(dir, SeedVault.FILE_NAME)
    private val deviceFile get() = File(dir, SeedVault.DEVICE_FILE_NAME)

    @After
    fun tearDown() {
        dir.setWritable(true)
        dir.deleteRecursively()
    }

    @Test
    fun aNewInstallIsNoneAndAsksNobody() {
        assertEquals(SeedVault.NONE, vault.protection())
        assertNull(vault.write(words))
        assertTrue(plainFile.exists())
        assertFalse(deviceFile.exists())
        assertEquals(SeedVault.Answer.Found(words), vault.read())
        assertEquals("nothing behind the screen lock: no one is asked", emptyList<Pair<String, Boolean>>(), gate.asked)
        assertEquals("protection none, and no passcode question either way", SeedVault.NONE, vault.protection())
    }

    @Test
    fun noneToDeviceToNoneMovesTheSeedAndAsksEachTime() {
        assertNull(vault.write(words))
        assertNull(vault.setProtection(SeedVault.DEVICE))
        assertEquals(SeedVault.DEVICE, vault.protection())
        assertTrue("the seed is behind the screen lock", deviceFile.exists())
        assertFalse("and the copy outside it is gone", plainFile.exists())
        assertNull("with its key", plainKeys.key)
        assertEquals("putting the lock on asks as the key is first used", listOf(SeedVault.GUARD_ON_REASON to true), gate.asked)

        gate.asked.clear()
        assertEquals(SeedVault.Answer.Found(words), vault.read())
        assertEquals("every read of the device copy asks", listOf(SeedVault.OPEN_REASON to true), gate.asked)

        gate.asked.clear()
        assertNull(vault.setProtection(SeedVault.NONE))
        assertEquals("taking the lock off reads from behind it: always asked", listOf(SeedVault.GUARD_OFF_REASON to true), gate.asked)
        assertEquals(SeedVault.NONE, vault.protection())
        assertTrue(plainFile.exists())
        assertFalse(deviceFile.exists())
        assertNull(deviceKeys.key)
        gate.asked.clear()
        assertEquals(SeedVault.Answer.Found(words), vault.read())
        assertEquals(emptyList<Pair<String, Boolean>>(), gate.asked)
    }

    @Test
    fun aMoveTheGateRefusesChangesNothing() {
        assertNull(vault.write(words))
        for (answer in listOf("no", "unavailable")) {
            gate.answer = answer
            val problem = vault.setProtection(SeedVault.DEVICE)
            assertEquals(answer, if (answer == "no") SeedVault.DECLINED else SeedVault.UNAVAILABLE, problem)
            assertEquals("the protection is what it was", SeedVault.NONE, vault.protection())
            assertFalse("no copy behind the lock", deviceFile.exists())
            assertNull("and no key made for one is left", deviceKeys.key)
            assertFalse(File(dir, SeedVault.DEVICE_CREATING_NAME).exists())
            assertEquals(SeedVault.Answer.Found(words), vault.read())
        }
        // and off: a no reads nothing, so nothing moves
        gate.answer = "yes"
        assertNull(vault.setProtection(SeedVault.DEVICE))
        gate.answer = "no"
        assertEquals(SeedVault.DECLINED, vault.setProtection(SeedVault.NONE))
        assertEquals(SeedVault.DEVICE, vault.protection())
        assertFalse(plainFile.exists())
        gate.answer = "yes"
        assertEquals(SeedVault.Answer.Found(words), vault.read())
    }

    @Test
    fun aReadBackThatFailsKeepsTheOldCopyAndTheProtection() {
        assertNull(vault.write(words))
        val before = plainFile.readBytes()
        vault.afterSeal = { it.writeBytes(byteArrayOf(1, 2, 3)) }
        assertEquals("it did not read back", vault.setProtection(SeedVault.DEVICE))
        assertEquals(SeedVault.NONE, vault.protection())
        assertTrue("the old ciphertext is kept", before.contentEquals(plainFile.readBytes()))
        assertFalse("the copy that did not read back is not left to shadow it", deviceFile.exists())
        vault.afterSeal = null
        assertEquals(SeedVault.Answer.Found(words), vault.read())

        // and off: the copy outside the lock does not read back, so the one behind it stays
        assertNull(vault.setProtection(SeedVault.DEVICE))
        vault.afterSeal = { it.writeBytes(byteArrayOf(1, 2, 3)) }
        assertEquals("the seed did not persist", vault.setProtection(SeedVault.NONE))
        assertEquals(SeedVault.DEVICE, vault.protection())
        assertTrue(deviceFile.exists())
        vault.afterSeal = null
        assertEquals(SeedVault.Answer.Found(words), vault.read())
    }

    @Test
    fun aMoveCutOffBetweenTheWriteAndTheDeleteLeavesTheSeedReadable() {
        assertNull(vault.write(words))
        for (mode in listOf(SeedVault.DEVICE, SeedVault.NONE)) {
            vault.afterWriteBeforeDelete = { throw Died() }
            try {
                vault.setProtection(mode)
                fail("the move should have died")
            } catch (_: Died) {}
            assertTrue("both copies are there", plainFile.exists() && deviceFile.exists())
            // the next launch reads the seed, the same words in either copy
            val relaunched = vault()
            assertEquals(mode, SeedVault.Answer.Found(words), relaunched.read())
            // and finishing the move leaves one copy
            assertNull(relaunched.setProtection(mode))
            assertEquals(mode, relaunched.protection())
            assertEquals(mode == SeedVault.DEVICE, deviceFile.exists())
            assertEquals(mode == SeedVault.NONE, plainFile.exists())
            assertEquals(SeedVault.Answer.Found(words), relaunched.read())
        }
    }

    @Test
    fun aWriteGoesWhereTheProtectionSaysAndLeavesNoOtherCopy() {
        assertNull(vault.setProtection(SeedVault.DEVICE))
        assertEquals("no seed yet: the choice stands for the seed to come", SeedVault.DEVICE, vault.protection())
        assertNull(vault.write(words))
        assertTrue(deviceFile.exists())
        assertFalse(plainFile.exists())
        // a phone whose screen lock is gone writes outside the lock, and the copy behind it goes with the write
        passcode = false
        assertNull(vault.write(words))
        assertTrue(plainFile.exists())
        assertFalse(deviceFile.exists())
        assertEquals(SeedVault.NO_PASSCODE, vault.setProtection(SeedVault.DEVICE))
    }

    @Test
    fun aReplaceUnderDeviceAsksNoSecondReadOfTheKeptSeed() {
        assertNull(vault.setProtection(SeedVault.DEVICE))
        assertNull(vault.write(words))
        gate.asked.clear()
        assertTrue(vault.seedForSecrets() is SeedVault.SecretsSeed.Found)
        assertEquals(1, gate.asked.size)
        gate.asked.clear()
        assertEquals("a different seed is refused without asking", SeedVault.DIFFERENT_SEED, vault.write(other))
        assertTrue(gate.asked.isEmpty())
        // the kept seed went with the refused write: the next one reads again
        var confirmed = 0
        assertNull(vault.write(other, replace = true, confirmReplace = { confirmed++; true }))
        assertEquals(1, confirmed)
        assertEquals(listOf(SeedVault.SAVE_REASON to true, SeedVault.SAVE_REASON to true), gate.asked)
        assertEquals(SeedVault.Answer.Found(other), vault.read())
    }

    @Test
    fun deleteRemovesEveryCopyAndSaysSo() {
        assertNull(vault.write(words))
        assertNull(vault.setProtection(SeedVault.DEVICE))
        // a copy left outside the lock too (a delete that would not go)
        plainKeys.create()
        plainFile.writeBytes(deviceFile.readBytes())
        changed = 0
        assertTrue(vault.delete())
        assertEquals(1, changed)
        assertEquals(false, vault.isThere())
        assertEquals(SeedVault.Answer.Absent, vault.read())
        assertNull(deviceKeys.key)
        assertNull(plainKeys.key)
        for (marker in listOf(SeedVault.CREATING_NAME, SeedVault.DEVICE_CREATING_NAME)) assertFalse(File(dir, marker).exists())
    }

    @Test
    fun aDeleteThatCannotFinishIsNotAWipe() {
        assertNull(vault.write(words))
        changed = 0
        plainKeys.failExists = true
        assertFalse(vault.delete())
        plainKeys.failExists = false
        assertEquals(0, changed)
    }

    @Test
    fun aDeleteCutOffAfterItsFileWentReadsAbsentNotFailed() {
        assertNull(vault.setProtection(SeedVault.DEVICE))
        assertNull(vault.write(words))
        // the marker is down and the file gone; the process died before the key went
        File(dir, SeedVault.DEVICE_CREATING_NAME).writeBytes(ByteArray(0))
        deviceFile.delete()
        assertNotNull(deviceKeys.key)
        assertEquals(SeedVault.Answer.Absent, vault().read())
        assertNull(deviceKeys.key)
        // without the marker a key with no file stays failed (S4)
        assertNull(vault.write(words))
        deviceFile.delete()
        assertTrue(vault().read() is SeedVault.Answer.Failed)
    }

    @Test
    fun thePinLocksBiometricOpensTheSeedBehindTheLock() {
        assertEquals("nothing behind the lock: the bridge asks a prompt of its own", null, vault.unlock("Unlock Foxy", false))
        assertNull(vault.setProtection(SeedVault.DEVICE))
        assertNull(vault.write(words))
        gate.asked.clear()
        assertEquals("yes", vault.unlock("Unlock Foxy", false))
        assertEquals(listOf("Unlock Foxy" to false), gate.asked)
        assertNotNull("the yes opened the seed", vault.kept())
        assertEquals("a seed already open: a prompt of its own", null, vault.unlock("Unlock Foxy", true))
        gate.asked.clear()
        assertTrue(vault.seedForSecrets() is SeedVault.SecretsSeed.Found)
        assertTrue("and the read after it asks nothing", gate.asked.isEmpty())

        vault.forgetNativeSeed()
        gate.answer = "no"
        assertEquals("no", vault.unlock("Leave POS mode", true))
        // nothing to ask with at the seed key's strength (a phone whose only biometric is a weak face unlock):
        // not "unavailable", which would turn the biometric off for choosing more protection; the bridge asks its own
        gate.answer = "unavailable"
        assertEquals(null, vault.unlock("Unlock Foxy", false))
        assertNull(vault.kept())
    }

    @Test
    fun aDeclinedReadIsAFailureNeverNoSeed() {
        assertNull(vault.setProtection(SeedVault.DEVICE))
        assertNull(vault.write(words))
        gate.answer = "no"
        assertEquals(SeedVault.Answer.Failed(SeedVault.DECLINED), vault.read())
        assertTrue(vault.seedForSecrets() is SeedVault.SecretsSeed.Failed)
        assertEquals("a write is never blind over a seed it could not read",
            "The keychain did not answer (${SeedVault.DECLINED}), so nothing was written.", vault.write(other))
        assertEquals(true, vault.isThere())
    }

    @Test
    fun typedWordsGoWhereTheKeptSeedGoes() {
        val kept = ArrayList<String?>()
        vault.onForget = { kept.add(it) }
        vault.forgetNativeSeed()
        vault.forgetNativeSeed("c1")
        vault.putAway(0)
        vault.pageWillLoad()
        assertNull(vault.write(words, keepCandidate = "c2"))
        assertEquals(listOf(null, "c1", null, null, "c2", "c2"), kept)
    }

    @Test
    fun takingTheGuardOffAlwaysAsksFreshNeverOnAnEarlierUnlock() {
        assertNull(vault.write(words))
        assertNull(vault.setProtection(SeedVault.DEVICE))
        assertEquals("putting the guard on may use an earlier yes", emptyList<String>(), gate.fresh)
        assertEquals(SeedVault.Answer.Found(words), vault.read())
        assertEquals("yes", vault.unlock("Unlock Foxy", true))
        assertEquals("ordinary reads and the PIN lock's biometric may", emptyList<String>(), gate.fresh)
        assertNull(vault.setProtection(SeedVault.NONE))
        assertEquals("plan: moving to none always prompts, never on an earlier unlock", listOf(SeedVault.GUARD_OFF_REASON), gate.fresh)
    }

    /**
     * A device -> none move whose delete of the copy behind the lock failed
     * leaves protection none, the same words outside the lock, and the copy
     * behind it, which read believes first and so keeps asking. The next
     * seedProtect none removes it (it used to answer at once).
     */
    @Test
    fun aCopyLeftBehindTheLockByAFailedDeleteIsRemovedByTheNextNone() {
        assertNull(vault.write(words))
        assertNull(vault.setProtection(SeedVault.DEVICE))
        val sealed = deviceFile.readBytes()
        val key = deviceKeys.key
        assertNull(vault.setProtection(SeedVault.NONE))
        // what a delete that did not happen leaves behind
        deviceFile.writeBytes(sealed)
        deviceKeys.key = key
        assertEquals(SeedVault.NONE, vault.protection())
        gate.asked.clear()
        assertEquals(SeedVault.Answer.Found(words), vault.read())
        assertEquals("the copy left behind the lock answers, and asks", listOf(SeedVault.OPEN_REASON to true), gate.asked)

        gate.asked.clear()
        gate.fresh.clear()
        assertNull(vault.setProtection(SeedVault.NONE))
        assertEquals("the guard comes off with a fresh ask", listOf(SeedVault.GUARD_OFF_REASON), gate.fresh)
        assertFalse(deviceFile.exists())
        assertNull(deviceKeys.key)
        assertTrue(plainFile.exists())
        gate.asked.clear()
        assertEquals(SeedVault.Answer.Found(words), vault.read())
        assertTrue("and no read asks any more", gate.asked.isEmpty())
        assertNull("a further none has nothing to do", vault.setProtection(SeedVault.NONE))
        assertTrue(gate.asked.isEmpty())
    }

    @Test
    fun aCopyLeftBehindTheLockThatDiffersIsNotRemovedBlind() {
        assertNull(vault.write(words))
        assertNull(vault.setProtection(SeedVault.DEVICE))
        val sealed = deviceFile.readBytes()
        val key = deviceKeys.key
        assertNull(vault.setProtection(SeedVault.NONE))
        assertNull(vault.write(other, replace = true) { true })
        deviceFile.writeBytes(sealed)
        deviceKeys.key = key
        assertNotNull(vault.setProtection(SeedVault.NONE))
        assertTrue("neither copy is removed", deviceFile.exists() && plainFile.exists())
        assertEquals(SeedVault.NONE, vault.protection())
    }

    @Test
    fun aDeclinedAskLeavesTheCopyBehindTheLockWhereItIs() {
        assertNull(vault.write(words))
        assertNull(vault.setProtection(SeedVault.DEVICE))
        val sealed = deviceFile.readBytes()
        val key = deviceKeys.key
        assertNull(vault.setProtection(SeedVault.NONE))
        deviceFile.writeBytes(sealed)
        deviceKeys.key = key
        gate.answer = "no"
        assertEquals(SeedVault.DECLINED, vault.setProtection(SeedVault.NONE))
        assertTrue(deviceFile.exists())
        assertTrue(plainFile.exists())
    }
}
