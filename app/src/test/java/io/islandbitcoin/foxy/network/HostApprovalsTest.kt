package io.islandbitcoin.foxy.network

import io.islandbitcoin.foxy.FakeKeys
import io.islandbitcoin.foxy.TestSupport
import io.islandbitcoin.foxy.bridge.Json
import io.islandbitcoin.foxy.bridge.PromptQueue
import io.islandbitcoin.foxy.bridge.PromptQueueTest
import io.islandbitcoin.foxy.keychain.SealedFile
import io.islandbitcoin.foxy.keychain.TextStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S13: which hosts need the person's Allow, and the dialog's rules. Ported
 * from RouteTests.swift's host-approval cases, with the carry-over, the 30 s
 * quiet after a Don't Allow (an injected clock) and the sealed storage.
 */
class HostApprovalsTest {
    private var now = 1_000_000L
    private val screen = PromptQueueTest.Screen()
    private val allowed = TextStore.Memory()
    private val carried = TextStore.Memory()
    private val approvals = HostApprovals(allowed, carried, PromptQueue(screen.show)) { now }

    @Test
    fun anAllowedHostCoversItsSubdomains() {
        val a = setOf("mint.example.com")
        for (yes in listOf("mint.example.com", "pay.mint.example.com", "MINT.Example.com.", " mint.example.com ")) {
            assertTrue(yes, HostApprovals.approves(yes, a, emptySet()))
        }
        for (no in listOf("example.com", "evilmint.example.com", "mint.example.com.evil.net", "", ".")) {
            assertFalse(no, HostApprovals.approves(no, a, emptySet()))
        }
    }

    @Test
    fun theDefaultMintsAreApproved() {
        assertEquals(setOf("mint.minibits.cash", "mint.westernbtc.com", "mint.macadamia.cash", "nofee.testnut.cashu.space", "testnut.cashu.space"), HostApprovals.BUILT_IN)
        for (h in HostApprovals.BUILT_IN) assertTrue(h, approvals.isApproved(h))
        assertFalse(approvals.isApproved("cashu.space"))
    }

    @Test
    fun theBlockExplorersAreApprovedAndNoOtherOnion() {
        assertEquals(4, HostApprovals.EXPLORERS.size)
        assertEquals(2, HostApprovals.EXPLORERS.count { it.endsWith(".onion") })
        assertEquals(setOf("mempool.space", "blockstream.info"), HostApprovals.EXPLORERS.filter { !it.endsWith(".onion") }.toSet())
        for (h in HostApprovals.EXPLORERS) assertTrue(h, approvals.isApproved(h))
        for (other in listOf("example.onion", "evilmempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion",
            "mempool.space.evil.net", "blockstream.info.evil.net", "evilmempool.space")) {
            assertFalse(other, approvals.isApproved(other))
        }
    }

    @Test
    fun aCarriedHostIsApprovedExactly() {
        val c = HostApprovals.carriable(listOf("co.uk", "getalby.com", "mint.coinos.io", "8333.space")).toSet()
        for (yes in listOf("co.uk", "getalby.com", "GetAlby.com.", "mint.coinos.io", "8333.space")) {
            assertTrue(yes, HostApprovals.approves(yes, emptySet(), c))
        }
        for (no in listOf("attacker.co.uk", "api.getalby.com", "x.mint.coinos.io", "coinos.io", "evil.8333.space")) {
            assertFalse(no, HostApprovals.approves(no, emptySet(), c))
        }
        assertTrue(HostApprovals.approves("pay.example.com", setOf("example.com"), setOf("co.uk")))
    }

    @Test
    fun whatACarryOverTakes() {
        assertEquals(listOf("mint.example.com", "ok-host.io"), HostApprovals.carriable(listOf(" Mint.Example.COM. ", "localhost", "", "a b.com",
            "x@y.com", "https://x.com", "ok-host.io", "mint.example.com:3338")))
        assertEquals(200, HostApprovals.carriable(List(300) { "a.com" }).size)
        assertEquals(emptyList<String>(), HostApprovals.carriable(listOf("a".repeat(250) + ".com")))
    }

