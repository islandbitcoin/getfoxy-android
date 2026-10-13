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
import io.islandbitcoin.foxy.tor.Cancel
import io.islandbitcoin.foxy.tor.Tor
import io.islandbitcoin.foxy.tor.TorScheduler

/**
 * Putting Foxy away and bringing it back (docs/BRIDGE.md §2; FoxyWebView.swift:999-1179),
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
 * The 20 s is a ceiling the page cannot hold open (docs/ROUTE.md "parked when
 * the work is done or after 20 s, whichever first"): a read of `_tidying` that
 * has not answered in [READ_AT_MOST_MS] counts as not tidying (a WebView whose
 * JS is throttled may never call back). That timeout is a main-looper post like
 * every look, so a look is decided at least every [READ_AT_MOST_MS] +
 * [LOOK_EVERY_MS] whatever the page does, and [Route.leaving] answers Leave
 * once 20 s have passed: Tor is parked by 20 s + 1.25 s at the latest.
 *
 * Back: the door opens, the seed kept for leaving goes, Tor sets up again,
 * `__foxyUptime` and then `_resumed(secondsAway)` are pushed. Every timer is a
 * main-looper post, cancelled on a return and on destroy. No service, alarm or
 * job (S21).
 */
class PutAway(
    private val host: () -> Page?,
    private val vault: SeedVault,
    /** Tor off the network, once the work of leaving is done. */
    private val parkTor: () -> Unit = { Tor.park() },
    /** Tor back, after this many seconds away. */
    private val torBack: (Long) -> Unit = { Tor.resumed(it) },
    private val sched: TorScheduler = MainLooper,
    /**
     * True while Foxy's own screen-lock confirmation is up (DeviceAuth.confirming,
     * API 28-29): Settings' activity over Foxy stops it, and that stop may not be
     * a put-away. The seed being read, the seed screen about to open, a candidate
     * and the door are let be until the confirmation answers ([promptAnswered]),
     * [PROMPT_AT_MOST_MS] at most. Only a yes proves the person at the screen
     * lock is the one who asked: a no or a cancel (the person may have left from
     * the prompt and someone else come back to it) puts away what was skipped
     * and is a return like any other, so the page PIN-locks (`_resumed`).
     */
    private val ownPrompt: () -> Boolean = { false },
    /**
     * ENG-668: Foxy is put away (CardTapDelivery.wentToBackground): a card's address
     * that comes with the return waits until the page has been told Foxy is back.
     */
    private val wentAway: () -> Unit = {},
    /**
     * ENG-668: the page has just been told of a return (`_resumed`), and in that
     * order (CardTapDelivery.cameBack, FoxyWebView.swift:1173-1178): it raises its
     * lock on hearing it, and a card tapped on the way back must find the lock up.
     */
    private val resumedTold: () -> Unit = {},
) : DefaultLifecycleObserver {
    /** What putting away needs of the page (WebHost). */
    interface Page {
        fun keepAwake(on: Boolean)
        fun evaluate(js: String)
        /** evaluateJavascript with its result; may never answer while the page's JS is throttled. */
        fun read(js: String, answer: (String?) -> Unit)
        /** Swaps, melts, mints and restores the mint has not answered (FoxyBridge.moneyInFlight). */
        val moneyInFlight: Int
        fun pushUptime()

        /** No camera pane, paste button or seed screen is left up for a page put away (P3). */
        fun letGo() {}
    }

    companion object {
        const val FIRST_LOOK_MS = 400L
        const val LOOK_EVERY_MS = 250L
        const val HOLD_EVERY_MS = 500L
        const val HOLD_AT_MOST_MS = 26_000L

        /** A read of `_tidying` not answered by then counts as not tidying. */
        const val READ_AT_MOST_MS = 1_000L

        /**
         * A stop under Foxy's own confirmation still away after this long (the
         * person left from the prompt) is put away after all: the confirmation's
         * own wait, after which its answer is a no (DeviceAuth.ANSWER_WAIT_MS).
         */
        const val PROMPT_AT_MOST_MS = 120_000L

        /**
         * Back from a stop under the confirmation with no answer yet (the result
         * comes after ProcessLifecycleOwner's start): an answer not in by then
         * counts as a no.
         */
        const val ANSWER_AT_MOST_MS = 2_000L
    }

    /** The main looper, as [TorScheduler]: every timer cancellable. */
    private object MainLooper : TorScheduler {
        private val main = Handler(Looper.getMainLooper())
        override fun now(): Long = SystemClock.elapsedRealtime()
        override fun post(r: () -> Unit) {
            main.post(r)
        }
        override fun after(ms: Long, r: () -> Unit): Cancel {
            val token = Any()
            main.postAtTime({ r() }, token, SystemClock.uptimeMillis() + ms)
            return Cancel { main.removeCallbacksAndMessages(token) }
        }
    }

    private val timers = mutableListOf<Cancel>()
    private var backgroundedAt: Long? = null
    private var parkRun = 0
    private var startedOnce = false

    /** The last stop was under Foxy's own confirmation, and nothing was put away for it. */
    private var promptStop = false

    /** When that stop came, for the seconds away of a return the confirmation did not prove. */
    private var promptStoppedAt = 0L

    /** The confirmation answered while Foxy was still stopped under it (true for a yes). */
    private var promptAnswer: Boolean? = null

    /** Back from a stop under the confirmation, its answer not in yet: nothing is decided until it is. */
    private var awaitingAnswer = false

    private fun after(ms: Long, r: () -> Unit) {
        timers.add(sched.after(ms, r))
    }

    private fun cancelTimers() {
        timers.forEach { it.cancel() }
        timers.clear()
    }

    override fun onStop(owner: LifecycleOwner) = away()

    override fun onStart(owner: LifecycleOwner) = back()

    /** Foxy is put away (ProcessLifecycleOwner's stop), unless the stop is Foxy's own confirmation. */
    fun away() {
        if (awaitingAnswer) {
            // gone again before the confirmation answered: the return proved nothing, so this is a put-away
            awaitingAnswer = false
            promptAnswer = null
            cancelTimers()
            return awayNow(promptStoppedAt)
        }
        if (ownPrompt()) {
            Log.line("[foxy] put away: the screen lock's confirmation is over Foxy; nothing is put away for it until it answers")
            promptStop = true
            promptStoppedAt = sched.now()
            promptAnswer = null
            parkRun += 1
            val run = parkRun
            after(PROMPT_AT_MOST_MS) {
                if (run == parkRun && promptStop) {
                    Log.line("[foxy] put away: still away ${PROMPT_AT_MOST_MS / 1000}s after the confirmation; putting away")
                    promptStop = false
                    promptAnswer = null
                    awayNow(promptStoppedAt)
                }
            }
            return
        }
        awayNow()
    }

    /**
     * The screen-lock confirmation's answer (DeviceAuth.confirmAnswered), main
     * thread. Ignored unless a stop was let be for it.
     */
    fun promptAnswered(yes: Boolean) {
        when {
            awaitingAnswer -> settle(yes)
            // still stopped: kept for the return, which decides (the 120 s still puts away)
            promptStop -> promptAnswer = yes
        }
    }

    /** Back from a stop under the confirmation, with its answer. */
    private fun settle(yes: Boolean) {
        awaitingAnswer = false
        promptAnswer = null
        parkRun += 1
        cancelTimers()
        if (yes) {
            // the screen lock was given: the person who asked is the one back, nothing was put away
            Log.line("[foxy] put away: back from the confirmation, given; nothing was put away")
            return
        }
        // not proved: what the stop skipped is put away now, and the page hears a return (and PIN-locks)
        val away = maxOf(0L, (sched.now() - promptStoppedAt) / 1000)
        Log.line("[foxy] put away: back from the confirmation, not given; a return after ${away}s")
        vault.putAway()
        vault.endLeaving()
        host()?.letGo()
        Route.openDoor()
        // Tor was never taken off the network for this stop: there is nothing to set up again
        val h = host() ?: return
        h.pushUptime()
        h.evaluate("window.FoxyWallet && window.FoxyWallet._resumed($away)")
        resumedTold()
    }

    private fun awayNow(since: Long = sched.now()) {
        backgroundedAt = since
        wentAway()
        vault.putAway()
        FieldLog.saveForNextRun()
        host()?.keepAwake(false)
        host()?.letGo()
        parkRun += 1
        val run = parkRun
        val began = sched.now()
        var restSince: Long? = null
        var left = false

        fun hold() {
            if (run != parkRun) return
            if (sched.now() - began > HOLD_AT_MOST_MS) return
            Route.shutDoor()
            after(HOLD_EVERY_MS) { hold() }
        }

        fun leave(afterMs: Long) {
            if (run != parkRun || left) return
            left = true
            vault.endLeaving()
            Route.shutDoor()
            after(afterMs) {
                if (run == parkRun) parkTor()
                hold()
            }
        }

        fun decide(page: Page, tidying: Boolean): Boolean {
            val money = page.moneyInFlight
            val out = Route.out
            val now = sched.now()
            if (money > 0 || tidying) restSince = null else if (out > 0 && restSince == null) restSince = now
            val step = Route.leaving(money, tidying, out, now - began, restSince?.let { now - it } ?: 0)
            if (step is Route.Leaving.Leave) {
                if (tidying || money > 0) Log.line("[foxy] put away: still busy with the mint after 20s; leaving anyway")
                leave(step.afterMs)
                return true
            }
            return false
        }

        fun look() {
            if (run != parkRun || left) return
            // no page: no work of leaving to wait for
            val page = host() ?: return leave(0)
            var answered = false
            fun answer(tidying: Boolean) {
                if (answered || run != parkRun || left) return
                answered = true
                if (!decide(page, tidying)) after(LOOK_EVERY_MS) { look() }
            }
            // a page that does not answer within the second is not tidying (its JS may be throttled)
            after(READ_AT_MOST_MS) {
                if (!answered) Log.line("[foxy] put away: the page did not answer _tidying in ${READ_AT_MOST_MS}ms")
                answer(false)
            }
            page.read("!!(window.FoxyWallet && window.FoxyWallet._tidying)") { answer(it == "true") }
        }

        host()?.evaluate("window.FoxyWallet && window.FoxyWallet._putAway && window.FoxyWallet._putAway(true)")
        after(FIRST_LOOK_MS) { look() }
    }

    /** Foxy is back (ProcessLifecycleOwner's start). */
    fun back() {
        val first = !startedOnce
        startedOnce = true
        if (promptStop) {
            // back from under Foxy's own confirmation: nothing was put away; its answer decides whether anything comes back
            promptStop = false
            parkRun += 1
            cancelTimers()
            val answer = promptAnswer
            if (answer != null) return settle(answer)
            awaitingAnswer = true
            val run = parkRun
            after(ANSWER_AT_MOST_MS) {
                if (run == parkRun && awaitingAnswer) {
                    Log.line("[foxy] put away: the confirmation did not answer in ${ANSWER_AT_MOST_MS}ms after the return; taken as no")
                    settle(false)
                }
            }
            return
        }
        // back: a putting-away still waiting stops here, and requests may leave again
        parkRun += 1
        cancelTimers()
        vault.endLeaving()
        Route.openDoor()
        if (first) return
        val away = backgroundedAt?.let { maxOf(0L, (sched.now() - it) / 1000) } ?: 0L
        backgroundedAt = null
        torBack(away)
        val h = host() ?: return
        h.pushUptime()
        h.evaluate("window.FoxyWallet && window.FoxyWallet._resumed($away)")
        resumedTold()
    }

    /** The activity is going: no timer outlives it. */
    fun cancel() {
        parkRun += 1
        awaitingAnswer = false
        cancelTimers()
    }
}
