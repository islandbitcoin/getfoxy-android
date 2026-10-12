package io.islandbitcoin.foxy.flashcard

import io.islandbitcoin.foxy.bridge.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the phone will carry to a card for the page, and what it will not
 * (flashcard/CardGate.kt): FoxyTests/CardGateTests.swift at getfoxy/iOS 8df0bb1,
 * ported test for test, and then every instruction byte, every class byte and
 * every P1 and P2 the gate could be handed, so the 31 are the only ones it
 * carries whatever the parameters, and SET_TAG (52) is not one of them, as
 * upstream. The list is also held to the page's own (`CARD_INS` in the pinned
 * core/web), both ways, as iOS's tests/flashcard-model.js holds it.
 */
class CardGateTest {
    private fun bytes(hex: String): ByteArray = CardGate.bytes(hex)!!
    private val select = "00a404000af0464f5859434152440100"
    private fun hex2(n: Int) = "%02x".format(n)

    /** The 31 of CardGate.swift:35-53 at 8df0bb1, as written there. */
    private val carried = listOf("01", "10", "11", "13", "14", "15", "16", "17", "18", "19", "1a", "20", "22", "23", "24", "25", "26",
        "30", "31", "32", "34", "35", "36", "37", "40", "41", "42", "43", "44", "45", "51")

    @Test
    fun hexIsWholeBytesAndNothingElse() {
        assertTrue(CardGate.bytes("00A4b0ff")!!.contentEquals(byteArrayOf(0x00, 0xA4.toByte(), 0xB0.toByte(), 0xFF.toByte())))
        assertEquals("00a4b0ff", CardGate.hex(byteArrayOf(0x00, 0xA4.toByte(), 0xB0.toByte(), 0xFF.toByte())))
        for (bad in listOf("", "0", "0g", "00 a4", "0x00", "00\n", "００", "0٠", "00".repeat(CardGate.LONGEST + 1))) {
            assertNull("${bad.take(12)} is not hex for a command", CardGate.bytes(bad))
        }
        assertNotNull(CardGate.bytes("00".repeat(CardGate.LONGEST)))
        // every byte there is, both ways
        val all = ByteArray(256) { it.toByte() }
        assertTrue(CardGate.bytes(CardGate.hex(all))!!.contentEquals(all))
    }

    @Test
    fun theAppletIsChosenByItsWholeNameOnly() {
        assertEquals(CardGate.Command.Select, CardGate.read(bytes(select)))
        assertEquals("with no length expected back", CardGate.Command.Select, CardGate.read(bytes("00a404000af0464f58594341524401")))
        // its package's nine bytes, a longer name, another applet, another kind of SELECT, a payment card's directory, the NFC tag
        for (other in listOf(
            "00a4040009f0464f58594341524400",
            "00a404000bf0464f5859434152440100",
            "00a404000af0464f58594341524402",
            "00a400000af0464f58594341524401",
            "00a404020af0464f58594341524401",
            "00a404000e325041592e5359532e444446303100",
            "00a4040007d276000085010100",
            "00a40400",
        )) {
            assertNull("$other chooses something else", CardGate.read(bytes(other)))
        }
        // a SELECT by name with any other P1 or P2, or under another class, is not the choosing
        for (p in 0..255) {
            if (p != 0x04) assertNull("SELECT P1 ${hex2(p)}", CardGate.read(bytes("00a4" + hex2(p) + "000af0464f5859434152440100")))
            if (p != 0x00) assertNull("SELECT P2 ${hex2(p)}", CardGate.read(bytes("00a404" + hex2(p) + "0af0464f5859434152440100")))
            if (p != 0x00) assertNull("SELECT under class ${hex2(p)}", CardGate.read(bytes(hex2(p) + "a404000af0464f5859434152440100")))
        }
    }

