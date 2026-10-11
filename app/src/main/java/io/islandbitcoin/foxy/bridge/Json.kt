package io.islandbitcoin.foxy.bridge

/**
 * The JSON the bridge reads and writes, here rather than in org.json.
 *
 * Three reasons. The page's messages arrive as strings (the shim JSON-encodes
 * them), and the checks ported from iOS need to tell a number from a boolean
 * and a missing field from a null the way WebKit's NSNumber did: a parser of our
 * own makes every number a Double and every boolean a Boolean, and a JSON null a
 * key that is there with the value null. Replies are written with sorted keys,
 * as iOS's JSONSerialization `.sortedKeys` writes them, so the bytes match. And
 * the same code runs in the JVM unit tests, where android.jar's org.json is a
 * stub that throws.
 */
object Json {
    /** Nesting deeper than this is refused: no page message is more than three deep. */
    const val MOST_DEPTH = 32

    class Malformed(message: String) : Exception(message)

    /**
     * A JSON text as Kotlin values: [Map] (insertion order), [List], [String],
     * [Double], [Boolean] or null. Anything after the value, other than
     * whitespace, is refused.
     */
    @Throws(Malformed::class)
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.space()
        val value = p.value(0)
        p.space()
        if (p.i != text.length) throw Malformed("text after the value")
        return value
    }

    private class Parser(val s: String) {
        var i = 0

        fun space() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun value(depth: Int): Any? {
            if (depth > MOST_DEPTH) throw Malformed("too deep")
            if (i >= s.length) throw Malformed("end of text")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw Malformed("unexpected '$c'")
            }
        }

        fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw Malformed("bad literal")
            i += w.length
            return v
        }

        fun obj(depth: Int): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            space()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                space()
                if (i >= s.length || s[i] != '"') throw Malformed("a key must be a string")
                val k = str()
                space()
                if (i >= s.length || s[i] != ':') throw Malformed("expected ':'")
                i++
                space()
                out[k] = value(depth + 1)
                space()
                if (i >= s.length) throw Malformed("end of text in an object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> throw Malformed("expected ',' or '}'")
                }
            }
        }

        fun arr(depth: Int): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            space()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                space()
                out.add(value(depth + 1))
                space()
                if (i >= s.length) throw Malformed("end of text in an array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> throw Malformed("expected ',' or ']'")
                }
            }
        }

        fun str(): String {
            i++
            val out = StringBuilder()
            while (true) {
                if (i >= s.length) throw Malformed("end of text in a string")
                val c = s[i++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (i >= s.length) throw Malformed("end of text in an escape")
                        when (val e = s[i++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw Malformed("short \\u escape")
                                val hex = s.substring(i, i + 4)
                                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) throw Malformed("bad \\u escape")
                                out.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> throw Malformed("bad escape '\\$e'")
                        }
                    }
                    c.code < 0x20 -> throw Malformed("a control character in a string")
                    else -> out.append(c)
                }
            }
        }

        fun num(): Double {
            val start = i
            if (s[i] == '-') i++
            if (i >= s.length) throw Malformed("bad number")
            if (s[i] == '0') {
                i++
            } else if (s[i] in '1'..'9') {
                while (i < s.length && s[i] in '0'..'9') i++
            } else {
                throw Malformed("bad number")
            }
            if (i < s.length && s[i] == '.') {
                i++
                val digits = i
                while (i < s.length && s[i] in '0'..'9') i++
                if (i == digits) throw Malformed("bad fraction")
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                val digits = i
                while (i < s.length && s[i] in '0'..'9') i++
                if (i == digits) throw Malformed("bad exponent")
            }
            return s.substring(start, i).toDouble()
        }
    }

    /**
     * A JSON string literal, quotes included. Besides what JSON requires, U+2028
     * and U+2029 are escaped (older JavaScript took them for line ends) and so
     * are `<`, `>` and `&`, so no text can close a script element or start a
     * comment wherever the literal ends up.
     */
    fun quote(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (c in value) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\u2028', '\u2029', '<', '>', '&' -> out.append("\\u%04x".format(c.code))
                else -> if (c.code < 0x20 || c.code == 0x7f) out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        return out.append('"').toString()
    }

    /**
     * A value as JSON text, object keys sorted. Strings, booleans, whole numbers
     * ([Int], [Long]), maps with string keys, lists and null; anything else is a
     * programming error.
     */
    fun write(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is String -> out.append(quote(value))
            is Boolean -> out.append(if (value) "true" else "false")
            is Int, is Long -> out.append(value.toString())
            is Map<*, *> -> {
                out.append('{')
                val keys = value.keys.map { it as String }.sorted()
                keys.forEachIndexed { n, k ->
                    if (n > 0) out.append(',')
                    out.append(quote(k)).append(':')
                    write(value[k], out)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { n, v ->
                    if (n > 0) out.append(',')
                    write(v, out)
                }
                out.append(']')
            }
            else -> throw IllegalArgumentException("not a JSON value: ${value::class.java.name}")
        }
    }
}
