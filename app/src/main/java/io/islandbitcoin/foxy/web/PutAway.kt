package io.islandbitcoin.foxy.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.islandbitcoin.foxy.debug.FieldLog
import io.islandbitcoin.foxy.debug.Log
import io.islandbitcoin.foxy.keychain.SeedVault
import io.islandbitcoin.foxy.network.Route
import io.islandbitcoin.foxy.tor.Tor

/**
 * Putting Foxy away and bringing it back (docs/BRIDGE.md §2; FoxyWebView.swift:973-1147),
 * driven by ProcessLifecycleOwner.
 *
 * Away: the seed kept only for the work of leaving, the field log's diary saved,
 * the screen let sleep, `_putAway(true)` pushed; after 0.4 s `_tidying` is read
 * every 0.25 s and [Route.leaving] decides, waiting up to 20 s while a swap,
 * melt, mint or restore is out (the bridge's money in flight) or the change
 * top-up runs, and 3 s for anything else; then the seed for leaving goes, the
 * door shuts, and Tor comes off the network (parked: it has no connection to
 * lose and holds nothing against its guards). The door is kept shut while
 * Foxy is away (26 s at most, as iOS holds).
 *
 * Back: the door opens, the seed kept for leaving goes, Tor sets up again,
 * `__foxyUptime` and then `_resumed(secondsAway)` are pushed. Every timer is a
 * main-looper post, cancelled on a return and on destroy. No service, alarm or
 * job (S21).
 */
class PutAway(
    private val host: () -> WebHost?,
    private val vault: SeedVault,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    /** Tor off the network, once the work of leaving is done. */
    private val parkTor: () -> Unit = { Tor.park() },
    /** Tor back, after this many seconds away. */
    private val torBack: (Long) -> Unit = { Tor.resumed(it) },
) : DefaultLifecycleObserver {
    companion object {
        const val FIRST_LOOK_MS = 400L
        const val LOOK_EVERY_MS = 250L
        const val HOLD_EVERY_MS = 500L
        const val HOLD_AT_MOST_MS = 26_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val token = Any()
    private var backgroundedAt: Long? = null
    private var parkRun = 0
    private var startedOnce = false

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAt = clock()
        vault.putAway()
        FieldLog.saveForNextRun()
        host()?.keepAwake(false)
        parkRun += 1
        val run = parkRun
        val began = clock()
        var restSince: Long? = null

        fun hold() {
            if (run != parkRun) return
            if (clock() - began > HOLD_AT_MOST_MS) return
            Route.shutDoor()
            main.postAtTime({ hold() }, token, SystemClock.uptimeMillis() + HOLD_EVERY_MS)
        }

        fun look() {
            if (run != parkRun) return
            val h = host() ?: return vault.endLeaving()
            h.read("!!(window.FoxyWallet && window.FoxyWallet._tidying)") { answer ->
                if (run != parkRun) return@read
                val tidying = answer == "true"
                // swaps, melts, mints and restores the mint has not answered (FoxyBridge.moneyInFlight)
                val money = h.bridge?.moneyInFlight ?: 0
                val out = Route.out
                if (money > 0 || tidying) restSince = null else if (out > 0 && restSince == null) restSince = clock()
                val step = Route.leaving(money, tidying, out, clock() - began, restSince?.let { clock() - it } ?: 0)
                if (step is Route.Leaving.Leave) {
                    if (tidying || money > 0) Log.line("[foxy] put away: still busy with the mint after 20s; leaving anyway")
                    vault.endLeaving()
                    Route.shutDoor()
                    main.postAtTime({
                        if (run == parkRun) parkTor()
                        hold()
                    }, token, SystemClock.uptimeMillis() + step.afterMs)
                } else {
                    main.postAtTime({ look() }, token, SystemClock.uptimeMillis() + LOOK_EVERY_MS)
                }
            }
        }

        host()?.evaluate("window.FoxyWallet && window.FoxyWallet._putAway && window.FoxyWallet._putAway(true)")
        main.postAtTime({ look() }, token, SystemClock.uptimeMillis() + FIRST_LOOK_MS)
    }

    override fun onStart(owner: LifecycleOwner) {
        val first = !startedOnce
        startedOnce = true
        // back: a putting-away still waiting stops here, and requests may leave again
        parkRun += 1
        main.removeCallbacksAndMessages(token)
        vault.endLeaving()
        Route.openDoor()
        if (first) return
        val away = backgroundedAt?.let { maxOf(0L, (clock() - it) / 1000) } ?: 0L
        backgroundedAt = null
        torBack(away)
        val h = host() ?: return
        h.pushUptime()
        h.evaluate("window.FoxyWallet && window.FoxyWallet._resumed($away)")
    }

    /** The activity is going: no timer outlives it. */
    fun cancel() {
        parkRun += 1
        main.removeCallbacksAndMessages(token)
    }
}
