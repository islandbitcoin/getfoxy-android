package io.islandbitcoin.foxy.scanner

import io.islandbitcoin.foxy.TestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Animated QR codes (AnimatedQRReader) against frames cashu.me's own library
 * made: fixtures/animated-qr.json, from @gandlaf21/bc-ur 1.1.12 (copied from
 * the iOS repository with its provenance). Ported from FoxyTests/AnimatedQRTests.swift.
 */
class AnimatedQRReaderTest {
    class Case(val name: String, val token: String, val seqLength: Int, val frames: List<String>)

    companion object {
        @Suppress("UNCHECKED_CAST")
        val fixture: Map<String, Any?> by lazy { TestSupport.fixture("animated-qr.json") as Map<String, Any?> }

        @Suppress("UNCHECKED_CAST")
        val cases: List<Case> by lazy {
            (fixture["cases"] as List<Map<String, Any?>>).map {
                Case(it["name"] as String, it["token"] as String, (it["seqLength"] as Double).toInt(), it["frames"] as List<String>)
            }
        }

        /** Frames in this order until the reader has the message; null if it never does. */
        fun read(frames: List<String>, order: List<Int>, reader: AnimatedQRReader = AnimatedQRReader()): Pair<String, Int>? {
            var used = 0
            for (i in order) {
                used += 1
                val step = reader.receive(frames[i])
                if (step is AnimatedQRReader.Step.Done) return step.text to used
            }
            return null
        }

        /** A frame forged with the given fields, CBOR and bytewords as the encoder writes them. */
        fun frame(seqNum: Long, seqLength: Int, messageLength: Int, checksum: Long, fragment: ByteArray): String {
            val cbor = ArrayList<Byte>()
            cbor.add(0x85.toByte())
            for (v in listOf(seqNum, seqLength.toLong(), messageLength.toLong(), checksum)) {
                cbor.add(0x1A)
                cbor.add((v ushr 24).toByte())
                cbor.add((v ushr 16).toByte())
                cbor.add((v ushr 8).toByte())
                cbor.add(v.toByte())
            }
            cbor.add(0x58)
            cbor.add(fragment.size.toByte())
            cbor.addAll(fragment.toList())
            return "ur:bytes/$seqNum-$seqLength/" + Bytewords.encodeMinimal(cbor.toByteArray())
        }
    }

    @Test
    fun theFixtureIsThere() {
        assertEquals(6, cases.size)
        assertTrue((fixture["chooseFragments"] as List<*>).size > 280)
    }

    @Test
    fun mixedFramesHoldTheFragmentsTheEncoderChose() {
        @Suppress("UNCHECKED_CAST")
        for (c in fixture["chooseFragments"] as List<Map<String, Any?>>) {
            val seqNum = (c["seqNum"] as Double).toLong()
            val seqLen = (c["seqLen"] as Double).toInt()
            val checksum = (c["checksum"] as Double).toLong()
            val want = (c["indexes"] as List<Double>).map { it.toInt() }
            assertEquals("seqNum $seqNum, $seqLen fragments, checksum $checksum", want, Fountain.chooseFragments(seqNum, seqLen, checksum))
        }
    }

    @Test
    fun everyFrameInOrderGivesTheToken() {
        for (c in cases) {
            val got = read(c.frames, c.frames.indices.toList())
            assertEquals(c.name, c.token, got?.first)
            assertEquals(c.name + ": the plain fragments alone are enough", c.seqLength, got?.second)
        }
    }

    @Test
    fun joiningTheLoopAfterItsPlainFramesStillGivesTheToken() {
        for (c in cases.filter { it.seqLength > 1 }) {
            assertEquals(c.name + ": mixed frames only", c.token, read(c.frames, (c.seqLength until c.frames.size).toList())?.first)
        }
    }

    @Test
    fun framesOutOfOrderAndRepeatedGiveTheToken() {
        for (c in cases) {
            // a fixed shuffle, every frame seen twice (the iOS test's LCG, unsigned)
            val order = (c.frames.indices + c.frames.indices).toMutableList()
            var x = 0x9E3779B97F4A7C15uL
            for (k in order.size - 1 downTo 1) {
                x = x * 6364136223846793005uL + 1442695040888963407uL
                val j = ((x shr 33) % (k + 1).toULong()).toInt()
                val t = order[k]
                order[k] = order[j]
                order[j] = t
            }
            assertEquals(c.name, c.token, read(c.frames, order)?.first)
        }
    }

    @Test
    fun duplicatesOfOneFrameAreProgressNotNews() {
        val c = cases.first { it.seqLength > 10 }
        val reader = AnimatedQRReader()
        val first = reader.receive(c.frames[0])
        assertTrue(first is AnimatedQRReader.Step.Progress)
        repeat(20) { assertEquals(first, reader.receive(c.frames[0])) }
        assertEquals(1.0 / c.seqLength, reader.progress, 1e-12)
    }

    @Test
    fun aMisreadFrameIsSkippedWithoutLosingTheOthers() {
        val c = cases.first { it.seqLength > 10 }
        val reader = AnimatedQRReader()
        for (i in 0 until 5) reader.receive(c.frames[i])
        val before = reader.progress
        val bent = c.frames[5].toCharArray()
        val at = bent.size - 3
        bent[at] = if (bent[at] == 'a') 'e' else 'a'
        assertEquals(AnimatedQRReader.Step.Progress(before), reader.receive(String(bent)))
        assertEquals(c.token, read(c.frames, (5 until c.frames.size).toList(), reader)?.first)
    }

