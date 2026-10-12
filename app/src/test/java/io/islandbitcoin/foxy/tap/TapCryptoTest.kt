package io.islandbitcoin.foxy.tap

import io.islandbitcoin.foxy.keychain.Bytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The handshake, without a radio: FoxyTests/TapCryptoTests.swift, ported, and
 * the published vectors of what is underneath it (RFC 7748 for X25519,
 * RFC 5869 for HKDF, RFC 8439 for ChaCha20-Poly1305).
 */
class TapCryptoTest {

    private class Run(val keys: TapCrypto.Keys, val payerKeys: TapCrypto.Keys)

    private fun pub(priv: ByteArray) = TapCrypto.publicKey(priv)!!

    private fun handshake(service: java.util.UUID = TapCrypto.newService()): Run {
        val r = TapCrypto.newPrivateKey()
        val p = TapCrypto.newPrivateKey()
        val nr = TapCrypto.randomNonce()
        val np = TapCrypto.randomNonce()
        val rr = pub(r)
        val pp = pub(p)
        // the payer promises P before it has seen R
        val commit = TapCrypto.commitment(pp, np)
        assertArrayEquals("the promise is the same every time", commit, TapCrypto.commitment(pp, np))
        val th = TapCrypto.transcript(service = service, r = rr, p = pp, nr = nr, np = np)
        val onReceiver = TapCrypto.keys(TapCrypto.sharedSecret(r, pp)!!, th)
        val onPayer = TapCrypto.keys(TapCrypto.sharedSecret(p, rr)!!, th)
        return Run(onReceiver, onPayer)
    }

    @Test
    fun bothSidesEndUpWithTheSameKeysAndCode() {
        val run = handshake()
        assertEquals(run.keys.code, run.payerKeys.code)
        assertEquals(4, run.keys.code.length)
        assertTrue(run.keys.code.all { it in '0'..'9' })
        assertArrayEquals(run.keys.receiverToPayer, run.payerKeys.receiverToPayer)
        assertArrayEquals(run.keys.payerToReceiver, run.payerKeys.payerToReceiver)
        assertFalse(
            "one key each way: neither side seals with the other's",
            run.keys.receiverToPayer.contentEquals(run.keys.payerToReceiver),
        )
    }

    /** Somebody relaying between the two phones runs two handshakes and cannot make the digits agree. */
    @Test
    fun somebodyInTheMiddleIsCaughtByTheDigits() {
        var agreed = 0
        val tries = 300
        repeat(tries) {
            val service = TapCrypto.newService()
            val receiver = TapCrypto.newPrivateKey()
            val middleToReceiver = TapCrypto.newPrivateKey()
            val middleToPayer = TapCrypto.newPrivateKey()
            val payer = TapCrypto.newPrivateKey()
            val n = List(4) { TapCrypto.randomNonce() }
            val thA = TapCrypto.transcript(service = service, r = pub(receiver), p = pub(middleToReceiver), nr = n[0], np = n[1])
            val codeA = TapCrypto.keys(TapCrypto.sharedSecret(receiver, pub(middleToReceiver))!!, thA).code
            val thB = TapCrypto.transcript(service = service, r = pub(middleToPayer), p = pub(payer), nr = n[2], np = n[3])
            val codeB = TapCrypto.keys(TapCrypto.sharedSecret(payer, pub(middleToPayer))!!, thB).code
            if (codeA == codeB) agreed++
        }
        assertTrue("$agreed of $tries relayed handshakes produced matching digits", agreed < 4)
    }

    @Test
    fun thePromiseIsCheckable() {
        val p = pub(TapCrypto.newPrivateKey())
        val np = TapCrypto.randomNonce()
        val commit = TapCrypto.commitment(p, np)
        assertFalse(commit.contentEquals(TapCrypto.commitment(p, TapCrypto.randomNonce())))
        assertFalse(commit.contentEquals(TapCrypto.commitment(pub(TapCrypto.newPrivateKey()), np)))
    }

    /** The transcript is public, so the secret has to be in the code too. */
    @Test
    fun theCodeNeedsTheSharedSecretNotJustTheTranscript() {
        val r = TapCrypto.newPrivateKey()
        val p = TapCrypto.newPrivateKey()
        val th = TapCrypto.transcript(
            service = TapCrypto.newService(), r = pub(r), p = pub(p),
            nr = TapCrypto.randomNonce(), np = TapCrypto.randomNonce(),
        )
        val real = TapCrypto.keys(TapCrypto.sharedSecret(r, pub(p))!!, th)
        val guess = TapCrypto.keys(TapCrypto.sharedSecret(TapCrypto.newPrivateKey(), pub(p))!!, th)
        assertNotEquals(real.code, guess.code)
    }

    @Test
    fun aSealedMessageOpensWithItsOwnKey() {
        val run = handshake()
        val body = "the offer".toByteArray()
        val sealed = TapCrypto.seal(body, run.keys.receiverToPayer, 0u, run.keys.transcript, 0x04)!!
        assertArrayEquals(body, TapCrypto.open(sealed, run.payerKeys.receiverToPayer, 0u, run.keys.transcript, 0x04))
    }

