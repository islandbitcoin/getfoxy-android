/* The relay directory seed (tor/TorSeed.kt), unpacked when the APK is built.
 *
 * app/tor-seed/tor-microdescs.xz is iOS's Foxy/TorSeed/tor-microdescs.xz at
 * getfoxy/iOS 5581c96, byte for byte. iOS unpacks it on the phone with the
 * system's LZMA; Android has no xz decoder, and one written in Kotlin took 33 to
 * 73 s on a Pixel 8 Pro in a debug build (its runtime is slow at bit-level
 * loops), longer than the seed saves. So it is unpacked here, once per change,
 * by the decoder below (one stream, LZMA2, its CRC64 checked), and the build
 * refuses to go on unless both the .xz and what it unpacks to are the bytes
 * app/tor-seed/PIN pins. The APK carries the plain file (deflated by the APK
 * itself, inflated natively when it is read); the app checks its SHA-256 again
 * before Tor ever sees it.
 *
 * Applied from app/build.gradle.kts, to the app project (file() is the app folder); kept
 * outside app/ so lint does not read it as one of the module's build scripts. No dependency: a library for one file
 * would be one more pin.
 */
import java.io.IOException
import java.security.MessageDigest

abstract class UnpackTorSeed : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val packed: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pin: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun unpack() {
        val pins = pin.get().asFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
        val xz = packed.get().asFile.readBytes()
        val wantXz = pins["xz-sha256"] ?: throw GradleException("tor-seed/PIN has no xz-sha256")
        if (sha(xz) != wantXz) throw GradleException("tor-microdescs.xz is ${sha(xz)}, not the pinned $wantXz")
        val plain = Xz.decode(xz, 40 shl 20)
        val wantPlain = pins["plain-sha256"] ?: throw GradleException("tor-seed/PIN has no plain-sha256")
        if (sha(plain) != wantPlain || plain.size.toString() != pins["plain-bytes"]) {
            throw GradleException("tor-microdescs.xz unpacked to ${plain.size} bytes, ${sha(plain)}; the pin is ${pins["plain-bytes"]} bytes, $wantPlain")
        }
        val out = outputDir.get().asFile.resolve("tor")
        out.mkdirs()
        out.resolve("cached-microdescs").writeBytes(plain)
    }
}

object Xz {
    class Malformed(message: String) : IOException(message)

    private val MAGIC = byteArrayOf(0xFD.toByte(), '7'.code.toByte(), 'z'.code.toByte(), 'X'.code.toByte(), 'Z'.code.toByte(), 0)
    private const val LZMA2_FILTER = 0x21L

    /** The plain bytes of a single-stream .xz file; [Malformed] for anything else. */
    fun decode(input: ByteArray, sizeHint: Int = 0): ByteArray {
        val r = Reader(input)
        // stream header: magic, flags (check type), CRC32 of the flags
        if (input.size < 12 + 12) throw Malformed("too short")
        for (i in MAGIC.indices) if (input[i] != MAGIC[i]) throw Malformed("not an xz file")
        r.pos = 6
        val flags0 = r.byte()
        val check = r.byte()
        if (flags0 != 0 || check !in setOf(0, 1, 4, 10)) throw Malformed("unsupported stream flags")
        if (crc32(input, 6, 2) != r.u32le()) throw Malformed("stream header CRC32")
        val out = Output(maxOf(sizeHint, 1 shl 20))
        // blocks until the index indicator (a zero byte)
        while (true) {
            val headerStart = r.pos
            val sizeByte = r.byte()
            if (sizeByte == 0) break
            val headerSize = (sizeByte + 1) * 4
            if (headerStart + headerSize > input.size) throw Malformed("block header past the end")
            val blockFlags = r.byte()
            if (blockFlags and 0x3C != 0) throw Malformed("reserved block flags")
            val filters = (blockFlags and 3) + 1
            if (blockFlags and 0x40 != 0) r.vli()
            if (blockFlags and 0x80 != 0) r.vli()
            if (filters != 1) throw Malformed("only an LZMA2 block is decoded")
            if (r.vli() != LZMA2_FILTER) throw Malformed("not LZMA2")
            if (r.vli() != 1L) throw Malformed("LZMA2 properties are one byte")
            val dictProp = r.byte()
            if (dictProp > 40) throw Malformed("dictionary size")
            r.pos = headerStart + headerSize - 4
            if (crc32(input, headerStart, headerSize - 4) != r.u32le()) throw Malformed("block header CRC32")
            val blockOutStart = out.size
            val compStart = r.pos
            r.pos = Lzma2(input, r.pos, out).run()
            // block padding to four bytes, then the check
            val compSize = r.pos - compStart
            r.pos += (4 - (compSize % 4)) % 4
            when (check) {
                0 -> {}
                1 -> if (crc32(out.buf, blockOutStart, out.size - blockOutStart) != r.u32le()) throw Malformed("block CRC32")
                4 -> if (crc64(out.buf, blockOutStart, out.size - blockOutStart) != r.u64le()) throw Malformed("block CRC64")
                10 -> throw Malformed("a SHA-256 check is not read here")
            }
        }
        return out.buf.copyOf(out.size)
    }

