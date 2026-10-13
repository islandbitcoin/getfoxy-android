package io.islandbitcoin.foxy.network

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Tor's onion services, in a test: hands out a fixed id (or nothing) and records what it was asked. */
class FakeOnionServices(var answer: String? = ID, var answers: Boolean = true) : OnionServices {
    companion object {
        const val ID = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwd"
    }

    val added = CopyOnWriteArrayList<Int>()
    val removed = CopyOnWriteArrayList<String>()
    /** The answers held back while [answers] is false, for a test to give late. */
    val pending = CopyOnWriteArrayList<(String?) -> Unit>()

    override fun add(localPort: Int, done: (String?) -> Unit) {
        added.add(localPort)
        if (answers) done(answer) else pending.add(done)
    }

    override fun remove(serviceId: String) {
        removed.add(serviceId)
    }
}

/** Tor as Route sees it, in a test: running or not, on a fake SOCKS port. */
class FakeTor(override val isRunning: Boolean, override val socksPort: Int) : TorRoute

/**
 * The onion inbox on loopback, and a payment posted to it through a fake Tor
 * (FakeSocks, which joins the SOCKS stream to the inbox's own port as Tor's
 * onion service does): the bounds and answers of OnionInbox.swift, and the
 * bytes of OnionPost.swift, both ways. Nothing leaves the machine.
 */
class OnionInboxTest {
    private val services = FakeOnionServices()
    private val inboxes = mutableListOf<OnionInbox>()
    private val socks = FakeSocks()
    private val payments = LinkedBlockingQueue<Pair<String, InboxAnswer>>()

    @After
    fun tearDown() {
        inboxes.forEach { it.close() }
        socks.close()
    }

    private fun inbox(
        readMs: Long = OnionInbox.READ_PATIENCE_MS,
        answerMs: Long = OnionInbox.ANSWER_PATIENCE_MS,
        openMs: Long = OnionInbox.OPEN_PATIENCE_MS,
        onPayment: (String, InboxAnswer) -> Unit = { body, answer -> payments.add(body to answer) },
    ) = OnionInbox("a test", services, { it.run() }, readMs, answerMs, openMs, 200, onPayment).also { inboxes.add(it) }

    private fun open(inbox: OnionInbox): String? {
        val got = LinkedBlockingQueue<Any>()
        inbox.open { got.add(it ?: "null") }
        return (got.poll(10, TimeUnit.SECONDS) ?: error("open never answered")).takeIf { it != "null" } as String?
    }

