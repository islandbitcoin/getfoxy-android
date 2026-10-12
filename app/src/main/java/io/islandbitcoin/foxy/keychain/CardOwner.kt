package io.islandbitcoin.foxy.keychain

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A card's owner key, derived from the seed, and kept in native code: the port
 * of `CardOwner` in Foxy/Keychain/NUT13.swift (getfoxy/iOS 8df0bb1). The card
 * protocol is getfoxy/card docs/FOXY-CARD-SPEC.md 5.6.
 *
 * A card is shown its owner as a P-256 public key when it is set up, and the
 * owner proves it later by signing, with the private half, what the card is
 * asked to do: its label, a number the card gave in that tap, and the value. The
 * private half is worked out here, from the seed and the card's own key,
 * whenever it is wanted, and never leaves. The page is given the owner PUBLIC
 * key, signatures for a fixed list of labels (each with the shape of value that
 * label takes) and nothing else, so a page that had been got at cannot take the
 * key, and cannot have anything signed that this list does not name: not
 * LOCK_CARD, not SET_TAG, and not a time. Restoring the twelve words on another
 * phone, an iPhone included, makes the same key, so that phone is the owner of
 * every card this one was.
 *
 * h(counter) is HMAC-SHA256 keyed with the 64-byte BIP-39 seed, over
 * "FoxyCard/owner", a zero byte, the card's 33-byte compressed public key and,
 * from counter 1 on, that counter as one more byte. d is h(0) mod n, n being
 * P-256's order; where that is 0 it is h(1) mod n, and so on.
 */
object CardOwner {
    val TAG: ByteArray = "FoxyCard/owner".toByteArray(Charsets.US_ASCII)

    /** P-256's group order n, big-endian. */
    val ORDER: ByteArray get() = P256.ORDER

    /** The nonce a card gives: 16 bytes. */
    const val NONCE_LENGTH = 16

    /** The longest value any label takes: SET_CARD's data with the longest mint (100 bytes before it and 80 for the mint). */
    const val MOST_VALUE_BYTES = 180

    /**
     * What the page may have signed: the short names of the owner's commands,
     * and no others, in iOS's order. The bytes signed begin with "FoxyCard/" and
     * the name, so LOCK_CARD ("FoxyCard/lock"), SET_TAG ("FoxyCard/tag") and the
     * time ("FoxyCard/time") are not on this list and cannot be asked for. No
     * name is the start of another.
     */
    enum class Label(val raw: String) {
        CHANGE_PIN("change-pin"),
        SET_LIMIT("set-limit"),
        SET_OWNER("set-owner"),
        SET_CARD("set-card"),
        LOAD("load"),

        /** RESET, card software 1.17: the owner's proof is over the label and the nonce, and no value, as for load. */
        RESET("reset");

        /**
         * Whether `value` has the shape this label's command takes:
         *  - change-pin: the new PIN, 4 to 8 bytes, each an ASCII digit;
         *  - set-limit: 4 bytes (the day's), 8 (and the one tap's) or 12 (and the no-PIN allowance);
         *  - set-owner: the new owner key, 65 bytes, the first of them 04;
         *  - set-card: unit (1), refund key (33), time key (65, the first 04, or all zeros),
         *    mint length L (1, from 1 to 80) and the mint (L bytes);
         *  - load, reset: nothing.
         */
        fun accepts(value: ByteArray): Boolean = when (this) {
            CHANGE_PIN -> value.size in 4..8 && value.all { it in 0x30..0x39 }
            SET_LIMIT -> value.size == 4 || value.size == 8 || value.size == 12
            SET_OWNER -> value.size == 65 && value[0] == 0x04.toByte()
            SET_CARD -> {
                if (value.size < 100) {
                    false
                } else if (value[34] != 0x04.toByte() && (34 until 99).any { value[it] != 0.toByte() }) {
                    false
                } else {
                    val mint = value[99].toInt() and 0xff
                    mint in 1..80 && value.size == 100 + mint
                }
            }
            LOAD, RESET -> value.isEmpty()
        }

        companion object {
            fun of(name: String): Label? = entries.firstOrNull { it.raw == name }
        }
    }

    /** One signature asked for, after its shape has been checked. */
    class SignRequest(
        /** The card's compressed public key, which says whose owner key signs. */
        val key: ByteArray,
        val label: Label,
        val nonce: ByteArray,
        val value: ByteArray,
    ) {
        /** The shape checks again, on a request built some other way than by [signRequest]. */
        val isWellFormed: Boolean get() = isCardKey(key) && nonce.size == NONCE_LENGTH && label.accepts(value)

        override fun equals(other: Any?): Boolean = other is SignRequest && key.contentEquals(other.key) &&
            label == other.label && nonce.contentEquals(other.nonce) && value.contentEquals(other.value)

        override fun hashCode(): Int = ((key.contentHashCode() * 31 + label.hashCode()) * 31 + nonce.contentHashCode()) * 31 + value.contentHashCode()

        override fun toString(): String = "SignRequest(${label.raw}, ${value.size} bytes)"
    }

