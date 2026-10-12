package io.islandbitcoin.foxy.network

import io.islandbitcoin.foxy.debug.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The one route decision for every native request (docs/ROUTE.md §2;
 * Foxy/Network/Route.swift), as it stands in P1: there is no Tor and no
 * unprotected path, so nothing has a route. [available] is false, [start]
 * answers null for everything, and every caller answers [REFUSAL]. There is no
 * HTTP client in the app at all (tools/check-no-network.sh).
 *
 * The door is built now because putting Foxy away uses it: while Foxy is being
 * put away nothing new may leave, for [SHUT_FOR_MS] at a time, and a return
 * opens it. P2 puts Tor and OkHttp behind [start]; the seam does not change.
 */
object Route {
    const val REFUSAL = "Foxy is not connected to Tor."

    /** How long the door stays shut at a time. */
    const val SHUT_FOR_MS: Long = 5_000

    /** How long money (a swap, a melt, the change top-up) is waited for when Foxy is put away. */
    const val WAIT_FOR_MONEY_MS: Long = 20_000

    /** How long anything else is waited for once no money is moving. */
    const val WAIT_FOR_THE_REST_MS: Long = 3_000

    /** P1: no Tor, and the unprotected path is not built until P2. */
    val available: Boolean get() = false

    /** Requests out now. Always 0 in P1: nothing starts. */
    val out: Int get() = traffic.get()
    private val traffic = AtomicInteger(0)

    @Volatile internal var clock: () -> Long = { System.nanoTime() / 1_000_000 }
    private val shutUntil = AtomicLong(Long.MIN_VALUE)

    fun shut(now: Long = clock()): Boolean = now < shutUntil.get()

    fun shutDoor(now: Long = clock()) = shutUntil.set(now + SHUT_FOR_MS)

    fun openDoor() = shutUntil.set(Long.MIN_VALUE)

    /**
     * One request, on the route as it is now; null, with nothing sent, when
     * there is none. P1 has none, so every request is null and refused with
     * [REFUSAL]. The shape (a request in, a call or null out) is P2's.
     */
    fun start(host: String): Call? {
        if (shut()) {
            Log.line("[foxy] route: a request to $host not sent; Foxy is being put away")
            return null
        }
        if (!available) return null
        return null
    }

    /** A request on its way. P1 never makes one; P2's is an OkHttp call on a Tor session. */
    interface Call

    /** What putting Foxy away does next: wait, or leave the network after a moment. */
    sealed class Leaving {
        object Wait : Leaving() {
            override fun toString() = "Wait"
        }

        data class Leave(val afterMs: Long) : Leaving()
    }

    /**
     * The rule for putting Foxy away (Route.swift `leaving`). `money`: swaps and
     * payments not answered; `tidying`: the page's change top-up; `out`: every
     * request not answered; `waited`: since Foxy was put away; `onTheRest`: how
     * long of that has been for requests that move no money.
     */
    fun leaving(money: Int, tidying: Boolean, out: Int, waitedMs: Long, onTheRestMs: Long): Leaving {
        if (waitedMs > WAIT_FOR_MONEY_MS) return Leaving.Leave(if (money > 0 || tidying || out > 0) 0 else 500)
        if (money > 0 || tidying) return Leaving.Wait
        if (out > 0) return if (onTheRestMs > WAIT_FOR_THE_REST_MS) Leaving.Leave(0) else Leaving.Wait
        return Leaving.Leave(500)
    }
}
