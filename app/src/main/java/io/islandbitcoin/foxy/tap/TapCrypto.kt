package io.islandbitcoin.foxy.tap

import com.google.crypto.tink.subtle.X25519
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Tap to pay v2: the handshake, the keys and the four digits, as iOS has them
 * (Foxy/Bluetooth/TapCrypto.swift at the commit in core/PIN; docs TAP-SPEC.md §3).
 *
 * No radio in here. What it gives the two phones is a key nobody listening can
 * work out, and a four-digit code that differs for the two of them if anybody
 * is sitting in the middle. The payer commits to its public key before it has
 * seen the receiver's, so neither side can choose a key that makes the digits
 * come out any particular way.
 *
 * X25519 is Google Tink's (`com.google.crypto.tink.subtle.X25519`), because the
 * platform has XDH only from API 33 and minSdk is 28. SHA-256, HMAC-SHA256 and
 * ChaCha20-Poly1305 are the platform's own (ChaCha20-Poly1305 is in Conscrypt
 * from API 28). Every byte is pinned against what the iOS code writes
 * (fixtures/tap-vectors.json, TapVectorsTest).
 */
object TapCrypto {

    // Every hash in here is domain-separated (TapCrypto.swift "Labels").
    private val commitLabel = "foxy tap v2 commit".toByteArray(Charsets.UTF_8)
    private val transcriptLabel = "foxy tap v2".toByteArray(Charsets.UTF_8)
    private val codeLabel = "foxy tap v2 code".toByteArray(Charsets.UTF_8)
    private val keysInfo = "foxy tap v2 keys".toByteArray(Charsets.UTF_8)
    private val inLabel = "foxy tap v2 in".toByteArray(Charsets.UTF_8)
    private val outLabel = "foxy tap v2 out".toByteArray(Charsets.UTF_8)
    private val nearLeftLabel = "foxy tap v2 near left".toByteArray(Charsets.UTF_8)
    private val nearRightLabel = "foxy tap v2 near right".toByteArray(Charsets.UTF_8)

    const val NONCE_LENGTH = 16
    const val KEY_LENGTH = 32

    /**
     * The version of the wire, first in M1 and M2 in the clear and in the
     * transcript. 2 was the commitment handshake with no version sent; 3 adds
     * the version byte and binds every sealed message to its kind. The labels
     * still say "v2": they name the handshake's shape, and the doors' labels are
     * how any two Foxys find each other.
     */
    const val VERSION: Int = 3

    private val random = SecureRandom()

    // MARK: the service, and the doors under it

    /** A fresh service UUID, new for every invoice. Nothing about it is Foxy's. */
    fun newService(): UUID = UUID.randomUUID()

    /** The payer writes here (`foxy tap v2 in`). */
    fun inCharacteristic(service: UUID): UUID = derive(inLabel, service)

    /** The receiver notifies here (`foxy tap v2 out`). */
    fun outCharacteristic(service: UUID): UUID = derive(outLabel, service)

    /**
     * The door that carries nothing: which of the two a payer subscribes to is
     * the one bit it says, which edge of the receiver's screen the mark goes on.
     */
    fun nearCharacteristic(service: UUID, edge: String): UUID =
        derive(if (edge == "left") nearLeftLabel else nearRightLabel, service)

    /** SHA-256(label ‖ service)[0..16], read big-endian as `UUID(uuid:)` and `CBUUID(nsuuid:)` read it. */
    private fun derive(label: ByteArray, service: UUID): UUID = uuid(sha256(label, bytes(service)).copyOf(16))

    /** A UUID's sixteen bytes, most significant first, as `UUID.uuid` has them. */
    fun bytes(id: UUID): ByteArray {
        val out = ByteArray(16)
        for (i in 0 until 8) {
            out[i] = (id.mostSignificantBits ushr (56 - 8 * i)).toByte()
            out[8 + i] = (id.leastSignificantBits ushr (56 - 8 * i)).toByte()
        }
        return out
    }

