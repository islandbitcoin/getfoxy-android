package io.islandbitcoin.foxy.flashcard

/**
 * The address a Foxy card raises when it is held to a phone, and the one thing the
 * phone says to the page about it (getfoxy/iOS `Foxy/Flashcard/CardAddress.swift`, ENG-668).
 *
 * Every Flashcard of card software 1.19 answers as an NFC Forum Type 4 tag that
 * carries one NDEF URI record, `https://getfoxy.io/card` (prefix code `04` and the 15
 * ASCII bytes of `getfoxy.io/card`, getfoxy/card `docs/FOXY-CARD-SPEC.md` "the NDEF
 * file"), the same on every card. Nothing of it belongs to one card: the address is
 * not an identity, only a way to open Foxy. Android's tag dispatch hands it to
 * MainActivity (the `NDEF_DISCOVERED` filter in the manifest), or a browser hands it
 * over as a link (the App Link filter), and Foxy does one thing with it: tell the page
 * a card was tapped ([PAGE_CALL]), and the page does what MENU > FLASHCARD does if it
 * is a moment for it (`cardTapRule`).
 *
 * Nothing of the address reaches the page, and nothing of it is kept or logged. It is
 * only checked here. Nothing here touches NFC, an Intent or the web view, so the JVM
 * tests hold it to its rules (CardAddressTest, iOS `CardAddressTests`).
 */
object CardAddress {
    const val SCHEME = "https"
    const val HOST = "getfoxy.io"
    const val PATH = "/card"

    /**
     * Whether this is the card's address: `https`, the host `getfoxy.io` and nothing
     * else of the authority (not `www.`, not a subdomain, no user, no port), and the
     * path `/card`, or `/card/`, with nothing after it. A query or a fragment is
     * accepted and means nothing. Anything else is not the card's, and the caller
     * drops it without a word.
     *
     * It is read as it was handed over, byte by byte, and not through a URI
     * parser's idea of a host, a user or a port: the address has to start
     * `https://getfoxy.io/card`, and what may follow is the end, a slash and then the
     * end, a `?` or a `#`, or a `?` or a `#` at once. Any other character after the
     * host is another address, and so is any other spelling of the path (`/c%61rd`).
     * The scheme and the host are compared without regard to ASCII case; the path is not.
     */
    fun isCard(url: String?): Boolean {
        if (url == null) return false
        val text = url.toByteArray(Charsets.UTF_8)
        val head = "$SCHEME://$HOST".toByteArray(Charsets.US_ASCII)
        val route = PATH.toByteArray(Charsets.US_ASCII)
        if (text.size < head.size + route.size) return false
        for (i in head.indices) if (lower(text[i]) != head[i]) return false
        for (i in route.indices) if (text[head.size + i] != route[i]) return false
        var at = head.size + route.size
        if (at < text.size && text[at] == '/'.code.toByte()) at++
        if (at == text.size) return true
        val next = text[at]
        return next == '?'.code.toByte() || next == '#'.code.toByte()
    }

    /** An ASCII letter in lower case; every other byte as it is. */
    private fun lower(b: Byte): Byte = if (b in 'A'.code.toByte()..'Z'.code.toByte()) (b.toInt() or 0x20).toByte() else b

    /**
     * The whole of what the page is told, with nothing put into it. It does nothing,
     * and answers nothing, until the page has put `_cardTapped` on its wallet; when it
     * has, the answer is true, and that is how the phone learns the page has the
     * request and can stop asking (CardTapDelivery).
     */
    const val PAGE_CALL = "window.FoxyWallet && window.FoxyWallet._cardTapped && window.FoxyWallet._cardTapped()"
}
