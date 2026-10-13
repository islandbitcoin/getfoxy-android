package io.islandbitcoin.foxy.nostr

import fr.acinq.secp256k1.Secp256k1
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The cryptography a Nostr private message needs: NIP-44 v2, and the keys and
 * signatures of NIP-01 (Foxy/Nostr/NostrCrypto.swift at 8df0bb1).
 *
 * Foxy speaks Nostr for one thing: Cashu payment requests (NUT-18) paid over
 * a NIP-17 message. It never holds a Nostr identity: every payment is sealed
 * and wrapped with keys made for that payment and forgotten after, and every
 * key a request listens on is made for that request ([NostrInbox]).
 *
 * The curve is libsecp256k1's, through secp256k1-kmp, the binding the app
 * already carries for NUT-13. HMAC-SHA256 and SHA-256 are the platform's
 * (javax.crypto, java.security). HKDF is the forty lines of RFC 5869 over that
 * HMAC. ChaCha20 is [ChaCha20], below, as iOS has it: see there for why not the
 * platform's. Everything is checked against the specs' own vectors
 * (NostrCryptoTest): RFC 8439 for ChaCha20 and fixtures/nip44.vectors.json, the
 * NIP-44 reference vectors, for the rest.
 */
object NostrCrypto {
    /** What went wrong (NostrCrypto.swift `Failure`). */
    class Failure(val kind: Kind) : Exception(kind.name) {
        enum class Kind { BAD_KEY, BAD_PEER, TOO_LONG, PLATFORM }
    }

    private val random = SecureRandom()

    private val curve: Secp256k1 get() = Secp256k1.get()

    // MARK: Keys

    /** A private key that is a valid secp256k1 scalar, from the system's random source. */
    fun newKey(): ByteArray {
        repeat(64) {
            val key = ByteArray(32)
            random.nextBytes(key)
            if (secKeyValid(key)) return key
        }
        throw Failure(Failure.Kind.PLATFORM)
    }

    private fun secKeyValid(key: ByteArray): Boolean = try {
        key.size == 32 && curve.secKeyVerify(key)
    } catch (e: Exception) {
        false
    }

    /** The 32-byte x-only public key for a private key: Nostr's "pubkey". */
    fun publicKey(secret: ByteArray): ByteArray {
        if (secret.size != 32) throw Failure(Failure.Kind.PLATFORM)
        if (!secKeyValid(secret)) throw Failure(Failure.Kind.BAD_KEY)
        val full = try {
            curve.pubkeyCreate(secret)
        } catch (e: Exception) {
            throw Failure(Failure.Kind.BAD_KEY)
        }
        // uncompressed: 0x04, x, y
        if (full.size != 65) throw Failure(Failure.Kind.PLATFORM)
        return full.copyOfRange(1, 33)
    }

    /**
     * BIP-340 over a 32-byte digest (a Nostr event id), with fresh auxiliary
     * randomness, checked before it is used, as iOS checks it.
     */
    fun sign(digest: ByteArray, secret: ByteArray): ByteArray {
        if (digest.size != 32 || secret.size != 32) throw Failure(Failure.Kind.PLATFORM)
        val aux = ByteArray(32).also { random.nextBytes(it) }
        val signature = try {
            curve.signSchnorr(digest, secret, aux)
        } catch (e: Exception) {
            throw Failure(Failure.Kind.BAD_KEY)
        }
        if (signature.size != 64 || !verify(signature, digest, publicKey(secret))) throw Failure(Failure.Kind.BAD_KEY)
        return signature
    }

    /** Whether a BIP-340 signature holds for a digest and an x-only key. */
    fun verify(signature: ByteArray, digest: ByteArray, pubkey: ByteArray): Boolean {
        if (signature.size != 64 || digest.size != 32 || pubkey.size != 32) return false
        return try {
            curve.verifySchnorr(signature, digest, pubkey)
        } catch (e: Exception) {
            false
        }
    }

    // MARK: NIP-44 v2