    @Test
    fun plainCodesAndOtherURsAreNotTakenAsTokens() {
        val reader = AnimatedQRReader()
        assertEquals(AnimatedQRReader.Step.NotAnimated, reader.receive("cashuBo2Ftd2h0dHBzOi8vbWludC5leGFtcGxl"))
        assertEquals(AnimatedQRReader.Step.NotAnimated, reader.receive("lnbc1pvjluez"))
        assertEquals(AnimatedQRReader.Step.Rejected, reader.receive("ur:crypto-psbt/1-3/lpadaxcsencylobemohsgmoyadhdeynteelblrcygldwvarfl"))
        assertEquals(AnimatedQRReader.Step.Rejected, reader.receive("ur:bytes/1-3/zzzz"))
        assertEquals(AnimatedQRReader.Step.Rejected, reader.receive("ur:bytes"))
    }

    @Test
    fun upperCaseFramesReadAsTheirLowerCase() {
        // QR alphanumeric mode carries UR in capitals; the reader lowercases first
        val c = cases.first { it.seqLength == 5 }
        assertEquals(c.token, read(c.frames.map { it.uppercase() }, c.frames.indices.toList())?.first)
    }

    @Test
    fun anotherAnimatedCodeStartsOver() {
        val long = cases.filter { it.seqLength > 5 }
        assertTrue("two long cases needed", long.size >= 2)
        val reader = AnimatedQRReader()
        for (i in 0 until 3) reader.receive(long[0].frames[i])
        assertEquals(long[1].token, read(long[1].frames, long[1].frames.indices.toList(), reader)?.first)
    }

    @Test
    fun forgedFramesOverTheLimitsAreRefused() {
        val fragment = ByteArray(100) { 7 }
        // a well-formed one first, so the check is the limit and not the encoding
        assertEquals(AnimatedQRReader.Step.Progress(1.0 / 3.0), AnimatedQRReader().receive(frame(1, 3, 250, 1, fragment)))
        // a frame claiming 3,000 fragments, and one claiming a 200 kB message
        assertEquals(AnimatedQRReader.Step.Rejected, AnimatedQRReader().receive(frame(1, 3000, 299_950, 1, fragment)))
        assertEquals(AnimatedQRReader.Step.Rejected, AnimatedQRReader().receive(frame(1, 2000, 199_950, 1, fragment)))
        // lengths that do not add up
        assertEquals(AnimatedQRReader.Step.Rejected, AnimatedQRReader().receive(frame(1, 3, 900, 1, fragment)))
        // a fragment over 10,000 bytes cannot even be written in the one-byte length this forger uses; the cap is checked by the sizes above
        assertEquals(AnimatedQRReader.MAX_FRAGMENT_BYTES, 10_000)
    }

    @Test
    fun aMessageThatFailsItsChecksumIsDropped() {
        val c = cases.first { it.seqLength == 5 }
        val reader = AnimatedQRReader()
        var last: AnimatedQRReader.Step = AnimatedQRReader.Step.NotAnimated
        for (f in c.frames.take(5)) {
            val payload = Bytewords.decodeMinimal(f.split("/")[2])
            assertNotNull("fixture frame did not read", payload)
            val part = MiniCBOR.fountainPart(payload!!)!!
            last = reader.receive(frame(part.seqNum, part.seqLength, part.messageLength, part.checksum xor 1, part.fragment))
        }
        assertEquals(AnimatedQRReader.Step.Rejected, last)
        assertEquals(0.0, reader.progress, 0.0)
    }

    @Test
    fun theSequenceIsTwoPositiveNumbers() {
        assertEquals(1L to 5, AnimatedQRReader.sequence("1-5"))
        // n wraps to 32 bits, as the encoder's does
        assertEquals(0L to 3, AnimatedQRReader.sequence("4294967296-3"))
        assertEquals(4294967295L to 17, AnimatedQRReader.sequence("4294967295-17"))
        for (bad in listOf("0-5", "1-0", "-5", "1-", "a-5", "1-5-6", "12345678901-5", "1-1234567", "١-5")) {
            assertEquals(bad, null, AnimatedQRReader.sequence(bad))
        }
    }

    @Test
    fun theDivisionMatchesBignumberJS() {
        assertEquals(0.0, Xoshiro.bigNumberFraction(0), 0.0)
        assertEquals(1.0, Xoshiro.bigNumberFraction(-1L), 0.0) // 2^64 − 1 rounds up to 1.00000000000000000000
        assertEquals(0.5, Xoshiro.bigNumberFraction(Long.MIN_VALUE), 0.0) // 2^63
        assertEquals("0.00000000000000000005".toDouble(), Xoshiro.bigNumberFraction(1), 0.0) // 5.42e-20, half up
    }

    @Test
    fun crc32() {
        @Suppress("UNCHECKED_CAST")
        for (pair in fixture["crc32"] as List<List<Any?>>) {
            assertEquals((pair[1] as Double).toLong(), CRC32.checksum((pair[0] as String).toByteArray()))
        }
        assertEquals(0xEBE6C6E6L, CRC32.checksum("Hello, world!".toByteArray()))
        assertEquals(0L, CRC32.checksum(ByteArray(0)))
    }
}
