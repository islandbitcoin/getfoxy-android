package io.islandbitcoin.foxy.flashcard

import io.islandbitcoin.foxy.bridge.Json
import java.text.BreakIterator

/**
 * What the page may say to a card, decided before a byte is sent: a port of
 * Foxy/Flashcard/CardGate.swift (getfoxy/iOS 8df0bb1).
 *
 * The page builds the commands (build/wallet/08a-flashcard.js) and the phone
 * carries them. A phone that carried anything it was handed would let a page
 * that had been got at talk to whatever was held to it: a bank card, a
 * passport, a door key. So the phone reads each command first, and carries two
 * kinds only: the one that chooses this app's own applet, by its full name,
 * and the applet's own instructions, and those not until the applet has
 * answered that it was chosen. Android has no list of applet names in the
 * manifest that narrows which cards the app is handed (iOS's Info.plist does):
 * this gate is the only one.
 *
 * Nothing here touches NFC, so the JVM tests hold it to its rules (CardGateTest).
 */
object CardGate {
    /** The applet on a Foxy card (applet/build.xml in https://github.com/getfoxy/card): its package's nine bytes and 01. */
    val APPLET: ByteArray = byteArrayOf(0xF0.toByte(), 0x46, 0x4F, 0x58, 0x59, 0x43, 0x41, 0x52, 0x44, 0x01)

    /** The applet's class byte. */
    const val APPLET_CLASS: Int = 0xB0

    /**
     * The instructions the page uses, and no others the applet has: thirty-one,
     * exactly CardGate.swift's at 8df0bb1.
     *
     * Three the applet has stay out, because the page does not send them, so the
     * phone will not carry them for a page that had been got at: the count of
     * proofs (12); the limit set with the PIN (33), the form for a card that has
     * no owner, where the page sends the owner's form (34); and locking a card
     * for good (50).
     *
     * SET_TAG (52, card software 1.19) is out too, as it is upstream: the page at
     * the pinned commit does not send it, and FOXY-CARD-SPEC.md section 7's prose
     * says the phone's gate should carry it. That is a drift between iOS and the
     * card's spec, for getfoxy/iOS to settle; Android follows iOS and does not
     * add it on its own (CardGateTest pins it out).
     */
    val INSTRUCTIONS: Set<Int> = setOf(
        0x01, 0x10, 0x11, 0x13, 0x14, 0x15, 0x16, 0x17, // what it is, its key, what it holds, proof it is the card, its record, which pieces it holds
        0x18, // the card's own log (it gives it to its owner, or under its PIN)
        0x19, // the openings of the change the card made for itself (software 1.12), to finish its pieces
        0x1A, // the mint's proof the card keeps with an unspent piece (software 1.20); and 30 with P1 = 1 writes a piece with it
        0x20, // sign for a piece (a card that signs for each)
        0x22, 0x23, 0x24, 0x25, // one signature for a payment: its pieces, its outputs, the signature, and the last one again
        0x26, // one output of a payment's change, which the card makes for itself (software 1.12)
        0x30, 0x31, 0x32, // write a piece, free used places, its record
        0x34, // set the card's daily limit (the owner's proof, no PIN)
        0x35, // tell a card of software 1.14 and before the time, signed (CardTime.kt)
        0x36, // show a card of software 1.15 and on a Bitcoin block header: its clock is the time in the newest it has taken
        0x37, // tell a card of 1.15 and on this phone's own time, a note it keeps for the tap and trusts for nothing
        0x40, 0x41, 0x42, // its PIN: check, set, change
        0x43, // give it its owner key, or change it
        0x44, // a nonce to answer with the owner's proof
        0x45, // the owner's grant to load, in this tap, with no PIN
        0x51, // reset the card to what it was in its packet (software 1.17), under the owner's proof
    )

    /** A short command at its longest: four of header, a length, 255 of data and the length expected back. */
    const val LONGEST: Int = 4 + 1 + 255 + 1

    /** What a command is: the choosing of the applet, or one of its instructions. */
    sealed class Command {
        object Select : Command() {
            override fun toString() = "Select"
        }

        data class Applet(val ins: Int) : Command()
    }