    /** The UUID of sixteen bytes, most significant first. */
    fun uuid(b: ByteArray): UUID {
        require(b.size == 16) { "a UUID is sixteen bytes" }
        var msb = 0L
        var lsb = 0L
        for (i in 0 until 8) {
            msb = (msb shl 8) or (b[i].toLong() and 0xff)
            lsb = (lsb shl 8) or (b[8 + i].toLong() and 0xff)
        }
        return UUID(msb, lsb)
    }

    // MARK: X25519

    /** A new private key: 32 random bytes, as CryptoKit's rawRepresentation holds one. */
    fun newPrivateKey(): ByteArray = X25519.generatePrivateKey()

    /** The public key of a private one; null when it is not 32 bytes. */
    fun publicKey(privateKey: ByteArray): ByteArray? = try {
        X25519.publicFromPrivate(privateKey)
    } catch (e: GeneralSecurityException) {
        null
    }

    /**
     * The shared secret, or null when the other key is not a key: the wrong
     * size, or a point of small order (CryptoKit refuses those too; the
     * vectors pin it).
     */
    fun sharedSecret(privateKey: ByteArray, theirs: ByteArray): ByteArray? = try {
        X25519.computeSharedSecret(privateKey, theirs)
    } catch (e: GeneralSecurityException) {
        null
    }

    // MARK: the handshake

    /** What the payer sends first: a promise about a key it has not shown yet. */
    fun commitment(publicKey: ByteArray, nonce: ByteArray): ByteArray = sha256(commitLabel, publicKey, nonce)

    fun randomNonce(): ByteArray = ByteArray(NONCE_LENGTH).also { random.nextBytes(it) }

    /**
     * Everything both sides have seen, in one hash: the salt for the keys and
     * the associated data for every sealed message.
     */
    fun transcript(
        version: Int = VERSION,
        service: UUID,
        r: ByteArray,
        p: ByteArray,
        nr: ByteArray,
        np: ByteArray,
    ): ByteArray = sha256(transcriptLabel, byteArrayOf(version.toByte()), bytes(service), r, p, nr, np)

    /** One direction each, so neither side ever seals with the other's key. */
    class Keys(
        /** receiver → payer */
        val receiverToPayer: ByteArray,
        /** payer → receiver */
        val payerToReceiver: ByteArray,
        /** The four digits on both screens. */
        val code: String,
        /** What every sealed message is bound to. */
        val transcript: ByteArray,
    )

    /**
     * Both keys and the code from an agreed secret: HKDF-SHA256(ikm = s,
     * salt = th, info = "foxy tap v2 keys", 64), and the code
     * be32(SHA-256("foxy tap v2 code" ‖ th ‖ s)[0..4]) mod 10000. The secret is
     * in the code so a bystander with the public transcript cannot work it out.
     */
    fun keys(shared: ByteArray, transcript: ByteArray): Keys {
        val okm = hkdf(shared, transcript, keysInfo, 64)
        val d = sha256(codeLabel, transcript, shared)
        val n = ((d[0].toLong() and 0xff) shl 24) or ((d[1].toLong() and 0xff) shl 16) or
            ((d[2].toLong() and 0xff) shl 8) or (d[3].toLong() and 0xff)
        return Keys(
            receiverToPayer = okm.copyOfRange(0, 32),
            payerToReceiver = okm.copyOfRange(32, 64),
            code = String.format(java.util.Locale.ROOT, "%04d", n % 10000),
            transcript = transcript.copyOf(),
        )
    }

