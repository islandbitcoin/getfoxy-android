package io.islandbitcoin.foxy.flashcard

import io.islandbitcoin.foxy.keychain.Bytes
import io.islandbitcoin.foxy.keychain.P256

/**
 * The time a card is told, and the signature it checks it by: the port of
 * Foxy/Flashcard/CardTime.swift (getfoxy/iOS 8df0bb1), for a card of software
 * 1.14 and before. A card of 1.15 and on has no time key and takes no signed
 * time: its clock is the newest Bitcoin block header it has been shown, which the
 * page fetches over Tor itself (`mintRequest` to the two explorers' onions,
 * build/wallet/08b-block-headers.js), checks, and sends through the card session
 * as SET_HEADER, 80 bytes; nothing of that is native's.
 *
 * Such an old card keeps no clock of its own: it takes a time only with a
 * signature by the TIME KEY written to it when it was set up (SET_TIME, 35: ECDSA,
 * P-256, SHA-256, over "FoxyCard/time" and the time, four bytes big-endian).
 * Nothing about it is secret, and a signed time only moves a card's clock forward.
 *
 * INTERIM. READ THIS BEFORE TRUSTING THE DAILY LIMIT (the comment in
 * CardTime.swift, in short): the private half of the time key is built into the
 * app, here, in the clear, as it is in the iOS app, so anyone can extract it and
 * sign any time. It is as weak as trusting the receiving phone's own clock,
 * because that is what it is. It bounds an honest receiver and the holder's own
 * spending to the day; it does not bound a terminal built to cheat. No screen may
 * say that the limit stops an attacker. The key is iOS's, byte for byte, because a
 * card set up from an iPhone checks this key and no other.
 */
object InterimCardTime {
    const val PRIVATE_KEY_HEX = "5d3aa8864437b69bc699905ded587014b3d1f7e0de402684e5eec162d540b2ca"

    /** The public half of [PRIVATE_KEY_HEX]: 65 bytes, uncompressed, hex. It is a card's time key. */
    const val PUBLIC_KEY_HEX =
        "04403d6dc56a7e17ce887410e510f6214ff8699d5e09bf43dbbbb4159a9856cd0fe0737a186bbc8cbdaa924c49c3e5bc390968348d30c1c5406ba530a040fb17f8"

    /** What the card verifies a time with begins with these bytes. */
    val TAG: ByteArray = "FoxyCard/time".toByteArray(Charsets.US_ASCII)

    /** The largest time a card holds: four bytes of seconds. */
    const val LAST_TIME: Long = 0xFFFF_FFFFL

    /** What the bridge answers when the clock cannot be told to a card (FoxyBridge+Flashcard.swift `handleCardTime`). */
    const val UNTELLABLE = "The clock on this phone is not a time a card can be told."

    /** "FoxyCard/time" and the time, four bytes big-endian: the bytes that are signed (the signer hashes them once, itself). */
    fun message(time: Long): ByteArray {
        require(time in 0..LAST_TIME)
        return TAG + byteArrayOf((time ushr 24).toByte(), (time ushr 16).toByte(), (time ushr 8).toByte(), time.toByte())
    }

    /**
     * A clock reading in the card's terms: whole seconds since 1970, which must fit
     * in four bytes. Null for a clock before 1970 or past the year 2106, and for a
     * reading that is no number.
     */
    fun seconds(sinceEpoch: Double): Long? {
        val whole = Math.floor(sinceEpoch)
        if (!whole.isFinite() || whole < 0 || whole > LAST_TIME.toDouble()) return null
        return whole.toLong()
    }

    /** The phone's clock, in milliseconds since 1970, in the card's terms. */
    fun seconds(epochMillis: Long): Long? = seconds(Math.floorDiv(epochMillis, 1000L).toDouble())

    /** The signature over [message], DER, as lowercase hex. Randomised: each one differs, and each verifies. Null if none could be made. */
    fun signature(time: Long): String? = try {
        val key = Bytes.fromHex(PRIVATE_KEY_HEX)!!
        Bytes.hex(P256.sign(key, message(time)))
    } catch (e: Exception) {
        null
    }

    /** cardTime's answer for this clock reading: `{"sig":"<DER hex>","time":<seconds>}`. Null when the clock cannot be told to a card. */
    fun reply(time: Long?): String? {
        if (time == null || time !in 0..LAST_TIME) return null
        val sig = signature(time) ?: return null
        return "{\"sig\":\"$sig\",\"time\":$time}"
    }
}
