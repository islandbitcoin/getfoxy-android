package io.islandbitcoin.foxy.flashcard

import io.islandbitcoin.foxy.TestSupport
import io.islandbitcoin.foxy.bridge.Json
import io.islandbitcoin.foxy.keychain.Bytes
import io.islandbitcoin.foxy.keychain.P256
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The interim time signer ([InterimCardTime]): ported from FoxyTests/CardTimeTests.swift
 * (getfoxy/iOS 8df0bb1), one assertion at a time. The key pair is a pair, and is
 * iOS's byte for byte; a time is four bytes of seconds; what is signed is what the
 * card verifies. And the page at the pin writes the same public key to a card it
 * sets up (`FoxyWallet.cardTimeKey`), so a time signed here is one such a card takes.
 */
class CardTimeTest {
    private fun bytes(hex: String): ByteArray = Bytes.fromHex(hex)!!

    private val publicKey: ByteArray get() = bytes(InterimCardTime.PUBLIC_KEY_HEX)

    private fun bigEndian(time: Long) = byteArrayOf((time ushr 24).toByte(), (time ushr 16).toByte(), (time ushr 8).toByte(), time.toByte())

    @Suppress("UNCHECKED_CAST")
    private fun parsed(text: String?): Map<String, Any?> = Json.parse(text!!) as Map<String, Any?>

    /** The published key is the public half of the private one: a card set up with the first takes the second's times. */
    @Test
    fun thePublicKeyIsThePublicHalfOfThePrivateKey() {
        assertEquals(64, InterimCardTime.PRIVATE_KEY_HEX.length)
        assertEquals(130, InterimCardTime.PUBLIC_KEY_HEX.length)
        assertTrue("uncompressed, as the card's time key is", InterimCardTime.PUBLIC_KEY_HEX.startsWith("04"))
        for (text in listOf(InterimCardTime.PRIVATE_KEY_HEX, InterimCardTime.PUBLIC_KEY_HEX)) {
            assertEquals(text.lowercase(), text)
            assertTrue(text.all { it in "0123456789abcdef" })
        }
        assertEquals(InterimCardTime.PUBLIC_KEY_HEX, Bytes.hex(P256.publicKey(bytes(InterimCardTime.PRIVATE_KEY_HEX))))
        assertNotNull("a point on the curve", P256.decode(publicKey))
        // iOS's, byte for byte (Foxy/Flashcard/CardTime.swift:43-46)
        assertEquals("5d3aa8864437b69bc699905ded587014b3d1f7e0de402684e5eec162d540b2ca", InterimCardTime.PRIVATE_KEY_HEX)
        assertEquals(
            "04403d6dc56a7e17ce887410e510f6214ff8699d5e09bf43dbbbb4159a9856cd0fe0737a186bbc8cbdaa924c49c3e5bc390968348d30c1c5406ba530a040fb17f8",
            InterimCardTime.PUBLIC_KEY_HEX,
        )
    }

    /** The page at the pin writes this key to a card it sets up (08a-flashcard.js CARD_TIME_KEY): the two must be one. */
    @Test
    fun thePagesTimeKeyIsThisOne() {
        val page = File(TestSupport.core, "web/foxy-wallet.js").readText()
        val at = page.indexOf("CARD_TIME_KEY = '")
        assertTrue("the pinned page names its time key", at >= 0)
        val key = page.substring(at + "CARD_TIME_KEY = '".length).substringBefore("'")
        assertEquals(InterimCardTime.PUBLIC_KEY_HEX, key)
    }

    /** What the card verifies: "FoxyCard/time" and the time, four bytes, big-endian. */
    @Test
    fun whatIsSignedIsTheTagAndFourBytesBigEndian() {
        val tag = "466f7879436172642f74696d65" // "FoxyCard/time"
        assertArrayEquals("FoxyCard/time".toByteArray(Charsets.UTF_8), bytes(tag))
        assertArrayEquals(bytes(tag), InterimCardTime.TAG)
        assertEquals(tag + "00000000", Bytes.hex(InterimCardTime.message(0)))
        assertEquals(tag + "00000001", Bytes.hex(InterimCardTime.message(1)))
        assertEquals(tag + "01020304", Bytes.hex(InterimCardTime.message(0x0102_0304)))
        assertEquals(tag + "6b49d200", Bytes.hex(InterimCardTime.message(1_800_000_000)))
        assertEquals(tag + "ffffffff", Bytes.hex(InterimCardTime.message(0xFFFF_FFFFL)))
    }

