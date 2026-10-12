package io.islandbitcoin.foxy.keychain

import io.islandbitcoin.foxy.debug.Log
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The key the seed is sealed with: an AES-256-GCM key in the Android Keystore
 * ([KeystoreSeedKeys]), or a plain key in the JVM tests. Every method may throw;
 * a throw is a failed read, never "no seed".
 */
interface SeedKeys {
    fun exists(): Boolean
    fun get(): SecretKey?
    fun create(): SecretKey
    fun delete()
}

/**
 * The seed at rest (Foxy/Keychain/SeedStore.swift, `SeedVault`), under one of
 * two protections, as the person chose on the SECURE FOXY card:
 *
 * - **none** (every new install, and every P1/P2 install): the words encrypted
 *   with a Keystore key that needs no one ([KeystoreSeedKeys.ALIAS]) in
 *   `foxy-seed.v1.bin`; the phone's own lock screen is what stands in front;
 * - **device** (P3): the words encrypted with a second Keystore key that needs
 *   the person's biometric or screen lock for each use
 *   ([KeystoreSeedKeys.DEVICE_ALIAS], asked through [SeedGate]) in
 *   `foxy-seed.device.v1.bin`.
 *
 * Both under `noBackupFilesDir`, written as the counter file is. Which one the
 * person chose is `foxy-seed.protection`; none when it is not there, so an
 * upgrade from P2 leaves a `none` seed exactly where it was until `seedProtect`
 * is asked. A read asks the `device` copy first and believes it, then the
 * `none` one, as iOS reads its protected item first.
 *
 * A read has three answers (S4): **absent** only when there is neither a file
 * nor a key; a file with no key, a key with no file, a Keystore that throws (a
 * locked phone, a key gone invalid, a screen lock removed under a `device` key)
 * or a ciphertext that does not authenticate is **failed**, and failed is never
 * taken for absent: a page told "no seed" makes one, and the write that follows
 * would replace the real seed. The one exception is a write or a delete cut off
 * part way: it leaves its marker ([CREATING_NAME], [DEVICE_CREATING_NAME]),
 * and a key with no file beside a marker reads absent and the orphan key is
 * removed. A key with no file and no marker stays failed.
 *
 * Moving between the protections ([setProtection]) and every write never leave
 * the phone without a seed: written where it is going, read back, and only then
 * the old copy deleted; a step that fails stops the rest, and the protection
 * recorded is put back to what it was.
 *
 * Every disk-touching call runs on the one seed executor (`foxy-seed`,
 * FoxyServices.seedExecutor): a clear on another thread could delete a key
 * while a write on the executor sits between making it and renaming its file
 * in. [onSeedThread] is called first by each and, in debug builds, checks it.
 *
 * Only the BIP-39 seed made from the words is kept in memory, for the visit, and
 * it goes when Foxy is put away (but for the work of leaving, 25 s at most), when
 * the page goes or loads again, and on every write (S20); the typed words of
 * the restore screen ([SeedCandidates], through [onForget]) go with it.
 */
