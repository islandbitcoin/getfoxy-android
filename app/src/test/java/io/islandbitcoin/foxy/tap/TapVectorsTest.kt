package io.islandbitcoin.foxy.tap

import io.islandbitcoin.foxy.TestSupport
import io.islandbitcoin.foxy.keychain.Bytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest

/**
 * The Kotlin tap against what the iOS code writes, byte for byte.
 *
 * fixtures/tap-vectors.json is made by tools/tap-vectors/generate.sh from
 * getfoxy/iOS's own TapCrypto.swift, TapSession.swift and TapProtocol.swift at
 * the commit in core/PIN, with only the session's key and nonce seeded
 * (fixtures/README.md). FoxyTests' tap tests run on random keys, so these are
 * the fixed bytes: the doors, X25519, the commitment, the transcript, the keys
 * and the code, sealing, padding, and five whole conversations, every frame
 * each side wrote and every answer each side gave.
 */
@Suppress("UNCHECKED_CAST")
class TapVectorsTest {
    private val v = TestSupport.fixture("tap-vectors.json") as Map<String, Any?>

    private fun hex(s: Any?): ByteArray = Bytes.fromHex(s as String) ?: error("not hex: $s")
    private fun map(o: Any?) = o as Map<String, Any?>
    private fun list(o: Any?) = o as List<Any?>
    private fun sha(b: ByteArray) = Bytes.hex(MessageDigest.getInstance("SHA-256").digest(b))
    private fun run(start: Int, n: Int) = ByteArray(n) { ((start + it) and 0xff).toByte() }

    /** Bytes as the fixture carries them: hex, or a length and SHA-256 above 8 KB. */
    private fun assertBytes(want: Any?, got: ByteArray?, what: String) {
        assertNotNull("$what: nothing came back", got)
        val w = map(want)
        if (w["hex"] != null) {
            assertEquals(what, w["hex"], Bytes.hex(got!!))
        } else {
            assertEquals("$what: length", (w["length"] as Double).toInt(), got!!.size)
            assertEquals("$what: sha256", w["sha256"], sha(got))
        }
    }

    @Test
    fun theVersionIsThree() {
        assertEquals(TapCrypto.VERSION, (v["version"] as Double).toInt())
        assertTrue((v["source"] as String).endsWith(pinnedCommit()))
    }

    private fun pinnedCommit(): String =
        TestSupport.core.resolve("PIN").readLines().first { it.startsWith("commit=") }.removePrefix("commit=")

    @Test
    fun theDoorsAreTheOnesIosDerives() {
        for (d in list(v["doors"]).map(::map)) {
            val service = TapCrypto.uuid(hex(d["service"]))
            assertEquals(d["serviceUuid"], service.toString().uppercase())
            assertArrayEquals(hex(d["service"]), TapCrypto.bytes(service))
            assertEquals(d["in"], TapCrypto.inCharacteristic(service).toString().uppercase())
            assertEquals(d["out"], TapCrypto.outCharacteristic(service).toString().uppercase())
            assertEquals(d["nearLeft"], TapCrypto.nearCharacteristic(service, "left").toString().uppercase())
            assertEquals(d["nearRight"], TapCrypto.nearCharacteristic(service, "right").toString().uppercase())
        }
    }

    @Test
    fun x25519AgreesWithCryptoKitAndRefusesWhatItRefuses() {
        for (a in list(v["x25519"]).map(::map)) {
            val priv = hex(a["private"])
            assertEquals("public of ${a["private"]}", a["public"], Bytes.hex(TapCrypto.publicKey(priv)!!))
            val shared = TapCrypto.sharedSecret(priv, hex(a["peer"]))
            if (a["refused"] == true) {
                assertNull("CryptoKit refuses ${a["peer"]}, and so must this", shared)
            } else {
                assertEquals("shared with ${a["peer"]}", a["shared"], Bytes.hex(shared!!))
            }
        }
    }

    @Test
    fun theCommitmentTranscriptKeysAndCodeAreIosOwn() {
        val c = map(v["commitment"])
        assertEquals(c["commitment"], Bytes.hex(TapCrypto.commitment(hex(c["public"]), hex(c["nonce"]))))
        for (t in list(v["transcripts"]).map(::map)) {
            val th = TapCrypto.transcript(
                version = (t["version"] as Double).toInt(),
                service = TapCrypto.uuid(hex(t["service"])),
                r = hex(t["r"]), p = hex(t["p"]), nr = hex(t["nr"]), np = hex(t["np"]),
            )
            assertEquals("version ${t["version"]}", t["transcript"], Bytes.hex(th))
        }
        val k = map(v["keys"])
        val keys = TapCrypto.keys(hex(k["shared"]), hex(k["transcript"]))
        assertEquals(k["receiverToPayer"], Bytes.hex(keys.receiverToPayer))
        assertEquals(k["payerToReceiver"], Bytes.hex(keys.payerToReceiver))
        assertEquals(k["code"], keys.code)
        assertArrayEquals(hex(k["transcript"]), keys.transcript)
        for (row in list(v["codes"]).map(::map)) {
            assertEquals(row["code"], TapCrypto.keys(hex(row["shared"]), hex(row["transcript"])).code)
        }
    }