    /**
     * The conversation key two keys share: HKDF-extract over the x of a·B, with
     * "nip44-v2" as the salt. The x-only peer is lifted to even Y (the x of a·P
     * does not depend on that choice); a·P is libsecp256k1's tweak-multiply, which
     * refuses a scalar of 0 or at least n, as `secp256k1_ecdh` does on iOS.
     */
    fun conversationKey(secret: ByteArray, peer: ByteArray): ByteArray {
        if (secret.size != 32 || peer.size != 32) throw Failure(Failure.Kind.PLATFORM)
        val point = try {
            curve.pubkeyParse(byteArrayOf(0x02) + peer)
        } catch (e: Exception) {
            throw Failure(Failure.Kind.BAD_PEER)
        }
        if (!secKeyValid(secret)) throw Failure(Failure.Kind.BAD_KEY)
        val shared = try {
            curve.pubKeyTweakMul(point, secret)
        } catch (e: Exception) {
            throw Failure(Failure.Kind.BAD_KEY)
        }
        if (shared.size != 65) throw Failure(Failure.Kind.PLATFORM)
        val x = shared.copyOfRange(1, 33)
        try {
            return Hkdf.extract("nip44-v2".toByteArray(Charsets.UTF_8), x)
        } finally {
            x.fill(0)
            shared.fill(0)
        }
    }

    /**
     * The largest plaintext NIP-44 v2 takes here. The spec now allows more with a
     * longer length prefix, but the wallets Foxy delivers to (cashu.me's
     * nostr-tools) still refuse anything over this, and a payment is a few kB.
     */
    const val LONGEST_MESSAGE = 65535

    /**
     * A NIP-44 v2 payload: base64 of 0x02, the nonce, the ciphertext and the MAC.
     * `nonce` is for the vectors; it is fresh randomness otherwise.
     */
    fun encrypt(plaintext: ByteArray, conversationKey: ByteArray, nonce: ByteArray? = null): String {
        if (conversationKey.size != 32) throw Failure(Failure.Kind.BAD_KEY)
        if (plaintext.isEmpty() || plaintext.size > LONGEST_MESSAGE) throw Failure(Failure.Kind.TOO_LONG)
        val seed = nonce?.copyOf() ?: ByteArray(32).also { random.nextBytes(it) }
        if (seed.size != 32) throw Failure(Failure.Kind.BAD_KEY)
        val keys = messageKeys(conversationKey, seed)
        val ciphertext = ChaCha20.apply(pad(plaintext), keys.chacha, keys.nonce)
        val mac = hmac(keys.hmac, seed + ciphertext)
        return Base64.getEncoder().encodeToString(byteArrayOf(0x02) + seed + ciphertext + mac)
    }

    /**
     * The plaintext of a payload, or null when anything about it is wrong: the
     * version, its shape, the base64, the MAC, or the padding.
     */
    fun decrypt(payload: String, conversationKey: ByteArray): ByteArray? {
        if (conversationKey.size != 32 || payload.startsWith("#")) return null
        if (payload.toByteArray(Charsets.UTF_8).size < 132) return null
        val raw = base64(payload) ?: return null
        if (raw.size < 99 || raw.size > 0xFFFF + 132 || raw[0] != 0x02.toByte()) return null
        val nonce = raw.copyOfRange(1, 33)
        val ciphertext = raw.copyOfRange(33, raw.size - 32)
        val mac = raw.copyOfRange(raw.size - 32, raw.size)
        val keys = messageKeys(conversationKey, nonce)
        if (!MessageDigest.isEqual(hmac(keys.hmac, nonce + ciphertext), mac)) return null
        val padded = ChaCha20.apply(ciphertext, keys.chacha, keys.nonce)
        if (padded.size < 2) return null
        val length = (padded[0].toInt() and 0xff) shl 8 or (padded[1].toInt() and 0xff)
        if (length <= 0 || padded.size != 2 + paddedLength(length)) return null
        return padded.copyOfRange(2, 2 + length)
    }

