package io.islandbitcoin.foxy.network

import io.islandbitcoin.foxy.network.HttpRequestHead.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * A port of FoxyTests/OnionHTTPTests.swift (getfoxy/iOS 8df0bb1), case for
 * case: the two hand-written HTTP readers on the direct-payment path.
 *
 * HttpRequestHead reads what a payer's wallet posts to this phone's onion
 * inbox: anyone on the phone's loopback, and anyone who has the onion address,
 * can send whatever they like to it. HttpAnswer reads what the other phone
 * sends back down a SOCKS connection. Swift's `Data` slices are this port's
 * (offset, length) windows: the same bytes read out of a larger array.
 */

/** A request the inbox would accept, and the pieces to spoil it with. */
internal object Fixture {
    /** The shape of the real one: a path of 128 random bits, hex. */
    const val PATH = "/0123456789abcdef0123456789abcdef"
    const val HEAD_CAP = 8 * 1024
    const val BODY_CAP = OnionInbox.LARGEST_PAYMENT
    const val JSON = """{"mint":"https://m.example.com","proofs":[{"amount":1}]}"""

    fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    fun head(headers: List<String>, method: String = "POST", path: String = PATH, version: String = "HTTP/1.1"): ByteArray {
        val lines = listOf("$method $path $version") + headers
        return bytes(lines.joinToString("\r\n")) + bytes("\r\n\r\n")
    }

    /** The head a Foxy payer actually sends (OnionPost.send builds this), with `body` after it. */
    fun request(
        body: ByteArray = bytes(JSON),
        length: String? = null,
        extra: List<String> = emptyList(),
        method: String = "POST",
        path: String = PATH,
        version: String = "HTTP/1.1",
    ): ByteArray {
        val headers = mutableListOf(
            "Host: abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwxyz234567.onion",
            "Content-Type: application/json",
        )
        headers.add("Content-Length: ${length ?: body.size.toString()}")
        headers += extra
        headers.add("Connection: close")
        return head(headers, method, path, version) + body
    }

    fun parse(data: ByteArray, headCap: Int = HEAD_CAP, bodyCap: Int = BODY_CAP): Outcome =
        HttpRequestHead.parse(data, PATH, headCap, bodyCap)

    /** The same bytes, sitting inside a larger array so that they do not begin at index zero. */
    class Slice(val array: ByteArray, val offset: Int, val length: Int)

    fun slice(data: ByteArray, offset: Int = 9_001): Slice {
        val padded = ByteArray(offset) { 0x41 } + data + ByteArray(7) { 0x42 }
        return Slice(padded, offset, data.size)
    }

    fun parse(s: Slice, headCap: Int = HEAD_CAP, bodyCap: Int = BODY_CAP): Outcome =
        HttpRequestHead.parse(s.array, PATH, headCap, bodyCap, s.offset, s.length)
}

/** What the onion inbox takes for a payment, and what it turns away. */
class OnionInboxParsingTest {
    private fun parse(data: ByteArray, headCap: Int = Fixture.HEAD_CAP, bodyCap: Int = Fixture.BODY_CAP) = Fixture.parse(data, headCap, bodyCap)
    private fun b(s: String) = Fixture.bytes(s)
    private val payment = Outcome.Payment(Fixture.JSON)

    // MARK: The one request that gets through

    @Test
    fun aPostToTheSecretPathIsAPaymentWithItsBodyIntact() = assertEquals(payment, parse(Fixture.request()))

    @Test
    fun anEmptyBodyIsStillAPayment() {
        // the mint decides whether it is worth anything; the reader only frames it
        assertEquals(Outcome.Payment(""), parse(Fixture.request(ByteArray(0), length = "0")))
    }

    @Test
    fun aBodyIsHandedOnByteForByte() {
        // a blank line inside the body is not a second head, and neither is a lone CR
        for (body in listOf("{}", "a\r\n\r\nb", "\r\n", "\uD83E\uDD8A ecash", "x".repeat(4096), """{"memo":"POST / HTTP/1.1"}""", "\u0000\u0000")) {
            assertEquals(body.take(20), Outcome.Payment(body), parse(Fixture.request(b(body))))
        }
    }

    @Test
    fun http10IsReadTheSameWay() = assertEquals(payment, parse(Fixture.request(version = "HTTP/1.0")))

    // MARK: Arriving a piece at a time

