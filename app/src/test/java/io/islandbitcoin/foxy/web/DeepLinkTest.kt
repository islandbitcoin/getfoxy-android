package io.islandbitcoin.foxy.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The debug deep link's whitelist (FoxyWebView.swift:492-531): a screen from the
 * list, `amt`, `unit` and `rail` each checked, the hash rebuilt from what
 * matched and nothing else. And `foxy://card`, a card tap and no screen.
 */
class DeepLinkTest {
    @Test
    fun theThreeSpellingsNameTheSameScreen() {
        for (link in listOf("foxy://receive", "foxy:///receive", "foxy:receive", "FOXY://Receive")) {
            assertEquals(link, "foxy=receive", DeepLink.hash(link))
        }
        for (screen in DeepLink.SCREENS) assertEquals("foxy=$screen", DeepLink.hash("foxy://$screen"))
    }

    @Test
    fun theParametersAreCheckedAndRebuilt() {
        assertEquals("foxy=receive&amt=50&unit=sat&rail=cashu", DeepLink.hash("foxy://receive?amt=50&unit=sat&rail=cashu"))
        assertEquals("foxy=send&amt=12.5&unit=usd", DeepLink.hash("foxy://send?AMT=12.5&Unit=USD"))
        assertEquals("foxy=receive&rail=on-chain", DeepLink.hash("foxy://receive?rail=on-chain"))
    }

    @Test
    fun anUnknownScreenIsNoLink() {
        for (link in listOf("foxy://settings", "foxy://", "foxy:", "https://receive", "cashu:receive", "foxy://receive.evil", "foxy://%72eceive", "")) {
            assertNull(link, DeepLink.hash(link))
        }
    }

    @Test
    fun aBadAmountUnitOrRailIsDropped() {
        for (amt in listOf("-1", "1.234", "1e5", "1234567890", "", "50;alert(1)", "50'", "٥٠")) {
            assertEquals(amt, "foxy=receive", DeepLink.hash("foxy://receive?amt=$amt"))
        }
        assertEquals("foxy=receive", DeepLink.hash("foxy://receive?unit=btc&rail=liquid"))
        assertEquals("foxy=receive", DeepLink.hash("foxy://receive?unit=sat%27&rail=cashu%22"))
    }

    @Test
    fun extraParametersAreIgnoredAndNamed() {
        val ignored = ArrayList<String>()
        assertEquals("foxy=receive&amt=5", DeepLink.hash("foxy://receive?amt=5&to=evil&memo=%3Cscript%3E#frag") { ignored.add(it) })
        assertEquals(listOf("to", "memo"), ignored)
    }

    @Test
    fun aCardIsATapNotAScreen() {
        // FoxyWebView.swift:497-508: foxy://card names no screen and sets no hash; it is a card tap, in every spelling
        for (link in listOf("foxy://card", "foxy:///card", "foxy:card", "FOXY://Card", "foxy://card/", "foxy://card#x")) {
            assertSame(link, DeepLink.Link.CardTap, DeepLink.read(link))
            assertNull(link, DeepLink.hash(link))
        }
        assertFalse(DeepLink.CARD in DeepLink.SCREENS)
    }

    @Test
    fun aCardTapReadsNoParameters() {
        // it takes no parameters, and nothing of them goes anywhere: not into a hash, not to `ignored`
        val ignored = ArrayList<String>()
        for (link in listOf("foxy://card?amt=50&unit=sat", "foxy://card?to=evil&memo=%3Cscript%3E", "foxy://card?foxy=receive")) {
            assertSame(link, DeepLink.Link.CardTap, DeepLink.read(link) { ignored.add(it) })
            assertNull(link, DeepLink.hash(link) { ignored.add(it) })
        }
        assertEquals(emptyList<String>(), ignored)
    }

    @Test
    fun nearCardsAreNoLink() {
        for (link in listOf("foxy://cards", "foxy://card.evil", "foxy://%63ard", "https://getfoxy.io/card", "card:foxy", "foxy://flashcard")) {
            assertNull(link, DeepLink.read(link))
        }
    }

    @Test
    fun aScreenReadsAsItsHash() {
        assertEquals(DeepLink.Link.Screen("foxy=receive&amt=50"), DeepLink.read("foxy://receive?amt=50"))
        for (screen in DeepLink.SCREENS) assertEquals(DeepLink.Link.Screen("foxy=$screen"), DeepLink.read("foxy://$screen"))
    }

    @Test
    fun theScriptOnlyEverSetsTheHash() {
        assertEquals("location.hash = 'foxy=receive&amt=50'", DeepLink.script("foxy=receive&amt=50"))
    }
}