    @Test
    fun theWrongKeyDoesNotOpenIt() {
        val run = handshake()
        val sealed = TapCrypto.seal("x".toByteArray(), run.keys.receiverToPayer, 0u, run.keys.transcript, 0x04)!!
        assertNull(TapCrypto.open(sealed, run.keys.payerToReceiver, 0u, run.keys.transcript, 0x04))
    }

    @Test
    fun theWrongCounterDoesNotOpenIt() {
        val run = handshake()
        val sealed = TapCrypto.seal("x".toByteArray(), run.keys.receiverToPayer, 0u, run.keys.transcript, 0x04)!!
        assertNull(TapCrypto.open(sealed, run.keys.receiverToPayer, 1u, run.keys.transcript, 0x04))
    }

    @Test
    fun anotherHandshakeDoesNotOpenIt() {
        val run = handshake()
        val sealed = TapCrypto.seal("x".toByteArray(), run.keys.receiverToPayer, 0u, run.keys.transcript, 0x04)!!
        assertNull(TapCrypto.open(sealed, run.keys.receiverToPayer, 0u, ByteArray(32) { 9 }, 0x04))
    }

    /** Relabelled on the air, "say that again" must not open as "I kept the change". */
    @Test
    fun aMessageSealedAsOneKindDoesNotOpenAsAnother() {
        val run = handshake()
        val body = "12".toByteArray()
        val sealed = TapCrypto.seal(body, run.keys.payerToReceiver, 0u, run.keys.transcript, 0x0C)!!
        assertArrayEquals(body, TapCrypto.open(sealed, run.payerKeys.payerToReceiver, 0u, run.keys.transcript, 0x0C))
        assertNull(TapCrypto.open(sealed, run.payerKeys.payerToReceiver, 0u, run.keys.transcript, 0x08))
    }

    /** Two phones told different versions end up with different keys and digits. */
    @Test
    fun theVersionIsPartOfWhatWasAgreed() {
        val r = TapCrypto.newPrivateKey()
        val p = TapCrypto.newPrivateKey()
        val nr = TapCrypto.randomNonce()
        val np = TapCrypto.randomNonce()
        val service = TapCrypto.newService()
        val now = TapCrypto.transcript(service = service, r = pub(r), p = pub(p), nr = nr, np = np)
        assertArrayEquals(now, TapCrypto.transcript(TapCrypto.VERSION, service, pub(r), pub(p), nr, np))
        val other = TapCrypto.transcript(TapCrypto.VERSION + 1, service, pub(r), pub(p), nr, np)
        assertFalse(now.contentEquals(other))
        val shared = TapCrypto.sharedSecret(r, pub(p))!!
        assertFalse(TapCrypto.keys(shared, now).receiverToPayer.contentEquals(TapCrypto.keys(shared, other).receiverToPayer))
    }

    @Test
    fun aChangedByteDoesNotOpenIt() {
        val run = handshake()
        val sealed = TapCrypto.seal("the offer".toByteArray(), run.keys.receiverToPayer, 0u, run.keys.transcript, 0x04)!!
        sealed[3] = (sealed[3].toInt() xor 1).toByte()
        assertNull(TapCrypto.open(sealed, run.keys.receiverToPayer, 0u, run.keys.transcript, 0x04))
    }

    @Test
    fun aTagAloneIsRefusedUnopened() {
        val run = handshake()
        assertNull(TapCrypto.open(ByteArray(16), run.keys.receiverToPayer, 0u, run.keys.transcript, 0x04))
        assertNull(TapCrypto.open(ByteArray(0), run.keys.receiverToPayer, 0u, run.keys.transcript, 0x04))
    }

    @Test
    fun theCharacteristicsFollowTheServiceAndNothingElse() {
        val a = TapCrypto.newService()
        val b = TapCrypto.newService()
        assertEquals(TapCrypto.inCharacteristic(a), TapCrypto.inCharacteristic(a))
        assertNotEquals("the two directions are different doors", TapCrypto.inCharacteristic(a), TapCrypto.outCharacteristic(a))
        assertNotEquals("a new invoice is a new set of numbers", TapCrypto.inCharacteristic(a), TapCrypto.inCharacteristic(b))
        assertNotEquals(TapCrypto.inCharacteristic(a), a)
        assertNotEquals(TapCrypto.nearCharacteristic(a, "left"), TapCrypto.nearCharacteristic(a, "right"))
        assertEquals("anything but left is the right door", TapCrypto.nearCharacteristic(a, "right"), TapCrypto.nearCharacteristic(a, "top"))
    }

    @Test
    fun aUuidIsItsBytesMostSignificantFirst() {
        val id = java.util.UUID.fromString("F0C5A7A0-7A11-4B1D-9E57-F0C5F0C5A001")
        assertEquals("f0c5a7a07a114b1d9e57f0c5f0c5a001", Bytes.hex(TapCrypto.bytes(id)))
        assertEquals(id, TapCrypto.uuid(TapCrypto.bytes(id)))
    }

