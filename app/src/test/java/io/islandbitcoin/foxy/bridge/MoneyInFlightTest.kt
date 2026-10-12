package io.islandbitcoin.foxy.bridge

import io.islandbitcoin.foxy.FakeHost
import io.islandbitcoin.foxy.TestSupport
import io.islandbitcoin.foxy.keychain.CounterStore
import io.islandbitcoin.foxy.keychain.Flags
import io.islandbitcoin.foxy.keychain.SeedCandidates
import io.islandbitcoin.foxy.keychain.SeedEnvironment
import io.islandbitcoin.foxy.keychain.SeedVault
import io.islandbitcoin.foxy.keychain.TextStore
import io.islandbitcoin.foxy.network.HostApprovals
import io.islandbitcoin.foxy.network.Route
import io.islandbitcoin.foxy.network.RouteAnswer
import io.islandbitcoin.foxy.tor.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executor

/**
 * S21 with money: a melt POST out when Foxy is put away is waited for (up to
 * 20 s), and once it is answered the rule lets Foxy leave and the door shuts.
 * The bridge's count is what PutAway hands Route.leaving.
 */
class MoneyInFlightTest {
    private val answers = mutableListOf<(RouteAnswer) -> Unit>()
    private val prompts = PromptQueue { _, _ -> null }
    private val net = NetEnvironment(
        torRunning = { true }, bridged = { false }, snapshot = { Snapshot.noTor() }, restartTor = {},
        unprotected = { false }, setUnprotected = {}, available = { true },
        approvals = HostApprovals(TextStore.Memory(), TextStore.Memory(), prompts), prompts = prompts,
        send = { _, _, done -> answers.add(done); {} },
        after = { _, r -> r() },
    )
    private val dir = TestSupport.tempDir("money")
    private val bridge = FoxyBridge(FakeHost(), {
        SeedEnvironment(
            savedSeed = { SeedVault.SecretsSeed.Absent }, seedPresent = { false }, write = { null },
            counters = CounterStore(dir), candidates = SeedCandidates(), wordlist = { null }, generate = { "" },
            migrationWindowOpen = { false }, closeMigrationWindow = {}, flags = Flags(dir),
        )
    }, Executor { it.run() }, false, net)

    private fun ask(id: String, path: String, method: String = "POST") = bridge.onMessage(
        """{"action":"mintRequest","id":"$id","url":"https://testnut.cashu.space$path","method":"$method","body":"{}"}""", true, FoxyBridge.ORIGIN,
    )

    @Test
    fun putAwayWaitsForAMeltAndThenLeaves() {
        ask("melt", "/v1/melt/bolt11")
        ask("quote", "/v1/melt/quote/bolt11")
        ask("keys", "/v1/keys", "GET")
        assertEquals("only the melt is money", 1, bridge.moneyInFlight)
        val money = bridge.moneyInFlight
        assertEquals(Route.Leaving.Wait, Route.leaving(money, false, 3, 400, 0))
        assertEquals(Route.Leaving.Wait, Route.leaving(money, false, 3, 19_000, 9_000))
        assertEquals("after 20 s whatever is out is left", Route.Leaving.Leave(0), Route.leaving(money, false, 3, 20_100, 0))
        // the melt is answered: no money out, and nothing else out either
        answers[0](RouteAnswer.Failed(java.io.IOException("x"), false, 0))
        assertEquals(0, bridge.moneyInFlight)
        assertEquals(Route.Leaving.Leave(500), Route.leaving(bridge.moneyInFlight, false, 0, 1_000, 0))
    }

    @Test
    fun everyKindOfMoneyIsCountedAndAnsweredOnce() {
        for ((i, p) in listOf("/v1/swap", "/v1/restore", "/v1/mint/bolt11", "/v1/melt/bolt11").withIndex()) ask("m$i", p)
        assertEquals(4, bridge.moneyInFlight)
        answers.forEach { it(RouteAnswer.TooLarge) }
        assertEquals(0, bridge.moneyInFlight)
    }
}