    @Test
    fun theCarryOverHappensOnceAndALaterListChangesNothing() {
        approvals.carryOver(listOf("mint.coinos.io", "getalby.com"))
        assertTrue(approvals.isApproved("mint.coinos.io"))
        assertEquals(1, carried.writes)
        approvals.carryOver(listOf("attacker.example"))
        assertFalse("a script injected afterwards cannot add its host", approvals.isApproved("attacker.example"))
        assertEquals(1, carried.writes)
    }

    @Test
    fun anUnreadableCarriedListIsNotTakenForNone() {
        carried.failRead = true
        approvals.carryOver(listOf("attacker.example"))
        assertEquals("a store that did not answer is not 'never carried' (audit I4)", 0, carried.writes)
        assertFalse(approvals.isApproved("attacker.example"))
    }

    @Test
    fun aNewHostIsAskedOnceAndRemembered() {
        val heard = mutableListOf<Boolean>()
        approvals.ask("Mint.New.Example") { heard.add(it) }
        approvals.ask("mint.new.example") { heard.add(it) }
        assertEquals("one dialog for both", listOf(HostApprovals.title("mint.new.example")), screen.shown)
        screen.tap(HostApprovals.title("mint.new.example"), true)
        assertEquals(listOf(true, true), heard)
        assertTrue(approvals.isApproved("pay.mint.new.example"))
        assertEquals(listOf("mint.new.example"), Json.parse(allowed.text!!))
        // asked again: no dialog
        approvals.ask("mint.new.example") { heard.add(it) }
        assertEquals(1, screen.shown.size)
    }

    @Test
    fun aDontAllowRefusesEveryHostWaitingAndKeepsQuietThirtySeconds() {
        val heard = mutableListOf<String>()
        approvals.ask("a.example") { heard.add("a $it") }
        approvals.ask("b.example") { heard.add("b $it") }
        screen.tap(HostApprovals.title("a.example"), false)
        assertEquals(listOf("b false", "a false"), heard)
        assertEquals("b's dialog never came up", 1, screen.shown.size)
        now += HostApprovals.QUIET_AFTER_NO_MS - 1
        approvals.ask("c.example") { heard.add("c $it") }
        assertEquals("quiet: refused with no dialog", "c false", heard.last())
        assertEquals(1, screen.shown.size)
        now += 1
        approvals.ask("c.example") { heard.add("c $it") }
        assertEquals("asked again after 30 s", 2, screen.shown.size)
    }

    @Test
    fun withNothingToShowTheDialogOnTheAnswerIsNo() {
        screen.nothingToShowOn = true
        var heard: Boolean? = null
        approvals.ask("x.example") { heard = it }
        assertEquals(false, heard)
        assertFalse(approvals.isApproved("x.example"))
    }

    @Test
    fun theDialogSaysWhatItIs() {
        assertEquals("Allow Foxy to contact mint.example?", HostApprovals.title("mint.example"))
        assertTrue(HostApprovals.message("mint.example").endsWith("such as pay.mint.example."))
    }

    @Test
    fun approvalsAreSealedNotWritten() {
        val dir = TestSupport.tempDir("hosts")
        val keys = FakeKeys()
        val file = File(dir, "foxy-hosts-allowed.v1.bin")
        val sealed = SealedFile(file, keys, "foxy.hosts.allowed.v1")
        assertEquals(TextStore.Read.Absent, sealed.read())
        assertTrue(sealed.write("[\"mint.secret-example.com\"]"))
        assertFalse("the host names are not on the disk as text", String(file.readBytes(), Charsets.ISO_8859_1).contains("secret-example"))
        assertEquals(TextStore.Read.Found("[\"mint.secret-example.com\"]"), sealed.read())
        // another key, or none: failed, never absent
        keys.key = null
        assertTrue(sealed.read() is TextStore.Read.Failed)
        // the same file under another label does not open
        keys.create()
        assertTrue(SealedFile(file, keys, "foxy.hosts.carried.v1").read() is TextStore.Read.Failed)
    }
}
