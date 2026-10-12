package io.islandbitcoin.foxy.keychain

import fr.acinq.secp256k1.Secp256k1
import java.security.MessageDigest
import java.text.Normalizer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * NUT-13 deterministic secrets, derived natively: the port of
 * Foxy/Keychain/NUT13.swift.
 *
 * For SeedBridge's `counterReserve`, `counterReserveAt` and `restoreSecrets`:
 * the page builds outputs without holding the seed. The bytes must be cashu-ts
 * 4.10.1's, byte for byte, or a restore in another wallet finds nothing:
 * NUT13Test checks the spec's vectors and the bundled cashu-ts's own answers
 * (the iOS fixtures, app/src/test/resources).
 *
 * The elliptic-curve step is libsecp256k1's, through secp256k1-kmp; never ours.
 * HMAC is the platform's JCA. PBKDF2-HMAC-SHA512 is written out over that HMAC
 * so the password is bytes this file can wipe, not a String the provider
 * converts with a charset of its choosing.
 */
object NUT13 {
    sealed class Failure(message: String) : Exception(message) {
        /** Not a hex id starting 00 (16 characters) or 01 (66). */
        class BadKeysetId : Failure("bad keyset id")
        /** Outside 0 ..< 2^31 for a 00 keyset, or past 2^53 − 1 for a 01 one. */
        class BadCounter : Failure("bad counter")
        /** Not 12, 15, 18, 21 or 24 words, as @scure/bip39 requires. */
        class BadMnemonic : Failure("bad mnemonic")
        /** A BIP-32 child, or an HMAC blinding factor, that is not a valid key. About 1 in 2^127 per step. */
        class InvalidKey : Failure("invalid key")
        /** The platform's crypto failed. */
        class Platform : Failure("platform")
    }

    /** One counter's secret and blinding factor, 32 bytes each. */
    class Pair(val secret: ByteArray, val blindingFactor: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Pair && secret.contentEquals(other.secret) && blindingFactor.contentEquals(other.blindingFactor)

        override fun hashCode(): Int = secret.contentHashCode() * 31 + blindingFactor.contentHashCode()
    }

    const val LAST_BIP32_COUNTER: Long = 0x7FFF_FFFFL
    const val LAST_HMAC_COUNTER: Long = (1L shl 53) - 1

    /** secp256k1's group order n, big-endian. */
    val ORDER: ByteArray = Bytes.fromHex("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141")!!

    /** The BIP-39 seed (64 bytes from words; BIP-32's own vectors use 16). [wipe] zeroes it. */
    class Seed(bytes: ByteArray) {
        private val key: ByteArray = bytes.copyOf()

        internal fun bytes(): ByteArray = key

        fun hex(): String = Bytes.hex(key)

        /** Constant-time comparison of two seeds. */
        fun sameBytes(other: Seed): Boolean = MessageDigest.isEqual(key, other.key)

        fun wipe() = key.fill(0)
    }

    /**
     * BIP-39's `mnemonicToSeedSync(words)`: PBKDF2-HMAC-SHA512, 2048 rounds,
     * salt "mnemonic" (no passphrase), over the NFKD form of the words. Like
     * @scure/bip39 it splits on single spaces and checks only the word count.
     */
    fun seed(mnemonic: String): Seed {
        val nfkd = Normalizer.normalize(mnemonic, Normalizer.Form.NFKD)
        val words = nfkd.split(" ").size
        if (words !in setOf(12, 15, 18, 21, 24)) throw Failure.BadMnemonic()
        val password = nfkd.toByteArray(Charsets.UTF_8)
        val derived = pbkdf2Sha512(password, "mnemonic".toByteArray(Charsets.UTF_8), 2048)
        try {
            return Seed(derived)
        } finally {
            Bytes.wipe(password)
            Bytes.wipe(derived)
        }
    }

    /** PBKDF2 (RFC 8018) with HMAC-SHA512, one 64-byte block: all a BIP-39 seed needs. */
    internal fun pbkdf2Sha512(password: ByteArray, salt: ByteArray, rounds: Int): ByteArray {
        // a mnemonic of twelve words or more is never an empty password, which SecretKeySpec refuses
        val mac = try {
            Mac.getInstance("HmacSHA512").apply { init(SecretKeySpec(password, "HmacSHA512")) }
        } catch (e: Exception) {
            throw Failure.Platform()
        }
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        var u = mac.doFinal()
        val out = u.copyOf()
        for (r in 1 until rounds) {
            val next = mac.doFinal(u)
            Bytes.wipe(u)
            u = next
            for (i in out.indices) out[i] = (out[i].toInt() xor u[i].toInt()).toByte()
        }
        Bytes.wipe(u)
        return out
    }

