package io.islandbitcoin.foxy.bridge

import io.islandbitcoin.foxy.FakeHost
import io.islandbitcoin.foxy.network.HostApprovals
import io.islandbitcoin.foxy.network.Route
import io.islandbitcoin.foxy.network.RouteAnswer
import io.islandbitcoin.foxy.network.RouteRequest
import io.islandbitcoin.foxy.keychain.TextStore
import io.islandbitcoin.foxy.tor.Snapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The seven P2 actions against a fake Tor and a fake route: mintRequest's
 * checks in iOS's order, the headers it passes, its one retry, the money it
 * counts; CONTINUE WITHOUT TOR (S11); the price walk; the chart; and the rest.
 */
class NetBridgeTest {
    class Sent(val request: RouteRequest, val circuit: String, val done: (RouteAnswer) -> Unit)

    private val host = FakeHost()
    private val screen = PromptQueueTest.Screen()
    private val prompts = PromptQueue(screen.show)
    private val sent = mutableListOf<Sent>()
    private val cancelled = mutableListOf<RouteRequest>()
    private val timers = mutableListOf<Pair<Long, () -> Unit>>()
    private var tor = false
    private var unprotected = false
    private var restarts = mutableListOf<String>()
    private var routeOpen = true
    private val approvals = HostApprovals(TextStore.Memory(), TextStore.Memory(), prompts)
    private val env = NetEnvironment(
        torRunning = { tor },
        bridged = { false },
        snapshot = { Snapshot.noTor(unprotected = unprotected) },
        restartTor = { restarts.add(it) },
        unprotected = { unprotected },
        setUnprotected = { unprotected = it },
        available = { tor || unprotected },
        approvals = approvals,
        prompts = prompts,
        send = { r, c, d ->
            if (!routeOpen) {
                null
            } else {
                sent.add(Sent(r, c, d))
                val cancel: () -> Unit = { cancelled.add(r) }
                cancel
            }
        },
        after = { ms, r -> timers.add(ms to r) },
        nowSeconds = { 1_790_000_000 },
    )
    private val net = NetBridge(env, host) { id, text, error -> replies.add(Triple(id, text, error)) }
    private val replies = mutableListOf<Triple<String, String?, String?>>()

    @After
    fun reset() {
        Route.unprotected = false
    }

    private fun mint(id: String, json: String) = net.handle("mintRequest", id, body(json))
    private fun body(json: String): Map<String, Any?> = io.islandbitcoin.foxy.TestSupport.body(json)

    @Test
    fun mintRequestChecksInIOSOrder() {
        // 1. the URL parses
        mint("a", """{"url":"not a url"}""")
        // 2. urlProblem
        mint("b", """{"url":"http://mint.example.com/v1/keys"}""")
        mint("c", """{"url":"https://192.168.1.1/v1/keys"}""")
        // 3. GET or POST, before the route
        mint("d", """{"url":"https://mint.minibits.cash/v1/keys","method":"PUT"}""")
        // 4. the route
        mint("e", """{"url":"https://mint.minibits.cash/v1/keys","method":"GET"}""")
        // 5. an unknown host, with no route: refused before any dialog
        mint("f", """{"url":"https://mint.unknown.example/v1/keys"}""")
        assertEquals(
            listOf(
                Triple("a", null, Route.NOT_A_MINT), Triple("b", null, Route.NOT_A_MINT), Triple("c", null, Route.NOT_A_MINT),
                Triple("d", null, NetBridge.NOT_A_REQUEST), Triple("e", null, Route.REFUSAL), Triple("f", null, Route.REFUSAL),
            ),
            replies,
        )
        assertTrue("no dialog was shown", screen.shown.isEmpty())
        assertTrue("nothing was sent", sent.isEmpty())
    }