    /** RFC 5869 HKDF with HMAC-SHA256, as CryptoKit's `HKDF<SHA256>.deriveKey`. */
    internal fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = extract.doFinal(ikm)
        val expand = Mac.getInstance("HmacSHA256")
        expand.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var t = ByteArray(0)
        var at = 0
        var i = 1
        while (at < length) {
            expand.update(t)
            expand.update(info)
            expand.update(i.toByte())
            t = expand.doFinal()
            val n = minOf(t.size, length - at)
            System.arraycopy(t, 0, out, at, n)
            at += n
            i++
        }
        return out
    }

    // MARK: sealing

    /** A counter, not a random number: twelve bytes, big-endian, zero-padded. */
    fun nonce(counter: ULong): ByteArray {
        val b = ByteArray(12)
        for (i in 0 until 8) b[11 - i] = ((counter shr (8 * i)) and 0xffu).toByte()
        return b
    }

    /** What a sealed message is bound to: its handshake, and its kind. */
    private fun bound(th: ByteArray, kind: Int): ByteArray = th + byteArrayOf(kind.toByte())

    /** The ciphertext and its 16-byte tag, or null when the cipher refuses. */
    fun seal(message: ByteArray, key: ByteArray, counter: ULong, transcript: ByteArray, kind: Int): ByteArray? = try {
        val c = cipher()
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce(counter)))
        c.updateAAD(bound(transcript, kind))
        c.doFinal(message)
    } catch (e: GeneralSecurityException) {
        null
    }

    /**
     * Null when it has been touched, when the counter is wrong, when it is not
     * the kind it says, or when it belongs to another handshake. The caller drops
     * the link and says nothing about which: an error that tells them apart is
     * an oracle.
     */
    fun open(sealed: ByteArray, key: ByteArray, counter: ULong, transcript: ByteArray, kind: Int): ByteArray? {
        if (sealed.size <= 16) return null
        return try {
            val c = cipher()
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce(counter)))
            c.updateAAD(bound(transcript, kind))
            c.doFinal(sealed)
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    /**
     * ChaCha20-Poly1305 by the name the running platform knows it by: Android's
     * Conscrypt (API 28 on) calls it `ChaCha20/Poly1305/NoPadding`, the JDK the
     * JVM tests run on `ChaCha20-Poly1305`. A fresh instance each time, since a
     * JCA cipher refuses to encrypt twice under one key and nonce.
     *
     * No JVM test reaches the first branch: only `TapCryptoOnDeviceTest` runs
     * Conscrypt's, and it must pass on a phone before ENG-670/671's radio
     * merges (docs/DEVICE-TESTS.md, the hard gate under "Instrumented").
     */
    private fun cipher(): Cipher = try {
        Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
    } catch (e: GeneralSecurityException) {
        Cipher.getInstance("ChaCha20-Poly1305")
    }

    // MARK: padding (encryption hides what crossed, not how much)

    const val OFFER_SIZE = 2048
    const val PAYMENT_BLOCK = 4096

    /** Room for a reason, because M6 carries one. */
    const val RESULT_SIZE = 256

    /** Change goes back in one flat size, so its size does not say how much it is. */
    const val CHANGE_SIZE = 2048
    const val MOST_PAYMENT = 48 * 1024

    /** A payment bigger than one message goes in several, up to this much in all. */
    const val MOST_PAYMENT_TOTAL = 128 * 1024

    /** The most of a payment's own bytes one part carries: eleven blocks, less the four that say how long it is. */
    const val PART_BODY = 11 * 4096 - 4

    /** Four bytes of length, the body, then zeros to [size]; null when it will not fit. */
    fun pad(body: ByteArray, size: Int): ByteArray? {
        if (body.size + 4 > size) return null
        val out = ByteArray(size)
        val n = body.size
        out[0] = (n ushr 24).toByte()
        out[1] = (n ushr 16).toByte()
        out[2] = (n ushr 8).toByte()
        out[3] = n.toByte()
        System.arraycopy(body, 0, out, 4, n)
        return out
    }

    /** A payment, padded up to the next whole block rather than one fixed size. */
    fun padPayment(body: ByteArray): ByteArray? {
        val need = body.size + 4
        if (need > MOST_PAYMENT) return null
        val size = ((need + PAYMENT_BLOCK - 1) / PAYMENT_BLOCK) * PAYMENT_BLOCK
        return pad(body, minOf(size, MOST_PAYMENT))
    }

    fun unpad(padded: ByteArray): ByteArray? {
        if (padded.size < 4) return null
        val n = ((padded[0].toLong() and 0xff) shl 24) or ((padded[1].toLong() and 0xff) shl 16) or
            ((padded[2].toLong() and 0xff) shl 8) or (padded[3].toLong() and 0xff)
        if (4 + n > padded.size) return null
        return padded.copyOfRange(4, 4 + n.toInt())
    }

    internal fun sha256(vararg parts: ByteArray): ByteArray {
        val h = MessageDigest.getInstance("SHA-256")
        for (p in parts) h.update(p)
        return h.digest()
    }
}
