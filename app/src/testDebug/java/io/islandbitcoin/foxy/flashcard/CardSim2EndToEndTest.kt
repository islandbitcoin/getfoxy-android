package io.islandbitcoin.foxy.flashcard

import io.islandbitcoin.foxy.TestSupport
import io.islandbitcoin.foxy.bridge.CardBridge
import io.islandbitcoin.foxy.bridge.Json
import io.islandbitcoin.foxy.keychain.CardOwner
import io.islandbitcoin.foxy.keychain.NUT13
import io.islandbitcoin.foxy.keychain.P256
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * The whole chain against card protocol 2.0 (ENG-682): the bridge, CardGate,
 * the debug build's SimCardLink, and lnflash/cashu-javacard's CashuApplet (the
 * applet Flash and Foxy share, AID F0 "CASHUCARD" 01) in jCardSim behind its
 * CardServer, at tools/cardsim-2.PIN ([CardSimSession]).
 *
 * A 2.0 card answers no SELECT of Foxy 1.x's applet, so nothing after it is
 * carried; chosen by its whole 2.0 name it answers 02 00. Then what 2.0 adds on
 * the wire, through the gate to the applet: SET_OWNER with its anchor header
 * after the key (65 + 80 bytes), REFRESH (53) under the owner's proof, and the
 * owner's thirteen-byte SET_LIMIT (the rollover budget); and a card set up,
 * loaded, read and paid from as the 1.x test does.
 *
 * The owner key is the one this phone gives for the card (`cardOwnerKey`). Its
 * proofs are signed here, under the 2.0 labels (`CashuCard/…`, cashu-javacard
 * spec/APDU.md), because the phone's `cardOwnerSign` signs under Foxy 1.x's
 * (`FoxyCard/…`) until the v0.1.3 re-pin moves it with the page. At that re-pin
 * this test signs through the bridge instead.
 */
class CardSim2EndToEndTest : CardSimSession() {
    private val select = "00a404000af0464f5859434152440100"
    private val select2 = "00a404000bf04341534855434152440100"

    /** Mainnet headers 970,920 and 971,100 (cashu-javacard spec/vectors/headers.json): real work, above the applet's floor (D24). */
    private val anchor = "00e0ff3fc81f28235b97dccf4100141a66441997e8a9d11e4add00000000000000000000e75df58c5e5ba5c0cae98d574748b2fe6738376105cb024bad0cf4eaa0ac76ba6b74cb6af01e021761aa0196"
    private val tip = "00c0982393d11d01ce3623e775eb87f8f29d9e607bc98779db0602000000000000000000939a3be21aa361da579fa2578895413d925c780a5667f7b9fcf7a4c279648496a8eccc6af01e021776150c1e"
    private val tipTime = 1791814824L
    private val tipHash = "0000000000000000000174b1a4526c2718f9891c2c77945ed930a2f18959fe92"

    /** The owner's proof under the 2.0 label over a fresh nonce, signed with the key the phone gave, and the command's data: length, proof, value. */
    private fun owned(owner: ByteArray, label: String, value: String): String {
        val nonce = card(command("44", 0, "", 16), "a nonce for its owner")
        assertEquals(32, nonce.length)
        val signed = "CashuCard/$label".toByteArray(StandardCharsets.US_ASCII) + CardGate.bytes(nonce)!! + (CardGate.bytes(value) ?: ByteArray(0))
        val sig = CardGate.hex(P256.sign(owner, signed))
        return "%02x".format(sig.length / 2) + sig + value
    }