    private class Reader(val b: ByteArray) {
        var pos = 0
        fun byte(): Int {
            if (pos >= b.size) throw Malformed("unexpected end")
            return b[pos++].toInt() and 0xff
        }
        fun u32le(): Int = byte() or (byte() shl 8) or (byte() shl 16) or (byte() shl 24)
        fun u64le(): Long = (u32le().toLong() and 0xffffffffL) or (u32le().toLong() shl 32)
        fun vli(): Long {
            var v = 0L
            for (i in 0 until 9) {
                val c = byte()
                v = v or ((c and 0x7f).toLong() shl (7 * i))
                if (c and 0x80 == 0) return v
            }
            throw Malformed("a number too long")
        }
    }

    /** The output, which is also the dictionary: the whole file is kept, so every distance is in it. */
    private class Output(initial: Int) {
        var buf = ByteArray(initial)
        var size = 0

        fun put(b: Int) {
            if (size == buf.size) grow(1)
            buf[size++] = b.toByte()
        }

        fun grow(more: Int) {
            if (size + more <= buf.size) return
            var n = buf.size
            while (n < size + more) n = if (n < (1 shl 30)) n * 2 else throw Malformed("too large")
            buf = buf.copyOf(n)
        }

        /** The byte `dist` + 1 back (xz `dict_get`); 0 before the start of the dictionary. */
        fun back(dist: Int, dictStart: Int): Int {
            val at = size - dist - 1
            return if (at < dictStart) 0 else buf[at].toInt() and 0xff
        }
    }

    /** LZMA2 chunks over one LZMA decoder (state, probabilities and the range decoder). */
    private class Lzma2(private val inp: ByteArray, private var pos: Int, private val out: Output) {
        private var dictStart = out.size
        private var lc = 0
        private var lp = 0
        private var pb = 0
        private var needProps = true
        private var needDictReset = true

        // the range decoder
        private var range = 0
        private var code = 0
        private var rcEnd = 0

        // the LZMA state
        private var state = 0
        private var rep0 = 0
        private var rep1 = 0
        private var rep2 = 0
        private var rep3 = 0
        private val isMatch = ShortArray(STATES shl POS_STATES_MAX_BITS)
        private val isRep = ShortArray(STATES)
        private val isRep0 = ShortArray(STATES)
        private val isRep1 = ShortArray(STATES)
        private val isRep2 = ShortArray(STATES)
        private val isRep0Long = ShortArray(STATES shl POS_STATES_MAX_BITS)
        private val distSlot = ShortArray(DIST_STATES * DIST_SLOTS)
        private val distSpecial = ShortArray(FULL_DISTANCES - DIST_MODEL_END)
        private val distAlign = ShortArray(ALIGN_SIZE)
        private val matchLen = LenDecoder()
        private val repLen = LenDecoder()
        private var literal = ShortArray(0x300 shl 4)

        companion object {
            const val STATES = 12
            const val POS_STATES_MAX_BITS = 4
            const val DIST_STATES = 4
            const val DIST_SLOTS = 64
            const val DIST_MODEL_START = 4
            const val DIST_MODEL_END = 14
            const val FULL_DISTANCES = 128
            const val ALIGN_BITS = 4
            const val ALIGN_SIZE = 16
            const val MATCH_LEN_MIN = 2
            const val PROB_INIT: Short = 1024
            const val TOP = 1 shl 24
        }

        private inner class LenDecoder {
            val choice = ShortArray(2)
            val low = ShortArray(16 * 8)
            val mid = ShortArray(16 * 8)
            val high = ShortArray(256)
            fun reset() {
                choice.fill(PROB_INIT); low.fill(PROB_INIT); mid.fill(PROB_INIT); high.fill(PROB_INIT)
            }
            fun decode(posState: Int): Int = when {
                bit(choice, 0) == 0 -> MATCH_LEN_MIN + bittree(low, posState * 8, 8)
                bit(choice, 1) == 0 -> MATCH_LEN_MIN + 8 + bittree(mid, posState * 8, 8)
                else -> MATCH_LEN_MIN + 16 + bittree(high, 0, 256)
            }
        }