    @Test
    fun everyPrefixOfARequestAsksForMore() {
        for (body in listOf("", Fixture.JSON, "a\r\n\r\nb", "p".repeat(3000))) {
            val full = Fixture.request(b(body))
            for (cut in 0 until full.size) {
                assertEquals("cut at $cut of ${full.size}", Outcome.NeedMore, parse(full.copyOf(cut)))
            }
            assertEquals(Outcome.Payment(body), parse(full))
        }
    }

    @Test
    fun aHeadThatHasNotFinishedArrivingAsksForMore() {
        assertEquals(Outcome.NeedMore, parse(ByteArray(0)))
        assertEquals(Outcome.NeedMore, parse(b("P")))
        assertEquals(Outcome.NeedMore, parse(b("POST ${Fixture.PATH} HTTP/1.1\r\n")))
        // the blank line has not landed, so nothing in the head is judged yet
        assertEquals(Outcome.NeedMore, parse(b("GET /somewhere-else HTTP/1.1\r\nTransfer-Encoding: chunked\r\n")))
    }

    @Test
    fun aBodyStillOnItsWayAsksForMore() {
        val full = Fixture.request(b("z".repeat(500)))
        assertEquals(Outcome.NeedMore, parse(full.copyOf(full.size - 1)))
        assertEquals(Outcome.NeedMore, parse(full.copyOf(full.size - 499)))
    }

    // MARK: The path, before anything else

    @Test
    fun anotherPathIsNotFoundAndLearnsNothingElse() {
        for (wrong in listOf("/", "/v1/info", Fixture.PATH + "x", Fixture.PATH.dropLast(1), Fixture.PATH.uppercase(),
            "/0123456789ABCDEF0123456789abcdef", "*")) {
            assertEquals(wrong, Outcome.Refuse(404), parse(Fixture.request(path = wrong)))
        }
    }

    @Test
    fun thePathIsCheckedBeforeTheMethodAndBeforeTheHeaders() {
        val wrong = "/not-the-secret"
        for (spoiled in listOf(
            Fixture.request(method = "GET", path = wrong),
            Fixture.request(method = "DELETE", path = wrong),
            Fixture.request(extra = listOf("Transfer-Encoding: chunked"), path = wrong),
            Fixture.request(length = "not-a-number", path = wrong),
            Fixture.request(length = "${Fixture.BODY_CAP + 1}", path = wrong),
            Fixture.head(listOf("Nonsense"), method = "GET", path = wrong),
        )) {
            assertEquals(Outcome.Refuse(404), parse(spoiled))
        }
    }

    @Test
    fun anEmptyPathIsJustAnotherPath() {
        // "POST  HTTP/1.1": three parts, the middle one empty
        assertEquals(Outcome.Refuse(404), parse(Fixture.request(path = "")))
    }

    // MARK: The method

    @Test
    fun theSecretPathWithAnotherMethodIsNotAllowed() {
        for (method in listOf("GET", "PUT", "HEAD", "DELETE", "OPTIONS", "post", "Post", "POSTX", "", "PO\u0000ST")) {
            assertEquals(method, Outcome.Refuse(405), parse(Fixture.request(method = method)))
        }
    }

    // MARK: How long the body says it is

    @Test
    fun aPaymentMustSayHowLongItIs() {
        assertEquals(Outcome.Refuse(400), parse(Fixture.head(listOf("Host: x.onion")) + b(Fixture.JSON)))
        assertEquals("no headers at all", Outcome.Refuse(400), parse(Fixture.head(emptyList())))
    }

    @Test
    fun twoContentLengthsAreRefusedEvenWhenTheyAgree() {
        assertEquals(Outcome.Refuse(400), parse(Fixture.request(extra = listOf("Content-Length: ${b(Fixture.JSON).size}"))))
        assertEquals(Outcome.Refuse(400), parse(Fixture.request(extra = listOf("Content-Length: 1"))))
        assertEquals(Outcome.Refuse(400), parse(Fixture.request(extra = listOf("content-length: 0"))))
        assertEquals(Outcome.Refuse(400), parse(Fixture.request(extra = listOf("CONTENT-LENGTH: 99"))))
    }

    @Test
    fun aContentLengthIsDigitsAndNothingElse() {
        for (bad in listOf("", "abc", "-1", "+5", "0x10", "1e3", "5.0", "5 5", "5,5", " ", "\u00a05",
            "5\u00a0", "\u0661\u0662", "9999999999999999999999999999", "-0", "5;q=1")) {
            assertEquals("Content-Length: $bad", Outcome.Refuse(400), parse(Fixture.request(length = bad)))
        }
    }

