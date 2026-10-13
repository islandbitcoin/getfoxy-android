package io.islandbitcoin.foxy.flashcard

import io.islandbitcoin.foxy.bridge.CardBridge
import io.islandbitcoin.foxy.bridge.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * The whole chain against Foxy's 1.x applet: the bridge as the page posts to it
 * (`cardBegin`, `cardSend`, `cardSay`, `cardAgain`, `cardEnd`, and the owner's
 * key and proofs through `cardOwnerKey` and `cardOwnerSign`), CardGate, the
 * debug build's SimCardLink, and getfoxy/card's own CashuApplet running in
 * jCardSim behind its CardServer (tools/cardsim in that repository, at
 * tools/cardsim.PIN), on a loopback port ([CardSimSession]).
 *
 * The commands are the page's at the pin: a new card set up by its owner's
 * phone (SET_OWNER, SET_CARD under the owner's proof), money put on it under
 * the owner's grant (ALLOW_LOAD, LOAD_PROOF, and LOAD_PROOF with the mint's
 * proof, 1.20), its balance and its pieces read, a payment signed
 * (SPEND_ALL_BEGIN, _OUTPUTS, _SIGN), the card pulled away part way and looked
 * for again, and the sheet closed. No mint: the pieces are made up, and the
 * card checks none of what a mint would.
 *
 * 1.x only, as the page at the pin is: it chooses FOXYCARD, and the owner's
 * proofs are signed natively under `FoxyCard/` (keychain/CardOwner.kt), which a
 * 2.0 card refuses. [CardSim2EndToEndTest] is the 2.0 card. This class goes at
 * the v0.1.3 re-pin, with FOXYCARD (ENG-682).
 */
class CardSimEndToEndTest : CardSimSession() {
    private val select = "00a404000af0464f5859434152440100"

    /** The owner's proof for `label` over a fresh nonce, from the phone (cardOwnerSign), and the command's data: length, proof, value. */
    private fun owned(cardKey: String, label: String, value: String): String {
        val nonce = card(command("44", 0, "", 16), "a nonce for its owner")
        assertEquals(32, nonce.length)
        val (text, error) = ask("cardOwnerSign", mapOf("key" to cardKey, "label" to label, "nonce" to nonce, "value" to value))
        assertEquals(null, error)
        @Suppress("UNCHECKED_CAST")
        val sig = (Json.parse(text!!) as Map<String, Any?>)["sig"] as String
        return "%02x".format(sig.length / 2) + sig + value
    }

