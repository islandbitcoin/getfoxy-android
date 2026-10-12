package io.islandbitcoin.foxy.flashcard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The address a card raises when it is held to a phone, and the one message the page
 * gets about it (CardAddress.kt): getfoxy/iOS `FoxyTests/CardAddressTests.swift` at
 * 8df0bb1, case for case. iOS reads a URL's `absoluteString`; Android reads the string
 * an Intent was handed (`getDataString`), so a string iOS's URL parser refuses (a
 * space, a backslash) is checked here as it is, and refused all the same.
 */
class CardAddressTest {
    private fun card(text: String?) = CardAddress.isCard(text)

    /** The address that is on every card, and nothing around it that matters. */
    @Test
    fun theCardsOwnAddressIsRecognised() {
        assertEquals("https://getfoxy.io/card", "${CardAddress.SCHEME}://${CardAddress.HOST}${CardAddress.PATH}")
        assertTrue(card("https://getfoxy.io/card"))
        assertTrue("a trailing slash is the same place", card("https://getfoxy.io/card/"))
    }

    /** The NDEF file's record, decoded as Android decodes a URI record: prefix code 04 is https://. */
    @Test
    fun theTagsOwnRecordIsTheCardsAddress() {
        // getfoxy/card FOXY-CARD-SPEC.md, the NDEF file E104: 00 14, then D1 01 10 55 04 and the 15 bytes of getfoxy.io/card
        val file = "00 14 D1 01 10 55 04 67 65 74 66 6F 78 79 2E 69 6F 2F 63 61 72 64".split(' ').map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(0x14, file[1].toInt())
        assertEquals("a well-known record of type U", 'U'.code, file[5].toInt())
        assertEquals("prefix code 04", 0x04, file[6].toInt())
        val uri = "https://" + String(file.copyOfRange(7, file.size), Charsets.US_ASCII)
        assertEquals(0x10, file.size - 6)
        assertTrue(uri, card(uri))
    }

    /** A query or a fragment is accepted and means nothing: it is neither read nor passed on. */
    @Test
    fun aQueryOrAFragmentIsAcceptedAndIgnored() {
        for (text in listOf(
            "https://getfoxy.io/card?x=1", "https://getfoxy.io/card?", "https://getfoxy.io/card#top",
            "https://getfoxy.io/card#", "https://getfoxy.io/card/?a=1&b=2#c",
            "https://getfoxy.io/card?next=https://evil.example/", "https://getfoxy.io/card#https://evil.example/",
        )) {
            assertTrue(text, card(text))
        }
    }

    /** Scheme and host are written without regard to case, as every URL does; the path is not. */
    @Test
    fun schemeAndHostAreNotCaseSensitiveButThePathIs() {
        assertTrue(card("HTTPS://GETFOXY.IO/card"))
        assertTrue(card("Https://GetFoxy.io/card/"))
        assertFalse(card("https://getfoxy.io/Card"))
        assertFalse(card("https://getfoxy.io/CARD"))
    }

    /** Every way of being almost the card's address. */
    @Test
    fun nothingElseIsTheCardsAddress() {
        val others = listOf(
            // another scheme: the web's own, and this app's
            "http://getfoxy.io/card", "foxy://card", "foxy://getfoxy.io/card", "ftp://getfoxy.io/card",
            "file:///card", "javascript:alert(1)", "mailto:card@getfoxy.io", "data:text/html,getfoxy.io/card",
            // another host: a subdomain, the www, one that ends with the name, one that starts with it, a dot after it
            "https://www.getfoxy.io/card", "https://sub.getfoxy.io/card", "https://evilgetfoxy.io/card",
            "https://getfoxy.io.evil.example/card", "https://getfoxy.io./card", "https://getfoxy.com/card",
            "https://evil.example/card", "https://getfoxy%2Eio/card", "https://localhost/card",
            // something before the host: user information, with and without a password, and a name that is only a user
            "https://user@getfoxy.io/card", "https://user:secret@getfoxy.io/card", "https://getfoxy.io@evil.example/card",
            "https://getfoxy.io:443@evil.example/card", "https://:@getfoxy.io/card",
            // a port, the default one too
            "https://getfoxy.io:8443/card", "https://getfoxy.io:443/card", "https://getfoxy.io:/card",
            // another path, a longer one, a shorter one, and the same one spelled another way
            "https://getfoxy.io/", "https://getfoxy.io", "https://getfoxy.io/cards", "https://getfoxy.io/card/extra",
            "https://getfoxy.io/card//", "https://getfoxy.io//card", "https://getfoxy.io/card/.", "https://getfoxy.io/card/..",
            "https://getfoxy.io/card;x=1", "https://getfoxy.io/c%61rd", "https://getfoxy.io/card%2F", "https://getfoxy.io/card%00",
            "https://getfoxy.io/other/card", "https://getfoxy.io/card.html", "https://getfoxy.io/.well-known/card",
            // the address named by something else's query or fragment
            "https://evil.example/?u=https://getfoxy.io/card", "https://evil.example/card?https://getfoxy.io/card",
            "https://evil.example/#https://getfoxy.io/card",
            // not an address at all
            "https:", "https://", "https:///card", "card", "//getfoxy.io/card", "/card",
        )
        for (text in others) assertFalse("$text is not the card's address", card(text))
    }