    @Test
    fun sealingIsByteForByte() {
        for (s in list(v["seals"]).map(::map)) {
            val counter = (s["counter"] as String).toULong()
            val kind = (s["kind"] as Double).toInt()
            assertEquals(s["nonce"], Bytes.hex(TapCrypto.nonce(counter)))
            val sealed = TapCrypto.seal(hex(s["message"]), hex(s["key"]), counter, hex(s["transcript"]), kind)!!
            assertEquals("counter $counter kind $kind", s["sealed"], Bytes.hex(sealed))
            val opened = TapCrypto.open(hex(s["sealed"]), hex(s["key"]), counter, hex(s["transcript"]), kind)
            if (hex(s["message"]).isEmpty()) {
                // a tag and nothing else is refused unopened, as iOS's `sealed.count > 16` refuses it
                assertNull(opened)
            } else {
                assertArrayEquals(hex(s["message"]), opened)
            }
            assertNull(TapCrypto.open(hex(s["sealed"]), hex(s["key"]), counter, hex(s["transcript"]), kind xor 1))
        }
    }

    @Test
    fun paddingIsByteForByte() {
        val p = map(v["padding"])
        for (f in list(p["fixed"]).map(::map)) {
            val padded = TapCrypto.pad(hex(f["body"]), (f["size"] as Double).toInt())!!
            assertEquals(f["padded"], sha(padded))
        }
        for (f in list(p["payment"]).map(::map)) {
            val padded = TapCrypto.padPayment(ByteArray((f["length"] as Double).toInt()) { 3 })
            if (f["refused"] == true) {
                assertNull("${f["length"]} bytes", padded)
            } else {
                assertEquals((f["padded"] as Double).toInt(), padded!!.size)
                assertEquals(f["sha256"], sha(padded))
            }
        }
    }

    @Test
    fun theFirstVersionsCodeIsIosOwn() {
        for (c in list(v["code1"]).map(::map)) {
            assertEquals(c["code"], TapProtocol.code(c["payload"] as String, hex(c["nonce"])))
        }
    }

    // the long texts the generator stands in for by length and hash
    private val texts: List<String> = listOf(
        "{\"proofs\":\"" + "abcdefghi₿".repeat(8_400) + "\"}",
        "x".repeat(TapCrypto.MOST_PAYMENT_TOTAL + 1),
    )

    private fun text(o: Any?): String {
        if (o is String) return o
        val m = map(o)
        return texts.firstOrNull { sha(it.toByteArray(Charsets.UTF_8)) == m["sha256"] }
            ?: error("no text of ${m["length"]} bytes with that hash")
    }

    private fun describe(step: TapSession.Step): Map<String, Any?> = when (step) {
        is TapSession.Step.Send -> mapOf("step" to "send", "bytes" to step.bytes)
        TapSession.Step.Waiting -> mapOf("step" to "waiting")
        is TapSession.Step.Linked -> mapOf("step" to "linked", "code" to step.code)
        is TapSession.Step.Offer -> mapOf("step" to "offer", "text" to step.text, "code" to step.code)
        is TapSession.Step.Payment -> mapOf("step" to "payment", "text" to step.text)
        is TapSession.Step.Result -> mapOf("step" to "result", "text" to step.text)
        is TapSession.Step.Stop -> mapOf("step" to "stop", "why" to step.why)
        is TapSession.Step.OtherVersion -> mapOf("step" to "otherVersion", "theirs" to step.theirs, "reply" to step.reply)
        is TapSession.Step.Change -> mapOf("step" to "change", "text" to step.text)
        TapSession.Step.ChangeTaken -> mapOf("step" to "changeTaken")
        is TapSession.Step.Quote -> mapOf("step" to "quote", "text" to step.text)
        is TapSession.Step.Terms -> mapOf("step" to "terms", "text" to step.text)
        TapSession.Step.Asking -> mapOf("step" to "asking")
        is TapSession.Step.Again -> mapOf("step" to "again", "from" to step.from)
        is TapSession.Step.PaymentSize -> mapOf("step" to "paymentSize", "bytes" to step.bytes)
    }