    @Test
    fun paddingHidesTheSizeAndComesBackUnchanged() {
        for (n in listOf(0, 1, 300, 1200, 2043)) {
            val body = ByteArray(n) { 7 }
            val padded = TapCrypto.pad(body, TapCrypto.OFFER_SIZE)!!
            assertEquals("every offer is one size", TapCrypto.OFFER_SIZE, padded.size)
            assertArrayEquals(body, TapCrypto.unpad(padded))
        }
        assertNull("an offer that will not fit is refused rather than truncated", TapCrypto.pad(ByteArray(2045) { 1 }, TapCrypto.OFFER_SIZE))
        for ((n, want) in listOf(10 to 4096, 4093 to 8192, 5000 to 8192, 9000 to 12288)) {
            val padded = TapCrypto.padPayment(ByteArray(n) { 3 })!!
            assertEquals("$n bytes", want, padded.size)
            assertEquals(n, TapCrypto.unpad(padded)!!.size)
        }
        assertNull(TapCrypto.padPayment(ByteArray(TapCrypto.MOST_PAYMENT) { 1 }))
    }

    @Test
    fun unpadRefusesNonsense() {
        assertNull(TapCrypto.unpad(byteArrayOf(0, 0, 0)))
        assertNull("a length past the end", TapCrypto.unpad(byteArrayOf(-1, -1, -1, -1, 1, 2, 3)))
        assertNull("a length past the end, read unsigned", TapCrypto.unpad(byteArrayOf(-128, 0, 0, 0, 1)))
    }

    @Test
    fun theNonceIsATwelveByteBigEndianCounter() {
        assertEquals("000000000000000000000000", Bytes.hex(TapCrypto.nonce(0u)))
        assertEquals("000000000102030405060708", Bytes.hex(TapCrypto.nonce(0x0102030405060708u)))
        assertEquals("00000000ffffffffffffffff", Bytes.hex(TapCrypto.nonce(ULong.MAX_VALUE)))
    }

    // MARK: the layers underneath, against their own published vectors

    private fun hex(s: String) = Bytes.fromHex(s)!!

    /** RFC 7748 §5.2 and §6.1. */
    @Test
    fun x25519MatchesRfc7748() {
        assertEquals(
            "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
            Bytes.hex(
                TapCrypto.sharedSecret(
                    hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
                    hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"),
                )!!,
            ),
        )
        val alice = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bob = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", Bytes.hex(TapCrypto.publicKey(alice)!!))
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", Bytes.hex(TapCrypto.publicKey(bob)!!))
        val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(shared, Bytes.hex(TapCrypto.sharedSecret(alice, TapCrypto.publicKey(bob)!!)!!))
        assertEquals(shared, Bytes.hex(TapCrypto.sharedSecret(bob, TapCrypto.publicKey(alice)!!)!!))
    }

    @Test
    fun x25519RefusesKeysThatAreNotKeys() {
        val me = TapCrypto.newPrivateKey()
        assertNull("the wrong size", TapCrypto.sharedSecret(me, ByteArray(31)))
        assertNull("zero, a point of small order", TapCrypto.sharedSecret(me, ByteArray(32)))
        assertNull(TapCrypto.publicKey(ByteArray(31)))
    }

    /** RFC 5869 A.1. */
    @Test
    fun hkdfMatchesRfc5869() {
        val okm = TapCrypto.hkdf(
            ByteArray(22) { 0x0b },
            hex("000102030405060708090a0b0c"),
            hex("f0f1f2f3f4f5f6f7f8f9"),
            42,
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Bytes.hex(okm),
        )
    }

    /** RFC 8439 §2.8.2. */
    @Test
    fun chaCha20Poly1305MatchesRfc8439() {
        val key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce = hex("070000004041424344454647")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val text = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it."
        val c = javax.crypto.Cipher.getInstance("ChaCha20-Poly1305")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "ChaCha20"), javax.crypto.spec.IvParameterSpec(nonce))
        c.updateAAD(aad)
        val sealed = c.doFinal(text.toByteArray())
        assertEquals("1ae10b594f09e26a7e902ecbd0600691", Bytes.hex(sealed.copyOfRange(sealed.size - 16, sealed.size)))
        // TapCrypto's own seal over the same key: its nonce is the counter, its associated data th ‖ kind
        val th = hex("50515253c0c1c2c3c4c5c6")
        val ours = TapCrypto.seal(text.toByteArray(), key, 0u, th, 0xc7)!!
        val jca = javax.crypto.Cipher.getInstance("ChaCha20-Poly1305")
        jca.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "ChaCha20"), javax.crypto.spec.IvParameterSpec(ByteArray(12)))
        jca.updateAAD(aad)
        assertArrayEquals(jca.doFinal(text.toByteArray()), ours)
    }
}
