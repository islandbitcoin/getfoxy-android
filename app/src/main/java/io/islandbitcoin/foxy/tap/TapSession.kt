package io.islandbitcoin.foxy.tap

import io.islandbitcoin.foxy.debug.Log
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.UUID

/**
 * Tap to pay v2: the messages, with no radio in them (Foxy/Bluetooth/TapSession.swift).
 *
 * Both sides of the conversation live here as one type with two roles, so a
 * payer and a receiver can be run against each other in a test. The radio
 * (TapReceiverLink, TapPayerLink and the GATT layer under them) does nothing
 * but carry bytes between this and the air.
 *
 *     M1  payer → receiver   its version, and a promise about a key not yet shown
 *     M2  receiver → payer   its version, and the receiver's key and nonce
 *     M3  payer → receiver   the key it promised; the receiver checks it
 *     M4  receiver → payer   the offer, sealed
 *     M5  payer → receiver   the payment, sealed (in parts when large)
 *     M6  receiver → payer   what happened to it, sealed
 *     M7  receiver → payer   change, M8 payer → receiver "kept"
 *     M9  receiver → payer   a person is deciding
 *     M10 payer → receiver   a price or a word, M11 receiver → payer the terms
 *     M12 payer → receiver   "say again what you sealed from counter n on"
 *
 * [privateKey] and [nonce] are made fresh for every session; they are
 * parameters only so the tests can replay the iOS code's own runs
 * (TapVectorsTest).
 */
