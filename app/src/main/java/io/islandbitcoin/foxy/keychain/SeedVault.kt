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
 * The seed at rest (Foxy/Keychain/SeedStore.swift, `SeedVault`), protection
 * `none` (P1; `device` behind BiometricPrompt is P3).
 *
 * The words, encrypted with the Keystore key, in `foxy-seed.v1.bin` under
 * `noBackupFilesDir`, written as the counter file is. A read has three answers
 * (S4): **absent** only when there is neither a file nor a key; a file with no
 * key, a key with no file, a Keystore that throws (a locked phone, a key gone
 * invalid) or a ciphertext that does not authenticate is **failed**, and failed
 * is never taken for absent: a page told "no seed" makes one, and the write that
 * follows would replace the real seed.
 *
 * Only the BIP-39 seed made from the words is kept in memory, for the visit, and
 * it goes when Foxy is put away (but for the work of leaving, 25 s at most), when
 * the page goes or loads again, and on every write (S20).
 */
class SeedVault(
    private val dir: File,
    private val keys: SeedKeys,
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
        private const val FORMAT: Byte = 1
        private const val IV_BYTES = 12
        private val AAD = "foxy.seed.v1".toByteArray(Charsets.US_ASCII)
        const val LEAVING_MS: Long = 25_000

        const val DIFFERENT_SEED = "A different seed is already saved on this phone. Foxy did not replace it."

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

    val file: File get() = File(dir, FILE_NAME)

    private val lock = Any()
    private var nativeSeed: NUT13.Seed? = null
    private var leaving: Pair<NUT13.Seed, Long>? = null
    private var epoch = 0

    // MARK: At rest

    /** Whether a seed is here at all, reading nothing: true, false, or null when the Keystore would not say. */
    fun isThere(): Boolean? = try {
        file.exists() || keys.exists()
    } catch (e: Exception) {
        Log.line("[foxy] seed: the keystore would not say whether a key is there (${e.javaClass.simpleName})")
        null
    }

    fun read(): Answer {
        val hasFile: Boolean
        val hasKey: Boolean
        try {
            hasFile = file.exists()
            hasKey = keys.exists()
        } catch (e: Exception) {
            return Answer.Failed("the keystore did not answer (${e.javaClass.simpleName})")
        }
        if (!hasFile && !hasKey) return Answer.Absent
        if (!hasKey) return Answer.Failed("the seed is here and its key is not")
        if (!hasFile) return Answer.Failed("the seed's key is here and the seed is not")
        val key = try {
            keys.get() ?: return Answer.Failed("the seed's key could not be read")
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
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 2, IV_BYTES))
            cipher.updateAAD(AAD)
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
     * A seed write (seedCreate, seedMigrate): never over a different saved seed
     * in P1 (replacing one is seedAdopt's, P3, behind a native question), never
     * blind over a vault that did not answer, and read back before it counts.
     * Null when written. The kept seed is forgotten after any attempt.
     */
    fun write(words: String, replace: Boolean = false): String? {
        try {
            when (val step = writeStep(read(), words, replace)) {
                is WriteStep.Refuse -> {
                    if (step.why == DIFFERENT_SEED) Log.line("[foxy] seed write refused: a different seed is already saved")
                    return step.why
                }
                WriteStep.AskToReplace -> return "The saved seed was not replaced."
                WriteStep.Write -> {}
            }
            val problem = seal(words)
            return writeOutcome(problem, words) { read() }
        } finally {
            forgetNativeSeed()
        }
    }

    /** Encrypted and written, then read back. Null when the words are there. */
    private fun seal(words: String): String? {
        var created = false
        val plain = words.toByteArray(Charsets.UTF_8)
        try {
            val key = keys.get() ?: keys.create().also { created = true }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(AAD)
            val iv = cipher.iv
            if (iv == null || iv.size != IV_BYTES) return "the keystore refused the write"
            val body = cipher.doFinal(plain)
            val out = ByteArray(2 + IV_BYTES + body.size)
            out[0] = FORMAT
            out[1] = IV_BYTES.toByte()
            System.arraycopy(iv, 0, out, 2, IV_BYTES)
            System.arraycopy(body, 0, out, 2 + IV_BYTES, body.size)
            AtomicWrite.write(file, out)
        } catch (e: Exception) {
            Log.line("[foxy] seed: not written (${e.javaClass.simpleName})")
            // a key made for this write, with no seed under it, would read as a failure for good
            if (created && !file.exists()) runCatching { keys.delete() }
            return "the keystore refused the write"
        } finally {
            Bytes.wipe(plain)
        }
        val back = read()
        return if (back is Answer.Found && back.words == words) null else "the seed did not persist"
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

    /** Foxy went to the background: the seed stays only for the work of leaving. */
    fun putAway(now: Long = clock()) = synchronized(lock) {
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

    /** The work of leaving is over, or Foxy is back in front. */
    fun endLeaving() = synchronized(lock) {
        if (leaving != null) Log.line("[foxy] seed: dropped — the work of being put away is over")
        leaving = null
    }

    /** The page went, or the saved seed changed: the next action reads again. */
    fun forgetNativeSeed() = synchronized(lock) {
        if (nativeSeed != null) Log.line("[foxy] seed: dropped — the page went, Foxy left, or the seed changed")
        nativeSeed = null
        leaving = null
        epoch += 1
    }

    /** Foxy is loading its page: nothing kept for the page before it carries over. */
    fun pageWillLoad() = synchronized(lock) {
        if (nativeSeed != null) Log.line("[foxy] seed: dropped — the page is loading again")
        nativeSeed = null
        leaving = null
        epoch += 1
    }
}
