package io.islandbitcoin.foxy.scanner

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * Animated QR codes: a token too long for one code, shown as a loop of frames
 * (Foxy/Scanner/AnimatedQR.swift, ported byte for byte).
 *
 * cashu.me does this with Blockchain Commons' Uniform Resources (UR): the
 * token's bytes are wrapped as CBOR, cut into fragments and sent as a fountain
 * code, one frame per QR code, `ur:bytes/<n>-<count>/<bytewords>`. The first
 * `count` frames are the fragments themselves; every frame after that is a
 * random mix of several, XORed together, so the loop can be joined at any
 * point and a missed frame is made up by later ones. A token short enough for
 * one frame is `ur:bytes/<bytewords>`.
 *
 * Which fragments a mixed frame holds comes from a random generator seeded by
 * the frame's number and the message checksum, and has to match the encoder's
 * (`@gandlaf21/bc-ur` 1.1.12) exactly, down to how bignumber.js rounds a
 * division: the iOS fixture made by that library (test resources,
 * `fixtures/animated-qr.json`) is the only guard against a one-bit drift.
 *
 * The frames come from whatever QR code is in front of the camera, so nothing
 * in them is trusted: a message over 100 kB, over 2,000 fragments or with
 * fragments of different sizes is refused, a message is kept only if its
 * CRC-32 matches, and only `ur:bytes` that decodes to UTF-8 text is handed on.
 * Not thread-safe: one reader per scanner, used from its analysis thread.
 */
class AnimatedQRReader {
    sealed class Step {
        /** Not an animated code: the scanner handles it as before. */
        object NotAnimated : Step() {
            override fun toString() = "NotAnimated"
        }

        /** A frame was taken; this share of the fragments is in hand. */
        data class Progress(val share: Double) : Step()

        /** The whole message. */
        data class Done(val text: String) : Step()

        /**
         * Nothing usable: a frame that does not read before any frame has, or a
         * whole message that fails its checksum (the reader starts over). A bad
         * frame once a message is under way is skipped, not this.
         */
        object Rejected : Step() {
            override fun toString() = "Rejected"
        }
    }

    companion object {
        const val MAX_MESSAGE_BYTES = 100_000
        const val MAX_FRAGMENTS = 2_000
        const val MAX_FRAGMENT_BYTES = 10_000

        /** "n-count", both positive; n wraps to 32 bits, as the encoder's does. */
        fun sequence(s: String): Pair<Long, Int>? {
            val bits = s.split("-")
            if (bits.size != 2 || bits[0].isEmpty() || bits[1].isEmpty() || bits[0].length > 10 || bits[1].length > 6) return null
            if (!bits[0].all { it in '0'..'9' } || !bits[1].all { it in '0'..'9' }) return null
            val n = bits[0].toLong()
            val count = bits[1].toInt()
            if (n < 1 || count < 1) return null
            return (n and 0xFFFF_FFFFL) to count
        }

        /** The UR's CBOR: one byte string, read as UTF-8 text. */
        fun textFromCbor(cbor: ByteArray): String? {
            val reader = MiniCBOR(cbor)
            val bytes = reader.byteString() ?: return null
            if (!reader.atEnd || bytes.isEmpty()) return null
            return utf8(bytes)
        }

        internal fun utf8(bytes: ByteArray): String? = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            null
        }

        private fun xor(a: ByteArray, b: ByteArray) {
            for (k in 0 until minOf(a.size, b.size)) a[k] = (a[k].toInt() xor b[k].toInt()).toByte()
        }