    @Test
    fun aSpaceOrATabAroundTheNumberIsAllowed() {
        // OWS is what HTTP lets pad a value, and a real client may send it
        for (padded in listOf(" 2", "2 ", "\t2", "  2\t ")) {
            assertEquals(padded, Outcome.Payment("hi"), parse(Fixture.request(b("hi"), length = padded)))
        }
    }

    @Test
    fun theNumberIsReadWithoutRegardToTheNameCase() {
        for (name in listOf("content-length", "CONTENT-LENGTH", "Content-length", "cOnTeNt-LeNgTh")) {
            val head = Fixture.head(listOf("Host: x.onion", "$name: 2"))
            assertEquals(name, Outcome.Payment("hi"), parse(head + b("hi")))
        }
    }

    // MARK: No chunked bodies

    @Test
    fun anyTransferEncodingIsRefused() {
        for (value in listOf("chunked", "identity", "", " gzip, chunked", "CHUNKED")) {
            assertEquals(value, Outcome.Refuse(400), parse(Fixture.request(extra = listOf("Transfer-Encoding: $value"))))
            assertEquals(value, Outcome.Refuse(400), parse(Fixture.request(extra = listOf("transfer-encoding: $value"))))
        }
        // refused whether or not a length came with it
        assertEquals(Outcome.Refuse(400), parse(Fixture.head(listOf("Transfer-Encoding: chunked")) + b("0\r\n\r\n")))
    }

    // MARK: The caps

    @Test
    fun aHeadOverTheCapIsRefused() {
        val filler = "X-Pad: " + "p".repeat(400)
        val many = List(40) { filler } // about 16 kB of headers
        assertEquals(Outcome.Refuse(413), parse(Fixture.request(extra = many)))
        // and while it is still arriving, before any blank line has been seen
        val unfinished = b("POST ${Fixture.PATH} HTTP/1.1\r\n" + many.joinToString("\r\n"))
        assertEquals(Outcome.Refuse(413), parse(unfinished))
        assertEquals("under a larger cap the same bytes wait", Outcome.NeedMore, parse(unfinished, headCap = 1 shl 20))
    }

    @Test
    fun theHeadCapIsMeasuredToTheByte() {
        val head = Fixture.head(listOf("Content-Length: 0", "X-Pad: 0123456789"))
        val length = head.size - 4 // the blank line is not part of it
        assertEquals(Outcome.Payment(""), parse(head, headCap = length))
        assertEquals(Outcome.Refuse(413), parse(head, headCap = length - 1))
    }

    @Test
    fun aBodyOverTheCapIsRefusedBeforeItArrives() {
        assertEquals(Outcome.Refuse(413), parse(Fixture.head(listOf("Content-Length: ${Fixture.BODY_CAP + 1}"))))
        assertEquals(Outcome.Refuse(413), parse(Fixture.head(listOf("Content-Length: 9007199254740993"))))
        // exactly the cap is allowed, and waits for its bytes
        assertEquals(Outcome.NeedMore, parse(Fixture.head(listOf("Content-Length: ${Fixture.BODY_CAP}")) + ByteArray(10)))
        assertEquals(Outcome.Refuse(413), parse(Fixture.head(listOf("Content-Length: 11")) + b("hello"), bodyCap = 10))
    }

    @Test
    fun moreBodyThanTheLengthPromisedIsRefused() {
        val full = Fixture.request(b("hi"))
        assertEquals(Outcome.Refuse(400), parse(full + b("!")))
        // a second request pipelined behind the first is the same thing
        assertEquals(Outcome.Refuse(400), parse(full + Fixture.request()))
        assertEquals(Outcome.Refuse(400), parse(Fixture.head(listOf("Content-Length: 0")) + b("x")))
    }

    // MARK: Header lines

    @Test
    fun everyHeaderLineIsANameAColonAndAValue() {
        for (bad in listOf("Nonsense", "no colon here", ": 5", "\u0000", "Content-Length")) {
            assertEquals("[$bad]", Outcome.Refuse(400), parse(Fixture.request(extra = listOf(bad))))
        }
    }

