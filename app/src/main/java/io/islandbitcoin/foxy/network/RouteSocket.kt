package io.islandbitcoin.foxy.network

import io.islandbitcoin.foxy.debug.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** What a relay's socket tells its holder. Called from OkHttp's threads, each at most once but [text]. */
interface RelayListener {
    /** The socket is up (the WebSocket upgrade answered 101). */
    fun opened()

    /** One message from the relay: a text frame, or a binary one read as UTF-8 (URLSessionWebSocketTask's `.data`). */
    fun text(message: String)

    /** The socket ended, and why, in words; nothing more comes after. Not called after [RelaySocket.close]. */
    fun ended(why: String)
}

/** One WebSocket to a relay, over Tor: what the Nostr code holds instead of OkHttp's. */
interface RelaySocket {
    /** Queue one text message; false when the socket is closing or gone. */
    fun send(text: String): Boolean

    /** Close it now (1001, going away), and hear nothing more from it. */
    fun close()
}

/**
 * A WebSocket to a Nostr relay, over Tor and nowhere else (Route.swift
 * `startSocket` at 8df0bb1; docs/ROUTE.md §5).
 *
 * The only WebSocket of the app's own. `wss` only, a host that passes
 * [Route.hostProblem], Tor running, and Route's door open: anything else is
 * null with nothing sent. Never the open connection, even with CONTINUE WITHOUT
 * TOR on (S11 "never for onion or Nostr"), as on iOS.
 *
 * OkHttp's WebSocket on the Tor client of [RouteHttp.torClient]: the SOCKS
 * factory with the circuit label as the login, the relay's name handed to Tor
 * (ATYP 3, [TorDns] never looks anything up), TLS end to end with the relay,
 * the generic headers on the upgrade. The relay sees a Tor exit.
 * tools/check-no-network.sh allows a WebSocket in this file and nowhere else.
 */
object RouteSocket {
    /**
     * A socket to `url` on circuit `circuit`, or null when Foxy will not open
     * one (not wss, a host it will not ask for, no Tor, or being put away).
     */
    fun start(url: String, circuit: String, listener: RelayListener): RelaySocket? {
        if (Route.shut()) {
            Log.line("[foxy] route: a relay socket not opened; Foxy is being put away")
            return null
        }
        if (problem(url) != null) return null
        if (!Route.tor.isRunning) return null
        return open(RouteHttp.torClient(circuit), url, listener)
    }

    /** Why `url` is not a relay Foxy will open a socket to, or null when it is one. */
    fun problem(url: String, torRunning: Boolean = Route.tor.isRunning): String? {
        // OkHttp's parser takes http(s) only; a wss URL is read as its https twin, which is what goes on the wire
        if (!url.startsWith("wss://")) return Route.NOT_A_MINT
        val parsed = Route.parse("https://" + url.removePrefix("wss://")) ?: return Route.NOT_A_MINT
        if (parsed.host.isEmpty()) return Route.NOT_A_MINT
        return Route.hostProblem(parsed.host, torRunning)
    }

    /**
     * The socket on `client`, the URL already checked. Internal for the wire
     * test, which hands it a `ws://` name behind a fake Tor.
     */
    internal fun open(client: OkHttpClient, url: String, listener: RelayListener): RelaySocket {
        val ended = AtomicBoolean(false)
        val request = Request.Builder().url(url).build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!ended.get()) listener.opened()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!ended.get()) listener.text(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!ended.get()) listener.text(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                if (ended.compareAndSet(false, true)) listener.ended("the relay closed the connection")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (ended.compareAndSet(false, true)) listener.ended("the relay closed the connection")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!ended.compareAndSet(false, true)) return
                val words = when {
                    response != null -> "the relay answered ${response.code}"
                    t is IOException -> RouteHttp.failureWords(t, beforeRequest = isSocks(t))
                    else -> "The network connection was lost."
                }
                listener.ended(words)
            }
        })
        return object : RelaySocket {
            override fun send(text: String): Boolean = !ended.get() && socket.send(text)

            override fun close() {
                if (!ended.compareAndSet(false, true)) return
                // a close frame if the socket is up; cancel either way, so nothing lingers on the circuit
                socket.close(1001, null)
                socket.cancel()
            }
        }
    }

    private fun isSocks(t: Throwable): Boolean =
        generateSequence(t) { it.cause }.any { it is Socks5Failure } || t.suppressed.any { it is Socks5Failure }
}