        /** a ⊂ b, and not equal. */
        private fun strictSubset(a: Set<Int>, b: Set<Int>) = a.size < b.size && b.containsAll(a)
    }

    private class Part(val indexes: Set<Int>, val fragment: ByteArray)

    private var seqLength = 0
    private var messageLength = 0
    private var checksum = 0L
    private var fragmentLength = 0
    private val simple = HashMap<Int, ByteArray>()
    private var mixed = ArrayList<Part>()
    private val queue = ArrayDeque<Part>()
    private val seen = HashSet<String>()
    private var finished: String? = null

    fun reset() {
        seqLength = 0
        messageLength = 0
        checksum = 0
        fragmentLength = 0
        simple.clear()
        mixed = ArrayList()
        queue.clear()
        seen.clear()
        finished = null
    }

    /** The share of fragments in hand, 0 before the first frame. */
    val progress: Double
        get() = if (seqLength == 0) 0.0 else minOf(0.99, simple.size.toDouble() / seqLength.toDouble())

    fun receive(scanned: String): Step {
        val text = scanned.lowercase(java.util.Locale.ROOT)
        if (!text.startsWith("ur:")) return Step.NotAnimated
        finished?.let { return Step.Done(it) }
        // the camera reports the frame on screen many times a second
        if (text in seen) return Step.Progress(progress)
        if (seen.size > 4 * MAX_FRAGMENTS) seen.clear()
        seen.add(text)

        val parts = text.substring(3).split("/")
        if ((parts.size != 2 && parts.size != 3) || parts[0] != "bytes") return skip()

        if (parts.size == 2) {
            val cbor = Bytewords.decodeMinimal(parts[1]) ?: return skip()
            val token = textFromCbor(cbor) ?: return skip()
            finished = token
            return Step.Done(token)
        }

        val (seqNum, count) = sequence(parts[1]) ?: return skip()
        val payload = Bytewords.decodeMinimal(parts[2]) ?: return skip()
        val frame = MiniCBOR.fountainPart(payload) ?: return skip()
        if (frame.seqNum != seqNum || frame.seqLength != count) return skip()

        if (frame.seqLength < 1 || frame.seqLength > MAX_FRAGMENTS ||
            frame.messageLength < 1 || frame.messageLength > MAX_MESSAGE_BYTES ||
            frame.fragment.isEmpty() || frame.fragment.size > MAX_FRAGMENT_BYTES ||
            frame.fragment.size.toLong() * frame.seqLength < frame.messageLength ||
            frame.fragment.size.toLong() * (frame.seqLength - 1) >= frame.messageLength
        ) {
            return skip()
        }

        if (seqLength == 0) {
            seqLength = frame.seqLength
            messageLength = frame.messageLength
            checksum = frame.checksum
            fragmentLength = frame.fragment.size
        } else if (frame.seqLength != seqLength || frame.messageLength != messageLength ||
            frame.checksum != checksum || frame.fragment.size != fragmentLength
        ) {
            // another animated code in front of the camera: start on that one
            reset()
            seen.add(text)
            seqLength = frame.seqLength
            messageLength = frame.messageLength
            checksum = frame.checksum
            fragmentLength = frame.fragment.size
        }

        val indexes = Fountain.chooseFragments(frame.seqNum, seqLength, checksum)
        queue.addLast(Part(indexes.toSet(), frame.fragment))
        while (finished == null && queue.isNotEmpty()) {
            val part = queue.removeFirst()
            if (!process(part.indexes, part.fragment)) return reject()
        }
        finished?.let { return Step.Done(it) }
        return Step.Progress(progress)
    }

    private fun reject(): Step {
        reset()
        return Step.Rejected
    }

    /** A frame that does not read: a misread one should not cost the frames already in hand. */
    private fun skip(): Step = if (seqLength == 0) Step.Rejected else Step.Progress(progress)

    /** Peel one part: take out the fragments already known, keep what is left. False when the message is complete and does not check out. */
    private fun process(given: Set<Int>, bytes: ByteArray): Boolean {
        val indexes = given.toMutableSet()
        val fragment = bytes.copyOf()
        for (i in given) {
            val known = simple[i] ?: continue
            xor(fragment, known)
            indexes.remove(i)
        }
        if (indexes.isEmpty()) return true

        if (indexes.size == 1) {
            val i = indexes.first()
            simple[i] = fragment
            if (simple.size == seqLength) return assemble()
            val left = ArrayList<Part>()
            for (m in mixed) {
                if (i !in m.indexes) {
                    left.add(m)
                    continue
                }
                val nf = m.fragment.copyOf()
                xor(nf, fragment)
                val ni = m.indexes - i
                if (ni.size == 1) queue.addLast(Part(ni, nf)) else if (ni.isNotEmpty()) left.add(Part(ni, nf))
            }
            mixed = left
            return true
        }

        if (mixed.any { it.indexes == indexes }) return true
        for (m in mixed) {
            if (!strictSubset(m.indexes, indexes)) continue
            xor(fragment, m.fragment)
            indexes.removeAll(m.indexes)
        }
        if (indexes.size == 1) {
            queue.addLast(Part(indexes.toSet(), fragment))
            return true
        }
        if (indexes.isEmpty()) return true
        val frozen = indexes.toSet()
        val left = ArrayList<Part>()
        for (m in mixed) {
            if (!strictSubset(frozen, m.indexes)) {
                left.add(m)
                continue
            }
            val nf = m.fragment.copyOf()
            xor(nf, fragment)
            val ni = m.indexes - frozen
            if (ni.size == 1) queue.addLast(Part(ni, nf)) else left.add(Part(ni, nf))
        }
        left.add(Part(frozen, fragment))
        // a stream of frames that never resolve cannot grow this without end
        val most = 4 * seqLength + 64
        if (left.size > most) left.subList(0, left.size - most).clear()
        mixed = left
        return true
    }

    private fun assemble(): Boolean {
        val message = ByteArray(seqLength * fragmentLength)
        for (i in 0 until seqLength) {
            val f = simple[i] ?: return false
            System.arraycopy(f, 0, message, i * fragmentLength, f.size)
        }
        val trimmed = message.copyOf(messageLength)
        if (CRC32.checksum(trimmed) != checksum) return false
        val token = textFromCbor(trimmed) ?: return false
        finished = token
        return true
    }
}

