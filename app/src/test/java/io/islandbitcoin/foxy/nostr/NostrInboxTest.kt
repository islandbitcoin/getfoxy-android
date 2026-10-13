package io.islandbitcoin.foxy.nostr

import io.islandbitcoin.foxy.bridge.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The receiving half of the Nostr transport (FoxyTests/NostrInboxTests.swift at
 * 8df0bb1, case for case, then the inbox's sockets): the `nprofile` a request
 * names, and the unwrapping of a payment sent to it.
 *
 * Both halves are written here, so a mistake shared by both would pass
 * unnoticed; the defence is external vectors, BIP-173's own strings and NIP-19's
 * worked `nprofile` example, neither of which Foxy produced.
 */
@Suppress("UNCHECKED_CAST")
class NostrInboxTest {
    private val relays = FakeRelays()
    private val payments = mutableListOf<String>()

    private fun inbox(now: Long = 1_700_000_000) = NostrInbox({ payments.add(it) }, relays.connect, relays.main) { now }

    // MARK: bech32, written as well as read

    /** BIP-173's valid strings that carry data: if the encoder disagrees with these it is not bech32. */
    @Test
    fun encodesBip173Vectors() {
        for (text in listOf(
            "abcdef1qpzry9x8gf2tvdw0s3jn54khce6mua7lmqqqxw",
            "11qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqc8247j",
            "split1checkupstagehandshakeupstreamerranterredcaperred2y9e3w",
        )) {
            val d = Bech32.decode(text) ?: error("could not read the vector $text")
            assertEquals("re-encoding $text did not give it back", text, Bech32.encode(d.prefix, d.data))
        }
    }

    /** NIP-19's own worked example: the key, the relays and the string are the spec's. */
    @Test
    fun nip19NprofileExample() {
        val pubkey = NostrEvent.bytes("3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d")
        val expected = "nprofile1qqsrhuxx8l9ex335q7he0f09aej04zpazpl0ne2cgukyaw" +
            "d24mayt8gpp4mhxue69uhhytnc9e3k7mgpz4mhxue69uhkg6nzv9ejuumpv34kytnrdaksjlyr9p"
        assertEquals(expected, Bech32.nprofile(pubkey, listOf("wss://r.x.com", "wss://djbas.sadkb.com")))
    }

    @Test
    fun nprofileRoundTrip() {
        val pubkey = NostrCrypto.publicKey(NostrCrypto.newKey())
        val text = Bech32.nprofile(pubkey, NostrInbox.RELAYS)!!
        assertTrue(text.startsWith("nprofile1"))
        val read = Bech32.nprofile(text)!!
        assertArrayEquals(pubkey, read.pubkey)
        assertEquals(NostrInbox.RELAYS, read.relays)
    }

    @Test
    fun nprofileRefusesAKeyOfTheWrongLength() {
        assertNull(Bech32.nprofile(byteArrayOf(1, 2, 3), emptyList()))
    }

    // MARK: a payment, wrapped and unwrapped

    @Test
    fun unwrapsAPaymentSentToThisKey() {
        val secret = NostrCrypto.newKey()
        val payment = """{"id":"74134073","mint":"https://mint.test","unit":"sat","proofs":[]}"""
        val rumor = NostrEvent.unwrap(NostrEvent.giftWrapped(payment, NostrCrypto.publicKey(secret)), secret)!!
        assertEquals(payment, rumor.content)
        assertEquals(14, rumor.kind)
    }

    @Test
    fun aPaymentForSomebodyElseDoesNotOpen() {
        val theirs = NostrCrypto.publicKey(NostrCrypto.newKey())
        assertNull(NostrEvent.unwrap(NostrEvent.giftWrapped("{}", theirs), NostrCrypto.newKey()))
    }

    /** A wrap whose signature does not hold must not open, or a relay could put words in a payer's mouth. */
    @Test
    fun aRewrittenWrapIsRefused() {
        val secret = NostrCrypto.newKey()
        val wrap = NostrEvent.giftWrapped("{}", NostrCrypto.publicKey(secret))
        assertNotNull("the untouched wrap should open", NostrEvent.unwrap(wrap, secret))
        assertNull("a bad signature opened", NostrEvent.unwrap(wrap.copy(sig = "0".repeat(128)), secret))
        assertNull("a wrong id opened", NostrEvent.unwrap(wrap.copy(id = "0".repeat(64)), secret))
        assertNull("a wrap of the wrong kind opened", NostrEvent.unwrap(wrap.copy(kind = 1), secret))
        assertNull("a changed tag opened", NostrEvent.unwrap(wrap.copy(tags = listOf(listOf("p", "00".repeat(32)))), secret))
    }

    /** The seal may only carry its own author's rumor. */
    @Test
    fun aSealCannotCarryAnotherAuthorsRumor() {
        val secret = NostrCrypto.newKey()
        val pubkey = NostrCrypto.publicKey(secret)
        val strangerPub = NostrCrypto.publicKey(NostrCrypto.newKey())
        val rumor = NostrEvent.withId(NostrEvent.Event("", NostrEvent.hex(strangerPub), 1, 14,
            listOf(listOf("p", NostrEvent.hex(pubkey))), "{}", ""))

        val sealKey = NostrCrypto.newKey()
        val sealPub = NostrCrypto.publicKey(sealKey)
        val seal = NostrEvent.signed(NostrEvent.Event("", NostrEvent.hex(sealPub), 1, 13, emptyList(),
            NostrCrypto.encrypt(NostrEvent.json(rumor).toByteArray(), NostrCrypto.conversationKey(sealKey, pubkey)), ""), sealKey)

        val wrapKey = NostrCrypto.newKey()
        val wrap = NostrEvent.signed(NostrEvent.Event("", NostrEvent.hex(NostrCrypto.publicKey(wrapKey)), 1, 1059,
            listOf(listOf("p", NostrEvent.hex(pubkey))),
            NostrCrypto.encrypt(NostrEvent.json(seal).toByteArray(), NostrCrypto.conversationKey(wrapKey, pubkey)), ""), wrapKey)

        assertNull("a seal carrying somebody else's rumor was accepted", NostrEvent.unwrap(wrap, secret))
    }

    /** A rumor whose id does not match its content is refused, though its seal and wrap check out. */
    @Test
    fun aRumorWithTheWrongIdIsRefused() {
        val secret = NostrCrypto.newKey()
        val pubkey = NostrCrypto.publicKey(secret)
        val sealKey = NostrCrypto.newKey()
        val sealPub = NostrEvent.hex(NostrCrypto.publicKey(sealKey))
        val rumor = NostrEvent.withId(NostrEvent.Event("", sealPub, 1, 14, emptyList(), "{\"a\":1}", "")).copy(content = "{\"a\":2}")
        val seal = NostrEvent.signed(NostrEvent.Event("", sealPub, 1, 13, emptyList(),
            NostrCrypto.encrypt(NostrEvent.json(rumor).toByteArray(), NostrCrypto.conversationKey(sealKey, pubkey)), ""), sealKey)
        val wrapKey = NostrCrypto.newKey()
        val wrap = NostrEvent.signed(NostrEvent.Event("", NostrEvent.hex(NostrCrypto.publicKey(wrapKey)), 1, 1059,
            listOf(listOf("p", NostrEvent.hex(pubkey))),
            NostrCrypto.encrypt(NostrEvent.json(seal).toByteArray(), NostrCrypto.conversationKey(wrapKey, pubkey)), ""), wrapKey)
        assertNull(NostrEvent.unwrap(wrap, secret))
    }

    /**
     * The subscription covers NIP-59's backdating: a wrap's created_at is random
     * up to two days back, and a `since` from when the inbox opened filtered out
     * the first live payment from cashu.me on iOS.
     */
    @Test
    fun theSubscriptionCoversTheWholeBackdatingWindow() {
        val source = File("src/main/java/io/islandbitcoin/foxy/nostr/NostrInbox.kt").readText()
        assertTrue("the subscription no longer measures `since` from the backdating window", source.contains("- backdating - "))
        val now = 1_700_000_000L
        var oldest = now
        repeat(4000) { oldest = minOf(oldest, NostrEvent.backdated(now)) }
        val i = inbox(now)
        val ask = Json.parse(i.ask()) as List<Any?>
        assertEquals("REQ", ask[0])
        assertEquals(i.subscription, ask[1])
        val filter = ask[2] as Map<String, Any?>
        assertEquals(listOf(1059.0), filter["kinds"])
        assertEquals(listOf(NostrEvent.hex(i.pubkey)), filter["#p"])
        val since = (filter["since"] as Double).toLong()
        assertEquals(now - 172_800 - 3600, since)
        assertTrue("a wrap backdated to ${now - oldest}s ago falls outside `since`", since <= oldest)
        assertEquals(64.0, filter["limit"])
        i.close()
    }

    // MARK: the inbox itself

    /** Two inboxes never share a key: it is made for one request and dies with it. */
    @Test
    fun everyInboxHasItsOwnKey() {
        val a = inbox()
        val b = inbox()
        assertNotEquals(a.nprofile, b.nprofile)
        assertNotEquals(a.subscription, b.subscription)
        assertFalse("the subscription name is not the key", NostrEvent.hex(a.pubkey).contains(a.subscription))
        a.close()
        b.close()
    }

    /** A closed inbox names nothing, so a request cannot go out naming a key nobody listens on. */
    @Test
    fun aClosedInboxNamesNothing() {
        val i = inbox()
        assertNotNull(i.nprofile)
        i.close()
        assertNull(i.nprofile)
        assertFalse(i.isOpen)
    }

    @Test
    fun theNprofileNamesTheKeyAndTheFourRelaysItListensOn() {
        val i = inbox()
        val read = Bech32.nprofile(i.nprofile!!)!!
        assertArrayEquals(i.pubkey, read.pubkey)
        assertEquals(NostrDelivery.FALLBACK_RELAYS.take(4), read.relays)
        i.close()
    }

    /** Only EVENT lines for this subscription are read, and each wrap once: every relay sends the same one. */
    @Test
    fun relayChatterIsIgnoredAndWrapsAreDeduplicated() {
        val i = inbox()
        val event = NostrEvent.json(NostrEvent.giftWrapped("""{"id":"abc"}""", i.pubkey))
        i.took("""["EOSE","${i.subscription}"]""")
        i.took("""["NOTICE","hello"]""")
        i.took("""["EVENT","not-this-subscription",$event]""")
        i.took("not json")
        assertEquals("chatter was taken for a payment", 0, payments.size)
        i.took("""["EVENT","${i.subscription}",$event]""")
        i.took("""["EVENT","${i.subscription}",$event]""")
        assertEquals("the same payment was announced twice", listOf("""{"id":"abc"}"""), payments)
        i.close()
    }

    /** A wrap that merely claims the genuine wrap's id must not make the genuine one drop as a copy. */
    @Test
    fun aWrapThatOnlyClaimsTheGenuineIdDoesNotShadowIt() {
        val i = inbox()
        val payment = """{"id":"8c0e1f22","mint":"https://mint.test","unit":"sat","proofs":[]}"""
        val wrap = NostrEvent.giftWrapped(payment, i.pubkey)
        val forged = wrap.copy(content = "not what the id says")
        fun line(e: NostrEvent.Event) = "[\"EVENT\",\"${i.subscription}\"," + NostrEvent.json(e) + "]"
        i.took(line(forged))
        assertEquals("a wrap that does not check out pays nothing", emptyList<String>(), payments)
        i.took(line(wrap))
        assertEquals("and the genuine wrap, from the next relay, is taken", listOf(payment), payments)
        i.took(line(wrap))
        assertEquals("the same wrap from a third relay is one payment, not two", 1, payments.size)
        i.close()
    }

    @Test
    fun aLineLargerThanTheCapIsDroppedUnread() {
        val i = inbox()
        val line = "[\"EVENT\",\"${i.subscription}\"," + NostrEvent.json(NostrEvent.giftWrapped("{\"id\":\"p\"}", i.pubkey)) + "]"
        // the same genuine wrap, its line padded past the cap with whitespace JSON allows
        i.took(line + " ".repeat(NostrInbox.LARGEST_PAYMENT - line.length + 1))
        assertEquals("over the cap: not read", 0, payments.size)
        i.took(line + " ".repeat(NostrInbox.LARGEST_PAYMENT - line.length))
        assertEquals("at the cap: read", listOf("{\"id\":\"p\"}"), payments)
        i.close()
    }

    @Test
    fun aClosedInboxTakesNothing() {
        val i = inbox()
        val line = "[\"EVENT\",\"${i.subscription}\"," + NostrEvent.json(NostrEvent.giftWrapped("{}", i.pubkey)) + "]"
        i.close()
        i.took(line)
        assertEquals(0, payments.size)
    }

    // MARK: the inbox's sockets

    @Test
    fun openAsksFourRelaysEachOnACircuitOfItsOwnAndIsOpenOnceOneListens() {
        val i = inbox()
        var opened: Boolean? = null
        i.open { opened = it }
        assertEquals(NostrDelivery.FALLBACK_RELAYS.take(4), relays.sockets.map { it.url })
        assertEquals(4, relays.sockets.map { it.circuit }.toSet().size)
        assertTrue(relays.sockets.all { it.sent == listOf(i.ask()) })
        assertNull("not until a relay is up", opened)
        assertTrue("waiting on its relays still counts as open, as iOS's !sockets.isEmpty", i.isOpen)
        relays.sockets[2].listener.ended("the relay answered 503")
        assertNull(opened)
        assertTrue(i.isOpen)
        relays.sockets[1].listener.opened()
        assertEquals(true, opened)
        assertTrue(i.isOpen)
        // a payment on any of them
        val line = "[\"EVENT\",\"${i.subscription}\"," + NostrEvent.json(NostrEvent.giftWrapped("{\"id\":\"p\"}", i.pubkey)) + "]"
        relays.sockets[1].listener.text(line)
        assertEquals(listOf("{\"id\":\"p\"}"), payments)
        // the one listening relay ends: no longer open, so the bridge makes a fresh key rather than name a dead one
        relays.sockets[1].listener.ended("the relay closed the connection")
        assertFalse(i.isOpen)
        i.close()
        assertTrue("every socket closed with the key", relays.sockets.all { it.closed })
    }

    @Test
    fun openFailsWhenNoRelayWillListen() {
        val i = inbox()
        var opened: Boolean? = null
        i.open { opened = it }
        relays.sockets.forEach { it.listener.ended("Foxy is not connected to Tor.") }
        assertEquals(false, opened)
        assertFalse(i.isOpen)
        i.close()
    }

    @Test
    fun openWithoutTorFailsWithNothingSent() {
        relays.refuse = true
        val i = inbox()
        var opened: Boolean? = null
        i.open { opened = it }
        assertEquals(false, opened)
        assertTrue(relays.sockets.isEmpty())
        assertFalse("no socket at all is not open, as on iOS", i.isOpen)
        i.close()
    }

    @Test
    fun aClosedInboxOpensNothingAndHearsNothingMore() {
        val i = inbox()
        var opened: Boolean? = null
        i.open { opened = it }
        assertTrue(i.isOpen)
        i.close()
        assertFalse("a closed key is not open, even with its open unanswered", i.isOpen)
        relays.sockets[0].listener.opened()
        assertNull("closed before a relay answered: no answer at all", opened)
        val again = mutableListOf<Boolean>()
        val count = relays.sockets.size
        i.open { again.add(it) }
        assertEquals(listOf(false), again)
        assertEquals("nothing new connects once closed", count, relays.sockets.size)
    }
}
