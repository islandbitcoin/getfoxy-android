package io.islandbitcoin.foxy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.islandbitcoin.foxy.bridge.Json
import io.islandbitcoin.foxy.keychain.Bytes
import io.islandbitcoin.foxy.tap.TapCrypto
import io.islandbitcoin.foxy.tap.TapSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tap to pay's crypto on the phone, through the phone's own providers (Conscrypt's
 * ChaCha20-Poly1305, which the desk JVM does not have, and Tink's X25519 in the
 * APK), not the desk JVM's (ENG-669): iOS's own bytes from
 * fixtures/tap-vectors.json, which the JVM's TapVectorsTest checks in full.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("UNCHECKED_CAST")
class TapCryptoOnDeviceTest {
    private val v: Map<String, Any?> by lazy {
        val context = InstrumentationRegistry.getInstrumentation().context
        Json.parse(context.assets.open("tap-vectors.json").use { it.readBytes().toString(Charsets.UTF_8) }) as Map<String, Any?>
    }

    private fun hex(s: Any?) = Bytes.fromHex(s as String)!!

    @Test
    fun x25519AndTheKeysAreTheIPhonesOnThePhone() {
        for (a in v["x25519"] as List<Map<String, Any?>>) {
            assertEquals(a["public"], Bytes.hex(TapCrypto.publicKey(hex(a["private"]))!!))
            val shared = TapCrypto.sharedSecret(hex(a["private"]), hex(a["peer"]))
            if (a["refused"] == true) assertNull(shared) else assertEquals(a["shared"], Bytes.hex(shared!!))
        }
        val k = v["keys"] as Map<String, Any?>
        val keys = TapCrypto.keys(hex(k["shared"]), hex(k["transcript"]))
        assertEquals(k["receiverToPayer"], Bytes.hex(keys.receiverToPayer))
        assertEquals(k["payerToReceiver"], Bytes.hex(keys.payerToReceiver))
        assertEquals(k["code"], keys.code)
    }

    @Test
    fun sealingIsTheIPhonesOnThePhone() {
        for (s in v["seals"] as List<Map<String, Any?>>) {
            val counter = (s["counter"] as String).toULong()
            val kind = (s["kind"] as Double).toInt()
            val sealed = TapCrypto.seal(hex(s["message"]), hex(s["key"]), counter, hex(s["transcript"]), kind)!!
            assertEquals(s["sealed"], Bytes.hex(sealed))
            if (hex(s["message"]).isNotEmpty()) {
                assertArrayEquals(hex(s["message"]), TapCrypto.open(sealed, hex(s["key"]), counter, hex(s["transcript"]), kind))
            }
        }
    }

    /** The first conversation's handshake and offer, both sides made here with the iPhone run's keys. */
    @Test
    fun aHandshakeIsTheIPhonesOnThePhone() {
        val s = (v["sessions"] as List<Map<String, Any?>>).first()
        val ops = s["ops"] as List<Map<String, Any?>>
        val seed = (s["seed"] as Double).toInt()
        fun run(start: Int, n: Int) = ByteArray(n) { ((start + it) and 0xff).toByte() }
        val service = TapCrypto.uuid(hex(s["service"]))
        val payer = TapSession(TapSession.Role.PAYER, service, run(seed, 32), run(seed + 0x80, 16)) {}
        val receiver = TapSession(TapSession.Role.RECEIVER, service, run(seed + 0x40, 32), run(seed + 0xc0, 16)) {}
        fun sent(step: TapSession.Step) = (step as TapSession.Step.Send).bytes
        fun want(i: Int) = ((ops[i]["out"] as Map<String, Any?>)["bytes"] as Map<String, Any?>)["hex"]
        val m1 = sent(payer.begin())
        assertEquals(want(0), Bytes.hex(m1))
        val m2 = sent(receiver.received(m1))
        assertEquals(want(1), Bytes.hex(m2))
        val m3 = sent(payer.received(m2))
        assertEquals(want(2), Bytes.hex(m3))
        assertEquals((ops[3]["out"] as Map<String, Any?>)["code"], (receiver.received(m3) as TapSession.Step.Linked).code)
        val offer = ops[4]["arg"] as String
        assertEquals((ops[4]["out"] as Map<String, Any?>)["hex"], Bytes.hex(receiver.sealOffer(offer)!!))
    }
}
