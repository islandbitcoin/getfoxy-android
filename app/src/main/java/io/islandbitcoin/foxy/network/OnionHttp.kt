package io.islandbitcoin.foxy.network

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * The two hand-written HTTP readers on the direct-payment path, and the only
 * two places in Foxy where bytes from a stranger are taken apart by hand
 * (Foxy/Network/OnionInbox.swift `HTTPRequestHead`, Foxy/Network/OnionPost.swift
 * `HTTPAnswer`, ported with FoxyTests/OnionHTTPTests.swift).
 *
 * Both read a window of a byte array (`offset`, `length`) rather than an array
 * of their own: the inbox re-reads a buffer that is still growing, and every
 * count here is of the window's own bytes, never of where it sits.
 */
object OnionHttp {
    private val BLANK_LINE = byteArrayOf(13, 10, 13, 10)

    /** Where "\r\n\r\n" starts in data[offset, offset + length), or -1. */
    internal fun blankLine(data: ByteArray, offset: Int, length: Int): Int {
        val end = offset + length
        var i = offset
        while (i + 3 < end) {
            if (data[i] == BLANK_LINE[0] && data[i + 1] == BLANK_LINE[1] && data[i + 2] == BLANK_LINE[2] && data[i + 3] == BLANK_LINE[3]) return i
            i += 1
        }
        return -1
    }

    /** UTF-8, or null: Swift's `String(data:encoding: .utf8)`, which takes no malformed byte. */
    internal fun strictUtf8(data: ByteArray, from: Int, to: Int): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data, from, to - from))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    /** UTF-8 with U+FFFD for what is not: Swift's `String(decoding:as: UTF8.self)`. */
    internal fun lenientUtf8(data: ByteArray, from: Int, to: Int): String = String(data, from, to - from, Charsets.UTF_8)
}

/**
 * Reading one HTTP/1.1 request, as far as the inbox needs: POST, to the one
 * path, with a Content-Length within the limit. Nothing else is accepted (no
 * chunked bodies, no other methods), and a request that says it is too large
 * is refused before its body is read.
 */
object HttpRequestHead {
    sealed class Outcome {
        object NeedMore : Outcome() {
            override fun toString() = "NeedMore"
        }

        data class Refuse(val status: Int) : Outcome()

        data class Payment(val body: String) : Outcome()
    }

    fun parse(
        data: ByteArray,
        path: String,
        largestHead: Int,
        largestBody: Int,
        offset: Int = 0,
        length: Int = data.size - offset,
    ): Outcome {
        val end = OnionHttp.blankLine(data, offset, length)
        if (end < 0) return if (length > largestHead) Outcome.Refuse(413) else Outcome.NeedMore
        // how long the head is, not where it ends
        if (end - offset > largestHead) return Outcome.Refuse(413)
        val head = OnionHttp.strictUtf8(data, offset, end) ?: return Outcome.Refuse(400)
        val lines = head.split("\r\n")
        val parts = lines[0].split(" ")
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) return Outcome.Refuse(400)
        // the path first: a caller without it learns nothing else about what is here
        if (parts[1] != path) return Outcome.Refuse(404)
        if (parts[0] != "POST") return Outcome.Refuse(405)

        var contentLength: Long? = null
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) return Outcome.Refuse(400)
            val name = line.substring(0, colon)
            // a folded line (HTTP's old continuation) or a space before the colon hides a name: neither gets in
            if (name.startsWith(" ") || name.startsWith("\t") || name.endsWith(" ") || name.endsWith("\t")) return Outcome.Refuse(400)
            // a space or a tab is all that may pad a value
            val value = line.substring(colon + 1).trim(' ', '\t')
            when (name.lowercase(Locale.ROOT)) {
                "transfer-encoding" -> return Outcome.Refuse(400)
                "content-length" -> {
                    // digits, and only digits
                    if (contentLength != null || value.isEmpty() || !value.all { it in '0'..'9' }) return Outcome.Refuse(400)
                    contentLength = value.toLongOrNull() ?: return Outcome.Refuse(400)
                }
            }
        }
        val want = contentLength ?: return Outcome.Refuse(400)
        if (want > largestBody) return Outcome.Refuse(413)
        val bodyStart = end + 4
        val have = (offset + length - bodyStart).toLong()
        if (have < want) return Outcome.NeedMore
        if (have > want) return Outcome.Refuse(400)
        val body = OnionHttp.strictUtf8(data, bodyStart, offset + length) ?: return Outcome.Refuse(400)
        return Outcome.Payment(body)
    }
}

/** The status line and body of an answer read to its end (OnionPost.swift `HTTPAnswer`). */
object HttpAnswer {
    data class Answer(val status: Int, val body: String)

    fun parse(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Answer? {
        val end = OnionHttp.blankLine(data, offset, length)
        if (end < 0) return null
        val head = OnionHttp.strictUtf8(data, offset, end) ?: return null
        val first = head.split("\r\n")[0].split(" ").filter { it.isNotEmpty() }
        // three figures, and they must be figures
        if (first.size < 2 || !first[0].startsWith("HTTP/1.")) return null
        if (first[1].length != 3 || !first[1].all { it in '0'..'9' }) return null
        val status = first[1].toInt()
        if (status !in 100..599) return null
        return Answer(status, OnionHttp.lenientUtf8(data, end + 4, offset + length))
    }
}