    /** Raw bytes to the inbox's loopback port, and everything it answers until it closes. */
    private fun raw(port: Int, bytes: ByteArray, readMs: Int = 10_000): ByteArray {
        Socket().use { s ->
            s.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port), 5_000)
            s.soTimeout = readMs
            s.getOutputStream().apply {
                write(bytes)
                flush()
            }
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            try {
                while (true) {
                    val n = s.getInputStream().read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            } catch (_: IOException) {
            }
            return out.toByteArray()
        }
    }

    private fun post(url: String, body: String, timeoutMs: Long = 10_000, tor: TorRoute = FakeTor(true, socks.port)): OnionPost.Result {
        val got = LinkedBlockingQueue<OnionPost.Result>()
        OnionPost.send(url, body.toByteArray(), "0123456789abcdef0123456789abcdef", timeoutMs, tor, { false }) { got.add(it) }
        return got.poll(timeoutMs + 5_000, TimeUnit.MILLISECONDS) ?: error("the post never ended")
    }

    // MARK: The address

    @Test
    fun anInboxListensOnLoopbackAndAsksTorForAnAddressForwardingToIt() {
        val inbox = inbox()
        val url = open(inbox)
        assertEquals(listOf(inbox.localPort), services.added)
        assertTrue(inbox.localPort > 0)
        assertEquals("http://${FakeOnionServices.ID}.onion${inbox.path}", url)
        assertTrue(inbox.isOpen)
        // the path is "/" and 128 random bits in hex: the page's own check, foxy-wallet.js openInbox
        assertTrue(url!!, Regex("^http://[a-z2-7]{55}d\\.onion/[0-9a-f]{16,64}$").matches(url))
        assertEquals(33, inbox.path.length)
        // each inbox its own path
        assertFalse(inbox.path == inbox().path)
    }

    @Test
    fun noAddressFromTorIsNoInboxAndAnAddressMadeAfterTheCloseIsRemoved() {
        services.answer = null
        val none = inbox()
        assertNull(open(none))
        assertFalse(none.isOpen)
        assertNull(none.url)
        // closed while Tor was still making it: the address it then makes is taken down at once
        services.answers = false
        val late = inbox()
        val got = LinkedBlockingQueue<Any>()
        late.open { got.add(it ?: "null") }
        val deadline = System.currentTimeMillis() + 5_000
        while (services.pending.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        late.close()
        assertTrue("nothing to remove yet", services.removed.isEmpty())
        services.pending.single()(FakeOnionServices.ID)
        assertEquals("null", got.poll(5, TimeUnit.SECONDS))
        assertEquals(listOf(FakeOnionServices.ID), services.removed)
        assertNull(late.url)
    }

    @Test
    fun anAddressThatNeverComesIsGivenUpAfterItsPatience() {
        services.answers = false
        val inbox = inbox(openMs = 300)
        val start = System.nanoTime()
        assertNull(open(inbox))
        assertTrue("waited the patience", (System.nanoTime() - start) / 1_000_000 >= 250)
        assertFalse(inbox.isOpen)
    }

    @Test
    fun closingRemovesTheAddressAndStopsListening() {
        val inbox = inbox()
        open(inbox)
        val port = inbox.localPort
        val listener = inbox.listener ?: error("no listener")
        // the control: while open, a payment sent to the port reaches the page
        val first = thread { raw(port, Fixture.request(path = inbox.path)) }
        val (_, answer) = payments.poll(10, TimeUnit.SECONDS) ?: error("an open inbox took no payment")
        answer(200, "{\"ok\":true}")
        first.join(10_000)
        inbox.close()
        assertEquals(listOf(FakeOnionServices.ID), services.removed)
        assertNull(inbox.url)
        assertFalse(inbox.isOpen)
        // a second close removes nothing more
        inbox.close()
        assertEquals(1, services.removed.size)
        // the condition itself, which close() has made true by the time it returns: the listener is closed
        assertTrue("nothing listens once closed", listener.isClosed)
        assertNull(inbox.listener)
        assertEquals(0, inbox.localPort)
        /* And on the wire, the same request reaches nothing. Not "the connection
         * is refused": once closed the port is the system's again, and another
         * socket can be given it (or a connect to it can open onto itself), so a
         * connect that succeeds says nothing about this inbox. That is what
         * failed once in CI (ENG-672). */
        val after = try {
            raw(port, Fixture.request(path = inbox.path), readMs = 1_000)
        } catch (e: IOException) {
            null
        }
        assertNull("no payment reached the page after the close", payments.poll(500, TimeUnit.MILLISECONDS))
        assertFalse("and nothing answered as the inbox", after != null && HttpAnswer.parse(after)?.status == 200)
    }

    // MARK: Answers, byte for byte

    @Test
    fun theAnswerIsIosByteForByte() {
        assertEquals(
            "HTTP/1.1 200 OK\r\nContent-Length: 11\r\nConnection: close\r\nContent-Type: application/json\r\n\r\n{\"ok\":true}",
            String(OnionInbox.response(200, "{\"ok\":true}")),
        )
        assertEquals("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n", String(OnionInbox.response(404, "")))
        for ((status, words) in listOf(400 to "Bad Request", 405 to "Method Not Allowed", 410 to "Gone", 413 to "Payload Too Large",
            422 to "Unprocessable Content", 504 to "Gateway Timeout", 409 to "Error")) {
            assertTrue(String(OnionInbox.response(status, "")).startsWith("HTTP/1.1 $status $words\r\n"))
        }
        // the length is of the bytes, not the characters
        assertTrue(String(OnionInbox.response(422, "{\"error\":\"\uD83E\uDD8A\"}")).contains("Content-Length: 16\r\n"))
    }

    @Test
    fun anIphonePayersRequestIsAPaymentAndItsAnswerIsWhatTheIphoneReads() {
        val inbox = inbox()
        open(inbox)
        val body = """{"id":"a1b2c3d4","memo":"","mint":"https://mint.example.com","unit":"sat","proofs":[]}"""
        // OnionPost.swift `send`, the head as an iPhone writes it
        val iphone = "POST ${inbox.path} HTTP/1.1\r\nHost: ${FakeOnionServices.ID}.onion\r\n" +
            "Content-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
        val reply = AtomicReference<ByteArray>()
        val t = thread { reply.set(raw(inbox.localPort, iphone.toByteArray())) }
        val (got, answer) = payments.poll(10, TimeUnit.SECONDS)!!
        assertEquals(body, got)
        answer(200, "{\"ok\":true}")
        t.join(10_000)
        val bytes = reply.get()
        assertEquals(String(OnionInbox.response(200, "{\"ok\":true}")), String(bytes))
        // HTTPAnswer.parse, as the iPhone runs it
        assertEquals(HttpAnswer.Answer(200, "{\"ok\":true}"), HttpAnswer.parse(bytes))
    }

    @Test
    fun aRequestToAnotherPathIs404AndNeverReachesThePage() {
        val inbox = inbox()
        open(inbox)
        val bytes = raw(inbox.localPort, Fixture.request(path = "/0123456789abcdef0123456789abcdef"))
        assertEquals(404, HttpAnswer.parse(bytes)?.status)
        assertNull(payments.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun aPaymentNobodyAnswersIs504() {
        val inbox = inbox(answerMs = 300)
        open(inbox)
        val reply = AtomicReference<ByteArray>()
        val t = thread { reply.set(raw(inbox.localPort, Fixture.request(path = inbox.path))) }
        val (_, answer) = payments.poll(10, TimeUnit.SECONDS)!!
        t.join(10_000)
        val parsed = HttpAnswer.parse(reply.get())
        assertEquals(504, parsed?.status)
        assertEquals("{\"error\":\"no answer in time\"}", parsed?.body)
        // a late answer goes nowhere and breaks nothing
        answer(200, "{\"ok\":true}")
    }

    @Test
    fun aPaymentArrivingAtAClosedInboxIs410AndNeverReachesThePage() {
        val held = CountDownLatch(1)
        val main = LinkedBlockingQueue<Runnable>()
        val inbox = OnionInbox("held", services, { main.add(it) }, onPayment = { b, a -> payments.add(b to a) }).also { inboxes.add(it) }
        inbox.open { held.countDown() }
        // the main thread, by hand: the listener's start, then Tor's answer
        while (held.count > 0) main.poll(5, TimeUnit.SECONDS)?.run() ?: error("open never posted")
        val reply = AtomicReference<ByteArray>()
        val t = thread { reply.set(raw(inbox.localPort, Fixture.request(path = inbox.path))) }
        // the payment is posted to the main thread; the inbox closes before it runs
        val handOver = main.poll(10, TimeUnit.SECONDS)!!
        inbox.close()
        handOver.run()
        t.join(10_000)
        assertNull(payments.poll(100, TimeUnit.MILLISECONDS))
        // the connection itself went with the close; whatever arrived is no payment taken
        val bytes = reply.get()
        assertTrue(bytes.isEmpty() || HttpAnswer.parse(bytes)?.status == 410)
    }

    @Test
    fun aPayerThatStallsIsDroppedAfterTheReadPatience() {
        val inbox = inbox(readMs = 300)
        open(inbox)
        val start = System.nanoTime()
        // half a head, then nothing
        val bytes = raw(inbox.localPort, "POST ${inbox.path} HTTP/1.1\r\nContent-Le".toByteArray())
        assertEquals("dropped without an answer", 0, bytes.size)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000)
        assertNull(payments.poll(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun aHeadOverTheCapIs413WithoutWaitingForMore() {
        val inbox = inbox()
        open(inbox)
        val bytes = raw(inbox.localPort, ("POST ${inbox.path} HTTP/1.1\r\n" + "X-Pad: " + "p".repeat(9000)).toByteArray())
        assertEquals(413, HttpAnswer.parse(bytes)?.status)
    }

    @Test
    fun aFifthConnectionAtOnceIsClosed() {
        val inbox = inbox()
        open(inbox)
        val loop = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val held = (1..4).map { Socket().apply { connect(InetSocketAddress(loop, inbox.localPort), 5_000) } }
        Thread.sleep(300)
        Socket().use { fifth ->
            fifth.connect(InetSocketAddress(loop, inbox.localPort), 5_000)
            fifth.soTimeout = 5_000
            val read = try {
                fifth.getInputStream().read()
            } catch (e: java.net.SocketTimeoutException) {
                throw AssertionError("the fifth was held open")
            } catch (e: IOException) {
                -1
            }
            assertEquals("the fifth is closed unread", -1, read)
        }
        held.forEach { it.close() }
    }

    // MARK: Through Tor: a payer's post to a receiver's inbox

    @Test
    fun aPaymentPostedThroughTorIsTakenAndAnswered() {
        val inbox = inbox(onPayment = { body, answer ->
            payments.add(body to answer)
            answer(200, "{\"ok\":true}")
        })
        val url = open(inbox)!!
        socks.forwardTo = inbox.localPort
        val result = post(url, Fixture.JSON)
        assertEquals(OnionPost.Result.Done(200, "{\"ok\":true}"), result)
        assertEquals(Fixture.JSON, payments.poll(1, TimeUnit.SECONDS)?.first)
        // Tor was given the onion's name, by name, on port 80, with the circuit label as the login
        val h = socks.handshakes.single()
        assertEquals(3, h.atyp)
        assertEquals("${FakeOnionServices.ID}.onion", h.host)
        assertEquals(80, h.port)
        assertEquals("0123456789abcdef0123456789abcdef", h.user)
        assertEquals(h.user, h.pass)
    }

    @Test
    fun theReceiversRefusalReachesThePayerAsItsStatusAndWords() {
        val inbox = inbox(onPayment = { _, answer -> answer(422, "{\"error\":\"Token already spent.\"}") })
        val url = open(inbox)!!
        socks.forwardTo = inbox.localPort
        assertEquals(OnionPost.Result.Done(422, "{\"error\":\"Token already spent.\"}"), post(url, Fixture.JSON))
    }

    @Test
    fun theRequestAPayerWritesIsIosByteForByte() {
        // a server that records what arrives and answers as an inbox does
        val server = ServerSocket(0, 5, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val seen = AtomicReference<ByteArray>()
        val t = thread {
            server.accept().use { s ->
                val input = s.getInputStream()
                val got = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                // until the request is whole, as the inbox reads it
                while (HttpRequestHead.parse(got.toByteArray(), "/0123456789abcdef0123456789abcdef", 8192, 1 shl 18) == HttpRequestHead.Outcome.NeedMore) {
                    val n = input.read(buf)
                    if (n < 0) break
                    got.write(buf, 0, n)
                }
                seen.set(got.toByteArray())
                s.getOutputStream().write(OnionInbox.response(200, "{\"ok\":true}"))
                s.shutdownOutput()
            }
        }
        socks.forwardTo = server.localPort
        val url = "http://${FakeOnionServices.ID.uppercase()}.ONION/0123456789abcdef0123456789abcdef"
        // the host is the onion lowercased, as iOS writes it; the scheme is "http" as written
        assertEquals(OnionPost.Result.Done(200, "{\"ok\":true}"), post(url, Fixture.JSON))
        t.join(5_000)
        server.close()
        val want = "POST /0123456789abcdef0123456789abcdef HTTP/1.1\r\nHost: ${FakeOnionServices.ID}.onion\r\n" +
            "Content-Type: application/json\r\nContent-Length: ${Fixture.JSON.length}\r\nConnection: close\r\n\r\n" + Fixture.JSON
        assertEquals(want, String(seen.get()))
        assertArrayEquals(want.toByteArray(), OnionPost.request(OnionPost.target(url)!!, Fixture.JSON.toByteArray()))
    }
}

/** OnionPost on its own: what it refuses before anything leaves, and Tor's refusals, in iOS's words. */
class OnionPostTest {
    private val socks = FakeSocks()
    private val id = FakeOnionServices.ID
    private val url = "http://$id.onion/0123456789abcdef0123456789abcdef"

    @After
    fun tearDown() = socks.close()

    private fun post(
        url: String = this.url,
        timeoutMs: Long = 10_000,
        tor: TorRoute = FakeTor(true, socks.port),
        shut: Boolean = false,
    ): OnionPost.Result {
        val got = LinkedBlockingQueue<OnionPost.Result>()
        OnionPost.send(url, "{}".toByteArray(), "0123456789abcdef", timeoutMs, tor, { shut }) { got.add(it) }
        return got.poll(timeoutMs + 5_000, TimeUnit.MILLISECONDS) ?: error("the post never ended")
    }

    private fun failed(f: OnionPost.Failure) = OnionPost.Result.Failed(f)

    @Test
    fun theWordsAreIos() {
        assertEquals("That is not an onion address.", OnionPost.Failure.NotOnion.text)
        assertEquals("Foxy is not connected to Tor.", OnionPost.Failure.NoTor.text)
        assertEquals("Their phone could not be reached over Tor.", OnionPost.Failure.Unreachable(4).text)
        assertEquals("Tor refused the connection.", OnionPost.Failure.RefusedBySocks.text)
        assertEquals("Their phone did not answer in time.", OnionPost.Failure.TimedOut.text)
        assertEquals("The connection to their phone broke.", OnionPost.Failure.Broken.text)
    }

    @Test
    fun onlyAPlainV3OnionUrlIsATarget() {
        assertEquals(OnionPost.Target("$id.onion", 80, "/0123456789abcdef0123456789abcdef"), OnionPost.target(url))
        assertEquals(OnionPost.Target("$id.onion", 8080, "/x"), OnionPost.target("http://$id.onion:8080/x"))
        assertEquals(OnionPost.Target("$id.onion", 80, "/"), OnionPost.target("http://$id.onion"))
        for (bad in listOf(
            "https://$id.onion/x", "HTTP://$id.onion/x", "ftp://$id.onion/x", "http://mint.example.com/x",
            "http://${id.dropLast(1)}.onion/x", "http://${id.dropLast(1)}e.onion/x", "http://$id.onion.example.com/x",
            "http://user@$id.onion/x", "http://user:pw@$id.onion/x", "http://@$id.onion/x",
            "http://$id.onion/x?a=1", "http://$id.onion/x?", "http://$id.onion/x#f", "http://$id.onion?x",
            "http://$id.onion:/x", "http://$id.onion:99999/x", "http://$id.onion:8a/x", "http://$id.onion:-1/x",
            "http://$id.onion/a b", "http://$id.onion/a\r\nX: 1", "http://$id.onion/\u00e9", "http://127.0.0.1/x", "",
        )) {
            assertNull(bad, OnionPost.target(bad))
        }
    }

    @Test
    fun nothingLeavesForWhatIsNotAnOnionOrWithoutTor() {
        assertEquals(failed(OnionPost.Failure.NotOnion), post("http://mint.example.com/x"))
        assertEquals(failed(OnionPost.Failure.NoTor), post(tor = FakeTor(false, socks.port)))
        assertEquals(failed(OnionPost.Failure.NoTor), post(tor = FakeTor(true, 0)))
        // nothing new leaves while Foxy is being put away (Route's door)
        assertEquals(failed(OnionPost.Failure.NoTor), post(shut = true))
        assertEquals("not one connection to Tor's port", 0, socks.connections.get())
    }

    @Test
    fun nothingListeningOnTorsPortIsNoTor() {
        val dead = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).let { val p = it.localPort; it.close(); p }
        assertEquals(failed(OnionPost.Failure.NoTor), post(tor = FakeTor(true, dead)))
    }

    @Test
    fun torsRefusalOfTheStreamIsUnreachableWithItsCode() {
        socks.refuseNext = 1
        socks.refuseWith = 0xF0
        assertEquals(failed(OnionPost.Failure.Unreachable(0xF0)), post())
    }

    @Test
    fun aConnectionDroppedMidHandshakeIsBroken() {
        socks.dropNext = 1
        assertEquals(failed(OnionPost.Failure.Broken), post())
    }

    @Test
    fun aStreamTorHoldsTimesOutAtTheAttemptsCeiling() {
        socks.holdConnects = true
        val start = System.nanoTime()
        assertEquals(failed(OnionPost.Failure.TimedOut), post(timeoutMs = 500))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 4_000)
    }

    @Test
    fun anAnswerLongerThanTheCapIsBrokenAndOneThatIsNotHttpIsToo() {
        for (answer in listOf(OnionInbox.response(200, "x".repeat(OnionPost.LARGEST_ANSWER)), "nonsense\r\n\r\n".toByteArray())) {
            val server = ServerSocket(0, 5, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
            val t = thread {
                server.accept().use { s ->
                    s.getInputStream().read(ByteArray(4096))
                    try {
                        s.getOutputStream().write(answer)
                    } catch (_: IOException) {
                    }
                }
            }
            socks.forwardTo = server.localPort
            assertEquals(failed(OnionPost.Failure.Broken), post())
            t.join(5_000)
            server.close()
        }
    }

    @Test
    fun aLabelThatCannotBeALoginIsRefusedBeforeAnythingLeaves() {
        val got = LinkedBlockingQueue<OnionPost.Result>()
        OnionPost.send(url, "{}".toByteArray(), "", 1_000, FakeTor(true, socks.port), { false }) { got.add(it) }
        assertEquals(failed(OnionPost.Failure.RefusedBySocks), got.poll(5, TimeUnit.SECONDS))
        assertEquals(0, socks.connections.get())
    }
}