class SeedVault(
    private val dir: File,
    private val keys: SeedKeys,
    private val onSeedThread: () -> Unit = {},
    /** The `device` protection's key; null where there is none (the P1 JVM tests). */
    deviceKeys: SeedKeys? = null,
    private val gate: SeedGate = SeedGate.DIRECT,
    /** A screen lock is set: without one a `device` key cannot be made or used. */
    private val passcodeSet: () -> Boolean = { true },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    sealed class Answer {
        /** The words; toString never prints them. */
        class Found(val words: String) : Answer() {
            override fun equals(other: Any?) = other is Found && other.words == words
            override fun hashCode() = words.hashCode()
            override fun toString() = "Found(<words>)"
        }

        object Absent : Answer() {
            override fun toString() = "Absent"
        }

        data class Failed(val why: String) : Answer()
    }

    sealed class SecretsSeed {
        class Found(val seed: NUT13.Seed) : SecretsSeed()
        object Absent : SecretsSeed()
        object Failed : SecretsSeed()
    }

    sealed class WriteStep {
        data class Refuse(val why: String) : WriteStep()
        object AskToReplace : WriteStep()
        object Write : WriteStep()
    }

    companion object {
        const val FILE_NAME = "foxy-seed.v1.bin"
        const val DEVICE_FILE_NAME = "foxy-seed.device.v1.bin"
        const val PROTECTION_NAME = "foxy-seed.protection"

        /**
         * Written (atomically) before a write makes the seed's key, and before a
         * delete, and removed once the file is in place (or gone). A key with no
         * file and this marker is a write cut off between the two (the process
         * died while the Keystore made the key, which takes hundreds of ms in
         * StrongBox) or a delete cut off after its file went: no seed is there
         * under it, so the orphan key is removed and the store is absent. A key
         * with no file and no marker stays failed (S4). iOS has no such window:
         * one SecItemAdd is atomic.
         */
        const val CREATING_NAME = "foxy-seed.creating"
        const val DEVICE_CREATING_NAME = "foxy-seed.device.creating"
        private const val FORMAT: Byte = 1
        private const val IV_BYTES = 12
        private val AAD = "foxy.seed.v1".toByteArray(Charsets.US_ASCII)
        private val DEVICE_AAD = "foxy.seed.device.v1".toByteArray(Charsets.US_ASCII)
        const val LEAVING_MS: Long = 25_000

        const val NONE = "none"
        const val DEVICE = "device"

        const val DIFFERENT_SEED = "A different seed is already saved on this phone. Foxy did not replace it."

        /** What the person is asked for, each a fixed wording. */
        const val OPEN_REASON = "Open your wallet"
        const val SHOW_REASON = "Show your wallet's seed"
        const val SAVE_REASON = "Save your wallet's seed"
        const val GUARD_ON_REASON = "Put your screen lock in front of your wallet"
        const val GUARD_OFF_REASON = "Take your screen lock off your wallet"

        /** A `device` read the person said no to, or could not be asked for. */
        const val DECLINED = "the screen lock was not given"
        const val UNAVAILABLE = "this phone has no biometric or screen lock to ask"

        const val NO_PASSCODE = "This phone has no screen lock, so there is no fingerprint, face or screen lock to ask for."

        /** The write rules: a vault that did not answer is never written blind; a different seed only on a yes. */
        fun writeStep(current: Answer, words: String, replace: Boolean): WriteStep = when {
            current is Answer.Failed -> WriteStep.Refuse("The keychain did not answer (${current.why}), so nothing was written.")
            current is Answer.Found && current.words != words && !replace -> WriteStep.Refuse(DIFFERENT_SEED)
            current is Answer.Found && current.words != words -> WriteStep.AskToReplace
            else -> WriteStep.Write
        }

        /** M7: a write that reported a problem is a success if the vault holds the words after all. */
        fun writeOutcome(problem: String?, words: String, readAgain: () -> Answer): String? {
            if (problem == null) return null
            val again = readAgain()
            return if (again is Answer.Found && again.words == words) null else problem
        }

        /** Whether a seed kept at `keptAt` for the work of leaving still serves it (monotonic ms). */
        fun leavingCovers(keptAt: Long, now: Long): Boolean {
            val age = now - keptAt
            return age >= 0 && age < LEAVING_MS
        }
    }

    /** For the tests of a move cut off part way: runs after the new copy is written and read back, before the old one goes. */
    @Volatile internal var afterWriteBeforeDelete: (() -> Unit)? = null

    /** For the tests of a read-back that fails: runs on the sealed bytes after they are written. */
    @Volatile internal var afterSeal: ((File) -> Unit)? = null

    /** The saved seed was written or deleted: seed screens close (SeedScreens.closeAll). Any thread. */
    @Volatile var onChanged: () -> Unit = {}

    /** The seed kept in memory went: so do the typed words, all but `kept`. Any thread. */
    @Volatile var onForget: (kept: String?) -> Unit = {}

    /**
     * One sealed copy of the words: its file, its marker, its key. The `none`
     * store is the P1 vault as it was; the `device` store is the same, with
     * every use of its key asked through [gate].
     */
    private inner class Store(
        val fileName: String,
        val creatingName: String,
        val storeKeys: SeedKeys,
        val aad: ByteArray,
        val gated: Boolean,
    ) {
        val file: File get() = File(dir, fileName)
        val creating: File get() = File(dir, creatingName)

        /** Whether anything of this copy is here. Throws when the Keystore does not answer. */
        fun present(): Boolean = file.exists() || storeKeys.exists()

        /**
         * A write cut off after its key was made and before its file was in
         * place, or a delete cut off after its file went, leaves a key, no file
         * and the marker: the key seals nothing, so it and the marker go. A
         * marker beside a file is from a write cut off after its rename, or a
         * delete cut off before its file went, and only the marker goes.
         * Throws when the Keystore does not answer.
         */
        fun clearCutOff() {
            if (!creating.exists()) return
            if (file.exists()) {
                creating.delete()
                return
            }
            if (storeKeys.exists()) {
                Log.line("[foxy] seed: a write or delete was cut off before it finished; its key is removed")
                storeKeys.delete()
            }
            creating.delete()
        }

        /** The three-way read of this copy; [present] has been asked. */
        fun read(reason: String, credential: Boolean): Answer {
            val hasFile: Boolean
            val hasKey: Boolean
            try {
                hasFile = file.exists()
                hasKey = storeKeys.exists()
            } catch (e: Exception) {
                return Answer.Failed("the keystore did not answer (${e.javaClass.simpleName})")
            }
            if (!hasFile && !hasKey) return Answer.Absent
            if (!hasKey) return Answer.Failed("the seed is here and its key is not")
            if (!hasFile) return Answer.Failed("the seed's key is here and the seed is not")
            val key = try {
                storeKeys.get() ?: return Answer.Failed("the seed's key could not be read")
            } catch (e: Exception) {
                return Answer.Failed("the keystore did not answer (${e.javaClass.simpleName})")
            }
            val sealed = try {
                file.readBytes()
            } catch (e: IOException) {
                return Answer.Failed("the seed file could not be read")
            }
            if (sealed.size < 2 + IV_BYTES + 16 || sealed[0] != FORMAT || sealed[1].toInt() != IV_BYTES) {
                return Answer.Failed("the seed file is not one this build reads")
            }
            var plain: ByteArray? = null
            try {
                val make = {
                    Cipher.getInstance("AES/GCM/NoPadding").apply {
                        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 2, IV_BYTES))
                    }
                }
                val cipher = if (!gated) make() else when (val a = gate.open(reason, credential, make)) {
                    is SeedGate.Answer.Opened -> a.cipher
                    SeedGate.Answer.No -> return Answer.Failed(DECLINED)
                    SeedGate.Answer.Unavailable -> return Answer.Failed(UNAVAILABLE)
                }
                cipher.updateAAD(aad)
                plain = cipher.doFinal(sealed, 2 + IV_BYTES, sealed.size - 2 - IV_BYTES)
                val words = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(plain)).toString()
                return Answer.Found(words)
            } catch (e: GeneralSecurityException) {
                return Answer.Failed("the seed could not be opened (${e.javaClass.simpleName})")
            } catch (e: java.nio.charset.CharacterCodingException) {
                return Answer.Failed("the seed is not text")
            } catch (e: RuntimeException) {
                return Answer.Failed("the keystore did not answer (${e.javaClass.simpleName})")
            } finally {
                Bytes.wipe(plain)
            }
        }

        /**
         * Encrypted and written, then read back. Null when the words are there.
         * The `none` copy is read back whole; the `device` copy, whose every
         * read asks the person, is read back as the bytes on the disk and its
         * key there (iOS settles for the same: its quiet read of an item behind
         * Face ID, SeedStore.swift `protect`). A wrong ciphertext would be
         * caught at the next read, which fails rather than answering absent.
         */
        fun seal(words: String, reason: String): String? {
            var created = false
            val plain = words.toByteArray(Charsets.UTF_8)
            val out: ByteArray
            try {
                val key = storeKeys.get() ?: run {
                    // the marker first: a death between the key and the file must read absent, not failed
                    AtomicWrite.write(creating, ByteArray(0))
                    created = true
                    storeKeys.create()
                }
                val make = { Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) } }
                val cipher = if (!gated) make() else when (val a = gate.open(reason, true, make)) {
                    is SeedGate.Answer.Opened -> a.cipher
                    SeedGate.Answer.No -> {
                        dropMadeKey(created)
                        return DECLINED
                    }
                    SeedGate.Answer.Unavailable -> {
                        dropMadeKey(created)
                        return UNAVAILABLE
                    }
                }
                out = sealWith(cipher, plain) ?: run {
                    dropMadeKey(created)
                    return "the keystore refused the write"
                }
                AtomicWrite.write(file, out)
                if (created) creating.delete()
            } catch (e: Exception) {
                Log.line("[foxy] seed: not written (${e.javaClass.simpleName})")
                dropMadeKey(created)
                return "the keystore refused the write"
            } finally {
                Bytes.wipe(plain)
            }
            return readBack(words, out)
        }

        private fun sealWith(cipher: Cipher, plain: ByteArray): ByteArray? {
            cipher.updateAAD(aad)
            val iv = cipher.iv
            if (iv == null || iv.size != IV_BYTES) return null
            val body = cipher.doFinal(plain)
            val out = ByteArray(2 + IV_BYTES + body.size)
            out[0] = FORMAT
            out[1] = IV_BYTES.toByte()
            System.arraycopy(iv, 0, out, 2, IV_BYTES)
            System.arraycopy(body, 0, out, 2 + IV_BYTES, body.size)
            return out
        }

        private fun dropMadeKey(created: Boolean) {
            // a key made for this write, with no seed under it, would read as a failure for good
            if (created && !file.exists()) {
                runCatching { storeKeys.delete() }
                // the marker stays if the key would not go, so the next read clears it
                if (runCatching { !storeKeys.exists() }.getOrDefault(false)) creating.delete()
            }
        }

        private fun readBack(words: String, written: ByteArray): String? {
            afterSeal?.invoke(file)
            if (!gated) {
                val back = read(reason = OPEN_REASON, credential = true)
                return if (back is Answer.Found && back.words == words) null else "the seed did not persist"
            }
            val there = try {
                file.readBytes().contentEquals(written) && storeKeys.exists()
            } catch (e: Exception) {
                false
            }
            return if (there) null else "it did not read back"
        }

        /**
         * The copy deleted: the marker first, so a delete cut off after its file
         * went reads absent, not failed; then the file, then the key. True when
         * nothing of it is left.
         */
        fun delete(): Boolean {
            return try {
                if (!file.exists() && !storeKeys.exists()) {
                    creating.delete()
                    return true
                }
                AtomicWrite.write(creating, ByteArray(0))
                file.delete()
                runCatching { storeKeys.delete() }
                val gone = !file.exists() && !storeKeys.exists()
                if (gone) creating.delete()
                gone
            } catch (e: Exception) {
                Log.line("[foxy] seed: a copy could not be deleted (${e.javaClass.simpleName})")
                false
            }
        }
    }

    private val plainStore = Store(FILE_NAME, CREATING_NAME, keys, AAD, gated = false)
    private val deviceStore: Store? = deviceKeys?.let { Store(DEVICE_FILE_NAME, DEVICE_CREATING_NAME, it, DEVICE_AAD, gated = true) }

    val file: File get() = plainStore.file
    val deviceFile: File get() = File(dir, DEVICE_FILE_NAME)
    private val protectionFile: File get() = File(dir, PROTECTION_NAME)

    private val lock = Any()
    private var nativeSeed: NUT13.Seed? = null
    private var leaving: Pair<NUT13.Seed, Long>? = null
    private var epoch = 0

    // MARK: At rest

    /**
     * Whether a seed is here at all, without opening it: true, false, or null
     * when the Keystore would not say. Not read-only: like [read], it first
     * removes a cut-off write's orphan key and marker, so it runs on the seed
     * executor only, as [write] does.
     */
    fun isThere(): Boolean? {
        onSeedThread()
        return try {
            deviceStore?.clearCutOff()
            plainStore.clearCutOff()
            deviceStore?.present() == true || plainStore.present()
        } catch (e: Exception) {
            Log.line("[foxy] seed: the keystore would not say whether a key is there (${e.javaClass.simpleName})")
            null
        }
    }

    /**
     * The three-way read (S4): the `device` copy if anything of it is here
     * (which asks the person, with `reason`), else the `none` copy. Not
     * read-only: it first removes a cut-off write's orphan key and marker, so it
     * runs on the seed executor only.
     */
    fun read(reason: String = OPEN_REASON): Answer {
        onSeedThread()
        return readHere(reason, credential = true)
    }

    private fun readHere(reason: String, credential: Boolean): Answer {
        val devicePresent: Boolean
        try {
            deviceStore?.clearCutOff()
            plainStore.clearCutOff()
            devicePresent = deviceStore?.present() == true
        } catch (e: Exception) {
            return Answer.Failed("the keystore did not answer (${e.javaClass.simpleName})")
        }
        if (devicePresent) return deviceStore!!.read(reason, credential)
        return plainStore.read(reason, credential)
    }

    /** The words for Foxy's own seed screen (seedShow), and only for it: `device` always asks. */
    fun wordsForScreen(): Answer = read(SHOW_REASON)

    /**
     * A seed write (seedCreate, seedMigrate, seedAdopt): never over a different
     * saved seed unless `replace` and `confirmReplace` says yes (the Replace
     * alert, then the device owner), never blind over a vault that did not
     * answer, and read back before it counts. Written under the protection the
     * person chose (`device` only on a phone with a screen lock); the other
     * copy, if any, goes once the new one is in. Null when written. The kept
     * seed and every typed word but `keepCandidate` are forgotten after any
     * attempt.
     */
    fun write(words: String, replace: Boolean = false, keepCandidate: String? = null, confirmReplace: () -> Boolean = { false }): String? {
        try {
            when (val step = keptStep(words, replace) ?: writeStep(read(SAVE_REASON), words, replace)) {
                is WriteStep.Refuse -> {
                    if (step.why == DIFFERENT_SEED) Log.line("[foxy] seed write refused: a different seed is already saved")
                    return step.why
                }
                WriteStep.AskToReplace -> if (!confirmReplace()) {
                    Log.line("[foxy] seed replace declined")
                    return "The saved seed was not replaced."
                }
                WriteStep.Write -> {}
            }
            val toDevice = deviceStore != null && protection() == DEVICE && passcodeSet()
            val attempt = if (toDevice) protect(words, SAVE_REASON) else writePlain(words)
            // secrets from the words replaced would be the old wallet's, and a write that failed may still have landed
            forgetNativeSeed(keepCandidate)
            val problem = writeOutcome(attempt, words) { read(SAVE_REASON) }
            if (attempt != null && problem == null) Log.line("[foxy] seed write reported a problem ($attempt), but the vault holds the new words")
            if (problem == null) onChanged()
            return problem
        } finally {
            forgetNativeSeed(keepCandidate)
        }
    }

    /**
     * The write rules against the seed this visit already read, when one is
     * kept: it is the saved seed (any write, delete or move forgets it), so a
     * `device` seed is not read again, which would ask the person a second
     * time. Null when nothing is kept, and the vault is read.
     */
    private fun keptStep(words: String, replace: Boolean): WriteStep? {
        val seed = kept() ?: return null
        val typed = try {
            NUT13.seed(words)
        } catch (e: NUT13.Failure) {
            return null
        }
        return try {
            when {
                seed.sameBytes(typed) -> WriteStep.Write
                !replace -> WriteStep.Refuse(DIFFERENT_SEED)
                else -> WriteStep.AskToReplace
            }
        } finally {
            typed.wipe()
        }
    }

    /** The `device` copy written and read back, and only then the `none` copy deleted. Null when done. */
    private fun protect(words: String, reason: String): String? {
        val device = deviceStore ?: return "this phone cannot keep the seed behind its screen lock"
        device.seal(words, reason)?.let { problem ->
            // a copy that did not read back must not shadow the good one outside the lock (read believes the device copy)
            val outside = try {
                plainStore.present()
            } catch (e: Exception) {
                false
            }
            if (outside) device.delete()
            return problem
        }
        afterWriteBeforeDelete?.invoke()
        if (!plainStore.delete()) {
            // the same words in both places: read believes the device copy, and the next write removes it
            Log.line("[foxy] seed: the copy outside the screen lock could not be removed; it holds the same words")
        }
        return null
    }

    /** The `none` copy, and nothing left behind the screen lock: a copy there would go on answering with the old words. */
    private fun writePlain(words: String): String? {
        plainStore.seal(words, SAVE_REASON)?.let { return it }
        val device = deviceStore ?: return null
        val there = try {
            device.present()
        } catch (e: Exception) {
            true
        }
        if (!there) return null
        afterWriteBeforeDelete?.invoke()
        if (!device.delete()) {
            return "an older copy of the seed behind the screen lock could not be removed, so the saved seed was not changed"
        }
        return null
    }

    /**
     * Every copy of the seed deleted, and true only when none is left: each
     * delete is checked by asking, without reading, whether anything of it is
     * still there (review L5). seedWipe, after both of the person's yeses.
     */
    fun delete(): Boolean {
        onSeedThread()
        forgetNativeSeed()
        val deviceGone = deviceStore?.delete() ?: true
        val plainGone = plainStore.delete()
        val gone = deviceGone && plainGone && try {
            deviceStore?.present() != true && !plainStore.present()
        } catch (e: Exception) {
            false
        }
        if (!gone) {
            Log.line("[foxy] seed delete: the seed is still there")
            return false
        }
        onChanged()
        return true
    }

    // MARK: What stands in front of the seed

    /** How the person asked for the seed to be kept: [DEVICE] or [NONE]; none when nothing was ever chosen. */
    fun protection(): String = try {
        if (protectionFile.exists() && protectionFile.readText(Charsets.UTF_8).trim() == DEVICE) DEVICE else NONE
    } catch (e: IOException) {
        NONE
    }

    private fun recordProtection(mode: String): String? = try {
        AtomicWrite.write(protectionFile, mode.toByteArray(Charsets.US_ASCII))
        null
    } catch (e: IOException) {
        "the choice could not be saved"
    }

    /**
     * Move the saved seed to `mode` ([DEVICE] or [NONE]), or say why it did
     * not (SeedStore.swift `setProtection`). The seed is read first, written
     * where it is going, read back, and only then the copy it came from is
     * deleted. Going to none reads it from behind the screen lock, so the
     * person proves the phone is theirs before the guard comes off; that read is
     * never through an earlier unlock (each use of the `device` key asks).
     * Going to device asks them as the key is first used. On a failure the
     * protection recorded is what it was.
     */
    fun setProtection(mode: String): String? {
        onSeedThread()
        require(mode == DEVICE || mode == NONE) { "not a protection" }
        val current = protection()
        if (mode == NONE && current == NONE) return null
        val device = deviceStore ?: return "this phone cannot keep the seed behind its screen lock"
        if (mode == DEVICE && !passcodeSet()) return NO_PASSCODE
        if (mode == DEVICE && current == DEVICE) {
            // already where it should be, unless a copy is still outside the lock (a write made with no screen lock)
            val outside = try {
                plainStore.present()
            } catch (e: Exception) {
                true
            }
            if (!outside) return null
        }
        val reason = if (mode == NONE) GUARD_OFF_REASON else GUARD_ON_REASON
        val words = when (val saved = readHere(reason, credential = true)) {
            is Answer.Found -> saved.words
            // nothing saved yet: the choice still stands for the seed to come
            Answer.Absent -> return recordProtection(mode)
            is Answer.Failed -> return saved.why
        }
        if (mode == DEVICE) {
            // put back what it was on a failure, never NONE blind: two seeds would follow (SeedStore.swift:320-327)
            recordProtection(DEVICE)?.let { return it }
            protect(words, reason)?.let { problem ->
                recordProtection(current)
                return problem
            }
        } else {
            plainStore.seal(words, reason)?.let { return it }
            afterWriteBeforeDelete?.invoke()
            recordProtection(NONE)?.let { return it }
            if (!device.delete()) {
                // the same words in both places: harmless until a write, which removes it (writePlain)
                Log.line("[foxy] seed: the copy behind the screen lock could not be removed; it holds the same words")
            }
        }
        forgetNativeSeed()
        Log.line("[foxy] seed: now kept with protection $mode")
        return null
    }

    /**
     * The PIN lock's biometric, when the seed is behind the screen lock and
     * nothing is kept: the prompt is the seed's own (its key's CryptoObject), so
     * the yes opens the seed too and the counter read that follows asks nothing
     * more (FoxyBridge.swift `handleBiometric`, "a yes is remembered"). "yes",
     * "no" or "unavailable"; null when there is no such seed, and the bridge
     * asks a prompt of its own.
     */
    fun unlock(reason: String, credential: Boolean): String? {
        onSeedThread()
        val device = deviceStore ?: return null
        if (kept() != null) return null
        val at = epochNow
        try {
            device.clearCutOff()
            if (!device.present()) return null
        } catch (e: Exception) {
            return null
        }
        return when (val answer = device.read(reason, credential)) {
            is Answer.Found -> {
                val seed = try {
                    NUT13.seed(answer.words)
                } catch (e: NUT13.Failure) {
                    return "yes"
                }
                keep(seed, at)
                "yes"
            }
            Answer.Absent -> null
            is Answer.Failed -> if (answer.why == UNAVAILABLE) "unavailable" else "no"
        }
    }

    // MARK: In memory

    /** The seed for the counter, restore and P2PK actions: kept from this visit, else read and kept. */
    fun seedForSecrets(): SecretsSeed {
        kept()?.let { return SecretsSeed.Found(it) }
        val at = epochNow
        return when (val answer = read()) {
            Answer.Absent -> SecretsSeed.Absent
            is Answer.Failed -> {
                Log.line("[foxy] seed: could not be read (${answer.why})")
                SecretsSeed.Failed
            }
            is Answer.Found -> {
                val seed = try {
                    NUT13.seed(answer.words)
                } catch (e: NUT13.Failure) {
                    // saved words that are not a mnemonic cannot be read as a seed
                    return SecretsSeed.Failed
                }
                keep(seed, at)
                SecretsSeed.Found(seed)
            }
        }
    }

    val epochNow: Int get() = synchronized(lock) { epoch }

    /** A seed just read, kept unless something forgot the seed while it was read. */
    fun keep(seed: NUT13.Seed, readAt: Int) = synchronized(lock) {
        if (readAt == epoch) nativeSeed = seed
    }

    /** The visit's seed, or the one kept for the work of leaving while that still holds. */
    fun kept(now: Long = clock()): NUT13.Seed? = synchronized(lock) {
        nativeSeed?.let { return@synchronized it }
        val last = leaving ?: return@synchronized null
        if (leavingCovers(last.second, now)) return@synchronized last.first
        leaving = null
        Log.line("[foxy] seed: dropped — kept for being put away, and out of time")
        null
    }

    /** Foxy went to the background: the seed stays only for the work of leaving; typed words go now. */
    fun putAway(now: Long = clock()) {
        synchronized(lock) {
            val seed = nativeSeed
            leaving = if (seed != null) {
                Log.line("[foxy] seed: kept for the work of being put away, ${LEAVING_MS / 1000}s at most; a return asks again")
                seed to now
            } else {
                null
            }
            nativeSeed = null
            epoch += 1
        }
        onForget(null)
    }

    /** The work of leaving is over, or Foxy is back in front. */
    fun endLeaving() = synchronized(lock) {
        if (leaving != null) Log.line("[foxy] seed: dropped — the work of being put away is over")
        leaving = null
    }

    /** The page went, or the saved seed changed: the next action reads again. Typed words go too. */
    fun forgetNativeSeed() = forgetNativeSeed(null)

    /** As [forgetNativeSeed], keeping the typed words of `keepingCandidate` (a seed write that adopted them, M6). */
    fun forgetNativeSeed(keepingCandidate: String?) {
        synchronized(lock) {
            if (nativeSeed != null) Log.line("[foxy] seed: dropped — the page went, Foxy left, or the seed changed")
            nativeSeed = null
            leaving = null
            epoch += 1
        }
        onForget(keepingCandidate)
    }

    /** Foxy is loading its page: nothing kept for the page before it carries over. */
    fun pageWillLoad() {
        synchronized(lock) {
            if (nativeSeed != null) Log.line("[foxy] seed: dropped — the page is loading again")
            nativeSeed = null
            leaving = null
            epoch += 1
        }
        onForget(null)
    }
}