    @Test
    fun noAddressAtAllIsNotTheCardsAddress() {
        assertFalse(card(null))
        assertFalse(card(""))
        // the address with one part wrong (iOS builds these from URLComponents)
        assertFalse("a port", card("https://getfoxy.io:443/card"))
        assertFalse("a user", card("https://u@getfoxy.io/card"))
        assertFalse("another host", card("https://www.getfoxy.io/card"))
    }

    /** Read byte by byte: the host ends where the path begins, so nothing can be hidden between them. */
    @Test
    fun nothingCanBeHiddenBetweenTheHostAndThePath() {
        for (text in listOf(
            "https://getfoxy.io:/card", "https://getfoxy.io@/card", "https://getfoxy.io\\@evil.example/card",
            "https://getfoxy.io%2f@evil.example/card", "https://getfoxy.io?@evil.example/card",
            "https://getfoxy.io#@evil.example/card", "https://getfoxy.io /card", "https://getfoxy.io /card",
            // and Android-only: what follows the path may not be white space, a NUL or a backslash either
            "https://getfoxy.io/card ", "https://getfoxy.io/card\u0000", "https://getfoxy.io/card\\", " https://getfoxy.io/card",
        )) {
            assertFalse("$text is not the card's address", card(text))
        }
        // and a letter that only looks like one of ours
        assertFalse("a Cyrillic o", card("https://getfoxy.iо/card"))
        assertFalse("a Cyrillic e", card("https://gеtfoxy.io/card"))
        assertFalse("a fullwidth slash", card("https://getfoxy.io／card"))
        // only ASCII is lowered: a dotted capital I is not an i, and a long s not an s, whatever Unicode says
        assertFalse("a dotted capital I", card("https://getfoxy.\u0130o/card"))
        assertFalse("a long s", card("http\u017F://getfoxy.io/card"))
    }

    /** What the page is told is one fixed call: nothing of the address is in it, and nothing can be put into it. */
    @Test
    fun thePageIsToldOneFixedMessage() {
        assertEquals(
            "window.FoxyWallet && window.FoxyWallet._cardTapped && window.FoxyWallet._cardTapped()",
            CardAddress.PAGE_CALL,
        )
        assertFalse("no part of the address", "getfoxy" in CardAddress.PAGE_CALL)
        assertFalse("no part of the address", "http" in CardAddress.PAGE_CALL)
        assertFalse("no query", "card?" in CardAddress.PAGE_CALL)
        assertTrue("it takes no argument", CardAddress.PAGE_CALL.endsWith("._cardTapped()"))
        assertFalse("\n" in CardAddress.PAGE_CALL)
    }

    /** A different address, or none, leaves the delivery as it was and says nothing. */
    @Test
    fun anotherAddressGoesNowhere() {
        val lines = mutableListOf<String>()
        var asked = 0
        val delivery = CardTapDelivery(now = { 0L }, wait = { _, _ -> }, say = { lines.add(it) })
        delivery.tell = { _ -> asked++ }
        delivery.pageLoaded()
        delivery.coverChanged(up = false)
        for (text in listOf("https://www.getfoxy.io/card", "foxy://card", "https://getfoxy.io/cards", "https://evil.example/card")) {
            delivery.received(text)
        }
        delivery.received(null)
        assertEquals(0, asked)
        assertNull(delivery.since)
        assertTrue("dropped without a word: $lines", lines.isEmpty())
        // and the card's own is not
        delivery.received("https://getfoxy.io/card?x=1#y")
        assertEquals(1, asked)
        assertEquals("a card's address arrived", lines.first())
        assertFalse("the address is not logged", "getfoxy" in lines.joinToString())
    }
}