    @Test
    fun foldedAndPaddedHeaderNamesAreRefused() {
        for (bad in listOf(" continued", "\tcontinued", " X-Fold: 1", "\tContent-Length: 1", "Content-Length : 1", "X-Name\t: 1", " : 1")) {
            assertEquals("[$bad]", Outcome.Refuse(400), parse(Fixture.request(extra = listOf(bad))))
        }
        // the fold is refused before its value could be believed
        val folded = Fixture.head(listOf("Host: x.onion", " Content-Length: 2"))
        assertEquals(Outcome.Refuse(400), parse(folded + b("hi")))
    }

    @Test
    fun aHeaderValueMayBeEmptyOrHoldAnythingElse() {
        for (fine in listOf("X-Nothing:", "X-Colon: a:b:c", "X-Wide: \uD83E\uDD8A", "X-Quote: \"a\"")) {
            assertEquals(fine, payment, parse(Fixture.request(extra = listOf(fine))))
        }
    }

    // MARK: The request line

    @Test
    fun theRequestLineIsThreeParts() {
        val p = Fixture.PATH
        for (bad in listOf("POST $p", "POST", "", "POST  $p HTTP/1.1", "POST $p  HTTP/1.1", "POST $p HTTP/1.1 ", " POST $p HTTP/1.1", "POST\t$p\tHTTP/1.1")) {
            assertEquals("[$bad]", Outcome.Refuse(400), parse(b(bad + "\r\nContent-Length: 0\r\n\r\n")))
        }
    }

    @Test
    fun onlyHttp1IsSpokenHere() {
        for (version in listOf("HTTP/2.0", "HTTP/2", "HTTP/0.9", "RTSP/1.0", "http/1.1", "garbage", "")) {
            assertEquals(version, Outcome.Refuse(400), parse(Fixture.request(version = version)))
        }
    }

    // MARK: Bytes that are not text

    @Test
    fun bytesThatAreNotUtf8AreRefusedRatherThanCrash() {
        val broken = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0xC3.toByte(), 0x28)
        // in the head
        val head = b("POST ${Fixture.PATH} HTTP/1.1\r\nX-Junk: ") + broken + b("\r\nContent-Length: 0\r\n\r\n")
        assertEquals(Outcome.Refuse(400), parse(head))
        // in the request line, before the path could be compared
        assertEquals(Outcome.Refuse(400), parse(b("POST ") + broken + b(" HTTP/1.1\r\nContent-Length: 0\r\n\r\n")))
        // and in the body, which is handed to the page as a string
        assertEquals(Outcome.Refuse(400), parse(Fixture.head(listOf("Content-Length: ${broken.size}")) + broken))
    }

    @Test
    fun anUnfinishedMultibyteCharacterInTheBodyIsRefusedNotTruncated() {
        val fox = b("\uD83E\uDD8A")
        assertEquals(Outcome.Refuse(400), parse(Fixture.head(listOf("Content-Length: 3")) + fox.copyOf(3)))
    }

    // MARK: Bytes that do not begin at zero

    @Test
    fun aSliceThatDoesNotBeginAtZeroReadsTheSameWay() {
        assertEquals(payment, Fixture.parse(Fixture.slice(Fixture.request())))
        assertEquals(payment, Fixture.parse(Fixture.slice(Fixture.request(), offset = 1)))
        assertEquals(payment, Fixture.parse(Fixture.slice(Fixture.request(), offset = 300_000)))
        val r = Fixture.request()
        assertEquals(Outcome.NeedMore, Fixture.parse(Fixture.slice(r.copyOf(r.size - 1))))
        assertEquals(Outcome.Refuse(404), Fixture.parse(Fixture.slice(Fixture.request(path = "/elsewhere"))))
        assertEquals(Outcome.Refuse(400), Fixture.parse(Fixture.slice(Fixture.request(length = "x"))))
        // the cap still counts the slice's own bytes, not where it sits
        val head = Fixture.head(listOf("Content-Length: 0"))
        assertEquals(Outcome.Payment(""), Fixture.parse(Fixture.slice(head), headCap = head.size - 4))
        assertEquals(Outcome.Refuse(413), Fixture.parse(Fixture.slice(head), headCap = head.size - 5))
    }
}

/** What comes back from the other phone (HttpAnswer), read off a connection that is closing. */
class OnionAnswerParsingTest {
    private fun answer(text: String) = HttpAnswer.parse(text.toByteArray(Charsets.UTF_8))