    /**
     * Base64 as Foundation's `Data(base64Encoded:)` reads it: the standard
     * alphabet, padded to a multiple of four, nothing else. Java's decoder takes
     * a missing pad, which Foundation does not.
     */
    private fun base64(text: String): ByteArray? {
        if (text.length % 4 != 0) return null
        return try {
            Base64.getDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** The cipher's key and nonce, and the MAC's key: HKDF-expand of the conversation key over the nonce. */
    class MessageKeys(val chacha: ByteArray, val nonce: ByteArray, val hmac: ByteArray)

    fun messageKeys(conversationKey: ByteArray, nonce: ByteArray): MessageKeys {
        val bytes = Hkdf.expand(conversationKey, nonce, 76)
        return MessageKeys(bytes.copyOfRange(0, 32), bytes.copyOfRange(32, 44), bytes.copyOfRange(44, 76))
    }

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    /** NIP-44's padding: the next step up, so messages of a size all look alike. */
    fun paddedLength(length: Int): Int {
        if (length <= 32) return 32
        // 1 << (floor(log2(length - 1)) + 1), in integers
        val nextPower = Integer.highestOneBit(length - 1) shl 1
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * ((length - 1) / chunk + 1)
    }

    /** The length in two bytes, the message, then zeros up to [paddedLength]. */
    fun pad(message: ByteArray): ByteArray {
        val total = paddedLength(message.size)
        val out = ByteArray(2 + total)
        out[0] = (message.size ushr 8).toByte()
        out[1] = (message.size and 0xff).toByte()
        message.copyInto(out, 2)
        return out
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
}

/** HKDF with HMAC-SHA256 (RFC 5869), the two halves NIP-44 uses separately. */
internal object Hkdf {
    private fun mac(key: ByteArray): Mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }

    /** PRK = HMAC(salt, IKM). */
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray = mac(salt).doFinal(ikm)

    /** T(1) | T(2) | ... with T(i) = HMAC(PRK, T(i-1) | info | i), cut to `length`. */
    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32)
        val m = mac(prk)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var at = 0
        var i = 1
        while (at < length) {
            m.update(t)
            m.update(info)
            m.update(i.toByte())
            t = m.doFinal()
            val n = minOf(t.size, length - at)
            t.copyInto(out, at, 0, n)
            at += n
            i++
        }
        return out
    }
}

/**
 * ChaCha20 as RFC 8439 §2.4 states it: a 32-byte key, a 12-byte nonce and a
 * 32-bit block counter, counter 0 first (NostrCrypto.swift `ChaCha20`).
 *
 * Not the platform's. Android lists "ChaCha20/NONE/NoPadding" from API 28
 * (Conscrypt), set up with the nonce as an IvParameterSpec; the JVM the unit
 * tests run on (SunJCE, JDK 17) refuses that ("ChaCha20 algorithm requires
 * ChaCha20ParameterSpec"), and neither lets the caller choose the block counter
 * RFC 8439's vectors start at. The cipher that ran on the phone would then be
 * one no JVM test could run, its vectors checkable only on a device. This is
 * sixty lines, the RFC's own vectors check it (NostrCryptoTest), and it is the
 * same code on the phone as under test.
 */
object ChaCha20 {
    fun apply(input: ByteArray, key: ByteArray, nonce: ByteArray, counter: Int = 0): ByteArray {
        if (key.size != 32 || nonce.size != 12) return ByteArray(0)
        val out = ByteArray(input.size)
        var at = 0
        var block = counter
        while (at < input.size) {
            val stream = keystream(key, nonce, block)
            val n = minOf(64, input.size - at)
            for (i in 0 until n) out[at + i] = (input[at + i].toInt() xor stream[i].toInt()).toByte()
            at += 64
            block++
        }
        return out
    }

    private val CONSTANTS = intArrayOf(0x61707865, 0x3320646e, 0x79622d32, 0x6b206574)

    fun keystream(key: ByteArray, nonce: ByteArray, counter: Int): ByteArray {
        val state = IntArray(16)
        CONSTANTS.copyInto(state)
        for (i in 0 until 8) state[4 + i] = word(key, i * 4)
        state[12] = counter
        for (i in 0 until 3) state[13 + i] = word(nonce, i * 4)
        val x = state.copyOf()
        repeat(10) {
            quarter(x, 0, 4, 8, 12)
            quarter(x, 1, 5, 9, 13)
            quarter(x, 2, 6, 10, 14)
            quarter(x, 3, 7, 11, 15)
            quarter(x, 0, 5, 10, 15)
            quarter(x, 1, 6, 11, 12)
            quarter(x, 2, 7, 8, 13)
            quarter(x, 3, 4, 9, 14)
        }
        val out = ByteArray(64)
        for (i in 0 until 16) {
            val v = x[i] + state[i]
            out[4 * i] = v.toByte()
            out[4 * i + 1] = (v ushr 8).toByte()
            out[4 * i + 2] = (v ushr 16).toByte()
            out[4 * i + 3] = (v ushr 24).toByte()
        }
        return out
    }

    private fun word(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or
            ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)

    private fun quarter(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 16)
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 12)
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 8)
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 7)
    }
}
