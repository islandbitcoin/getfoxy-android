package io.islandbitcoin.foxy.nostr

import io.islandbitcoin.foxy.bridge.Json
import io.islandbitcoin.foxy.keychain.Bytes
import java.security.SecureRandom

/**
 * Nostr events, and the gift wrap a private message is carried in
 * (Foxy/Nostr/NostrEvent.swift at 8df0bb1).
 *
 * NIP-01 for the event and its id, NIP-59 for the wrapping, NIP-17 for what a
 * private message looks like inside it. A payment is three events, one inside
 * the next:
 *
 *  - the **rumor**, kind 14, holding the payment, unsigned;
 *  - the **seal**, kind 13, the rumor encrypted to the receiver, signed by a key
 *    made for this payment. Wallets check this signature and that the rumor
 *    names the same key (nostr-tools, rust-nostr), so it is a real signature by
 *    a real key, just not one Foxy keeps;
 *  - the **gift wrap**, kind 1059, the seal encrypted to the receiver again,
 *    signed by a second one-time key. This is what relays see: an encrypted
 *    message addressed to the receiver, from nobody.
 *
 * The seal's and the wrap's times are moved back by up to two days, as the NIP
 * says, so a relay cannot line messages up by when they arrived. The rumor keeps
 * the real time; only the receiver reads it.
 */
object NostrEvent {
    data class Event(
        val id: String,
        val pubkey: String,
        val createdAt: Long,
        val kind: Int,
        val tags: List<List<String>>,
        val content: String,
        val sig: String,
    )

    /** NIP-59's two days. */
    const val BACKDATING_S = 172_800L

    private val random = SecureRandom()

    fun now(): Long = System.currentTimeMillis() / 1000

    /** Up to two days back, as NIP-59 asks. Never later than now: relays drop events from the future. */
    fun backdated(now: Long = now()): Long = now - (random.nextInt(BACKDATING_S.toInt() + 1)).toLong()

    // MARK: The three events

    /**
     * The whole wrap, ready to publish, or a throw ([NostrCrypto.Failure]).
     * `payload` is the NUT-18 payment JSON; `receiver` the 32-byte x-only key it is for.
     */
    fun giftWrapped(payload: String, receiver: ByteArray, now: Long = now()): Event {
        val sealKey = NostrCrypto.newKey()
        val wrapKey = NostrCrypto.newKey()
        try {
            val sealPub = NostrCrypto.publicKey(sealKey)
            val receiverHex = hex(receiver)

            // the rumor: unsigned, with its id, as NIP-59 states
            val rumor = Event("", hex(sealPub), now, 14, listOf(listOf("p", receiverHex)), payload, "")
            val rumorJson = json(withId(rumor))

            val toReceiver = NostrCrypto.conversationKey(sealKey, receiver)
            val seal = signed(
                Event("", hex(sealPub), backdated(now), 13, emptyList(),
                    NostrCrypto.encrypt(rumorJson.toByteArray(Charsets.UTF_8), toReceiver), ""),
                sealKey,
            )

            val wrapPub = NostrCrypto.publicKey(wrapKey)
            val fromWrap = NostrCrypto.conversationKey(wrapKey, receiver)
            return signed(
                Event("", hex(wrapPub), backdated(now), 1059, listOf(listOf("p", receiverHex)),
                    NostrCrypto.encrypt(json(seal).toByteArray(Charsets.UTF_8), fromWrap), ""),
                wrapKey,
            )
        } finally {
            // the keys are this payment's, and forgotten with it
            sealKey.fill(0)
            wrapKey.fill(0)
        }
    }

    /**
     * The rumor inside a gift wrap addressed to `secret`, or null: the mirror of
     * [giftWrapped], with every check NIP-59 asks for. A relay hands out whatever
     * it likes, so nothing here is taken on trust:
     *
     *  - the wrap's own signature, over its own id;
     *  - the seal's signature, by the key the seal names;
     *  - the rumor naming that same seal key as its author, which stops anybody
     *    re-sealing somebody else's rumor and passing it off as theirs;
     *  - both ids recomputed from the canonical form rather than believed.
     *
     * The wrap's and the seal's times are meaningless by design ([backdated]), so
     * nothing here reads them.
     */
    fun unwrap(wrap: Event, secret: ByteArray): Event? {
        if (wrap.kind != 1059 || !verified(wrap)) return null
        val sealJson = decryptFrom(wrap.pubkey, wrap.content, secret) ?: return null
        val seal = parse(sealJson) ?: return null
        if (seal.kind != 13 || !verified(seal)) return null
        val rumorJson = decryptFrom(seal.pubkey, seal.content, secret) ?: return null
        val rumor = parse(rumorJson) ?: return null
        if (rumor.kind != 14) return null
        // the seal may only carry its own author's rumor
        if (rumor.pubkey != seal.pubkey) return null
        if (withId(rumor).id != rumor.id) return null
        return rumor
    }