    @Test
    fun anAnswerGivesItsStatusAndBody() {
        val parsed = answer("HTTP/1.1 200 OK\r\nContent-Length: 13\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n{\"paid\":true}")
        assertEquals(200, parsed?.status)
        assertEquals("{\"paid\":true}", parsed?.body)
    }

    @Test
    fun theStatusIsWhateverTheInboxSent() {
        // every code OnionInbox.respond can send, and the edges of the range
        for (status in listOf(200, 400, 404, 405, 410, 413, 422, 504, 100, 599)) {
            assertEquals(status, answer("HTTP/1.1 $status Reason\r\n\r\n")?.status)
        }
    }

    @Test
    fun somethingThatIsNotAStatusIsNotAnAnswer() {
        for (bad in listOf("HTTP/1.1 99 X", "HTTP/1.1 600 X", "HTTP/1.1 1000 X", "HTTP/1.1 20 X", "HTTP/1.1 abc X",
            "HTTP/1.1 +200 X", "HTTP/1.1 2x0 X", "HTTP/1.1 -200 X", "HTTP/1.1", "HTTP/1.1 ",
            "HTTP/2.0 200 OK", "http/1.1 200 OK", "ICY 200 OK", "200 OK", "")) {
            assertNull(bad, answer(bad + "\r\n\r\nbody"))
        }
    }

    @Test
    fun aReasonPhraseIsNotNeeded() {
        assertEquals(204, answer("HTTP/1.1 204\r\n\r\n")?.status)
        assertEquals("extra spaces are skipped", 200, answer("HTTP/1.1  200  OK  \r\n\r\n")?.status)
    }

    @Test
    fun anAnswerNeedsItsBlankLine() {
        assertNull(HttpAnswer.parse(ByteArray(0)))
        assertNull(answer("HTTP/1.1 200 OK"))
        assertNull(answer("HTTP/1.1 200 OK\r\n"))
        assertNull(answer("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n"))
        assertNull("bare newlines do not end a head", answer("HTTP/1.1 200 OK\n\n"))
    }

    @Test
    fun theBodyIsEverythingAfterTheBlankLineWhateverTheHeadersSay() {
        assertEquals("short", answer("HTTP/1.1 200 OK\r\nContent-Length: 99\r\n\r\nshort")?.body)
        assertEquals("longer than that", answer("HTTP/1.1 200 OK\r\nContent-Length: 1\r\n\r\nlonger than that")?.body)
        assertEquals("no headers and no body", "", answer("HTTP/1.1 200 OK\r\n\r\n")?.body)
        assertEquals("only the first blank line ends the head", "a\r\n\r\nb", answer("HTTP/1.1 200 OK\r\n\r\na\r\n\r\nb")?.body)
    }

    @Test
    fun answersOfAnySizeUpToTheCallersCap() {
        val big = "j".repeat(OnionPost.LARGEST_ANSWER - 64)
        val parsed = HttpAnswer.parse("HTTP/1.1 200 OK\r\n\r\n".toByteArray() + big.toByteArray())
        assertEquals(200, parsed?.status)
        assertEquals(big.length, parsed?.body?.length)
    }

    @Test
    fun bytesThatAreNotUtf8() {
        val broken = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        // in the head there is nothing to read, so there is no answer
        assertNull(HttpAnswer.parse("HTTP/1.1 200 ".toByteArray() + broken + "\r\n\r\nx".toByteArray()))
        // in the body they become replacement characters rather than nothing
        val parsed = HttpAnswer.parse("HTTP/1.1 422 X\r\n\r\n".toByteArray() + broken)
        assertEquals(422, parsed?.status)
        assertEquals("\ufffd\ufffd", parsed?.body)
    }

    @Test
    fun aSliceThatDoesNotBeginAtZeroReadsTheSameWay() {
        val raw = "HTTP/1.1 413 Payload Too Large\r\nContent-Length: 2\r\n\r\nno".toByteArray()
        for (offset in listOf(1, 4, 70_000)) {
            val s = Fixture.slice(raw, offset)
            val parsed = HttpAnswer.parse(s.array, s.offset, s.length)
            assertEquals("offset $offset", 413, parsed?.status)
            assertEquals("offset $offset", "no", parsed?.body)
        }
    }
}

/** A pocket xorshift, so that the same few thousand spoiled requests run on every machine and every day. */
private class Pocket(seed: Long) {
    private var state: Long = seed or 1

