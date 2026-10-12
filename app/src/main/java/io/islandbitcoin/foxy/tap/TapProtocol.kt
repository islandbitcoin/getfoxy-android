package io.islandbitcoin.foxy.tap

import io.islandbitcoin.foxy.bridge.TapRules
import java.security.MessageDigest
import java.util.Locale

/**
 * Tap to pay: the parts with no radio in them (Foxy/Bluetooth/TapProtocol.swift):
 * the framing both directions use, the first version's code and text assembler,
 * and the bounds on what a stranger in range may make this phone hold.
 *
 * What an offer may be (`isPayload`, `isInvoice`, `isRequest`) is
 * [TapRules], which the bridge already checks `tapReceiveStart` with; this
 * reads the same rules rather than a second copy of them.
 */
object TapProtocol {

    /**
     * The most a payment message may announce. Below 65535 on purpose: the frame
     * says its length in two bytes, so a cap at or above that is not a cap.
     */
    const val MAX_PAYMENT = 48 * 1024

    const val NONCE_LENGTH = 16
    const val MAX_INVOICE = TapRules.MOST_INVOICE_BYTES

    /** Both ways of being paid, and the JSON around them. */
    const val MAX_PAYLOAD = TapRules.MOST_PAYLOAD_BYTES

    /** The most a frame's two-byte length can say. */
    const val MOST_FRAME = 0xffff

    /**
     * The first version's four digits: SHA-256("foxy tap v1" ‖ 0 ‖ payload ‖ nonce),
     * the first four bytes big-endian, mod 10000. Kept, as iOS keeps it.
     */
    fun code(payload: String, nonce: ByteArray): String {
        val h = MessageDigest.getInstance("SHA-256")
        h.update("foxy tap v1".toByteArray(Charsets.UTF_8))
        h.update(0)
        h.update(payload.toByteArray(Charsets.UTF_8))
        h.update(nonce)
        val d = h.digest()
        val n = ((d[0].toLong() and 0xff) shl 24) or ((d[1].toLong() and 0xff) shl 16) or
            ((d[2].toLong() and 0xff) shl 8) or (d[3].toLong() and 0xff)
        return String.format(Locale.ROOT, "%04d", n % 10000)
    }

    fun isInvoice(s: String): Boolean = TapRules.isInvoice(s)

    fun isRequest(s: String): Boolean = TapRules.isRequest(s)

    fun isPayload(s: String): Boolean = TapRules.isPayload(s)

    /**
     * A message as it goes over the air: two bytes of length, big-endian, then
     * its bytes. Null for one the two bytes cannot say, which no sealed message
     * reaches (one is at most 48 KB and a little).
     */
    fun framed(body: ByteArray): ByteArray? {
        if (body.size > MOST_FRAME) return null
        return byteArrayOf((body.size ushr 8).toByte(), body.size.toByte()) + body
    }

    /** The text of the first version's offer, framed the same way. */
    fun frame(text: String): ByteArray? = framed(text.toByteArray(Charsets.UTF_8))

    /** A framed message cut into what one write or one notification carries. */
    fun pieces(frame: ByteArray, size: Int): List<ByteArray> {
        val step = maxOf(20, size)
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i < frame.size) {
            out.add(frame.copyOfRange(i, minOf(i + step, frame.size)))
            i += step
        }
        return out
    }

    /**
     * The pieces of a framed text message put back together (the first
     * version's offer, and a payment as text). [limit] and [check] are what the
     * two directions differ by.
     */
    class Assembler(
        private val limit: Int = MAX_PAYLOAD,
        private val check: (String) -> Boolean = TapRules::isPayload,
    ) {
        sealed class Result {
            data object More : Result()
            data class Done(val text: String) : Result()
            data object Bad : Result()
        }

        private var buffer = ByteArray(0)

        fun add(piece: ByteArray): Result {
            buffer += piece
            if (buffer.size < 2) return Result.More
            val want = ((buffer[0].toInt() and 0xff) shl 8) or (buffer[1].toInt() and 0xff)
            if (want <= 0 || want > limit) return Result.Bad
            if (buffer.size < 2 + want) return Result.More
            if (buffer.size != 2 + want) return Result.Bad
            val text = TapSession.utf8(buffer.copyOfRange(2, buffer.size)) ?: return Result.Bad
            return if (check(text)) Result.Done(text) else Result.Bad
        }

        companion object {
            /** For the payment direction: bigger, and text is all it must be. */
            fun forPayment() = Assembler(MAX_PAYMENT) { it.isNotEmpty() }
        }
    }

    /**
     * The same framing, for messages that are not text: v2's public keys,
     * nonces and ciphertext. Hands back what arrived, unchanged.
     */
    class Bytes(private val limit: Int = MAX_PAYMENT) {
        sealed class Result {
            data object More : Result()
            class Done(val body: ByteArray) : Result() {
                override fun equals(other: Any?) = other is Done && other.body.contentEquals(body)
                override fun hashCode() = body.contentHashCode()
            }
            data object Bad : Result()
        }

        /** How much of the message in hand has arrived, how long it says it is, and its kind. */
        data class Progress(val have: Int, val want: Int, val kind: Int?)

        var buffer = ByteArray(0)
            private set

        /** Null until the length is known. */
        val progress: Progress?
            get() {
                if (buffer.size < 2) return null
                val want = 2 + (((buffer[0].toInt() and 0xff) shl 8) or (buffer[1].toInt() and 0xff))
                return Progress(buffer.size, want, if (buffer.size > 2) buffer[2].toInt() and 0xff else null)
            }

        fun add(piece: ByteArray): Result {
            buffer += piece
            if (buffer.size < 2) return Result.More
            val want = ((buffer[0].toInt() and 0xff) shl 8) or (buffer[1].toInt() and 0xff)
            if (want <= 0 || want > limit) return Result.Bad
            if (buffer.size < 2 + want) return Result.More
            if (buffer.size != 2 + want) return Result.Bad
            val body = buffer.copyOfRange(2, buffer.size)
            buffer = ByteArray(0)
            return Result.Done(body)
        }
    }
}