    /** NIP-44 decrypt of `content` sent by `fromPubkey` (hex x-only) to us. */
    private fun decryptFrom(fromPubkey: String, content: String, secret: ByteArray): String? {
        val peer = bytes(fromPubkey)
        if (peer.size != 32) return null
        val key = try {
            NostrCrypto.conversationKey(secret, peer)
        } catch (e: NostrCrypto.Failure) {
            return null
        }
        val plain = NostrCrypto.decrypt(content, key) ?: return null
        return utf8(plain)
    }

    /** Strict UTF-8, as Swift's `String(bytes:encoding: .utf8)`: null for bytes that are not. */
    private fun utf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (e: java.nio.charset.CharacterCodingException) {
        null
    }

    /** The event's own signature over its own recomputed id. */
    fun verified(event: Event): Boolean {
        val key = bytes(event.pubkey)
        val sig = bytes(event.sig)
        if (key.size != 32 || sig.size != 64 || withId(event).id != event.id) return false
        return NostrCrypto.verify(sig, bytes(event.id), key)
    }

    /** An event as a relay sends it, from JSON text. */
    fun parse(text: String): Event? = try {
        fromJson(Json.parse(text))
    } catch (e: Json.Malformed) {
        null
    }

    /**
     * An event out of parsed JSON (NostrEvent.swift `parse`): the id, pubkey and
     * content strings, the kind and time numbers, the tags as arrays of strings
     * (anything else in them dropped, which then fails the id check), and the
     * signature when there is one. Written back out by [canonical] whenever an id
     * is checked, so the escaping on the read side is the writer's.
     */
    fun fromJson(value: Any?): Event? {
        val o = value as? Map<*, *> ?: return null
        val id = o["id"] as? String ?: return null
        val pubkey = o["pubkey"] as? String ?: return null
        val kind = (o["kind"] as? Double)?.takeIf { it.isFinite() && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt() ?: return null
        val createdAt = (o["created_at"] as? Double)?.takeIf { it.isFinite() }?.toLong() ?: return null
        val content = o["content"] as? String ?: return null
        val tags = (o["tags"] as? List<*>)?.mapNotNull { tag -> (tag as? List<*>)?.mapNotNull { it as? String } } ?: emptyList()
        return Event(id, pubkey, createdAt, kind, tags, content, o["sig"] as? String ?: "")
    }

    /** The event with its id filled in: sha256 of the canonical array. */
    fun withId(event: Event): Event = event.copy(id = hex(NostrCrypto.sha256(canonical(event).toByteArray(Charsets.UTF_8))))

    fun signed(event: Event, secret: ByteArray): Event {
        val out = withId(event)
        return out.copy(sig = hex(NostrCrypto.sign(bytes(out.id), secret)))
    }

    // MARK: JSON, written here

    /** What an event's id is taken over (NIP-01): a compact array, no spaces. */
    fun canonical(event: Event): String =
        "[0,\"${event.pubkey}\",${event.createdAt},${event.kind},${tagsJson(event.tags)},${string(event.content)}]"

    /**
     * The event as a relay takes it. A rumor is written without `sig`: it is not
     * signed, and NIP-59 says so. Written by hand, as iOS writes it, so that a
     * mint URL's "/" is never escaped: hashed one way here and read another by
     * the receiver, every id would be wrong.
     */
    fun json(event: Event): String =
        "{\"id\":\"${event.id}\",\"pubkey\":\"${event.pubkey}\",\"created_at\":${event.createdAt}," +
            "\"kind\":${event.kind},\"tags\":${tagsJson(event.tags)},\"content\":${string(event.content)}" +
            (if (event.sig.isEmpty()) "" else ",\"sig\":\"${event.sig}\"") + "}"

    fun tagsJson(tags: List<List<String>>): String =
        "[" + tags.joinToString(",") { tag -> "[" + tag.joinToString(",") { string(it) } + "]" } + "]"

    /** A JSON string as NIP-01 asks: the six named escapes, \u00xx for the other control characters, the rest as it is. */
    fun string(value: String): String {
        val out = StringBuilder(value.length + 2).append('"')
        for (c in value) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (c.code < 0x20) out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        return out.append('"').toString()
    }

    // MARK: Bytes

    fun hex(bytes: ByteArray): String = Bytes.hex(bytes)

    /** Hex to bytes; empty for anything that is not whole bytes of hex. */
    fun bytes(hex: String): ByteArray = Bytes.fromHex(hex) ?: ByteArray(0)
}

/**
 * bech32, for the `nprofile` (NIP-19) at both ends: reading the one a payment
 * request names, and writing the one Foxy's own request carries
 * (NostrEvent.swift `Bech32`).
 */
object Bech32 {
    private const val ALPHABET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    class Decoded(val prefix: String, val data: ByteArray)