/** bytewords (BCR-2020-012), minimal style. */
object Bytewords {
    const val WORDS = "ableacidalsoapexaquaarchatomauntawayaxisbackbaldbarnbeltbetabiasbluebodybragbrewbulbbuzzcalmcashcatschefcityclawcodecolacookcostcruxcurlcuspcyandarkdatadaysdelidicedietdoordowndrawdropdrumdulldutyeacheasyechoedgeepicevenexamexiteyesfactfairfernfigsfilmfishfizzflapflewfluxfoxyfreefrogfuelfundgalagamegeargemsgiftgirlglowgoodgraygrimgurugushgyrohalfhanghardhawkheathelphighhillholyhopehornhutsicedideaidleinchinkyintoirisironitemjadejazzjoinjoltjowljudojugsjumpjunkjurykeepkenokeptkeyskickkilnkingkitekiwiknoblamblavalazyleaflegsliarlimplionlistlogoloudloveluaulucklungmainmanymathmazememomenumeowmildmintmissmonknailnavyneednewsnextnoonnotenumbobeyoboeomitonyxopenovalowlspaidpartpeckplaypluspoempoolposepuffpumapurrquadquizraceramprealredorichroadrockroofrubyruinrunsrustsafesagascarsetssilkskewslotsoapsolosongstubsurfswantacotasktaxitenttiedtimetinytoiltombtoystriptunatwinuglyundouniturgeuservastveryvetovialvibeviewvisavoidvowswallwandwarmwaspwavewaxywebswhatwhenwhizwolfworkyankyawnyellyogayurtzapszerozestzinczonezoom"

    /** First and last letter of each word, 26 × 26, to its byte; -1 for none. */
    private val table: IntArray = IntArray(26 * 26) { -1 }.also { t ->
        for (i in 0 until 256) {
            val first = WORDS[i * 4] - 'a'
            val last = WORDS[i * 4 + 3] - 'a'
            t[last * 26 + first] = i
        }
    }

    /** Two letters a byte, then a CRC-32 of the rest in the last four bytes. */
    fun decodeMinimal(s: String): ByteArray? {
        if (s.length % 2 != 0 || s.length < 10 || s.length > 2 * (AnimatedQRReader.MAX_FRAGMENT_BYTES + 64)) return null
        val out = ByteArray(s.length / 2)
        var k = 0
        while (k < s.length) {
            val x = s[k] - 'a'
            val y = s[k + 1] - 'a'
            if (x !in 0..25 || y !in 0..25) return null
            val v = table[y * 26 + x]
            if (v < 0) return null
            out[k / 2] = v.toByte()
            k += 2
        }
        val body = out.copyOf(out.size - 4)
        var sum = 0L
        for (b in out.copyOfRange(out.size - 4, out.size)) sum = (sum shl 8) or (b.toLong() and 0xFF)
        return if (CRC32.checksum(body) == sum) body else null
    }