        fun run(): Int {
            while (true) {
                val control = byte()
                if (control == 0) return pos
                if (control >= 0xE0 || control == 1) {
                    needProps = true
                    needDictReset = false
                    dictStart = out.size
                } else if (needDictReset) {
                    throw Malformed("the first chunk does not reset the dictionary")
                }
                if (control >= 0x80) {
                    val unpacked = ((control and 0x1F) shl 16) + (byte() shl 8) + byte() + 1
                    val packed = (byte() shl 8) + byte() + 1
                    when {
                        control >= 0xC0 -> {
                            props(byte())
                            needProps = false
                            resetState()
                        }
                        needProps -> throw Malformed("an LZMA chunk with no properties")
                        control >= 0xA0 -> resetState()
                    }
                    rcInit(packed)
                    lzma(out.size + unpacked)
                    // the range decoder is left normalized (xz-embedded lzma_main): the chunk's last byte is read
                    normalize()
                    if (pos != rcEnd || code != 0) throw Malformed("an LZMA chunk's size")
                } else {
                    if (control > 2) throw Malformed("a chunk control byte")
                    val n = (byte() shl 8) + byte() + 1
                    if (pos + n > inp.size) throw Malformed("an uncompressed chunk past the end")
                    for (i in 0 until n) out.put(inp[pos + i].toInt() and 0xff)
                    pos += n
                }
            }
        }

        private fun byte(): Int {
            if (pos >= inp.size) throw Malformed("unexpected end")
            return inp[pos++].toInt() and 0xff
        }

        private fun props(b: Int) {
            if (b > (4 * 5 + 4) * 9 + 8) throw Malformed("LZMA properties")
            pb = b / 45
            val rest = b % 45
            lp = rest / 9
            lc = rest % 9
            if (lc + lp > 4) throw Malformed("lc + lp")
            literal = ShortArray(0x300 shl (lc + lp))
        }

        private fun resetState() {
            state = 0
            rep0 = 0; rep1 = 0; rep2 = 0; rep3 = 0
            isMatch.fill(PROB_INIT); isRep.fill(PROB_INIT); isRep0.fill(PROB_INIT); isRep1.fill(PROB_INIT)
            isRep2.fill(PROB_INIT); isRep0Long.fill(PROB_INIT); distSlot.fill(PROB_INIT); distSpecial.fill(PROB_INIT)
            distAlign.fill(PROB_INIT); matchLen.reset(); repLen.reset(); literal.fill(PROB_INIT)
        }

        private fun rcInit(packed: Int) {
            rcEnd = pos + packed
            if (rcEnd > inp.size || packed < 5) throw Malformed("an LZMA chunk past the end")
            if (byte() != 0) throw Malformed("the range coder's first byte")
            code = (byte() shl 24) or (byte() shl 16) or (byte() shl 8) or byte()
            range = -1
        }

        private fun normalize() {
            if (Integer.compareUnsigned(range, TOP) < 0) {
                if (pos >= rcEnd) throw Malformed("an LZMA chunk read past its size")
                range = range shl 8
                code = (code shl 8) or byte()
            }
        }

        private fun bit(probs: ShortArray, i: Int): Int {
            normalize()
            val p = probs[i].toInt()
            val bound = (range ushr 11) * p
            return if (Integer.compareUnsigned(code, bound) < 0) {
                range = bound
                probs[i] = (p + ((2048 - p) ushr 5)).toShort()
                0
            } else {
                range -= bound
                code -= bound
                probs[i] = (p - (p ushr 5)).toShort()
                1
            }
        }

        /** A bit tree of `limit` leaves at `base`: its symbol, 0..limit-1. */
        private fun bittree(probs: ShortArray, base: Int, limit: Int): Int {
            var symbol = 1
            while (symbol < limit) symbol = (symbol shl 1) + bit(probs, base + symbol)
            return symbol - limit
        }

        /** A reverse bit tree of `bits` bits at `base` (index from 1), added to `dest`. */
        private fun bittreeReverse(probs: ShortArray, base: Int, dest: Int, bits: Int): Int {
            var symbol = 1
            var d = dest
            for (i in 0 until bits) {
                val b = bit(probs, base + symbol)
                symbol = (symbol shl 1) + b
                d += b shl i
            }
            return d
        }

        private fun direct(dest: Int, bits: Int): Int {
            var d = dest
            for (i in 0 until bits) {
                normalize()
                range = range ushr 1
                code -= range
                val mask = 0 - (code ushr 31)
                code += range and mask
                d = (d shl 1) + (mask + 1)
            }
            return d
        }