    // MARK: Requests

    /** A keyset id this derives for: hex, "00" and 16 characters, or "01" and 66. */
    fun keysetVersion(keysetId: String): Int? {
        if (!keysetId.all { Bytes.isHexChar(it) }) return null
        if (keysetId.length == 16 && keysetId.startsWith("00")) return 0
        if (keysetId.length == 66 && keysetId.startsWith("01")) return 1
        return null
    }

    /** A `00` keyset's BIP-32 index: the id read as a number, mod 2^31 − 1. */
    fun derivationIndex(keysetId: String): Long? {
        if (keysetVersion(keysetId) != 0) return null
        val number = java.lang.Long.parseUnsignedLong(keysetId, 16)
        return java.lang.Long.remainderUnsigned(number, 0x7FFF_FFFFL)
    }

    /** Whether every counter from `start` to `start + count − 1` exists for this version. */
    fun countersFit(version: Int, start: Long, count: Int): Boolean {
        if (count < 1 || start < 0) return false
        val last = if (version == 0) LAST_BIP32_COUNTER else LAST_HMAC_COUNTER
        val end = Bytes.add(start, (count - 1).toLong()) ?: return false
        return end <= last
    }

    /** Secrets and blinding factors for counters start ..< start + count. */
    fun derive(seed: Seed, keysetId: String, start: Long, count: Int): List<Pair> {
        val version = keysetVersion(keysetId) ?: throw Failure.BadKeysetId()
        if (!countersFit(version, start, count)) throw Failure.BadCounter()
        if (version == 1) {
            val id = Bytes.fromHex(keysetId) ?: throw Failure.BadKeysetId()
            return (0 until count).map { hmacPair(seed, id, start + it) }
        }
        val keysetInt = derivationIndex(keysetId) ?: throw Failure.BadKeysetId()
        // m/129372'/0'/{keysetIdInt}' is the same for every counter: walked once
        var parent = master(seed)
        try {
            for (index in longArrayOf(129372, 0, keysetInt)) {
                val next = child(parent, index or 0x8000_0000L)
                parent.wipe()
                parent = next
            }
            return (0 until count).map { offset ->
                val base = child(parent, (start + offset) or 0x8000_0000L)
                try {
                    // children 0 and 1 hash the same public key: computed once
                    val point = publicKey(base.key)
                    val secret = child(base, 0, point)
                    val blinding = child(base, 1, point)
                    // the keys are the answer; the chain codes are not, and go now
                    Bytes.wipe(secret.chain)
                    Bytes.wipe(blinding.chain)
                    Pair(secret.key, blinding.key)
                } finally {
                    base.wipe()
                }
            }
        } finally {
            parent.wipe()
        }
    }

    /** counterReserve's and restoreSecrets' answer: one JSON object, hex lowercase, in counter order. */
    fun reply(keysetId: String, start: Long, pairs: List<Pair>): String {
        val secrets = pairs.joinToString(",") { "\"" + Bytes.hex(it.secret) + "\"" }
        val factors = pairs.joinToString(",") { "\"" + Bytes.hex(it.blindingFactor) + "\"" }
        // keysetId has passed keysetVersion: hex digits only, nothing to escape
        return "{\"keysetId\":\"$keysetId\",\"start\":$start,\"secrets\":[$secrets],\"blindingFactors\":[$factors]}"
    }

    // MARK: Keyset version 01: HMAC-SHA256