    /** The minimal encoding of `bytes` with its CRC-32, for the tests' forged frames. */
    fun encodeMinimal(bytes: ByteArray): String {
        val sum = CRC32.checksum(bytes)
        val all = bytes + byteArrayOf((sum ushr 24).toByte(), (sum ushr 16).toByte(), (sum ushr 8).toByte(), sum.toByte())
        val out = StringBuilder(all.size * 2)
        for (b in all) {
            val i = b.toInt() and 0xFF
            out.append(WORDS[i * 4]).append(WORDS[i * 4 + 3])
        }
        return out.toString()
    }
}

/** CRC-32 (IEEE), as an unsigned 32-bit value in a Long. */
object CRC32 {
    fun checksum(bytes: ByteArray): Long = java.util.zip.CRC32().apply { update(bytes) }.value
}

/** The little CBOR a UR frame uses: definite lengths only. */
class MiniCBOR(private val bytes: ByteArray) {
    private var at = 0

    val atEnd: Boolean get() = at == bytes.size

    class FountainFrame(val seqNum: Long, val seqLength: Int, val messageLength: Int, val checksum: Long, val fragment: ByteArray)

    companion object {
        private val U32_MAX = 0xFFFF_FFFFUL
        private val I32_MAX = Int.MAX_VALUE.toULong()

        /** [seqNum, seqLength, messageLength, checksum, fragment] */
        fun fountainPart(payload: ByteArray): FountainFrame? {
            val r = MiniCBOR(payload)
            if (r.arrayHeader() != 5UL) return null
            val seqNum = r.unsigned() ?: return null
            if (seqNum > U32_MAX) return null
            val seqLength = r.unsigned() ?: return null
            if (seqLength > I32_MAX) return null
            val messageLength = r.unsigned() ?: return null
            if (messageLength > I32_MAX) return null
            val checksum = r.unsigned() ?: return null
            if (checksum > U32_MAX) return null
            val fragment = r.byteString() ?: return null
            if (!r.atEnd) return null
            return FountainFrame(seqNum.toLong(), seqLength.toInt(), messageLength.toInt(), checksum.toLong(), fragment)
        }
    }

    /** A major type and its argument. */
    private fun head(): Pair<Int, ULong>? {
        if (at >= bytes.size) return null
        val first = bytes[at].toInt() and 0xFF
        at += 1
        val major = first ushr 5
        val info = first and 0x1F
        val size = when (info) {
            in 0..23 -> return major to info.toULong()
            24 -> 1
            25 -> 2
            26 -> 4
            27 -> 8
            else -> return null
        }
        if (bytes.size - at < size) return null
        var v = 0UL
        repeat(size) {
            v = (v shl 8) or (bytes[at].toULong() and 0xFFUL)
            at += 1
        }
        return major to v
    }

    fun unsigned(): ULong? {
        val (major, v) = head() ?: return null
        return if (major == 0) v else null
    }

    fun arrayHeader(): ULong? {
        val (major, v) = head() ?: return null
        return if (major == 4) v else null
    }

    fun byteString(): ByteArray? {
        val (major, v) = head() ?: return null
        if (major != 2 || v > (bytes.size - at).toULong()) return null
        val n = v.toInt()
        val out = bytes.copyOfRange(at, at + n)
        at += n
        return out
    }
}

/** Which fragments a frame mixes. */
object Fountain {
    /** Frames 1…count are fragment n−1; later ones mix a random set, chosen as the encoder chose it. */
    fun chooseFragments(seqNum: Long, seqLength: Int, checksum: Long): List<Int> {
        if (seqNum <= seqLength) return listOf((seqNum - 1).toInt())
        val seed = ByteArray(8)
        for ((n, v) in listOf(seqNum, checksum).withIndex()) {
            seed[n * 4] = (v ushr 24).toByte()
            seed[n * 4 + 1] = (v ushr 16).toByte()
            seed[n * 4 + 2] = (v ushr 8).toByte()
            seed[n * 4 + 3] = v.toByte()
        }
        val rng = Xoshiro(seed)
        val degree = minOf(seqLength, maxOf(1, chooseDegree(seqLength, rng)))
        // the encoder shuffles every index; only the first `degree` are used,
        // and nothing after them draws on the generator
        val remaining = (0 until seqLength).toMutableList()
        val picked = ArrayList<Int>()
        while (picked.size < degree && remaining.isNotEmpty()) {
            val k = minOf(remaining.size - 1, rng.nextInt(0, remaining.size - 1))
            picked.add(remaining.removeAt(k))
        }
        return picked
    }