    @Test
    fun onlyTheInstructionsThePageUsesAreCarried() {
        // every command build/wallet/08a-flashcard.js builds: class B0, and one of these thirty-one
        assertEquals(31, carried.size)
        assertEquals("exactly these, and the table says so", carried.map { it.toInt(16) }.toSet(), CardGate.INSTRUCTIONS)
        for (ins in carried) {
            assertNotNull("instruction $ins", CardGate.read(bytes("b0" + ins + "0000")))
        }
        assertEquals("a PIN to check", CardGate.Command.Applet(0x40), CardGate.read(bytes("b040000004" + "31323334")))
        assertEquals("sign for the piece in place 5", CardGate.Command.Applet(0x20), CardGate.read(bytes("b020050040")))
        // one signature for a payment: the places, the outputs (37 bytes each), the signature, and the last one again
        assertEquals("the places a payment is made of", CardGate.Command.Applet(0x22), CardGate.read(bytes("b0220000" + "03" + "000507" + "04")))
        assertEquals("one output of its swap", CardGate.Command.Applet(0x23), CardGate.read(bytes("b0230000" + "25" + "ab".repeat(37))))
        assertEquals("six of them", CardGate.Command.Applet(0x23), CardGate.read(bytes("b0230000" + "de" + "ab".repeat(222))))
        assertEquals("sign for the payment", CardGate.Command.Applet(0x24), CardGate.read(bytes("b024000040")))
        assertEquals("the last signature again", CardGate.Command.Applet(0x25), CardGate.read(bytes("b025000040")))
        // the change a card of software 1.12 makes for itself: one output of it (the amount), and the openings to finish its pieces with
        assertEquals("one output of the card's own change", CardGate.Command.Applet(0x26), CardGate.read(bytes("b0260000" + "04" + "00000040" + "21")))
        assertEquals("the openings of that change, the first page", CardGate.Command.Applet(0x19), CardGate.read(bytes("b019000000")))
        assertEquals("and the last", CardGate.Command.Applet(0x19), CardGate.read(bytes("b019020000")))
        assertEquals("the card's own log", CardGate.Command.Applet(0x18), CardGate.read(bytes("b018000000")))
        // software 1.20: the mint's proof kept with a piece, for one place or two (no data), and a piece written with its proof (30 with P1 = 1: 81 bytes and 96)
        assertEquals("the proof of place 0 and no second place", CardGate.Command.Applet(0x1A), CardGate.read(bytes("b01a00ff00")))
        assertEquals("the proofs of places 2 and 7", CardGate.Command.Applet(0x1A), CardGate.read(bytes("b01a020700")))
        assertEquals(
            "one piece with the mint's proof for it, 177 bytes, and its place and whether the proof was kept expected back",
            CardGate.Command.Applet(0x30), CardGate.read(bytes("b0300100" + "b1" + "ab".repeat(177) + "02")),
        )
        assertNull("a length that says 177 and carries 176", CardGate.read(bytes("b0300100" + "b1" + "ab".repeat(176))))
        assertEquals("the time, signed (a card of 1.14 and before)", CardGate.Command.Applet(0x35), CardGate.read(bytes("b035000006" + "01020304" + "00" + "00")))
        // a card of software 1.15 and on: a block header (80 bytes, and the clock expected back), and the phone's own time as a note (4 bytes, nothing back)
        assertEquals("a block header", CardGate.Command.Applet(0x36), CardGate.read(bytes("b0360000" + "50" + "ab".repeat(80) + "04")))
        assertEquals("the phone's own time, as a note", CardGate.Command.Applet(0x37), CardGate.read(bytes("b0370000" + "04" + "6ac9dc75")))
        assertEquals("the owner's grant to load", CardGate.Command.Applet(0x45), CardGate.read(bytes("b0450000" + "03" + "300100")))
        assertEquals("a reset (software 1.17), under the owner's proof", CardGate.Command.Applet(0x51), CardGate.read(bytes("b05100ad" + "03" + "300100")))
        assertEquals("an owner key", CardGate.Command.Applet(0x43), CardGate.read(bytes("b0430000" + "03" + "040102")))
        assertEquals("the limit, with the owner's proof", CardGate.Command.Applet(0x34), CardGate.read(bytes("b034000005" + "0102030405")))
        assertEquals("which pieces it holds, from place 0", CardGate.Command.Applet(0x17), CardGate.read(bytes("b017000000")))
        assertEquals("the next page of them", CardGate.Command.Applet(0x17), CardGate.read(bytes("b017400000")))
        assertTrue(CardGate.allows(bytes("b017000000"), selected = true))
        assertFalse("not before the applet is chosen", CardGate.allows(bytes("b017000000"), selected = false))
        // 33 (the limit with the PIN, for a card with no owner) and 50 (lock the card for good) are not sent by the page, so
        // they are refused; and the others are ones the page never asks for, the signing upstream had, and nothing at all
        for (ins in listOf("33", "50", "12", "21", "27", "00", "ff", "a4")) {
            assertNull("instruction $ins is not one the page sends", CardGate.read(bytes("b0" + ins + "0000")))
        }
        assertNull("the PIN form of the limit, with its data", CardGate.read(bytes("b033000004" + "00000000")))
        assertNull("locking a card for good", CardGate.read(bytes("b050" + "00de" + "00")))
        // another class with one of our instructions
        for (cla in listOf("00", "80", "84", "b1")) {
            assertNull("class $cla", CardGate.read(bytes(cla + "400000043132333a")))
            assertNull("class $cla, the time", CardGate.read(bytes(cla + "35000001" + "00")))
        }
    }