    fun next(): Long {
        state = state xor (state shl 13)
        state = state xor (state ushr 7)
        state = state xor (state shl 17)
        return state
    }

    fun below(n: Int): Int = if (n <= 0) 0 else java.lang.Long.remainderUnsigned(next(), n.toLong()).toInt()
    fun byte(): Byte = next().toByte()
}

/** Thousands of spoiled requests, and what must hold for every one of them. */
class OnionHttpFuzzTest {
    private val headCap = 512
    private val bodyCap = 512

    private fun parse(data: ByteArray) = HttpRequestHead.parse(data, Fixture.PATH, headCap, bodyCap)

    /**
     * A second reading of the same bytes, written out plainly, to judge a
     * payment by: the page is handed a body only when the bytes really were a
     * POST to the secret path, carrying one Content-Length within the cap,
     * followed by exactly that many bytes and no more.
     */
    private fun reallyIsAPayment(data: ByteArray, body: String): Boolean {
        val end = OnionHttp.blankLine(data, 0, data.size)
        if (end < 0 || end > headCap) return false
        val head = OnionHttp.strictUtf8(data, 0, end) ?: return false
        val lines = head.split("\r\n")
        val parts = lines[0].split(" ")
        if (parts.size != 3 || parts[0] != "POST" || parts[1] != Fixture.PATH || !parts[2].startsWith("HTTP/1.")) return false
        val lengths = mutableListOf<Long>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) return false
            val name = line.substring(0, colon)
            if (name.startsWith(" ") || name.startsWith("\t") || name.endsWith(" ") || name.endsWith("\t")) return false
            val lowered = name.lowercase()
            if (lowered == "transfer-encoding") return false
            if (lowered == "content-length") {
                val value = line.substring(colon + 1).trim(' ', '\t')
                if (value.isEmpty() || !value.all { it in '0'..'9' }) return false
                lengths.add(value.toLongOrNull() ?: return false)
            }
        }
        if (lengths.size != 1 || lengths[0] < 0 || lengths[0] > bodyCap) return false
        val rest = data.copyOfRange(end + 4, data.size)
        return rest.size.toLong() == lengths[0] && OnionHttp.strictUtf8(rest, 0, rest.size) == body
    }

    private fun lineBreaks(bytes: List<Byte>): List<Int> {
        val found = mutableListOf(0)
        var i = 0
        while (i + 1 < bytes.size) {
            if (bytes[i] == 0x0D.toByte() && bytes[i + 1] == 0x0A.toByte()) {
                found.add(i + 2)
                i += 2
            } else {
                i += 1
            }
        }
        return found
    }

    /** One spoiling of `base`, chosen by the generator alone. */
    private fun spoil(base: ByteArray, rng: Pocket): ByteArray {
        var bytes = base.toMutableList()
        when (rng.below(11)) {
            0 -> bytes = bytes.take(rng.below(bytes.size + 1)).toMutableList()
            1 -> {
                val at = rng.below(bytes.size)
                bytes[at] = (bytes[at].toInt() xor (1 shl rng.below(8))).toByte()
            }
            2 -> bytes[rng.below(bytes.size)] = rng.byte()
            3 -> {
                // the bytes that mean something to a reader of HTTP
                val poison = byteArrayOf(0x0D, 0x0A, 0x00, 0x20, 0x09, 0x3A, 0x2B, 0x2D, 0xFF.toByte(), 0xC3.toByte())
                bytes.add(rng.below(bytes.size + 1), poison[rng.below(poison.size)])
            }
            4 -> bytes.removeAt(rng.below(bytes.size))
            5 -> {
                // a header line said twice, which is how a smuggled length gets in
                val breaks = lineBreaks(bytes)
                if (breaks.size >= 2) {
                    val which = rng.below(breaks.size - 1)
                    val line = bytes.subList(breaks[which], breaks[which + 1]).toList()
                    bytes.addAll(breaks[which], line)
                }
            }
            6 -> repeat(rng.below(64)) { bytes.add(rng.byte()) }
            7 -> {
                val cut = rng.below(bytes.size + 1)
                val suffix = rng.below(bytes.size + 1)
                bytes = (bytes.take(cut) + bytes.takeLast(suffix)).toMutableList()
            }
            8 -> {
                // a length of the generator's choosing, sensible or not
                val shapes = listOf("${rng.below(10_000)}", "-${rng.below(99)}", "+${rng.below(99)}", "9".repeat(1 + rng.below(25)), "",
                    " ${rng.below(20)} ", "${rng.below(20)} , ${rng.below(20)}", "0${rng.below(9)}")
                bytes = (Fixture.head(listOf("Content-Length: ${shapes[rng.below(shapes.size)]}")) + "body".toByteArray()).toMutableList()
            }
            9 -> bytes = MutableList(rng.below(600)) { rng.byte() }
            else -> bytes.addAll(rng.below(bytes.size), "\r\n\r\n".toByteArray().toList())
        }
        return bytes.toByteArray()
    }

    @Test
    fun thousandsOfSpoiledRequestsGetNothingThroughAndCrashNothing() {
        val rng = Pocket(0x464F5859_31383230L) // "FOXY" and the day iOS wrote this
        val bases = listOf(
            Fixture.request("{}".toByteArray()),
            Fixture.request("q".repeat(300).toByteArray()),
            Fixture.request(ByteArray(0), length = "0"),
            Fixture.head(listOf("Content-Length: 4")) + "body".toByteArray(),
        )
        var payments = 0
        var refusals = 0
        var waits = 0
        for (round in 0 until 4000) {
            val data = spoil(bases[round % bases.size], rng)
            val outcome = parse(data)
            when (outcome) {
                is Outcome.Payment -> {
                    payments += 1
                    assertTrue("round $round got through: ${String(data.copyOf(minOf(200, data.size)))}", reallyIsAPayment(data, outcome.body))
                }
                Outcome.NeedMore -> {
                    waits += 1
                    // waiting means the buffer may still grow: anything already past both caps must have been refused
                    assertTrue("round $round waits on too many bytes", data.size <= headCap + 4 + bodyCap)
                }
                is Outcome.Refuse -> {
                    refusals += 1
                    assertTrue("round $round answered ${outcome.status}", outcome.status in setOf(400, 404, 405, 413))
                }
            }
            // the same bytes read out of a slice must come to the same answer
            val s = Fixture.slice(data, 1 + round % 997)
            assertEquals("round $round read differently out of a slice", outcome, HttpRequestHead.parse(s.array, Fixture.PATH, headCap, bodyCap, s.offset, s.length))
        }
        // a spoiling that never got anything through would prove nothing about the accepting side
        assertTrue("no mutation was ever accepted, so the oracle was never tested", payments > 0)
        assertTrue(refusals > 100)
        assertTrue(waits > 0)
    }

    @Test
    fun theAnswerParserSurvivesTheSameTreatment() {
        val rng = Pocket(0x414E5357_45520001L)
        val base = "HTTP/1.1 200 OK\r\nContent-Length: 13\r\nConnection: close\r\n\r\n{\"paid\":true}".toByteArray()
        var answers = 0
        for (round in 0 until 3000) {
            val data = spoil(base, rng)
            val parsed = HttpAnswer.parse(data) ?: continue
            answers += 1
            assertTrue("round $round gave status ${parsed.status}", parsed.status in 100..599)
            // the body is whatever followed the first blank line, U+FFFD for any byte that was not UTF-8
            val blank = OnionHttp.blankLine(data, 0, data.size)
            assertEquals("round $round", String(data, blank + 4, data.size - blank - 4, Charsets.UTF_8), parsed.body)
            val s = Fixture.slice(data, 1 + round % 89)
            assertEquals("round $round read differently out of a slice", parsed.status, HttpAnswer.parse(s.array, s.offset, s.length)?.status)
        }
        assertTrue(answers > 0)
    }

    // MARK: the request the phone writes to an onion

    /** The path goes on the request line as the URL holds it, percent-encoded, never a line break. */
    @Test
    fun theRequestTargetIsThePathAsEncodedAndNeverALineBreak() {
        val host = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwd"
        assertTrue(OnionAddress.isServiceId(host))
        assertEquals("/api/blocks/tip/hash", OnionPost.target("http://$host.onion/api/blocks/tip/hash")?.path)
        assertEquals("no path is the root", "/", OnionPost.target("http://$host.onion")?.path)
        val crafted = OnionPost.target("http://$host.onion/pay%0d%0aX-Added:%201%0d%0a")
        assertNotNull(crafted)
        val target = crafted!!.path
        assertFalse(target, target.contains("\r") || target.contains("\n") || target.contains(" "))
        assertEquals("sent as written, one line", "/pay%0d%0aX-Added:%201%0d%0a", target)
    }
}