    @Test
    fun aNewCardIsSetUpLoadedReadPaidFromLostFoundAgainAndClosed() {
        // ---- the sheet, and a card on it
        assertEquals("ok" to null, ask("cardBegin", mapOf("text" to "Tap behind the phone.")))
        assertEquals(listOf("connected: Reading the card"), pushes())
        // nothing of the applet's before it is chosen
        assertEquals(null to CardBridge.NOT_FOXYS, ask("cardSend", mapOf("apdu" to command("11", 0, "", 4))))
        val version = card(select, "SELECT")
        assertEquals("the applet answers its version, 1.20", "0114", version)

        // ---- what it is: a card out of its packet, with no owner, no PIN and nothing on it
        val info = card(command("01", 2, "", 0), "GET_INFO P1 = 2")
        assertEquals("43 bytes (software 1.19 and on)", 86, info.length)
        assertEquals("no PIN", "00", info.substring(14, 16))
        val cardKey = card(command("10", 0, "", 0), "GET_PUBKEY")
        assertTrue(cardKey, CardOwnerKeyShape.matches(cardKey))
        assertEquals("nothing on it", "00000000", card(command("11", 0, "", 4), "GET_BALANCE"))

        // ---- set up by its owner's phone: the owner key from this phone's seed, then the record under its proof
        val (ownerText, ownerError) = ask("cardOwnerKey", mapOf("key" to cardKey))
        assertEquals(null, ownerError)
        @Suppress("UNCHECKED_CAST")
        val owner = (Json.parse(ownerText!!) as Map<String, Any?>)["pub"] as String
        card(command("43", 0, owner, null), "SET_OWNER")
        val m = mint.toByteArray(StandardCharsets.US_ASCII)
        val record = "00" + g + "00".repeat(65) + "%02x".format(m.size) + CardGate.hex(m)
        card(command("32", 0, owned(cardKey, "set-card", record), null), "SET_CARD under the owner's proof")
        assertEquals("the record set", "01", card(command("01", 2, "", 0), "GET_INFO").substring(22, 24))

        // ---- money onto it, under the owner's grant: one piece plain, one with the mint's proof kept beside it (1.20)
        ask("cardSay", mapOf("text" to "Putting it on the card"))
        card(command("45", 0, owned(cardKey, "load", ""), null), "ALLOW_LOAD")
        val first = card(command("30", 0, piece(64, 1), 1), "LOAD_PROOF")
        val proof = (0 until 96).joinToString("") { "%02x".format((it * 7 + 3) and 0xff) }
        val second = card(command("30", 1, piece(32, 101) + proof, 2), "LOAD_PROOF with the mint's proof")
        assertEquals("its place, and the proof kept", "01", second.substring(2, 4))
        assertEquals("96 sats on the card", u32(96), card(command("11", 0, "", 4), "GET_BALANCE"))
        val dleq = card(command("1a", second.substring(0, 2).toInt(16), "", 0, p2 = 0xff), "GET_DLEQ")
        assertEquals("the mint's proof, as it was given", "01$proof", dleq)
        val pieces = card(command("17", 0, "", 0), "GET_PIECES")
        assertTrue("both pieces are listed", pieces.contains(piece(64, 1)) && pieces.contains(piece(32, 101)))
        assertTrue(pushes().contains("say: Putting it on the card"))

        // ---- a payment of the 64 into one output: one signature, the piece burned
        card(command("22", 0, first.substring(0, 2), 4), "SPEND_ALL_BEGIN")
        card(command("23", 0, u32(64) + g, null), "SPEND_ALL_OUTPUTS")
        var signature = ""
        for (i in 0 until 64) {
            signature = card(command("24", 0, "", 64), "SPEND_ALL_SIGN")
            if (signature.length != 4) break // 00 01 is "not yet", asked again
        }
        assertEquals("one 64-byte signature", 128, signature.length)
        assertEquals("the 32 left", u32(32), card(command("11", 0, "", 4), "GET_BALANCE"))

        // ---- 2.0's REFRESH is carried to a 1.x card too (the gate keeps one list), and the 1.20 applet has no 53 (ENG-682)
        assertEquals("REFRESH, which 1.20 does not have", "6d00", sw(command("53", 0, "00", 4)))

        // ---- what the gate keeps from the card, with the applet chosen: locking for good, SET_TAG (as upstream), the PIN's limit
        for (refused in listOf("b0500000de", command("52", 1, "00", null), command("33", 0, u32(0), null))) {
            assertEquals(refused, null to CardBridge.NOT_FOXYS, ask("cardSend", mapOf("apdu" to refused)))
        }
        assertEquals("ok" to null, ask("cardEnd", mapOf("text" to "Done.")))
        assertEquals("end: Done.", pushes().last())
        assertEquals("nothing is carried once the sheet has closed", null to CardLinkError.LOST.words, ask("cardSend", mapOf("apdu" to select)))

        // ---- a second tap: the card pulled away after two commands, then put back and found again by the same sheet
        assertEquals("ok", ctl("pull 2"))
        assertEquals("ok" to null, ask("cardBegin", mapOf("text" to "Tap behind the phone.")))
        card(select, "SELECT")
        assertEquals(u32(32), card(command("11", 0, "", 4), "GET_BALANCE"))
        assertEquals(null to CardLinkError.LOST.words, ask("cardSend", mapOf("apdu" to command("11", 0, "", 4))))
        assertEquals("lost: the tag was lost", pushes().last())
        val again = post("cardAgain", mapOf("text" to "Hold the card here again"))
        main.settle(1_000)
        assertEquals("the card is not on the reader yet", null, reply(again))
        // the simulator serves one connection at a time: the control is heard between the sheet's looks
        assertEquals("ok", ctl("on"))
        main.pump("the card found again") { reply(again) != null }
        assertEquals("ok" to null, reply(again))
        assertEquals("the applet is chosen again first", null to CardBridge.NOT_FOXYS, ask("cardSend", mapOf("apdu" to command("11", 0, "", 4))))
        card(select, "SELECT")
        assertEquals("what the card holds lasts from tap to tap", u32(32), card(command("11", 0, "", 4), "GET_BALANCE"))
        assertEquals("ok" to null, ask("cardEnd", mapOf("error" to "Card taken away too soon.")))
        assertEquals("end: Card taken away too soon.", pushes().last())
    }
}