    @Test
    fun anUnknownHostIsAskedAboutAndADontAllowSendsNothing() {
        tor = true
        mint("n", """{"url":"https://mint.new.example/v1/info"}""")
        assertEquals(listOf(HostApprovals.title("mint.new.example")), screen.shown)
        assertTrue(sent.isEmpty())
        screen.tap(HostApprovals.title("mint.new.example"), false)
        assertEquals(listOf(Triple("n", null, "You did not allow Foxy to contact mint.new.example.")), replies)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun anAllowedHostIsSentOnTheLabelledCircuitWithOnlyTheTwoHeaders() {
        tor = true
        mint("y", """{"url":"https://mint.new.example/v1/swap","method":"post","circuit":"0123456789abcdef","body":"{\"inputs\":[]}",
            "headers":{"Content-Type":"application/json","Accept":"application/json","Cookie":"x=1","User-Agent":"Foxy/1","X-Who":"me"}}""")
        screen.tap(HostApprovals.title("mint.new.example"), true)
        val s = sent.single()
        assertEquals("0123456789abcdef", s.circuit)
        assertEquals("POST", s.request.method)
        assertEquals(mapOf("Content-Type" to "application/json", "Accept" to "application/json"), s.request.headers)
        assertEquals("{\"inputs\":[]}", String(s.request.body!!))
        assertEquals("a swap is money in flight", setOf("y"), net.moneyInFlight)
        s.done(RouteAnswer.Done(200, "{\"signatures\":[]}".toByteArray(), s.request.url))
        assertEquals(Triple("y", "200\n{\"signatures\":[]}", null), replies.single())
    }

    @Test
    fun aBodyWithNoTypeIsJsonAndAnUnlabelledRequestGetsACircuitOfItsOwn() {
        tor = true
        mint("j", """{"url":"https://testnut.cashu.space/v1/mint/quote/bolt11","method":"POST","body":"{}"}""")
        mint("k", """{"url":"https://testnut.cashu.space/v1/keys"}""")
        assertEquals(mapOf("Content-Type" to "application/json"), sent[0].request.headers)
        assertEquals(32, sent[0].circuit.length)
        assertTrue(sent[0].circuit != sent[1].circuit)
        assertNull("a GET carries no body", sent[1].request.body)
        assertTrue("a quote is not money in flight", net.moneyInFlight.isEmpty())
    }

    @Test
    fun onlyAHandshakeFailureIsSentAgainAndOnlyOnce() {
        tor = true
        mint("r", """{"url":"https://testnut.cashu.space/v1/melt/bolt11","method":"POST","body":"{}"}""")
        val first = sent.single()
        first.done(RouteAnswer.Failed(IOException("refused"), beforeRequest = true, dialled = 9050))
        assertTrue("nothing sent yet: after 0.4 s", sent.size == 1)
        assertEquals(NetBridge.RETRY_AFTER_MS, timers.single().first)
        timers.single().second()
        assertEquals(2, sent.size)
        assertTrue("the same request, on the same circuit", sent[1].request === first.request && sent[1].circuit == first.circuit)
        sent[1].done(RouteAnswer.Failed(IOException("refused"), beforeRequest = true, dialled = 9051))
        assertEquals(1, timers.size)
        assertEquals(Triple("r", null, "Foxy's private connection was rebuilt mid-request. Try again."), replies.single())
    }

    @Test
    fun twoHandshakeFailuresOnTheSamePortAreNotARebuiltTor() {
        tor = true
        mint("q", """{"url":"https://testnut.cashu.space/v1/keys"}""")
        sent.single().done(RouteAnswer.Failed(IOException("refused"), beforeRequest = true, dialled = 9050))
        timers.single().second()
        sent[1].done(RouteAnswer.Failed(IOException("refused"), beforeRequest = true, dialled = 9050))
        assertEquals("the same listener refused twice: Tor is not there", Triple("q", null, Route.REFUSAL), replies.single())
    }

    @Test
    fun aRetryOnPortNineIsNotARebuiltTor() {
        tor = true
        mint("p", """{"url":"https://testnut.cashu.space/v1/keys"}""")
        sent.single().done(RouteAnswer.Failed(IOException("refused"), beforeRequest = true, dialled = 9050))
        timers.single().second()
        // Tor's port became unknown between the tries: the retry dialled the dead port
        sent[1].done(RouteAnswer.Failed(IOException("refused"), beforeRequest = true, dialled = 9))
        assertEquals(Triple("p", null, Route.REFUSAL), replies.single())
    }

    @Test
    fun torRefusingTheStreamIsSaidAsTorsRefusal() {
        tor = true
        mint("u", """{"url":"https://testnut.cashu.space/v1/keys"}""")
        sent.single().done(RouteAnswer.Failed(io.islandbitcoin.foxy.network.Socks5Failure("Tor could not open the stream", 9050, code = 4), beforeRequest = true, dialled = 9050))
        timers.single().second()
        sent[1].done(RouteAnswer.Failed(io.islandbitcoin.foxy.network.Socks5Failure("Tor could not open the stream", 9050, code = 4), beforeRequest = true, dialled = 9050))
        assertEquals(Triple("u", null, "testnut.cashu.space could not be asked over Tor: host unreachable"), replies.single())
    }

    @Test
    fun aFailureAfterTheRequestLeftIsNeverSentAgain() {
        tor = true
        mint("s", """{"url":"https://testnut.cashu.space/v1/swap","method":"POST","body":"{}"}""")
        sent.single().done(RouteAnswer.Failed(java.net.SocketTimeoutException("timeout"), beforeRequest = false, dialled = 9050))
        assertTrue(timers.isEmpty())
        assertEquals(1, sent.size)
        assertEquals(Triple("s", null, "The request timed out."), replies.single())
    }

    @Test
    fun aTooLargeAnswerAndAnOnionFailureAreSaidPlainly() {
        tor = true
        mint("t", """{"url":"https://testnut.cashu.space/v1/keys"}""")
        sent.last().done(RouteAnswer.TooLarge)
        mint("o", """{"url":"http://mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion/api/blocks/tip/hash"}""")
        sent.last().done(RouteAnswer.Failed(IOException("x"), beforeRequest = false, dialled = 9050))
        assertEquals(Triple("t", null, "The answer from testnut.cashu.space was too large."), replies[0])
        assertEquals(
            Triple("o", null, "mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion could not be asked over Tor: The network connection was lost."),
            replies[1],
        )
    }

    @Test
    fun theRouteGoingAwayBetweenTheChecksAndTheSendIsTheRefusal() {
        tor = true
        routeOpen = false
        mint("g", """{"url":"https://testnut.cashu.space/v1/keys"}""")
        assertEquals(Triple("g", null, Route.REFUSAL), replies.single())
    }

    @Test
    fun theMethodsAndTheMoneyTable() {
        assertEquals("GET", NetBridge.mintMethod(emptyMap()))
        assertEquals("POST", NetBridge.mintMethod(mapOf("method" to "post")))
        for (m in listOf("PUT", "DELETE", "HEAD", "OPTIONS", "PATCH")) assertNull(m, NetBridge.mintMethod(mapOf("method" to m)))
        assertNull(NetBridge.mintMethod(mapOf("method" to 5.0)).let { if (it == "GET") null else it })
        for (yes in listOf("/v1/swap", "/v1/restore", "/v1/melt/bolt11", "/v1/mint/bolt11", "/Bitcoin/v1/swap")) {
            assertTrue(yes, NetBridge.movesMoney("POST", yes))
        }
        for (no in listOf("/v1/melt/quote/bolt11", "/v1/mint/quote/bolt11", "/v1/checkstate", "/v1/keys", "/v1/info")) {
            assertFalse(no, NetBridge.movesMoney("POST", no))
        }
        assertFalse("a GET moves nothing", NetBridge.movesMoney("GET", "/v1/swap"))
    }

    // MARK: CONTINUE WITHOUT TOR (S11)

    @Test
    fun turningItOnNeedsAYesInTheDialog() {
        net.handle("unprotected", "u1", body("""{"on":true}"""))
        assertEquals(listOf("Continue without Tor?"), screen.shown)
        assertTrue(replies.isEmpty())
        screen.tap("Continue without Tor?", false)
        assertFalse(unprotected)
        assertEquals("u1", replies.single().first)
        assertTrue("answered with the snapshot", replies.single().second!!.contains("\"unprotected\":false"))
        net.handle("unprotected", "u2", body("""{"on":true}"""))
        screen.tap("Continue without Tor?", true)
        assertTrue(unprotected)
        assertTrue(replies.last().second!!.contains("\"unprotected\":true"))
        assertTrue("pushed to the page", host.evaluated.any { it.contains("_privacy(") && it.contains("\"unprotected\":true") })
    }

    @Test
    fun turningItOffNeedsNothingAndAskWhileTorRunsIsIgnored() {
        unprotected = true
        net.handle("unprotected", "u3", body("""{"on":false}"""))
        assertFalse(unprotected)
        assertTrue(screen.shown.isEmpty())
        tor = true
        net.handle("unprotected", "u4", body("""{"on":true}"""))
        assertTrue("no dialog while Tor runs", screen.shown.isEmpty())
        assertFalse(unprotected)
        assertEquals(2, replies.size)
    }

    @Test
    fun aSecondAskWhileTheDialogIsUpIsANo() {
        net.handle("unprotected", "u5", body("""{"on":true}"""))
        net.handle("unprotected", "u6", body("""{"on":true}"""))
        assertEquals(1, screen.shown.size)
        assertEquals("u6", replies.single().first)
    }

    @Test
    fun torConnectingWhileTheDialogIsUpMakesAYesANo() {
        net.handle("unprotected", "u7", body("""{"on":true}"""))
        tor = true
        screen.tap("Continue without Tor?", true)
        assertFalse("Tor carries it now: no open connection", unprotected)
    }

    // MARK: the rest

    @Test
    fun privacyTorRetryAndHostsKnown() {
        net.handle("privacy", "p", emptyMap())
        net.handle("torRetry", "q", emptyMap())
        net.handle("hostsKnown", "h", body("""{"hosts":["mint.coinos.io",5,"getalby.com"]}"""))
        assertEquals(Snapshot.noTor(), replies[0].second)
        assertEquals(listOf("the person asked"), restarts)
        assertEquals(Snapshot.noTor(), replies[1].second)
        assertEquals(Triple("h", "ok", null), replies[2])
        assertTrue(approvals.isApproved("mint.coinos.io"))
    }

    @Test
    fun thePriceIsRefusedWithNoRoute() {
        net.handle("price", "x", emptyMap())
        assertEquals(Triple("x", "", Route.REFUSAL), replies.single())
        net.handle("candles", "c", emptyMap())
        assertEquals(Triple("c", "", Route.REFUSAL), replies.last())
    }

    @Test
    fun thePriceWalksTwoAtATimeFromTheOnionAndTheFirstAnswerWins() {
        tor = true
        net.handle("price", "p", emptyMap())
        assertEquals(listOf("mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion", "api.kraken.com"), sent.map { it.request.url.host })
        assertEquals(NetBridge.PRICE_CIRCUIT, sent[0].circuit)
        assertEquals(10_000, sent[0].request.timeoutMs)
        assertEquals(4_000, sent[1].request.timeoutMs)
        sent[0].done(RouteAnswer.Failed(IOException("no"), false, 0))
        assertEquals("the third starts as one fails", "www.bitstamp.net", sent[2].request.url.host)
        sent[1].done(RouteAnswer.Done(200, """{"result":{"XXBTZUSD":{"c":["65000.5","0.1"]}}}""".toByteArray(), sent[1].request.url))
        assertEquals(Triple("p", "65000.5", null), replies.single())
        assertEquals("the source still asking is called off", listOf(sent[2].request), cancelled)
    }

    @Test
    fun aDollarPaymentNeedsTwoSourcesWithinTwoPercent() {
        tor = true
        net.handle("price", "p", body("""{"agree":true}"""))
        sent[0].done(RouteAnswer.Done(200, """{"USD":60000}""".toByteArray(), sent[0].request.url))
        assertTrue(replies.isEmpty())
        sent[1].done(RouteAnswer.Done(200, """{"result":{"X":{"c":["66000"]}}}""".toByteArray(), sent[1].request.url))
        assertTrue("10% apart: not yet", replies.isEmpty())
        sent[2].done(RouteAnswer.Done(200, """{"last":"60600"}""".toByteArray(), sent[2].request.url))
        assertEquals(Triple("p", "60300.0", null), replies.single())
    }

    @Test
    fun whenNoSourceAnswersItSaysSo() {
        tor = true
        net.handle("price", "p", emptyMap())
        var i = 0
        while (i < sent.size) {
            sent[i].done(RouteAnswer.Done(503, ByteArray(0), sent[i].request.url))
            i++
        }
        assertEquals(6, sent.size)
        assertEquals(Triple("p", "", "no price source answered"), replies.single())
        // and the walk's own deadline
        net.handle("price", "q", emptyMap())
        timers.last().second()
        assertEquals(Triple("q", "", "no price source answered"), replies.last())
    }

    @Test
    fun theChartAsksKrakenForAnIntervalThatFitsAndAnswersPairs() {
        tor = true
        net.handle("candles", "c", body("""{"g":3600,"span":86400}"""))
        val u = sent.single().request.url
        assertEquals("api.kraken.com", u.host)
        assertEquals("60", u.queryParameter("interval"))
        assertEquals((1_790_000_000 - 86_400).toString(), u.queryParameter("since"))
        assertEquals(12_000, sent.single().request.timeoutMs)
        sent.single().done(RouteAnswer.Done(200, """{"error":[],"result":{"XXBTZUSD":[[1790000000,"1","2","0.5","64000.1","0","0",1],[1790003600,"1","2","0.5","65000","0","0",1]],"last":1}}""".toByteArray(), u))
        assertEquals(Triple("c", "[[1790000000,64000.1],[1790003600,65000]]", null), replies.single())
    }

    @Test
    fun theChartsNumbersAreClampedAndItsIntervalsChosen() {
        assertEquals(1, NetBridge.candleInterval(60, 3600))
        assertEquals(60, NetBridge.candleInterval(3600, 86_400))
        assertEquals("a year at hourly is too many candles", 1440, NetBridge.candleInterval(3600, 365 * 86_400))
        assertEquals(10080, NetBridge.candleInterval(86_400, 1_461 * 86_400))
        assertEquals(21600, NetBridge.candleInterval(86_400, 1_461 * 86_400 * 4))
        tor = true
        net.handle("candles", "c", body("""{"g":-5,"span":1e30}"""))
        val u = sent.single().request.url
        assertEquals((1_790_000_000L - 1_461L * 86_400).toString(), u.queryParameter("since"))
        assertNull(NetBridge.candleRows("not json"))
        assertNull(NetBridge.candleRows("""{"error":["EGeneral"]}"""))
    }
}
