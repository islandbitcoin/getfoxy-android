package io.islandbitcoin.foxy.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** The bridge's JSON: numbers are Doubles, booleans Booleans, null a present key, and nothing malformed gets through. */
class JsonTest {
    @Test
    fun valuesKeepTheirKinds() {
        @Suppress("UNCHECKED_CAST")
        val o = Json.parse("""{"a":1,"b":true,"c":null,"d":"x\u00e9\n","e":[1.5,-2e3],"f":{}}""") as Map<String, Any?>
        assertEquals(1.0, o["a"])
        assertEquals(true, o["b"])
        assertNull(o["c"])
        assertEquals(true, o.containsKey("c"))
        assertEquals("xé\n", o["d"])
        assertEquals(listOf(1.5, -2000.0), o["e"])
        assertEquals(emptyMap<String, Any?>(), o["f"])
        assertEquals(9_007_199_254_740_991.0, Json.parse("9007199254740991"))
        assertEquals("\uD83E\uDD8A", Json.parse("\"\\ud83e\\udd8a\""))
    }

    @Test
    fun malformedTextIsRefused() {
        val bad = listOf("", "{", "}", "{\"a\"}", "{\"a\":}", "{a:1}", "[1,]", "[1 2]", "01", "1.", ".5", "-", "1e", "tr", "nu",
            "\"x", "\"\\x\"", "\"\\u12\"", "\"a\nb\"", "{} {}", "'x'", "NaN", "Infinity", "[" .repeat(Json.MOST_DEPTH + 2))
        for (text in bad) {
            try {
                Json.parse(text)
                fail("parsed: $text")
            } catch (e: Json.Malformed) {
            }
        }
    }

    @Test
    fun writingSortsKeysAndQuotesSafely() {
        assertEquals("""{"a":1,"b":[true,null,"x"],"c":{"d":2}}""", Json.write(mapOf("c" to mapOf("d" to 2L), "a" to 1, "b" to listOf(true, null, "x"))))
        assertEquals("\"\\u003c/script\\u003e \\u2028 \\\" \\\\ \\n\"", Json.quote("</script> \u2028 \" \\ \n"))
        assertEquals("</script> \u2028 \" \\ \n", Json.parse(Json.quote("</script> \u2028 \" \\ \n")))
        try {
            Json.write(1.5)
            fail("a fraction is not a value the bridge writes")
        } catch (e: IllegalArgumentException) {
        }
    }
}