        private fun lzma(end: Int) {
            val pbMask = (1 shl pb) - 1
            val lpMask = (1 shl lp) - 1
            while (out.size < end) {
                val posState = out.size and pbMask
                if (bit(isMatch, (state shl POS_STATES_MAX_BITS) + posState) == 0) {
                    literal(lpMask)
                    continue
                }
                val len: Int
                if (bit(isRep, state) == 0) {
                    rep3 = rep2; rep2 = rep1; rep1 = rep0
                    len = matchLen.decode(posState)
                    state = if (state < 7) 7 else 10
                    rep0 = distance(len)
                    if (rep0 == -1) throw Malformed("an end marker inside LZMA2")
                } else {
                    if (bit(isRep0, state) == 0) {
                        if (bit(isRep0Long, (state shl POS_STATES_MAX_BITS) + posState) == 0) {
                            state = if (state < 7) 9 else 11
                            copy(1, end)
                            continue
                        }
                    } else {
                        val dist: Int
                        if (bit(isRep1, state) == 0) {
                            dist = rep1
                        } else {
                            if (bit(isRep2, state) == 0) {
                                dist = rep2
                            } else {
                                dist = rep3
                                rep3 = rep2
                            }
                            rep2 = rep1
                        }
                        rep1 = rep0
                        rep0 = dist
                    }
                    len = repLen.decode(posState)
                    state = if (state < 7) 8 else 11
                }
                copy(len, end)
            }
        }

        private fun literal(lpMask: Int) {
            val prev = out.back(0, dictStart)
            val base = 0x300 * ((prev ushr (8 - lc)) + ((out.size and lpMask) shl lc))
            var symbol = 1
            if (state < 7) {
                while (symbol < 0x100) symbol = (symbol shl 1) + bit(literal, base + symbol)
            } else {
                var matchByte = out.back(rep0, dictStart) shl 1
                var offset = 0x100
                while (symbol < 0x100) {
                    val matchBit = matchByte and offset
                    matchByte = matchByte shl 1
                    val i = offset + matchBit + symbol
                    if (bit(literal, base + i) == 1) {
                        symbol = (symbol shl 1) + 1
                        offset = offset and matchBit
                    } else {
                        symbol = symbol shl 1
                        offset = offset and matchBit.inv()
                    }
                }
            }
            out.put(symbol and 0xff)
            state = when {
                state < 4 -> 0
                state < 10 -> state - 3
                else -> state - 6
            }
        }

        private fun distance(len: Int): Int {
            val lenState = minOf(len - MATCH_LEN_MIN, DIST_STATES - 1)
            val slot = bittree(distSlot, lenState * DIST_SLOTS, DIST_SLOTS)
            if (slot < DIST_MODEL_START) return slot
            val limit = (slot ushr 1) - 1
            var dist = 2 + (slot and 1)
            if (slot < DIST_MODEL_END) {
                dist = dist shl limit
                return bittreeReverse(distSpecial, dist - slot - 1, dist, limit)
            }
            dist = direct(dist, limit - ALIGN_BITS)
            dist = dist shl ALIGN_BITS
            return bittreeReverse(distAlign, 0, dist, ALIGN_BITS)
        }

        private fun copy(len: Int, end: Int) {
            val dist = rep0
            var at = out.size
            if (dist < 0 || at - dist - 1 < dictStart) throw Malformed("a distance before the dictionary")
            if (at + len > end) throw Malformed("a match past the chunk")
            out.grow(len)
            val buf = out.buf
            var from = at - dist - 1
            if (dist + 1 >= len) {
                // no overlap: one copy
                System.arraycopy(buf, from, buf, at, len)
                at += len
            } else {
                // a run that repeats what it is writing: byte by byte, from locals
                for (i in 0 until len) buf[at++] = buf[from++]
            }
            out.size = at
        }
    }

    // MARK: Checks

    private val CRC32_TABLE = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }

    fun crc32(b: ByteArray, off: Int, len: Int): Int {
        var c = -1
        for (i in off until off + len) c = CRC32_TABLE[(c xor b[i].toInt()) and 0xff] xor (c ushr 8)
        return c.inv()
    }

    private val CRC64_TABLE = LongArray(256) { n ->
        var c = n.toLong()
        repeat(8) { c = if (c and 1L != 0L) (c ushr 1) xor -0x3693a86a2878f0beL else c ushr 1 }
        c
    }

    /** CRC-64/XZ (ECMA-182, reflected). */
    fun crc64(b: ByteArray, off: Int, len: Int): Long {
        var c = -1L
        for (i in off until off + len) c = CRC64_TABLE[((c xor b[i].toLong()) and 0xff).toInt()] xor (c ushr 8)
        return c.inv()
    }
}

tasks.register<UnpackTorSeed>("unpackTorSeed") {
    packed.set(file("tor-seed/tor-microdescs.xz"))
    pin.set(file("tor-seed/PIN"))
    outputDir.set(layout.buildDirectory.dir("generated/torseed"))
}