    /** Hex from the page, as bytes: whole bytes, hex and nothing else, and no longer than a command can be. */
    fun bytes(hex: String): ByteArray? {
        if (hex.isEmpty() || hex.length % 2 != 0 || hex.length > LONGEST * 2) return null
        val out = ByteArray(hex.length / 2)
        var high = 0
        for ((i, c) in hex.withIndex()) {
            val v = when (c) {
                in '0'..'9' -> c - '0'
                in 'a'..'f' -> c - 'a' + 10
                in 'A'..'F' -> c - 'A' + 10
                else -> return null
            }
            if (i % 2 == 0) high = v else out[i / 2] = ((high shl 4) or v).toByte()
        }
        return out
    }

    private val DIGITS = "0123456789abcdef".toCharArray()

    fun hex(data: ByteArray): String {
        val out = CharArray(data.size * 2)
        for ((i, b) in data.withIndex()) {
            val v = b.toInt() and 0xFF
            out[i * 2] = DIGITS[v ushr 4]
            out[i * 2 + 1] = DIGITS[v and 15]
        }
        return String(out)
    }

    /**
     * What this command is, or null when it is not one this app sends.
     *
     * It must be a well-formed short command first: a header alone, a header
     * and the length expected back, or a header, a length, that many bytes and
     * perhaps the length expected back. A command whose length byte does not
     * match what follows it is read differently by different cards.
     */
    fun read(apdu: ByteArray): Command? {
        if (apdu.size < 4 || apdu.size > LONGEST) return null
        val after = apdu.size - 4
        var data = ByteArray(0)
        if (after > 1) {
            val lc = apdu[4].toInt() and 0xFF
            if (lc <= 0 || !(after == 1 + lc || after == 2 + lc)) return null
            data = apdu.copyOfRange(5, 5 + lc)
        }
        val cla = apdu[0].toInt() and 0xFF
        val ins = apdu[1].toInt() and 0xFF
        if (cla == 0x00 && ins == 0xA4) {
            if ((apdu[2].toInt() and 0xFF) != 0x04 || (apdu[3].toInt() and 0xFF) != 0x00 || !data.contentEquals(APPLET)) return null
            return Command.Select
        }
        if (cla != APPLET_CLASS || ins !in INSTRUCTIONS) return null
        return Command.Applet(ins)
    }

    /** Whether to carry it now. Until the applet has answered that it was chosen, the only thing said to a card is the choosing. */
    fun allows(apdu: ByteArray, selected: Boolean): Boolean = when (read(apdu)) {
        Command.Select -> true
        is Command.Applet -> selected
        null -> false
    }

    /** Whether an answer says the command was carried out (9000). */
    fun succeeded(answer: ByteArray): Boolean =
        answer.size >= 2 && answer[answer.size - 2] == 0x90.toByte() && answer[answer.size - 1] == 0x00.toByte()

    /** What the phone's sheet says from the moment a card has connected until the page says something else. */
    const val SCANNING = "Reading the card"

    /** The stages the phone reports to the page as a session goes on. */
    val STAGES: Set<String> = setOf("connected", "say", "end", "lost")

    /**
     * The script that tells the page how the session is going (`_card`), or
     * null when the stage is not one of the four. The words are cut down to a
     * line first, then written as a JSON string ([Json.quote]), so nothing in
     * them can end the string or the call: a quote, a backslash, every control
     * character and the angle brackets are escaped.
     */
    fun progressScript(stage: String, text: String): String? {
        if (stage !in STAGES) return null
        return "window.FoxyWallet && window.FoxyWallet._card && window.FoxyWallet._card({\"stage\":\"" + stage + "\",\"text\":" +
            Json.quote(line(text)) + "})"
    }

    /** The most a line on the sheet holds, in characters as a person counts them (Swift's `prefix(120)`). */
    const val LINE_MOST: Int = 120

    /**
     * A line for the phone's own card sheet: one line, nothing that is not
     * print, and short. The page chooses the words; it does not get to put a
     * paragraph, or a control character, on a native sheet. As Swift's
     * CharacterSet.controlCharacters (Cc and Cf) and .newlines are taken out,
     * scalar by scalar, and the rest cut to [LINE_MOST] grapheme clusters.
     */
    fun line(text: String?): String {
        val kept = StringBuilder()
        val s = text ?: ""
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            if (type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()) continue
            if (cp in 0x0A..0x0D || cp == 0x85 || cp == 0x2028 || cp == 0x2029) continue
            kept.appendCodePoint(cp)
        }
        val all = kept.toString()
        val clusters = BreakIterator.getCharacterInstance()
        clusters.setText(all)
        var end = 0
        repeat(LINE_MOST) {
            val next = clusters.next()
            if (next == BreakIterator.DONE) return all
            end = next
        }
        return all.substring(0, end)
    }
}