    /**
     * SET_TAG (52, card software 1.19) is not carried, as CardGate.swift at 8df0bb1
     * does not carry it: FOXY-CARD-SPEC.md section 7 says the phone's gate should,
     * and that drift is iOS's to settle, not Android's to fix alone. When iOS adds
     * it, the re-pin adds it here and in [carried], and this test changes with it.
     */
    @Test
    fun setTagIsNotCarriedAsUpstream() {
        assertFalse(0x52 in CardGate.INSTRUCTIONS)
        for (p1 in listOf("00", "01")) {
            val setTag = bytes("b052" + p1 + "00" + "03" + "300100")
            assertNull("SET_TAG, tag ${if (p1 == "00") "on" else "off"}", CardGate.read(setTag))
            assertFalse(CardGate.allows(setTag, selected = true))
        }
    }

    /** Every instruction byte under the applet's class, and every P1 and P2 for each: the 31 are carried, whatever the parameters, and nothing else is. */
    @Test
    fun everyInstructionByteAndEveryParameter() {
        var allowed = 0
        for (ins in 0..255) {
            val mine = ins in CardGate.INSTRUCTIONS
            if (mine) allowed++
            for (p in 0..255) {
                val withP1 = bytes("b0" + hex2(ins) + hex2(p) + "00")
                val withP2 = bytes("b0" + hex2(ins) + "00" + hex2(p))
                val withData = bytes("b0" + hex2(ins) + hex2(p) + hex2(255 - p) + "02" + "abcd" + "00")
                for (command in listOf(withP1, withP2, withData)) {
                    assertEquals("b0 ${hex2(ins)} P ${hex2(p)}", if (mine) CardGate.Command.Applet(ins) else null, CardGate.read(command))
                    assertEquals("b0 ${hex2(ins)} P ${hex2(p)}, chosen", mine, CardGate.allows(command, selected = true))
                    assertFalse("b0 ${hex2(ins)} P ${hex2(p)}, before the applet is chosen", CardGate.allows(command, selected = false))
                }
            }
        }
        assertEquals(31, allowed)
        // and under every other class, none of them
        for (cla in 0..255) {
            if (cla == 0xB0) continue
            for (ins in 0..255) {
                if (cla == 0x00 && ins == 0xA4) continue
                assertNull("class ${hex2(cla)} ins ${hex2(ins)}", CardGate.read(bytes(hex2(cla) + hex2(ins) + "0000")))
            }
        }
    }

    @Test
    fun aCommandMustBeWellFormed() {
        assertNull("shorter than a header", CardGate.read(bytes("b040")))
        assertNull("says five bytes and carries four", CardGate.read(bytes("b04000000531323334")))
        assertNull("says three bytes and carries six", CardGate.read(bytes("b040000003313233343536")))
        assertNull("a length of none with bytes after it", CardGate.read(bytes("b0400000000000")))
        assertNotNull("the longest there is", CardGate.read(bytes("b0400000" + "ff" + "00".repeat(255) + "00")))
        assertNull("longer than the longest", CardGate.read(ByteArray(CardGate.LONGEST + 1).also { it[0] = 0xB0.toByte(); it[1] = 0x40 }))
        // every length byte against what follows it: only exact, or exact and one more (the length expected back)
        for (lc in 1..255) {
            for (carriedBytes in listOf(lc - 1, lc, lc + 1, lc + 2)) {
                if (carriedBytes < 1 || 4 + 1 + carriedBytes > CardGate.LONGEST) continue
                val command = bytes("b0400000" + hex2(lc) + "00".repeat(carriedBytes))
                val wellFormed = carriedBytes == lc || carriedBytes == lc + 1
                assertEquals("Lc $lc carrying $carriedBytes", wellFormed, CardGate.read(command) != null)
            }
        }
    }