    class Profile(val pubkey: ByteArray, val relays: List<String>)

    /**
     * The data part of a bech32 string, as bytes, with its prefix; null when the
     * checksum, the characters or the case do not hold up. NIP-19 lifts bech32's
     * 90-character limit, so length is checked only against 5000.
     */
    fun decode(text: String): Decoded? {
        val lower = text.lowercase(java.util.Locale.ROOT)
        if (!(lower == text || text.uppercase(java.util.Locale.ROOT) == text)) return null
        if (lower.length < 8 || lower.length > 5000) return null
        val split = lower.lastIndexOf('1')
        if (split <= 0) return null
        val prefix = lower.substring(0, split)
        val values = ByteArray(lower.length - split - 1)
        for (i in values.indices) {
            val at = ALPHABET.indexOf(lower[split + 1 + i])
            if (at < 0) return null
            values[i] = at.toByte()
        }
        if (values.size <= 6 || !verify(prefix, values)) return null
        return Decoded(prefix, bits(values.copyOfRange(0, values.size - 6)))
    }

    /** The TLVs of an nprofile: type 0 the 32-byte key, type 1 a relay; unknown types skipped, as NIP-19 says. */
    fun nprofile(text: String): Profile? {
        val d = decode(text) ?: return null
        if (d.prefix != "nprofile") return null
        val data = d.data
        var pubkey: ByteArray? = null
        val relays = mutableListOf<String>()
        var at = 0
        while (at + 2 <= data.size) {
            val type = data[at].toInt() and 0xff
            val length = data[at + 1].toInt() and 0xff
            if (at + 2 + length > data.size) return null
            val value = data.copyOfRange(at + 2, at + 2 + length)
            if (type == 0 && length == 32) pubkey = value
            if (type == 1) utf8(value)?.let { relays.add(it) }
            at += 2 + length
        }
        return Profile(pubkey ?: return null, relays)
    }

    private fun utf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (e: java.nio.charset.CharacterCodingException) {
        null
    }

    /** An `nprofile` naming `pubkey` and the relays it is listened on. */
    fun nprofile(pubkey: ByteArray, relays: List<String>): String? {
        if (pubkey.size != 32) return null
        val tlv = java.io.ByteArrayOutputStream()
        tlv.write(0)
        tlv.write(32)
        tlv.write(pubkey)
        for (relay in relays) {
            val value = relay.toByteArray(Charsets.UTF_8)
            if (value.size > 255) continue
            tlv.write(1)
            tlv.write(value.size)
            tlv.write(value)
        }
        return encode("nprofile", tlv.toByteArray())
    }

    fun encode(prefix: String, data: ByteArray): String? {
        if (prefix.isEmpty() || !prefix.all { it.code in 33..126 }) return null
        val values = fiveBit(data)
        val sum = checksum(prefix, values)
        val out = StringBuilder(prefix).append('1')
        for (v in values + sum) out.append(ALPHABET[v.toInt()])
        return out.toString()
    }

    /** Eight-bit bytes to five-bit groups, the last one zero-padded: the inverse of [bits]. */
    private fun fiveBit(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var acc = 0
        var held = 0
        for (b in data) {
            acc = (acc shl 8) or (b.toInt() and 0xff)
            held += 8
            while (held >= 5) {
                held -= 5
                out.write((acc ushr held) and 31)
            }
            acc = acc and ((1 shl held) - 1)
        }
        if (held > 0) out.write((acc shl (5 - held)) and 31)
        return out.toByteArray()
    }

    private fun bits(values: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var acc = 0
        var held = 0
        for (v in values) {
            acc = (acc shl 5) or v.toInt()
            held += 5
            while (held >= 8) {
                held -= 8
                out.write((acc ushr held) and 0xff)
            }
            acc = acc and ((1 shl held) - 1)
        }
        return out.toByteArray()
    }

    private fun expand(prefix: String): ByteArray {
        val out = ByteArray(prefix.length * 2 + 1)
        for (i in prefix.indices) {
            out[i] = (prefix[i].code ushr 5).toByte()
            out[prefix.length + 1 + i] = (prefix[i].code and 31).toByte()
        }
        return out
    }

    private fun checksum(prefix: String, values: ByteArray): ByteArray {
        val polynomial = polymod(expand(prefix) + values + ByteArray(6)) xor 1
        return ByteArray(6) { ((polynomial ushr (5 * (5 - it))) and 31).toByte() }
    }

    private fun verify(prefix: String, values: ByteArray): Boolean = polymod(expand(prefix) + values) == 1

    private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    private fun polymod(values: ByteArray): Int {
        var check = 1
        for (v in values) {
            val top = check ushr 25
            check = ((check and 0x1ffffff) shl 5) xor (v.toInt() and 0xff)
            for (i in 0 until 5) if ((top ushr i) and 1 == 1) check = check xor GENERATOR[i]
        }
        return check
    }
}
