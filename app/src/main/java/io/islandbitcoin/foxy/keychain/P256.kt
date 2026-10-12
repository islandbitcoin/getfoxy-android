package io.islandbitcoin.foxy.keychain

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/**
 * NIST P-256 (secp256r1) through the platform's own JCA, and nothing else: what
 * CryptoKit's `P256.Signing` does for CardOwner and the interim card time on
 * iOS. A Foxy card checks ECDSA over P-256 with SHA-256, DER, against a 65-byte
 * uncompressed key (getfoxy/card docs/FOXY-CARD-SPEC.md 5.6).
 *
 * JCA has no "public key of this private key". It is made here with the
 * platform's ECDH, so the scalar only ever meets the provider's own curve code:
 * ECDH of d with the generator G is the x of d·G; the two points with that x are
 * (x, y) and (x, p − y), and the one a signature by d verifies against is d·G.
 */
object P256 {
    sealed class Failure(message: String) : Exception(message) {
        /** A scalar of 0 or ≥ n, or a point the provider will not take. */
        class InvalidKey : Failure("invalid key")
        /** The platform's crypto failed. */
        class Platform : Failure("platform")
    }

    /** P-256's group order n, big-endian. */
    val ORDER: ByteArray = Bytes.fromHex("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551")!!

    /** The field prime p, and b of y² = x³ − 3x + b (SEC 2, 2.4.2). */
    private val P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)

    /** The curve as the provider names it: taken from a key it made, so the provider never has to recognise one it was handed. */
    val spec: ECParameterSpec by lazy {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val params = (generator.generateKeyPair().public as java.security.interfaces.ECPublicKey).params
        check(params.order == BigInteger(1, ORDER)) { "the provider's secp256r1 is not P-256" }
        check((params.curve.field as java.security.spec.ECFieldFp).p == P) { "the provider's secp256r1 is not P-256" }
        params
    }

    private fun privateKey(d: ByteArray): PrivateKey {
        val k = BigInteger(1, d)
        if (d.size != 32 || k.signum() == 0 || k >= spec.order) throw Failure.InvalidKey()
        return try {
            KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(k, spec))
        } catch (e: Exception) {
            throw Failure.InvalidKey()
        }
    }

    private fun publicKey(point: ECPoint) = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, spec))

    private fun fixed32(n: BigInteger): ByteArray {
        val raw = n.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size == 33 && raw[0] == 0.toByte() -> raw.copyOfRange(1, 33)
            raw.size < 32 -> ByteArray(32 - raw.size) + raw
            else -> throw Failure.Platform()
        }
    }

    /** 04 ‖ X ‖ Y, 65 bytes. */
    fun encode(point: ECPoint): ByteArray = byteArrayOf(0x04) + fixed32(point.affineX) + fixed32(point.affineY)

    /** A 65-byte uncompressed key, on the curve; null otherwise. */
    fun decode(key: ByteArray): ECPoint? {
        if (key.size != 65 || key[0] != 0x04.toByte()) return null
        val x = BigInteger(1, key.copyOfRange(1, 33))
        val y = BigInteger(1, key.copyOfRange(33, 65))
        if (x >= P || y >= P) return null
        if (y.multiply(y).mod(P) != x.pow(3).subtract(x.multiply(BigInteger.valueOf(3))).add(B).mod(P)) return null
        return ECPoint(x, y)
    }

    /** The public key of the scalar `d` (32 bytes, 0 < d < n): 65 bytes, 04 ‖ X ‖ Y. */
    fun publicKey(d: ByteArray): ByteArray {
        val key = privateKey(d)
        val x = try {
            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(key)
            agreement.doPhase(publicKey(spec.generator), true)
            BigInteger(1, agreement.generateSecret())
        } catch (e: Exception) {
            throw Failure.Platform()
        }
        // y² = x³ − 3x + b; p ≡ 3 mod 4, so a square root is (y²)^((p + 1) / 4)
        val ySquared = x.pow(3).subtract(x.multiply(BigInteger.valueOf(3))).add(B).mod(P)
        val y = ySquared.modPow(P.add(BigInteger.ONE).shiftRight(2), P)
        if (y.multiply(y).mod(P) != ySquared) throw Failure.Platform()
        val probe = "FoxyCard/P256 which y".toByteArray(Charsets.US_ASCII)
        val signed = sign(key, probe)
        for (candidate in listOf(y, P.subtract(y).mod(P))) {
            val point = ECPoint(x, candidate)
            if (verify(point, probe, signed)) return encode(point)
        }
        throw Failure.Platform()
    }

    private fun sign(key: PrivateKey, message: ByteArray): ByteArray = try {
        Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(message)
            sign()
        }
    } catch (e: Exception) {
        throw Failure.Platform()
    }

    /** ECDSA over P-256 with SHA-256 of `message` (hashed once, by the signer), DER. Randomised, as CryptoKit's is. */
    fun sign(d: ByteArray, message: ByteArray): ByteArray = sign(privateKey(d), message)

    private fun verify(point: ECPoint, message: ByteArray, der: ByteArray): Boolean = try {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey(point))
            update(message)
            verify(der)
        }
    } catch (e: Exception) {
        false
    }

    /** Whether `der` is a signature by the 65-byte key `key` over `message`. False for anything that is not. */
    fun verify(key: ByteArray, message: ByteArray, der: ByteArray): Boolean {
        val point = decode(key) ?: return false
        return verify(point, message, der)
    }
}