    @Test
    fun nothingIsSaidToACardBeforeTheAppletAnswers() {
        val verify = bytes("b04000000431323334")
        assertFalse("a PIN is not sent to a card that has not been chosen", CardGate.allows(verify, selected = false))
        // the time and the owner's grant are the applet's too: carried only once it has answered that it was chosen
        for (command in listOf(
            bytes("b035000006" + "01020304" + "0000"), bytes("b0360000" + "50" + "ab".repeat(80) + "04"),
            bytes("b0370000" + "04" + "6ac9dc75"), bytes("b0450000" + "03" + "300100"), bytes("b044000010"), bytes("b0420000" + "02" + "0102"),
        )) {
            assertFalse("${CardGate.hex(command)} before the applet is chosen", CardGate.allows(command, selected = false))
            assertTrue("${CardGate.hex(command)} after", CardGate.allows(command, selected = true))
        }
        assertFalse("locking is refused even then", CardGate.allows(bytes("b0500000de"), selected = true))
        assertTrue(CardGate.allows(bytes(select), selected = false))
        assertTrue(CardGate.allows(verify, selected = true))
        assertTrue("choosing again is how a session starts over", CardGate.allows(bytes(select), selected = true))
        assertFalse("reading a payment card's record, even after ours was chosen", CardGate.allows(bytes("00b2010c00"), selected = true))
        assertTrue(CardGate.succeeded(bytes("01009000")))
        assertTrue(CardGate.succeeded(bytes("9000")))
        assertFalse(CardGate.succeeded(bytes("6a82")))
        assertFalse(CardGate.succeeded(bytes("90")))
        assertFalse(CardGate.succeeded(ByteArray(0)))
        assertFalse(CardGate.succeeded(bytes("9001")))
    }

    @Test
    fun aLineForTheSheetIsOneShortLine() {
        assertEquals("Keep the card there", CardGate.line("Keep the card there"))
        assertEquals("onetwothreefour", CardGate.line("one\ntwo\u0007\tthree\u2028four"))
        assertEquals(120, CardGate.line("a".repeat(500)).length)
        assertEquals("", CardGate.line(null))
        // a character as a person counts it: an accented letter of two scalars is one, a fox of two chars is one
        val accented = CardGate.line("é".repeat(200))
        assertEquals(240, accented.length)
        val foxes = CardGate.line("🦊".repeat(200))
        assertEquals(240, foxes.length)
        // format characters (Cf) go, as Swift's controlCharacters takes them
        assertEquals("ab", CardGate.line("a\u200Bb"))
        assertEquals("ab", CardGate.line("a\u0085b"))
    }

    /** The script that tells the page how a session is going. */
    @Test
    fun progressIsAScriptForTheFourStages() {
        val head = "window.FoxyWallet && window.FoxyWallet._card && window.FoxyWallet._card("
        assertEquals(head + "{\"stage\":\"connected\",\"text\":\"Scanning. Hold still.\"})", CardGate.progressScript("connected", "Scanning. Hold still."))
        assertEquals(head + "{\"stage\":\"connected\",\"text\":\"Reading the card\"})", CardGate.progressScript("connected", CardGate.SCANNING))
        assertEquals(head + "{\"stage\":\"say\",\"text\":\"Keep the card there\"})", CardGate.progressScript("say", "Keep the card there"))
        assertEquals(head + "{\"stage\":\"end\",\"text\":\"Done\"})", CardGate.progressScript("end", "Done"))
        assertEquals(head + "{\"stage\":\"lost\",\"text\":\"the tag was lost\"})", CardGate.progressScript("lost", CardLinkError.LOST.words))
        assertEquals("an end with no words is still an end", head + "{\"stage\":\"end\",\"text\":\"\"})", CardGate.progressScript("end", ""))
        for (stage in listOf("", "Say", "SAY", "say ", "connect", "done", "say\",\"x\":\"", "end\n", "tap")) {
            assertNull("$stage is not a stage", CardGate.progressScript(stage, "x"))
        }
    }