    /**
     * HMAC-SHA256 keyed with the seed, over "Cashu_KDF_HMAC_SHA256" || keyset id
     * bytes || counter (u64 big-endian) || 0x00 for the secret or 0x01 for the
     * blinding factor, which is reduced mod n by one subtraction.
     */
    fun hmacPair(seed: Seed, keysetId: ByteArray, counter: Long): Pair {
        val label = "Cashu_KDF_HMAC_SHA256".toByteArray(Charsets.US_ASCII)
        val message = ByteArray(label.size + keysetId.size + 9)
        System.arraycopy(label, 0, message, 0, label.size)
        System.arraycopy(keysetId, 0, message, label.size, keysetId.size)
        for (k in 0 until 8) message[label.size + keysetId.size + k] = (counter ushr (8 * (7 - k))).toByte()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(seed.bytes(), "HmacSHA256"))
        message[message.size - 1] = 0x00
        val secret = mac.doFinal(message)
        message[message.size - 1] = 0x01
        val digest = mac.doFinal(message)
        try {
            val blinding = reducedOnce(digest)
            // cashu-ts refuses r = 0 too
            if (Bytes.isZero(blinding)) throw Failure.InvalidKey()
            return Pair(secret, blinding)
        } finally {
            Bytes.wipe(digest)
        }
    }

    /** x mod n for a 256-bit x below 2n: x − n when x ≥ n, else x, picked with a mask. */
    fun reducedOnce(x: ByteArray): ByteArray = reducedOnce(x, ORDER)

    fun reducedOnce(x: ByteArray, order: ByteArray): ByteArray {
        require(x.size == 32 && order.size == 32)
        val difference = ByteArray(32)
        var borrow = 0
        for (i in 31 downTo 0) {
            val d = (x[i].toInt() and 0xff) - (order[i].toInt() and 0xff) - borrow
            difference[i] = d.toByte()
            borrow = if (d < 0) 1 else 0
        }
        // a borrow out of the top byte means x < n: keep x
        val keep = (0 - borrow) and 0xff
        return ByteArray(32) { ((x[it].toInt() and keep) or (difference[it].toInt() and keep.inv() and 0xff)).toByte() }
    }

    // MARK: Keyset version 00: BIP-32

    class ExtendedKey(val key: ByteArray, val chain: ByteArray) {
        fun wipe() {
            Bytes.wipe(key)
            Bytes.wipe(chain)
        }
    }

    /** BIP-32's master key: HMAC-SHA512 keyed "Bitcoin seed" over the seed. */
    fun master(seed: Seed): ExtendedKey {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec("Bitcoin seed".toByteArray(Charsets.US_ASCII), "HmacSHA512"))
        val out = mac.doFinal(seed.bytes())
        val master = ExtendedKey(out.copyOfRange(0, 32), out.copyOfRange(32, 64))
        Bytes.wipe(out)
        // BIP-32: a master key of 0 or ≥ n is invalid
        if (!verify(master.key)) {
            master.wipe()
            throw Failure.InvalidKey()
        }
        return master
    }

    /** BIP-32's CKDpriv. A hardened index hashes 0x00 || k; a normal one the compressed public key of k. */
    fun child(parent: ExtendedKey, index: Long, publicKey: ByteArray? = null): ExtendedKey {
        require(index in 0..0xFFFF_FFFFL)
        val data = ByteArray(37)
        val head: Int
        if (index and 0x8000_0000L != 0L) {
            data[0] = 0
            System.arraycopy(parent.key, 0, data, 1, 32)
            head = 33
        } else {
            val point = publicKey ?: publicKey(parent.key)
            System.arraycopy(point, 0, data, 0, 33)
            head = 33
        }
        data[head] = (index ushr 24).toByte()
        data[head + 1] = (index ushr 16).toByte()
        data[head + 2] = (index ushr 8).toByte()
        data[head + 3] = index.toByte()
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(parent.chain, "HmacSHA512"))
        val i = mac.doFinal(data)
        val tweak = i.copyOfRange(0, 32)
        try {
            // BIP-32: IL ≥ n makes this child invalid. IL = 0 is let through, as iOS lets it (1 in 2^256).
            if (!verify(tweak) && !Bytes.isZero(tweak)) throw Failure.InvalidKey()
            val key = try {
                Secp256k1.privKeyTweakAdd(parent.key, tweak)
            } catch (e: Exception) {
                // fails only when k + IL ≡ 0 mod n, which BIP-32 also calls invalid
                throw Failure.InvalidKey()
            }
            return ExtendedKey(key, i.copyOfRange(32, 64))
        } finally {
            Bytes.wipe(data)
            Bytes.wipe(i)
            Bytes.wipe(tweak)
        }
    }

    private fun verify(key: ByteArray): Boolean = try {
        Secp256k1.secKeyVerify(key)
    } catch (e: Exception) {
        false
    }

    /** The 33-byte compressed public key of a secret key. */
    fun publicKey(key: ByteArray): ByteArray {
        val full = try {
            Secp256k1.pubkeyCreate(key)
        } catch (e: Exception) {
            throw Failure.InvalidKey()
        }
        val compressed = try {
            Secp256k1.pubKeyCompress(full)
        } catch (e: Exception) {
            throw Failure.Platform()
        }
        if (compressed.size != 33) throw Failure.Platform()
        return compressed
    }
}
