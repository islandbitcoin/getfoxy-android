package io.islandbitcoin.foxy.nostr

import io.islandbitcoin.foxy.bridge.Json
import io.islandbitcoin.foxy.network.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Publishing a payment (NostrDelivery.swift `send` and `publish`) against
 * stand-in relays: the wrap goes to each relay the request names on a circuit
 * of its own, a relay's OK is read, a refusal or a silence is reported, every
 * relay finishes once whatever races, and a relay Route will not open is
 * refused without anything sent.
 */
@Suppress("UNCHECKED_CAST")
class NostrDeliveryTest {
    private val relays = FakeRelays()
    private val delivery = NostrDelivery(relays.connect, relays.main, relays.after)
    private val receiverSecret = NostrCrypto.newKey()
    private val receiver = NostrCrypto.publicKey(receiverSecret)
    private val payment = "{\"id\":\"74134073\",\"mint\":\"https://mint.test\",\"unit\":\"sat\",\"proofs\":[]}"

    private fun profile(vararg urls: String) = Bech32.nprofile(receiver, urls.toList())!!

    private var result: NostrDelivery.Result? = null

    private fun send(target: String) = delivery.send(payment, target) {
        assertNull("answered once", result)
        result = it
    }

    /** The wrap a relay was sent, opened as the receiver opens it. */
    private fun opened(socket: FakeRelays.Relay): NostrEvent.Event {
        assertEquals(1, socket.sent.size)
        val parts = Json.parse(socket.sent[0]) as List<Any?>
        assertEquals("EVENT", parts[0])
        val wrap = NostrEvent.fromJson(parts[1])!!
        return NostrEvent.unwrap(wrap, receiverSecret) ?: error("the wrap did not open")
    }

    private fun wrapId(socket: FakeRelays.Relay): String = ((Json.parse(socket.sent[0]) as List<Any?>)[1] as Map<String, Any?>)["id"] as String

    @Test
    fun theWrapGoesToEveryRelayEachOnACircuitOfItsOwn() {
        send(profile("wss://one.example", "wss://two.example"))
        assertEquals(listOf("wss://one.example", "wss://two.example"), relays.sockets.map { it.url })
        val circuits = relays.sockets.map { it.circuit }
        assertTrue(circuits.all { Regex("^[0-9a-f]{32}$").matches(it) })
        assertEquals("one circuit per relay", 2, circuits.toSet().size)
        // the same wrap to both, and it holds the payment for the receiver
        assertEquals(relays.sockets[0].sent, relays.sockets[1].sent)
        assertEquals(payment, opened(relays.sockets[0]).content)
        assertNull("nothing answered yet", result)
        assertEquals(listOf(NostrDelivery.PATIENCE_MS, NostrDelivery.PATIENCE_MS), relays.timers.map { it.first })
    }

    @Test
    fun aRelaysOkAndARefusalAreBothCounted() {
        send(profile("wss://one.example", "wss://two.example", "wss://three.example"))
        val id = wrapId(relays.sockets[0])
        relays.sockets[0].listener.text("[\"NOTICE\",\"hello\"]")
        assertNull("chatter is not an answer", result)
        relays.sockets[0].listener.text("[\"OK\",\"$id\",true,\"\"]")
        relays.sockets[1].listener.text("[\"OK\",\"$id\",false,\"blocked: no\"]")
        relays.sockets[2].listener.text("[\"OK\",\"$id\",false,\"duplicate: already have it\"]")
        val sent = result as NostrDelivery.Result.Sent
        assertEquals(listOf("wss://one.example", "wss://three.example"), sent.outcome.accepted)
        assertEquals(mapOf("wss://two.example" to "blocked: no"), sent.outcome.refused)
        assertTrue("each socket closed once it answered", relays.sockets.all { it.closed })
    }

    @Test
    fun aRelayFinishesOnceWhateverRaces() {
        send(profile("wss://one.example", "wss://two.example"))
        val id = wrapId(relays.sockets[0])
        val one = relays.sockets[0]
        one.listener.text("[\"OK\",\"$id\",true,\"\"]")
        // then it fails, says OK again, and its timer fires: none of it counts twice
        one.listener.ended("The network connection was lost.")
        one.listener.text("[\"OK\",\"$id\",true,\"\"]")
        relays.runTimers()
        val sent = result as NostrDelivery.Result.Sent
        assertEquals(listOf("wss://one.example"), sent.outcome.accepted)
        assertEquals(mapOf("wss://two.example" to "no answer in time"), sent.outcome.refused)
    }

    @Test
    fun aSilentRelayTimesOutAndAFailedOneSaysWhy() {
        send(profile("wss://one.example", "wss://two.example"))
        relays.sockets[1].listener.ended("the relay answered 503")
        assertNull(result)
        relays.runTimers()
        val sent = result as NostrDelivery.Result.Sent
        assertEquals(emptyList<String>(), sent.outcome.accepted)
        assertEquals(mapOf("wss://two.example" to "the relay answered 503", "wss://one.example" to "no answer in time"), sent.outcome.refused)
    }

    @Test
    fun noRelayOpensWithoutTorAndNothingIsSent() {
        relays.refuse = true
        send(profile("wss://one.example"))
        val sent = result as NostrDelivery.Result.Sent
        assertEquals(mapOf("wss://one.example" to Route.REFUSAL), sent.outcome.refused)
        assertTrue(relays.timers.isEmpty())
    }

    @Test
    fun aRequestNamingNoRelayGoesToTheFallbacks() {
        send(profile())
        assertEquals(NostrDelivery.FALLBACK_RELAYS, relays.sockets.map { it.url })
    }

    @Test
    fun anAddressThatDoesNotReadIsAFailureWithNothingSent() {
        send("nprofile1qqqqqq")
        assertEquals(NostrCrypto.Failure.Kind.BAD_PEER, (result as NostrDelivery.Result.Failed).failure.kind)
        result = null
        // a profile whose relays are none Foxy will use
        send(profile("ws://plain.example", "https://web.example"))
        assertEquals(NostrCrypto.Failure.Kind.BAD_PEER, (result as NostrDelivery.Result.Failed).failure.kind)
        assertTrue(relays.sockets.isEmpty())
    }

    @Test
    fun aSocketThatWillNotTakeTheEventIsRefusedAtOnce() {
        relays.refuse = false
        val d = NostrDelivery({ url, circuit, listener -> relays.connect(url, circuit, listener)?.also { (it as FakeRelays.Relay).takes = false } },
            relays.main, relays.after)
        var got: NostrDelivery.Result? = null
        d.send(payment, profile("wss://one.example")) { got = it }
        assertEquals(mapOf("wss://one.example" to "the relay connection closed"), (got as NostrDelivery.Result.Sent).outcome.refused)
    }

    @Test
    fun theWrapsTimesAreBackdatedButNeverInTheFuture() {
        val before = NostrEvent.now()
        send(profile("wss://one.example"))
        val wrap = NostrEvent.fromJson((Json.parse(relays.sockets[0].sent[0]) as List<Any?>)[1])
        assertNotNull(wrap)
        assertTrue(wrap!!.createdAt <= NostrEvent.now())
        assertTrue(wrap.createdAt >= before - NostrEvent.BACKDATING_S)
        assertTrue("the rumor keeps the real time", opened(relays.sockets[0]).createdAt >= before)
    }
}