    // MARK: What the page sends

    /** A card's public key as the page gives it: 33 bytes, compressed, so starting 02 or 03. */
    fun isCardKey(key: ByteArray): Boolean = key.size == 33 && (key[0] == 0x02.toByte() || key[0] == 0x03.toByte())

    private fun utf8Length(text: String) = text.toByteArray(Charsets.UTF_8).size

    /** A card's key from the page's text: 66 hex characters, either case, for 33 bytes that start 02 or 03. */
    fun key(text: Any?): ByteArray? {
        if (text !is String || utf8Length(text) != 66) return null
        val key = Bytes.fromHex(text) ?: return null
        return if (isCardKey(key)) key else null
    }

    /**
     * cardOwnerSign's {key, label, nonce, value}: the key as above; a label that is
     * exactly one of the six names; a nonce of 32 hex characters; and a value in hex
     * that has the shape the label takes (an empty string for load and reset).
     * Nothing is read until this has passed.
     */
    fun signRequest(body: Map<String, Any?>): SignRequest? {
        val key = key(body["key"]) ?: return null
        val name = body["label"] as? String ?: return null
        val label = Label.of(name) ?: return null
        val nonceText = body["nonce"] as? String ?: return null
        if (utf8Length(nonceText) != 2 * NONCE_LENGTH) return null
        val nonce = Bytes.fromHex(nonceText) ?: return null
        if (nonce.size != NONCE_LENGTH) return null
        val valueText = body["value"] as? String ?: return null
        if (utf8Length(valueText) > 2 * MOST_VALUE_BYTES) return null
        val value = Bytes.fromHex(valueText) ?: return null
        if (!label.accepts(value)) return null
        return SignRequest(key, label, nonce, value)
    }

    // MARK: The key

    /** What is HMACed for a counter: the tag, a zero byte, the card's key and, from counter 1 on, the counter. */
    fun hashInput(cardKey: ByteArray, counter: Int): ByteArray {
        require(counter in 0..255)
        val head = TAG + byteArrayOf(0x00) + cardKey
        return if (counter > 0) head + byteArrayOf(counter.toByte()) else head
    }

    /**
     * d: the first h(counter) that is not 0 mod n, as 32 bytes, for counters 0, 1, 2
     * and so on. `hash` answers h(counter). A 256-bit value is below 2n, so mod n
     * is one subtraction. Null only if all 256 counters gave 0, which does not happen.
     */
    fun scalar(hash: (counter: Int) -> ByteArray): ByteArray? {
        for (counter in 0..255) {
            val digest = hash(counter)
            try {
                val d = NUT13.reducedOnce(digest, ORDER)
                if (!Bytes.isZero(d)) return d
            } finally {
                Bytes.wipe(digest)
            }
        }
        return null
    }

    /** The owner private key for the card with this key, as 32 bytes. The caller wipes it. */
    fun scalar(seed: NUT13.Seed, cardKey: ByteArray): ByteArray? {
        val mac = try {
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(seed.bytes(), "HmacSHA256")) }
        } catch (e: Exception) {
            throw NUT13.Failure.Platform()
        }
        return scalar { counter -> mac.doFinal(hashInput(cardKey, counter)) }
    }

    /** Runs `body` with this card's owner private key, and wipes it after. The key must not be kept or answered. */
    private fun <T> withPrivateKey(seed: NUT13.Seed, cardKey: ByteArray, body: (ByteArray) -> T): T {
        val d = scalar(seed, cardKey) ?: throw NUT13.Failure.InvalidKey()
        try {
            return body(d)
        } finally {
            Bytes.wipe(d)
        }
    }

    /** The owner public key: 65 bytes, 04 ‖ X ‖ Y. */
    fun publicKey(seed: NUT13.Seed, cardKey: ByteArray): ByteArray = withPrivateKey(seed, cardKey) { P256.publicKey(it) }

    /** What the card verifies: "FoxyCard/" and the label, the nonce, the value. */
    fun message(label: Label, nonce: ByteArray, value: ByteArray): ByteArray =
        "FoxyCard/".toByteArray(Charsets.US_ASCII) + label.raw.toByteArray(Charsets.US_ASCII) + nonce + value

    /** The owner's signature for a request that has passed its checks: ECDSA over P-256, SHA-256 of the message, DER. */
    fun signature(request: SignRequest, seed: NUT13.Seed): ByteArray {
        val signed = message(request.label, request.nonce, request.value)
        return withPrivateKey(seed, request.key) { P256.sign(it, signed) }
    }
}