class TapSession(
    val role: Role,
    val service: UUID,
    privateKey: ByteArray = TapCrypto.newPrivateKey(),
    nonce: ByteArray = TapCrypto.randomNonce(),
    private val diary: (String) -> Unit = Log::line,
) {
    enum class Role { PAYER, RECEIVER }

    /** What the caller should do with what came back. */
    sealed class Step {
        /** Put these bytes on the wire. */
        class Send(val bytes: ByteArray) : Step() {
            override fun equals(other: Any?) = other is Send && other.bytes.contentEquals(bytes)
            override fun hashCode() = bytes.contentHashCode()
            override fun toString() = "Send(${bytes.size} bytes)"
        }

        /** Nothing to do yet. */
        data object Waiting : Step()

        /** Both sides agree; this is what goes on the two screens. */
        data class Linked(val code: String) : Step()

        /** The offer arrived (payer only). */
        data class Offer(val text: String, val code: String) : Step()

        /** The payment arrived (receiver only). */
        data class Payment(val text: String) : Step()

        /** The receiver's word on the payment (payer only). */
        data class Result(val text: String) : Step()

        /** Drop the link. The reason is for Foxy's own log, never the wire. */
        data class Stop(val why: String) : Step()

        /**
         * Drop the link and say why on the screen: the other phone speaks
         * another version. `reply`, when there is one, goes on the wire first.
         */
        class OtherVersion(val theirs: Int, val reply: ByteArray?) : Step() {
            override fun equals(other: Any?): Boolean {
                if (other !is OtherVersion || other.theirs != theirs) return false
                val a = reply
                val b = other.reply
                return if (a == null || b == null) a == null && b == null else a.contentEquals(b)
            }

            override fun hashCode() = theirs * 31 + (reply?.contentHashCode() ?: 0)
            override fun toString() = "OtherVersion($theirs, ${reply?.size})"
        }

        /** M7, at the payer: a token for the change owed. */
        data class Change(val text: String) : Step()

        /** M8, at the receiver: the payer's page has written that change down. */
        data object ChangeTaken : Step()

        /** M10, at the receiver: the payer's price, or its word. */
        data class Quote(val text: String) : Step()

        /** M11, at the payer: the receiver agreed, and this is the request. */
        data class Terms(val text: String) : Step()

        /** M9, at the payer: a person on the other phone is deciding. */
        data object Asking : Step()

        /** M12, at the receiver: the payer asks for anything sealed from this counter on. */
        data class Again(val from: ULong) : Step()

        /** At the receiver: how many bytes of payment are about to arrive. */
        data class PaymentSize(val bytes: Long) : Step()
    }

    internal enum class Kind(val byte: Int) {
        COMMIT(0x01), HELLO(0x02), REVEAL(0x03),
        OFFER(0x04), PAYMENT(0x05), RESULT(0x06),
        CHANGE(0x07), CHANGE_TAKEN(0x08),
        ASKING(0x09),
        QUOTE(0x0A), TERMS(0x0B),
        AGAIN(0x0C),
        PAYMENT_PART(0x0D), PAYMENT_SIZE(0x0E),
        ;

        /** As Swift prints the case, for the "where it does not belong" reason. */
        val swiftName: String
            get() = when (this) {
                CHANGE_TAKEN -> "changeTaken"
                PAYMENT_PART -> "paymentPart"
                PAYMENT_SIZE -> "paymentSize"
                else -> name.lowercase()
            }

        companion object {
            fun of(b: Int): Kind? = entries.firstOrNull { it.byte == b }
        }
    }

    private val priv: ByteArray = privateKey.copyOf()
    private val myNonce: ByteArray = nonce.copyOf()
    private var keys: TapCrypto.Keys? = null
    private var theirCommit = ByteArray(0)
    private var sentCounter: ULong = 0u
    private var heardCounter: ULong = 0u
    private val said = HashSet<String>()

    /** What has arrived of a payment that is coming in parts. */
    private var gathered = ByteArray(0)

    /** What this receiver has sealed since the payment arrived, with each one's counter, to be said again (M12). */
    private val repeatable = ArrayList<Pair<ULong, ByteArray>>()

    init {
        require(priv.size == TapCrypto.KEY_LENGTH) { "a private key is 32 bytes" }
        require(myNonce.size == TapCrypto.NONCE_LENGTH) { "a nonce is 16 bytes" }
    }

    val publicKey: ByteArray = TapCrypto.publicKey(priv) ?: error("a private key is 32 bytes")

    /** The four digits, once there are any. */
    val code: String? get() = keys?.code

    /** The frames sealed under [counter] or later, oldest first. */
    fun framesSince(counter: ULong): List<ByteArray> = repeatable.filter { it.first >= counter }.map { it.second }

    /** The payer speaks first. */
    fun begin(): Step {
        if (role != Role.PAYER) return Step.Waiting
        val commit = TapCrypto.commitment(publicKey, myNonce)
        note("handshake: promising a key")
        return Step.Send(frame(Kind.COMMIT, byteArrayOf(TapCrypto.VERSION.toByte()) + commit))
    }

    /** One message off the wire. */
    fun received(body: ByteArray): Step {
        val first = body.firstOrNull()?.toInt()?.and(0xff) ?: return Step.Stop("a message of no kind Foxy knows")
        val kind = Kind.of(first) ?: return Step.Stop("a message of no kind Foxy knows")
        val rest = body.copyOfRange(1, body.size)
        return when {
            role == Role.RECEIVER && kind == Kind.COMMIT -> {
                // a promise with no version in front of it is a Foxy from before one was sent
                if (rest.size == 32) {
                    note("handshake: the payer is a Foxy from before the version was sent")
                    return Step.OtherVersion(2, null)
                }
                // the version first, whatever follows it
                val theirs = rest.firstOrNull()?.toInt()?.and(0xff) ?: return Step.Stop("a promise of the wrong size")
                if (theirs != TapCrypto.VERSION) {
                    note("handshake: the payer speaks version $theirs, this phone ${TapCrypto.VERSION}")
                    return Step.OtherVersion(theirs, frame(Kind.HELLO, byteArrayOf(TapCrypto.VERSION.toByte())))
                }
                if (rest.size != 1 + 32) return Step.Stop("a promise of the wrong size")
                theirCommit = rest.copyOfRange(1, rest.size)
                note("handshake: a payer promised a key")
                Step.Send(frame(Kind.HELLO, byteArrayOf(TapCrypto.VERSION.toByte()) + publicKey + myNonce))
            }

            role == Role.PAYER && kind == Kind.HELLO -> {
                // an older receiver stops at this phone's promise and never says hello; kept for the one that does
                if (rest.size == 32 + TapCrypto.NONCE_LENGTH) {
                    note("handshake: the receiver is a Foxy from before the version was sent")
                    return Step.OtherVersion(2, null)
                }
                val theirs = rest.firstOrNull()?.toInt()?.and(0xff) ?: return Step.Stop("a hello of the wrong size")
                if (theirs != TapCrypto.VERSION) {
                    note("handshake: the receiver speaks version $theirs, this phone ${TapCrypto.VERSION}")
                    return Step.OtherVersion(theirs, null)
                }
                if (rest.size != 1 + 32 + TapCrypto.NONCE_LENGTH) return Step.Stop("a hello of the wrong size")
                val theirKey = rest.copyOfRange(1, 33)
                val theirNonce = rest.copyOfRange(33, rest.size)
                if (!settle(theirKey, theirNonce, publicKey, myNonce)) return Step.Stop("that key is not a key")
                note("handshake: keys agreed")
                Step.Send(frame(Kind.REVEAL, publicKey + myNonce))
            }

            role == Role.RECEIVER && kind == Kind.REVEAL -> {
                if (rest.size != 32 + TapCrypto.NONCE_LENGTH) return Step.Stop("a reveal of the wrong size")
                val p = rest.copyOfRange(0, 32)
                val np = rest.copyOfRange(32, rest.size)
                // the promise is the whole protection
                if (!TapCrypto.commitment(p, np).contentEquals(theirCommit)) {
                    return Step.Stop("the key shown is not the key promised")
                }
                if (!settle(publicKey, myNonce, p, np)) return Step.Stop("that key is not a key")
                note("handshake: keys agreed, the code is on screen")
                Step.Linked(keys?.code ?: "")
            }

            role == Role.RECEIVER && kind == Kind.PAYMENT -> {
                val k = keys ?: return Step.Stop("a payment before there was a key")
                val b = openBody(rest, k.payerToReceiver, k, first) ?: return Step.Stop("a payment that would not open")
                heardCounter++
                // the last part of several, or the only one
                val whole = gathered + b
                gathered = ByteArray(0)
                val joined = utf8(whole) ?: return Step.Stop("a payment whose parts do not read as text")
                note(
                    "a payment arrived, sealed (${rest.size} bytes on the wire" +
                        (if (whole.size > b.size) ", ${whole.size} in all" else "") + ")",
                )
                Step.Payment(joined)
            }

            role == Role.RECEIVER && kind == Kind.PAYMENT_PART -> {
                val k = keys ?: return Step.Stop("part of a payment before there was a key")
                val b = openBody(rest, k.payerToReceiver, k, first)
                    ?: return Step.Stop("part of a payment that would not open")
                heardCounter++
                gathered += b
                if (gathered.size > TapCrypto.MOST_PAYMENT_TOTAL) {
                    gathered = ByteArray(0)
                    return Step.Stop("a payment bigger than any Foxy sends")
                }
                Step.Waiting
            }

            role == Role.RECEIVER && kind == Kind.PAYMENT_SIZE -> {
                val k = keys ?: return Step.Stop("a size before there was a key")
                val n = openBody(rest, k.payerToReceiver, k, first)?.let(::utf8)?.let(::swiftInt)
                if (n == null || n <= 0) return Step.Stop("a size that would not open")
                heardCounter++
                Step.PaymentSize(n)
            }

            role == Role.PAYER && kind == Kind.OFFER -> {
                val k = keys ?: return Step.Stop("an offer before there was a key")
                val text = openBody(rest, k.receiverToPayer, k, first)?.let(::utf8)
                    ?: return Step.Stop("an offer that would not open")
                heardCounter++
                note("the offer arrived, sealed (${rest.size} bytes on the wire)")
                Step.Offer(text, k.code)
            }

            role == Role.PAYER && kind == Kind.CHANGE -> {
                val k = keys ?: return Step.Stop("change before there was a key")
                val text = openBody(rest, k.receiverToPayer, k, first)?.let(::utf8)
                    ?: return Step.Stop("change that would not open")
                heardCounter++
                note("change arrived, sealed (${rest.size} bytes on the wire)")
                Step.Change(text)
            }

            role == Role.RECEIVER && kind == Kind.AGAIN -> {
                val k = keys ?: return Step.Stop("a repeat asked for before there was a key")
                val from = openBody(rest, k.payerToReceiver, k, first)?.let(::utf8)?.let(::swiftUInt64)
                    ?: return Step.Stop("a repeat request that would not open")
                heardCounter++
                note("the payer was away and asks for what was said from message $from on")
                Step.Again(from)
            }

            role == Role.RECEIVER && kind == Kind.CHANGE_TAKEN -> {
                val k = keys ?: return Step.Stop("a change receipt before there was a key")
                openBody(rest, k.payerToReceiver, k, first) ?: return Step.Stop("a change receipt that would not open")
                heardCounter++
                note("the payer kept the change")
                Step.ChangeTaken
            }

            role == Role.RECEIVER && kind == Kind.QUOTE -> {
                val k = keys ?: return Step.Stop("a quote before there was a key")
                val text = openBody(rest, k.payerToReceiver, k, first)?.let(::utf8)
                    ?: return Step.Stop("a quote that would not open")
                heardCounter++
                note("the payer's price arrived, sealed (${rest.size} bytes on the wire)")
                Step.Quote(text)
            }

            role == Role.PAYER && kind == Kind.TERMS -> {
                val k = keys ?: return Step.Stop("terms before there was a key")
                val text = openBody(rest, k.receiverToPayer, k, first)?.let(::utf8)
                    ?: return Step.Stop("terms that would not open")
                heardCounter++
                note("they agreed the price; the request arrived, sealed (${rest.size} bytes on the wire)")
                Step.Terms(text)
            }

            role == Role.PAYER && kind == Kind.ASKING -> {
                val k = keys ?: return Step.Stop("a wait before there was a key")
                openBody(rest, k.receiverToPayer, k, first) ?: return Step.Stop("a wait that would not open")
                heardCounter++
                note("they are asking a person about this payment")
                Step.Asking
            }

            role == Role.PAYER && kind == Kind.RESULT -> {
                val k = keys ?: return Step.Stop("a result before there was a key")
                val text = openBody(rest, k.receiverToPayer, k, first)?.let(::utf8)
                    ?: return Step.Stop("a result that would not open")
                heardCounter++
                note("the receiver answered")
                Step.Result(text)
            }

            else -> Step.Stop("a ${kind.swiftName} where it does not belong")
        }
    }

    /** Opened under the next counter it expects, and unpadded; null for either failing. */
    private fun openBody(sealed: ByteArray, key: ByteArray, k: TapCrypto.Keys, kind: Int): ByteArray? =
        TapCrypto.open(sealed, key, heardCounter, k.transcript, kind)?.let(TapCrypto::unpad)

    /** The receiver's offer, sealed and padded to one size. */
    fun sealOffer(text: String): ByteArray? {
        val k = keys
        if (role != Role.RECEIVER || k == null) return null
        val padded = TapCrypto.pad(text.toByteArray(Charsets.UTF_8), TapCrypto.OFFER_SIZE) ?: run {
            note("the offer is too big to hand over (${text.toByteArray(Charsets.UTF_8).size} bytes)")
            return null
        }
        return sealed(Kind.OFFER, padded, k.receiverToPayer)
    }

    /** M10: the payer's price, and the sats it makes of the dollar amount. */
    fun sealQuote(text: String): ByteArray? {
        val k = keys
        if (role != Role.PAYER || k == null) return null
        val padded = TapCrypto.pad(text.toByteArray(Charsets.UTF_8), TapCrypto.RESULT_SIZE) ?: return null
        return sealed(Kind.QUOTE, padded, k.payerToReceiver)
    }

    /** M11: the receiver agreed to that price; here is the request, in sats. */
    fun sealTerms(text: String): ByteArray? {
        val k = keys
        if (role != Role.RECEIVER || k == null) return null
        val padded = TapCrypto.pad(text.toByteArray(Charsets.UTF_8), TapCrypto.OFFER_SIZE) ?: run {
            note("the agreed request is too big to hand over")
            return null
        }
        return sealed(Kind.TERMS, padded, k.receiverToPayer)
    }

    /** The payer's ecash, sealed and padded up to a whole block. */
    fun sealPayment(text: String): ByteArray? {
        val k = keys
        if (role != Role.PAYER || k == null) return null
        val body = text.toByteArray(Charsets.UTF_8)
        val padded = TapCrypto.padPayment(body) ?: run {
            note("the payment is too big to hand over (${body.size} bytes)")
            return null
        }
        note("handing the payment over, padded to ${padded.size} bytes")
        return sealed(Kind.PAYMENT, padded, k.payerToReceiver)
    }

    /** The same payment as the messages it takes: one, or parts and a last. */
    fun sealPaymentFrames(text: String): List<ByteArray>? {
        val k = keys
        if (role != Role.PAYER || k == null) return null
        val body = text.toByteArray(Charsets.UTF_8)
        if (body.size > TapCrypto.MOST_PAYMENT_TOTAL) {
            note("the payment is too big to hand over (${body.size} bytes)")
            return null
        }
        if (body.size <= TapCrypto.PART_BODY) return sealPayment(text)?.let { listOf(it) }
        val frames = ArrayList<ByteArray>()
        var at = 0
        while (at < body.size) {
            val end = minOf(at + TapCrypto.PART_BODY, body.size)
            val padded = TapCrypto.padPayment(body.copyOfRange(at, end)) ?: return null
            val frame = sealed(if (end == body.size) Kind.PAYMENT else Kind.PAYMENT_PART, padded, k.payerToReceiver)
                ?: return null
            frames.add(frame)
            at = end
        }
        note("handing the payment over, ${body.size} bytes in ${frames.size} parts")
        return frames
    }

    /**
     * How many bytes a payment will be on the wire, near enough, without sealing
     * anything: the size has to be sealed before the payment, so it is worked
     * out from the length. It is only for a percentage.
     */
    fun paymentWireEstimate(text: String): Int {
        var left = text.toByteArray(Charsets.UTF_8).size
        val block = TapCrypto.PAYMENT_BLOCK
        var total = 0
        do {
            val chunk = minOf(left, TapCrypto.PART_BODY)
            total += ((chunk + 4 + block - 1) / block) * block + 40
            left -= chunk
        } while (left > 0)
        return total
    }

    /** How many bytes are about to be sent, said first when it is a lot. */
    fun sealPaymentSize(bytes: Int): ByteArray? {
        val k = keys
        if (role != Role.PAYER || k == null) return null
        val padded = TapCrypto.pad(bytes.toString().toByteArray(Charsets.UTF_8), TapCrypto.RESULT_SIZE) ?: return null
        return sealed(Kind.PAYMENT_SIZE, padded, k.payerToReceiver)
    }

    /** What the receiver made of it. */
    fun sealResult(text: String): ByteArray? {
        val k = keys
        if (role != Role.RECEIVER || k == null) return null
        val padded = TapCrypto.pad(text.toByteArray(Charsets.UTF_8), TapCrypto.RESULT_SIZE) ?: return null
        return sealed(Kind.RESULT, padded, k.receiverToPayer)
    }

    /**
     * M7: the change owed. One size whenever it fits; change that does not fit
     * goes in whole blocks, as a payment does, rather than not at all.
     */
    fun sealChange(text: String): ByteArray? {
        val k = keys
        if (role != Role.RECEIVER || k == null) return null
        val body = text.toByteArray(Charsets.UTF_8)
        val padded = TapCrypto.pad(body, TapCrypto.CHANGE_SIZE) ?: TapCrypto.padPayment(body) ?: run {
            note("the change is too big to hand over (${body.size} bytes)")
            return null
        }
        if (padded.size != TapCrypto.CHANGE_SIZE) note("the change is large, so it goes in ${padded.size} bytes")
        return sealed(Kind.CHANGE, padded, k.receiverToPayer)
    }

    /** M9: a person on this phone is being asked about the payment. */
    fun sealAsking(): ByteArray? {
        val k = keys
        if (role != Role.RECEIVER || k == null) return null
        val padded = TapCrypto.pad("asking".toByteArray(Charsets.UTF_8), TapCrypto.RESULT_SIZE) ?: return null
        return sealed(Kind.ASKING, padded, k.receiverToPayer)
    }

    /** M12: this payer was away; say again whatever came after what it has opened. */
    fun sealAgain(): ByteArray? {
        val k = keys
        if (role != Role.PAYER || k == null) return null
        val padded = TapCrypto.pad(heardCounter.toString().toByteArray(Charsets.UTF_8), TapCrypto.RESULT_SIZE)
            ?: return null
        return sealed(Kind.AGAIN, padded, k.payerToReceiver)
    }

    /** M8: the payer's page has the change written down. */
    fun sealChangeTaken(): ByteArray? {
        val k = keys
        if (role != Role.PAYER || k == null) return null
        val padded = TapCrypto.pad("kept".toByteArray(Charsets.UTF_8), TapCrypto.RESULT_SIZE) ?: return null
        return sealed(Kind.CHANGE_TAKEN, padded, k.payerToReceiver)
    }

    /** Said by the caller when it drops a link, so a stopped tap leaves a reason behind. */
    fun stopped(why: String) {
        diary("[tap] ${if (role == Role.PAYER) "pay" else "receive"}: stopped — $why")
    }

    // MARK: the small print

    private fun sealed(kind: Kind, padded: ByteArray, key: ByteArray): ByteArray? {
        val k = keys ?: return null
        val box = TapCrypto.seal(padded, key, sentCounter, k.transcript, kind.byte) ?: return null
        val used = sentCounter
        sentCounter++
        val out = frame(kind, box)
        // kept, to be said again to a payer that was away (M12); a handful at most
        if (role == Role.RECEIVER && (kind == Kind.ASKING || kind == Kind.RESULT || kind == Kind.CHANGE)) {
            repeatable.add(used to out)
            while (repeatable.size > 8) repeatable.removeAt(0)
        }
        return out
    }

    private fun frame(kind: Kind, body: ByteArray): ByteArray = byteArrayOf(kind.byte.toByte()) + body

    private fun settle(receiverKey: ByteArray, receiverNonce: ByteArray, payerKey: ByteArray, payerNonce: ByteArray): Boolean {
        val other = if (role == Role.PAYER) receiverKey else payerKey
        val shared = TapCrypto.sharedSecret(priv, other) ?: return false
        val th = TapCrypto.transcript(service = service, r = receiverKey, p = payerKey, nr = receiverNonce, np = payerNonce)
        keys = TapCrypto.keys(shared, th)
        return true
    }

    /**
     * Foxy's own log, each line said once: which side, how far it got, how big
     * the sealed messages were, and why it stopped. No key, no code, no invoice
     * and no ecash.
     */
    private fun note(what: String) {
        if (!said.add(what)) return
        diary("[tap] ${if (role == Role.PAYER) "pay" else "receive"}: $what")
    }

    companion object {
        /** Whether a byte is the kind of a message that carries payment. */
        fun carriesPayment(kind: Int?): Boolean = kind == Kind.PAYMENT.byte || kind == Kind.PAYMENT_PART.byte

        /** UTF-8 that is UTF-8, as `String(data:encoding: .utf8)`: null for anything malformed, never a replacement character. */
        internal fun utf8(bytes: ByteArray): String? = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            null
        }

        /** Swift's `Int(text)` on a 64-bit phone: an optional sign and ASCII digits only, no overflow. */
        internal fun swiftInt(text: String): Long? {
            val v = swiftDigits(text) ?: return null
            val n = if (text.startsWith("-")) v.negate() else v
            return if (n.bitLength() < 64) n.toLong() else null
        }

        /** Swift's `UInt64(text)`: an optional `+` and ASCII digits, within 64 bits; no minus sign. */
        internal fun swiftUInt64(text: String): ULong? {
            if (text.startsWith("-")) return null
            val v = swiftDigits(text) ?: return null
            return if (v.bitLength() <= 64) v.toString().toULong() else null
        }

        private fun swiftDigits(text: String): java.math.BigInteger? {
            val digits = if (text.startsWith("+") || text.startsWith("-")) text.substring(1) else text
            if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return null
            return java.math.BigInteger(digits)
        }
    }
}