    private fun assertStep(want: Map<String, Any?>, got: TapSession.Step, what: String) {
        val g = describe(got)
        assertEquals("$what: step", want["step"], g["step"])
        when (want["step"]) {
            "send" -> assertBytes(want["bytes"], g["bytes"] as ByteArray, what)
            "linked" -> assertEquals(what, want["code"], g["code"])
            "offer" -> {
                assertEquals(what, want["text"], g["text"])
                assertEquals(what, want["code"], g["code"])
            }
            "payment" -> assertEquals(what, text(want["text"]), g["text"])
            "result", "change", "quote", "terms" -> assertEquals(what, want["text"], g["text"])
            "stop" -> assertEquals(what, want["why"], g["why"])
            "otherVersion" -> {
                assertEquals(what, (want["theirs"] as Double).toInt(), g["theirs"])
                val reply = g["reply"] as ByteArray?
                if (want["reply"] == null) assertNull(what, reply) else assertEquals(what, want["reply"], Bytes.hex(reply!!))
            }
            "again" -> assertEquals(what, (want["from"] as Double).toLong().toULong(), g["from"])
            "paymentSize" -> assertEquals(what, (want["bytes"] as Double).toLong(), g["bytes"])
            "waiting", "changeTaken", "asking" -> Unit
            else -> fail("$what: a step the test does not know: ${want["step"]}")
        }
    }

    @Test
    fun wholeConversationsReplayByteForByte() {
        val sessions = list(v["sessions"]).map(::map)
        assertEquals(5, sessions.size)
        for (s in sessions) {
            val seed = (s["seed"] as Double).toInt()
            val service = TapCrypto.uuid(hex(s["service"]))
            val payer = TapSession(TapSession.Role.PAYER, service, run(seed, 32), run(seed + 0x80, 16)) {}
            val receiver = TapSession(TapSession.Role.RECEIVER, service, run(seed + 0x40, 32), run(seed + 0xc0, 16)) {}
            // the frames this side sealed, for a later op that hands iOS's bytes on by hash
            val sealedByHash = HashMap<String, ByteArray>()
            fun keep(b: ByteArray?) = b?.also { sealedByHash[sha(it)] = it }
            fun bytesOf(o: Any?): ByteArray {
                val m = map(o)
                return if (m["hex"] != null) hex(m["hex"]) else sealedByHash[m["sha256"]] ?: error("no frame with that hash")
            }
            for ((i, op) in list(s["ops"]).map(::map).withIndex()) {
                val side = if (op["side"] == "payer") payer else receiver
                val what = "${s["name"]} op $i ${op["side"]} ${op["op"]}"
                when (op["op"]) {
                    "begin" -> assertStep(map(op["out"]), side.begin(), what)
                    "received" -> {
                        val got = side.received(bytesOf(op["in"]))
                        if (got is TapSession.Step.Send) keep(got.bytes)
                        assertStep(map(op["out"]), got, what)
                    }
                    "framesSince" -> {
                        val frames = side.framesSince((op["arg"] as Double).toLong().toULong())
                        val want = list(op["out"])
                        assertEquals(what, want.size, frames.size)
                        for ((j, f) in frames.withIndex()) assertBytes(want[j], keep(f), "$what frame $j")
                    }
                    "paymentWireEstimate" -> assertEquals(what, (op["out"] as Double).toInt(), side.paymentWireEstimate(text(op["arg"])))
                    "sealPaymentFrames" -> {
                        val frames = side.sealPaymentFrames(text(op["arg"]))
                        when (val want = op["out"]) {
                            null -> assertNull(what, frames)
                            is List<*> -> {
                                assertEquals(what, want.size, frames!!.size)
                                for ((j, f) in frames.withIndex()) assertBytes(want[j], keep(f), "$what frame $j")
                            }
                            else -> assertBytes(want, keep(frames!!.first()), what)
                        }
                    }
                    else -> {
                        val arg = op["arg"]
                        val got = when (op["op"]) {
                            "sealOffer" -> side.sealOffer(text(arg))
                            "sealQuote" -> side.sealQuote(text(arg))
                            "sealTerms" -> side.sealTerms(text(arg))
                            "sealResult" -> side.sealResult(text(arg))
                            "sealChange" -> side.sealChange(text(arg))
                            "sealAsking" -> side.sealAsking()
                            "sealAgain" -> side.sealAgain()
                            "sealChangeTaken" -> side.sealChangeTaken()
                            "sealPaymentSize" -> side.sealPaymentSize((arg as Double).toInt())
                            else -> error("$what: an op the test does not know")
                        }
                        if (op["out"] == null) assertNull(what, got) else assertBytes(op["out"], keep(got), what)
                    }
                }
            }
        }
    }
}