    /** Words from the page, or from a card's error, cannot get out of the string they are put in. */
    @Test
    fun progressWordsStayInsideTheirString() {
        val head = "window.FoxyWallet && window.FoxyWallet._card && window.FoxyWallet._card("
        val hostile = listOf(
            "He said \"stop\" and left",
            "back\\slash \\\" and \\\\",
            "two\nlines\r\nand\ttabs",
            "</script><script>alert(1)</script>",
            "\"});window.evil=1;({\"",
            "bell\u0007 nul\u0000 esc\u001B del\u007F ls\u2028 ps\u2029",
            "emoji 🦊 and é",
            "x".repeat(500),
        )
        for (text in hostile) {
            val script = CardGate.progressScript("say", text)!!
            assertTrue("starts with the call", script.startsWith(head))
            assertTrue("ends with the call", script.endsWith("})"))
            assertFalse("one line", script.contains("\n") || script.contains("\r") || script.contains("\u2028") || script.contains("\u2029"))
            assertFalse("no closing tag, whatever the text", script.lowercase().contains("</script"))
            assertTrue("no raw control character", script.all { it.code >= 0x20 && it.code != 0x7F })
            // the part inside the call reads back as exactly an object with two keys, and the text is the line it was cut to
            @Suppress("UNCHECKED_CAST")
            val obj = Json.parse(script.substring(head.length, script.length - 1)) as Map<String, Any?>
            assertEquals(setOf("stage", "text"), obj.keys)
            assertEquals("say", obj["stage"])
            assertEquals(CardGate.line(text), obj["text"])
        }
        val quoted = CardGate.progressScript("say", "a \"b\" \\ c\nd </script>")!!
        @Suppress("UNCHECKED_CAST")
        val obj = Json.parse(quoted.substring(head.length, quoted.length - 1)) as Map<String, Any?>
        assertEquals("a \"b\" \\ cd </script>", obj["text"])
    }

    @Test
    fun whyASessionEndedIsSaidInThePagesWords() {
        // build/wallet/21a-flashcard.js tells these apart by their words (cardSession: /not available/, /timed out/)
        assertTrue(CardLinkError.UNAVAILABLE.words.contains("not available"))
        assertTrue(CardLinkError.TIMED_OUT.words.contains("timed out"))
        assertFalse(CardLinkError.CANCELLED.words.contains("timed out"))
        assertFalse(CardLinkError.CANCELLED.words.contains("not available"))
        // iOS's words, byte for byte (CardLink.swift `CardLinkError.words`)
        assertEquals("NFC is not available on this phone", CardLinkError.UNAVAILABLE.words)
        assertEquals("the session was cancelled", CardLinkError.CANCELLED.words)
        assertEquals("the session timed out", CardLinkError.TIMED_OUT.words)
        assertEquals("the tag was lost", CardLinkError.LOST.words)
    }

    /** The page this app ships chooses the same applet and sends exactly these instructions (`CARD_AID`, `CARD_INS`). */
    @Test
    fun theAppletAndTheInstructionsAreThePagesOwn() {
        val page = File(System.getProperty("foxy.core")!!, "web/foxy-wallet.js").readText()
        assertTrue("the page chooses the applet by the same name", page.contains("var CARD_AID = '${CardGate.hex(CardGate.APPLET)}';"))
        assertEquals("f0464f58594341524401", CardGate.hex(CardGate.APPLET))
        val table = Regex("""var CARD_INS = \{(.*?)\};""", RegexOption.DOT_MATCHES_ALL).find(page)!!.groupValues[1]
        val pageIns = Regex("""\b[a-zA-Z]+: '([0-9a-f]{2})'""").findAll(table).map { it.groupValues[1].toInt(16) }.toSet()
        assertEquals("CARD_INS and the gate, both ways", pageIns, CardGate.INSTRUCTIONS)
        // the page builds every command under the applet's class
        assertTrue(page.contains("return 'b0' + ins + cardByte(p1 || 0)"))
    }
}