    /** Degree d with weight 1/d, by the Walker–Vose alias table @apocentre/alias-sampling 0.5.3 builds, in its order. */
    fun chooseDegree(n: Int, rng: Xoshiro): Int {
        val t = aliasTable(n)
        val c = Math.floor(rng.nextDouble() * n.toDouble()).toInt()
        if (c < 0 || c >= n) return 1
        val pick = if (rng.nextDouble() < t.prob[c]) c else (t.alias[c] ?: c)
        return pick + 1
    }

    class Alias(val prob: DoubleArray, val alias: Array<Int?>)

    private val aliasCache = HashMap<Int, Alias>()

    fun aliasTable(n: Int): Alias = synchronized(aliasCache) {
        aliasCache[n]?.let { return it }
        val p = DoubleArray(n)
        var sum = 0.0
        for (i in 0 until n) {
            p[i] = 1.0 / (i + 1).toDouble()
            sum += p[i]
        }
        val big = DoubleArray(n)
        for (i in 0 until n) big[i] = p[i] * n.toDouble() / sum
        val small = ArrayList<Int>()
        val large = ArrayList<Int>()
        for (i in n - 1 downTo 0) {
            if (big[i] < 1) small.add(i) else large.add(i)
        }
        val prob = DoubleArray(n)
        val alias = arrayOfNulls<Int>(n)
        while (small.isNotEmpty() && large.isNotEmpty()) {
            val a = small.removeAt(small.size - 1)
            val g = large.removeAt(large.size - 1)
            prob[a] = big[a]
            alias[a] = g
            big[g] = big[g] + big[a] - 1
            if (big[g] < 1) small.add(g) else large.add(g)
        }
        while (large.isNotEmpty()) prob[large.removeAt(large.size - 1)] = 1.0
        while (small.isNotEmpty()) prob[small.removeAt(small.size - 1)] = 1.0
        if (aliasCache.size > 16) aliasCache.clear()
        val table = Alias(prob, alias)
        aliasCache[n] = table
        table
    }
}

/** xoshiro256**, seeded from SHA-256, as the encoder's. */
class Xoshiro(seed: ByteArray) {
    private val s = LongArray(4)

    init {
        val digest = MessageDigest.getInstance("SHA-256").digest(seed)
        for (i in 0 until 4) {
            var v = 0L
            for (n in 0 until 8) v = (v shl 8) or (digest[i * 8 + n].toLong() and 0xFF)
            s[i] = v
        }
    }

    private fun rotl(x: Long, k: Int): Long = (x shl k) or (x ushr (64 - k))

    fun next(): Long {
        val result = rotl(s[1] * 5, 7) * 9
        val t = s[1] shl 17
        s[2] = s[2] xor s[0]
        s[3] = s[3] xor s[1]
        s[1] = s[1] xor s[2]
        s[0] = s[0] xor s[3]
        s[2] = s[2] xor t
        s[3] = rotl(s[3], 45)
        return result
    }

    /** next() / 2^64 as bignumber.js 9 divides it by default: rounded half up to 20 decimal places, then read as a double. */
    fun nextDouble(): Double = bigNumberFraction(next())

    fun nextInt(low: Int, high: Int): Int = Math.floor(nextDouble() * (high - low + 1).toDouble() + low.toDouble()).toInt()

    companion object {
        private val TEN_20 = BigInteger.TEN.pow(20)
        private val HALF = BigInteger.ONE.shiftLeft(63)

        /** `x` read as unsigned 64 bits, over 2^64, rounded half up to 20 places. */
        fun bigNumberFraction(x: Long): Double {
            val unsigned = BigInteger(1, ByteBuffer.allocate(8).putLong(x).array())
            val q = unsigned.multiply(TEN_20).add(HALF).shiftRight(64)
            val digits = q.toString().padStart(21, '0')
            return (digits.substring(0, 1) + "." + digits.substring(1)).toDouble()
        }
    }
}