    @Test
    fun aSignatureVerifiesAgainstTheTimeKeyForThatTimeAndNoOther() {
        val key = publicKey
        for (time in listOf(0L, 1L, 86_400L, 1_800_000_000L, 0xFFFF_FFFFL)) {
            val text = InterimCardTime.signature(time)!!
            assertEquals(text.lowercase(), text)
            assertTrue("DER, a sequence", text.startsWith("30"))
            val sig = bytes(text)
            // over exactly the tag and the four bytes (hashed once, with SHA-256, as the card does)
            val signed = "FoxyCard/time".toByteArray(Charsets.UTF_8) + bigEndian(time)
            assertTrue("$time", P256.verify(key, signed, sig))
            assertTrue(P256.verify(key, InterimCardTime.message(time), sig))
            // not another time, not another label with the same four bytes, not a bare time
            assertFalse(P256.verify(key, InterimCardTime.message((time + 1) and 0xFFFF_FFFFL), sig))
            assertFalse(P256.verify(key, "FoxyCard/load".toByteArray(Charsets.UTF_8) + bigEndian(time), sig))
            assertFalse(P256.verify(key, bigEndian(time), sig))
        }
        // another key does not verify it
        val other = P256.publicKey(ByteArray(32).also { it[31] = 7 })
        assertFalse(P256.verify(other, InterimCardTime.message(5), bytes(InterimCardTime.signature(5)!!)))
    }

    /** A time is whole seconds since 1970 and fits four bytes; a clock outside that is refused, not wrapped. */
    @Test
    fun aTimeIsAUInt32OfSecondsAndNothingElse() {
        assertEquals(0L, InterimCardTime.seconds(0.0))
        assertEquals(1_800_000_000L, InterimCardTime.seconds(1_800_000_000.0))
        assertEquals("the second it is in, not the next", 1_800_000_000L, InterimCardTime.seconds(1_800_000_000.99))
        assertEquals(0xFFFF_FFFFL, InterimCardTime.seconds(4_294_967_295.0))
        assertEquals(0xFFFF_FFFFL, InterimCardTime.seconds(4_294_967_295.5))
        for (refused in listOf(-1.0, -0.5, 4_294_967_296.0, 1e12, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN)) {
            assertNull("$refused", InterimCardTime.seconds(refused))
        }
        assertNull(InterimCardTime.seconds(-Double.MAX_VALUE))
        assertNull(InterimCardTime.seconds(Double.MAX_VALUE))
        // the phone's clock in milliseconds, as the bridge reads it
        assertEquals(1_800_000_000L, InterimCardTime.seconds(1_800_000_000_999L))
        assertNull("a clock before 1970, even by a millisecond", InterimCardTime.seconds(-1L))
        assertNull(InterimCardTime.seconds(4_294_967_296_000L))
        // the phone's own clock now is a time: after 2020, long before the year 2106
        val now = InterimCardTime.seconds(System.currentTimeMillis())
        assertNotNull(now)
        assertTrue(now!! > 1_577_836_800L)
    }

    /** {"sig": ..., "time": ...} and nothing else, for the clock it was given. */
    @Test
    fun theReplyIsTheTimeAndItsSignatureAndNothingElse() {
        val key = publicKey
        for (seconds in listOf(1_800_000_000.0, 1_800_000_000.75, 0.0, 4_294_967_295.0)) {
            val text = InterimCardTime.reply(InterimCardTime.seconds(seconds))!!
            val obj = parsed(text)
            assertEquals(setOf("sig", "time"), obj.keys)
            val time = (obj["time"] as Double).toLong()
            assertEquals(Math.floor(seconds).toLong(), time)
            assertTrue(time <= 0xFFFF_FFFFL)
            assertTrue("a number, not a string", text.contains("\"time\":$time"))
            assertTrue(P256.verify(key, InterimCardTime.message(time), bytes(obj["sig"] as String)))
            assertFalse("the private half is never in the answer", text.contains(InterimCardTime.PRIVATE_KEY_HEX))
        }
        for (refused in listOf(-1.0, 4_294_967_296.0, Double.NaN)) {
            assertNull("$refused", InterimCardTime.reply(InterimCardTime.seconds(refused)))
        }
        assertNull(InterimCardTime.reply(-1L))
        assertNull(InterimCardTime.reply(0x1_0000_0000L))
        // the clock as it is
        val before = System.currentTimeMillis() / 1000
        val live = parsed(InterimCardTime.reply(InterimCardTime.seconds(System.currentTimeMillis())))
        val at = (live["time"] as Double).toLong()
        assertTrue("$at is not now", at in before..before + 5)
    }
}