/**
 * Which edges to hold together, by the pair of phones (TapProtocol.swift
 * `TapEdges`). The payer works out both edges and names the receiver's by its
 * choice of near door. Android models are not measured yet (ENG-671): an
 * unknown receiver takes the default, as on iOS.
 */
object TapEdges {
    /** Receivers a payer holds its right edge against (the receiver's left). */
    val leftEdgeReceivers: Set<String> = setOf(
        "iPhone18,1", // 17 Pro, measured
        "iPhone18,2", // 17 Pro Max, assumed
        "iPhone19,2", // 18 Pro, assumed
        "iPhone19,3", "iPhone19,7", // 18 Pro Max, assumed
    )

    data class Edges(val payer: String, val receiver: String)

    @Suppress("UNUSED_PARAMETER")
    fun edges(payer: String, receiver: String): Edges =
        if (receiver in leftEdgeReceivers) Edges("right", "left") else Edges("left", "right")
}

/**
 * What becomes of change a receiver has sent, as the payer's word arrives or
 * does not (TapProtocol.swift `ChangeHandover`). The page is answered once:
 * [Word.KEPT] when the payer says it wrote the change down, [Word.NOT_HANDED]
 * when the wait runs out or the payer goes first. A "kept" after that is
 * [Word.LATE]: the page takes the code down and writes "given back".
 */
class ChangeHandover {
    enum class Word { KEPT, NOT_HANDED, LATE }

    var answered = false
        private set

    /** The payer said it kept the change (`true`), or the wait ended without that. What to tell the page, or null. */
    fun heard(kept: Boolean): Word? {
        if (answered) return if (kept) Word.LATE else null
        answered = true
        return if (kept) Word.KEPT else Word.NOT_HANDED
    }
}

/** How far something large has got, said every four points and not more often (`TapProgress`). */
class TapProgress {
    // four below zero, so that the first word (0) is said at once
    private var last = -4

    /** The percentage to say now, or null when it has not moved enough. `cap` is 99 on the side still waiting. */
    fun step(done: Int, total: Int, cap: Int = 100): Int? {
        if (total <= 0) return null
        val pct = maxOf(0, minOf(cap, (done.toLong() * 100 / total).toInt()))
        if (pct < last + 4) return null
        last = pct
        return pct
    }
}

/**
 * Who holds a receiver's one place (`TapPlace`). The place is taken by the first
 * word (M1, a write), never by a subscribe: a phone that only listens holds nothing.
 */
object TapPlace {
    @Suppress("UNUSED_PARAMETER")
    fun taken(writer: String, holder: String?, listening: Boolean, linked: Boolean, early: Boolean): Boolean =
        holder == null && listening && !linked && !early
}

/**
 * What a paying phone does about a word that would not go out (`TapWriteFailure`).
 * A sealed word takes its place in the count whether or not it goes, and a place
 * is never given back, so any other word lets the link go.
 */
enum class TapWriteFailure {
    /** The question "what became of it": the answer can still come unasked. */
    WAIT_FOR_ANSWER,

    /** The payment itself: it is the page's to finish, by another road. */
    PAYMENT_LOST,

    /** The word that change was kept: the last word on this link anyway. */
    NOTHING_MORE,

    /** Any other word: this link cannot carry the next one. */
    LET_LINK_GO,
    ;

    companion object {
        fun after(question: Boolean, payment: Boolean, changeKept: Boolean): TapWriteFailure = when {
            question -> WAIT_FOR_ANSWER
            payment -> PAYMENT_LOST
            changeKept -> NOTHING_MORE
            else -> LET_LINK_GO
        }
    }
}

/** What either side tells the page as it goes (TapLink.swift `TapStage`); the raw value is the page's word. */
enum class TapStage(val word: String) {
    SEARCHING("searching"), FAR("far"), CONNECTING("connecting"), LINKED("linked"),
    SENDING("sending"), RECEIVING("receiving"),
    CODE("code"),
    ASKING("asking"),
    GONE("gone"),
    SHAKE("shake"),
    NEARBY("nearby"),
    VERSION("version"),
    OFF("off"), DENIED("denied"), UNSUPPORTED("unsupported"),
}
