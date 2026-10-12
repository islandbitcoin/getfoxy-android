package io.islandbitcoin.foxy.flashcard

import io.islandbitcoin.foxy.debug.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * DEBUG BUILDS ONLY: the card simulator's stand-in for the radio, a port of
 * CardLink.swift's `SimCardLink` (getfoxy/iOS 8df0bb1).
 *
 * The emulator has no NFC, and a phone with NFC is not always at hand with a
 * card, so every card screen could only ever be seen with a card in one hand
 * and a phone in the other. Here the same commands go over a socket on this
 * device's own loopback to a card that runs on the developer's machine: the
 * applet itself, in a JavaCard simulator (`tools/cardsim` in
 * https://github.com/getfoxy/card), listening on one of four ports (an
 * emulator or a phone reaches it through `adb reverse tcp:47431 tcp:47431`,
 * docs/DEVICE-TESTS.md). So the real page, the real bridge, CardGate and a
 * real mint can be driven against the applet's own code before there is a
 * card to hold.
 *
 * It stands in for the radio and nothing else. What it cannot show is the
 * radio: how long a card takes to sign, a card pulled away by a hand, the
 * phone's own sheet. Those stay tests for a phone.
 *
 * The talk is lines of text: "tap" (answered "ok" when a card is on the
 * reader, "none" when not), "apdu <hex>" (answered with hex, or "gone"), and
 * "end". Loopback only, and only in this source set: tools/check-no-network.sh
 * allows a socket here and nowhere else outside network/, and only one made
 * to the loopback address.
 *
 * Every method and callback is on the main thread ([main]); the socket is
 * [io]'s, one thing at a time.
 */
class SimCardLink(
    private val main: CardMain,
    private val ports: List<Int> = PORTS,
    private val waitMs: Long = WAIT_MS,
    private val io: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "foxy-cardsim") },
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) : CardLink {
    companion object {
        /** The card simulator's ports, tried in turn (CardLink.swift `SimCardLink.ports`). */
        val PORTS: List<Int> = (47431..47434).toList()

        /** As long as the phone's own sheet waits for a card. */
        const val WAIT_MS = 60_000L

        /** A port that answers nothing is passed over after this. */
        const val CONNECT_MS = 300

        /** The gap before the ports are tried again (iOS: 0.7 s). */
        const val ROUND_MS = 700L

        /** The most a command's answer may take. */
        const val ANSWER_MS = 15_000
    }

    override var onGone: (() -> Unit)? = null
    override var onProgress: ((String, String) -> Unit)? = null

    /** Main thread. */
    private var found: ((CardLinkError?) -> Unit)? = null
    private var ended = false
    private var connected = false

    /** Which look this is; a look that is no longer wanted stops ([io] reads it). */
    @Volatile private var look = 0

    /** The connection to the card's server: [io]'s only. */
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: OutputStream? = null

    override fun begin(text: String, found: (CardLinkError?) -> Unit) {
        this.found = found
        lookFor(now() + waitMs)
    }

    /** As the phone's: look for the card again, the session still open. */
    override fun again(text: String, found: (CardLinkError?) -> Unit) {
        if (ended) return found(CardLinkError.CANCELLED)
        say(text)
        this.found = found
        connected = false
        io.execute { hangUp() }
        lookFor(now() + waitMs)
    }

    /** Each port in turn, and round again, until a card answers or time is up. */
    private fun lookFor(deadline: Long) {
        val mine = ++look
        io.execute {
            while (look == mine) {
                for (port in ports) {
                    if (look != mine) return@execute
                    if (tap(port)) {
                        main.post {
                            if (look != mine || ended) return@post
                            connected = true
                            onProgress?.invoke("connected", CardGate.SCANNING)
                            tell(null)
                        }
                        return@execute
                    }
                }
                if (now() >= deadline) {
                    main.post {
                        if (look != mine || ended) return@post
                        onProgress?.invoke("lost", CardLinkError.TIMED_OUT.words)
                        finish(CardLinkError.TIMED_OUT)
                    }
                    return@execute
                }
                try {
                    Thread.sleep(ROUND_MS)
                } catch (e: InterruptedException) {
                    return@execute
                }
            }
        }
    }

    /** On [io]: one port, "tap", and the connection kept when a card is on the reader. */
    private fun tap(port: Int): Boolean {
        val s = Socket()
        return try {
            s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_MS)
            s.soTimeout = ANSWER_MS
            val r = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII))
            val w = s.getOutputStream()
            w.write("tap\n".toByteArray(StandardCharsets.US_ASCII))
            w.flush()
            if (r.readLine()?.trim() == "ok") {
                socket = s
                reader = r
                writer = w
                true
            } else {
                s.close()
                false
            }
        } catch (e: IOException) {
            try {
                s.close()
            } catch (x: IOException) {
                // nothing there
            }
            false
        }
    }

    /** On [io]: one line, and its answer; null when the connection has gone. */
    private fun ask(line: String): String? {
        val w = writer ?: return null
        val r = reader ?: return null
        return try {
            w.write((line + "\n").toByteArray(StandardCharsets.US_ASCII))
            w.flush()
            r.readLine()?.trim()
        } catch (e: IOException) {
            null
        }
    }

    /** On [io]. */
    private fun hangUp() {
        try {
            socket?.close()
        } catch (e: IOException) {
            // gone already
        }
        socket = null
        reader = null
        writer = null
    }

    override fun send(apdu: ByteArray, done: (ByteArray?, CardLinkError?) -> Unit) {
        if (!connected || ended) return done(null, CardLinkError.LOST)
        io.execute {
            val answer = ask("apdu " + CardGate.hex(apdu))?.let { CardGate.bytes(it) }?.takeIf { it.size >= 2 }
            if (answer == null) hangUp()
            main.post {
                if (answer == null) {
                    // the card left (the server said "gone"), or its server went
                    connected = false
                    if (!ended) onProgress?.invoke("lost", CardLinkError.LOST.words)
                    done(null, CardLinkError.LOST)
                } else {
                    done(answer, null)
                }
            }
        }
    }

    override fun say(text: String) {
        if (text.isEmpty()) return
        Log.line("[card] simulator sheet: ${text.length} characters")
        onProgress?.invoke("say", text)
    }

    override fun end(error: String?, text: String?) {
        Log.line("[card] simulator sheet: ended${if (!error.isNullOrEmpty()) ", with a reason" else ""}")
        if (!ended) onProgress?.invoke("end", (if (!error.isNullOrEmpty()) error else text) ?: "")
        val waitingForCard = found != null
        if (!ended) {
            io.execute {
                ask("end")
                hangUp()
            }
        }
        if (waitingForCard) finish(CardLinkError.CANCELLED) else close()
    }

    /** As the radio's link: a wait ends "Foxy was put away"; a card in hand is lost. */
    override fun putAway() {
        if (ended) return
        if (!connected && found != null) {
            end(NfcCardLink.PUT_AWAY, null)
        } else {
            onProgress?.invoke("lost", CardLinkError.CANCELLED.words)
            io.execute { hangUp() }
            close()
        }
    }

    private fun finish(why: CardLinkError) {
        if (ended) return
        val f = found
        found = null
        close()
        f?.invoke(why)
    }

    private fun close() {
        if (ended) return
        ended = true
        connected = false
        look++
        io.execute { hangUp() }
        io.shutdown()
        val g = onGone
        onGone = null
        g?.invoke()
    }

    private fun tell(why: CardLinkError?) {
        val f = found ?: return
        found = null
        f(why)
    }
}
