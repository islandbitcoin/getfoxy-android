package io.islandbitcoin.foxy

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.islandbitcoin.foxy.flashcard.InterimCardTime
import io.islandbitcoin.foxy.keychain.Bytes
import io.islandbitcoin.foxy.keychain.CardOwner
import io.islandbitcoin.foxy.keychain.NUT13
import io.islandbitcoin.foxy.keychain.P256
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A card's owner key and the interim time key on the phone, through the phone's own
 * JCA providers (Conscrypt), not the desk JVM's (ENG-666): the first two cases of
 * iOS's tests/fixtures/card-owner-vectors.json, which the JVM's CardOwnerTest checks
 * in full. An Android phone restored from an iPhone's words must make the same key.
 */
@RunWith(AndroidJUnit4::class)
class CardOwnerOnDeviceTest {
    private val g = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"

    @Test
    fun theOwnerKeyIsTheIPhonesOnThePhone() {
        val first = NUT13.seed("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")
        val pub = CardOwner.publicKey(first, Bytes.fromHex(g)!!)
        assertEquals(
            "044eaf9364a5251aea9d7df8771b385377c085c5454283702c7da17495d045fd3b88a27071fb9693a0ca350263b5c5752d0dc20f95a78cf93e2867c57ad250772e",
            Bytes.hex(pub),
        )
        val second = NUT13.seed("legal winner thank year wave sausage worth useful legal winner thank yellow")
        assertEquals(
            "04ffd4f32177b102a4e19ed68fbc793290f8758b3becd2be3fd59b15b770686a3564fdf4c4a17c713f36047c3a8d9091352dd6b3ebeff93fd5a5dd184ed7cd8c9a",
            Bytes.hex(CardOwner.publicKey(second, Bytes.fromHex("02fa05ed0a3649886270bacec5ddc1a3643009a759bee996f9a6ac060c9992a82e")!!)),
        )
        // a proof made here verifies against that key, and only for what was asked
        val request = CardOwner.SignRequest(Bytes.fromHex(g)!!, CardOwner.Label.LOAD, ByteArray(16) { it.toByte() }, ByteArray(0))
        val sig = CardOwner.signature(request, first)
        val message = CardOwner.message(request.label, request.nonce, request.value)
        assertTrue(P256.verify(pub, message, sig))
        assertFalse(P256.verify(pub, message + byteArrayOf(0), sig))
    }

    @Test
    fun theTimeKeyIsIOSsOnThePhone() {
        assertEquals(InterimCardTime.PUBLIC_KEY_HEX, Bytes.hex(P256.publicKey(Bytes.fromHex(InterimCardTime.PRIVATE_KEY_HEX)!!)))
        val sig = Bytes.fromHex(InterimCardTime.signature(1_800_000_000)!!)!!
        assertTrue(P256.verify(Bytes.fromHex(InterimCardTime.PUBLIC_KEY_HEX)!!, InterimCardTime.message(1_800_000_000), sig))
    }
}