    @Test
    fun a2CardIsChosenByItsOwnNameSetUpRefreshedLimitedLoadedAndPaidFrom() {
        assertEquals("ok" to null, ask("cardBegin", mapOf("text" to "Tap behind the phone.")))
        // ---- Foxy 1.x's name: carried, and not answered by a 2.0 card, so nothing of the applet's is carried after it
        assertTrue("a 2.0 card does not answer FOXYCARD", sw(select) != "9000")
        assertEquals(null to CardBridge.NOT_FOXYS, ask("cardSend", mapOf("apdu" to command("11", 0, "", 4))))
        // ---- the package's name alone is never carried (on a chip it reaches the opener, D25)
        assertEquals(null to CardBridge.NOT_FOXYS, ask("cardSend", mapOf("apdu" to "00a404000af0434153485543415244")))
        // ---- its whole 2.0 name
        assertEquals("the applet answers its version, 2.0", "0200", card(select2, "SELECT CASHUCARD 01"))
        val info = card(command("01", 2, "", 0), "GET_INFO P1 = 2")
        assertEquals("43 bytes", 86, info.length)
        assertEquals("version 2.0", "0200", info.substring(0, 4))
        assertEquals("no PIN", "00", info.substring(14, 16))
        assertEquals("no clock yet", "00000000", info.substring(34, 42))
        val cardKey = card(command("10", 0, "", 0), "GET_PUBKEY")
        assertTrue(cardKey, CardOwnerKeyShape.matches(cardKey))

        // ---- SET_OWNER with its anchor: the key this phone gives for the card, and a block header after it (145 bytes)
        val (ownerText, ownerError) = ask("cardOwnerKey", mapOf("key" to cardKey))
        assertEquals(null, ownerError)
        @Suppress("UNCHECKED_CAST")
        val ownerPub = (Json.parse(ownerText!!) as Map<String, Any?>)["pub"] as String
        val owner = CardOwner.scalar(NUT13.seed(TestSupport.ABANDON_ABOUT), CardGate.bytes(cardKey)!!)!!
        assertEquals("the key signed with here is the phone's", ownerPub, CardGate.hex(P256.publicKey(owner)))
        card(command("43", 0, ownerPub + anchor, null), "SET_OWNER with its anchor")
        val anchored = card(command("01", 2, "", 0), "GET_INFO")
        assertEquals("an owner", "01", anchored.substring(32, 34))
        assertEquals("the clock is the anchor's time", u32(1791718507), anchored.substring(34, 42))

        // ---- REFRESH (53): the owner's proof over a newer header; the card answers its clock
        assertEquals("the clock is the newer header's", u32(tipTime), card(command("53", 0, owned(owner, "refresh", tip), 4), "REFRESH"))

        // ---- the owner's SET_LIMIT with the thirteenth byte: a day's limit of 5000, no payment limit, no allowance, a budget of 3
        card(command("34", 0, owned(owner, "set-limit", u32(5000) + u32(0) + u32(0) + "03"), null), "SET_LIMIT, 13 bytes")
        assertEquals("the day's limit", u32(5000), card(command("01", 2, "", 0), "GET_INFO").substring(24, 32))

        // ---- its record under the owner's proof, then money onto it under the owner's grant
        val m = mint.toByteArray(StandardCharsets.US_ASCII)
        val record = "00" + g + "00".repeat(65) + "%02x".format(m.size) + CardGate.hex(m)
        card(command("32", 0, owned(owner, "set-card", record), null), "SET_CARD under the owner's proof")
        val got = card(command("16", 0, "", 0), "GET_CARD")
        assertEquals("the record set", "01", got.substring(2, 4))
        assertEquals("the day's limit in the record", u32(5000), got.substring(6, 14))
        assertEquals("the last header taken is the tip (as Bitcoin shows a block's hash)", tipHash, got.substring(88, 152))
        assertEquals("no roll since the REFRESH", "00", got.substring(152, 154))
        assertEquals("the rollover budget the thirteenth byte set", "03", got.substring(154, 156))
        card(command("45", 0, owned(owner, "load", ""), null), "ALLOW_LOAD")
        val first = card(command("30", 0, piece(64, 1), 1), "LOAD_PROOF")
        val proof = (0 until 96).joinToString("") { "%02x".format((it * 7 + 3) and 0xff) }
        val second = card(command("30", 1, piece(32, 101) + proof, 2), "LOAD_PROOF with the mint's proof")
        assertEquals("its place, and the proof kept", "01", second.substring(2, 4))
        assertEquals("96 sats on the card", u32(96), card(command("11", 0, "", 4), "GET_BALANCE"))
        assertEquals("the mint's proof, as it was given", "01$proof", card(command("1a", second.substring(0, 2).toInt(16), "", 0, p2 = 0xff), "GET_DLEQ"))

        // ---- a payment of the 64 into one output, inside the day's limit: one signature, the piece burned
        card(command("22", 0, first.substring(0, 2), 4), "SPEND_ALL_BEGIN")
        card(command("23", 0, u32(64) + g, null), "SPEND_ALL_OUTPUTS")
        var signature = ""
        for (i in 0 until 64) {
            signature = card(command("24", 0, "", 64), "SPEND_ALL_SIGN")
            if (signature.length != 4) break // 00 01 is "not yet", asked again
        }
        assertEquals("one 64-byte signature", 128, signature.length)
        assertEquals("the 32 left", u32(32), card(command("11", 0, "", 4), "GET_BALANCE"))
        assertEquals("64 spent today", u32(64), card(command("01", 2, "", 0), "GET_INFO").substring(50, 58))

        // ---- what 1.x had and 2.0 does not: answered 6D00 by the card, as the gate carries one list
        assertEquals("SPEND_PROOF (20), gone in 2.0", "6d00", sw(command("20", 0, "", 64)))
        assertEquals("SET_TIME (35), gone in 2.0", "6d00", sw(command("35", 0, u32(tipTime) + "00", 4)))
        // ---- what the gate keeps from a 2.0 card too: locking for good, SET_TAG, the PIN's limit
        for (refused in listOf("b0500000de", command("52", 1, "00", null), command("33", 0, u32(0), null))) {
            assertEquals(refused, null to CardBridge.NOT_FOXYS, ask("cardSend", mapOf("apdu" to refused)))
        }
        assertEquals("ok" to null, ask("cardEnd", mapOf("text" to "Done.")))

        // ---- a second tap: the applet chosen again, by its 2.0 name, and what it holds lasts
        assertEquals("ok" to null, ask("cardBegin", mapOf("text" to "Tap behind the phone.")))
        assertEquals(null to CardBridge.NOT_FOXYS, ask("cardSend", mapOf("apdu" to command("11", 0, "", 4))))
        assertEquals("0200", card(select2, "SELECT CASHUCARD 01"))
        assertEquals(u32(32), card(command("11", 0, "", 4), "GET_BALANCE"))
        assertEquals("ok" to null, ask("cardEnd", mapOf("text" to "Done.")))
    }
}
