package io.islandbitcoin.foxy.nostr

import io.islandbitcoin.foxy.network.RelayListener
import io.islandbitcoin.foxy.network.RelaySocket

/**
 * Relays as the Nostr code meets them, without a network: every socket asked
 * for is recorded (its URL, its circuit label and its listener), what is sent
 * down it is kept, and the test plays the relay's side by calling the listener.
 * `refuse` makes [connect] answer null, as Route does without Tor.
 */
class FakeRelays {
    inner class Relay(val url: String, val circuit: String, val listener: RelayListener) : RelaySocket {
        val sent = mutableListOf<String>()
        var closed = false
        var takes = true

        override fun send(text: String): Boolean {
            if (closed || !takes) return false
            sent.add(text)
            return true
        }

        override fun close() {
            closed = true
        }
    }

    val sockets = mutableListOf<Relay>()
    var refuse = false

    val connect: RelayConnect = { url, circuit, listener ->
        if (refuse) null else Relay(url, circuit, listener).also { sockets.add(it) }
    }

    fun at(url: String): Relay = sockets.last { it.url == url }

    /** Runs a posted Runnable at once: the tests are the main thread. */
    val main: (Runnable) -> Unit = { it.run() }

    val timers = mutableListOf<Pair<Long, () -> Unit>>()
    val after: (Long, () -> Unit) -> Unit = { ms, r -> timers.add(ms to r) }

    fun runTimers() {
        val due = timers.toList()
        timers.clear()
        due.forEach { it.second() }
    }
}
