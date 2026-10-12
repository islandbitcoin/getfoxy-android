package io.islandbitcoin.foxy.tor

import io.islandbitcoin.foxy.keychain.AtomicWrite
import java.io.File

/**
 * How Foxy's Tor reaches the Tor network (Foxy/Tor/TorTransport.swift:15-60).
 *
 * Direct first. Where that is blocked, the built-in obfs4 bridges, then
 * Snowflake, tried in that order without asking. obfs4 before Snowflake for
 * privacy: an obfs4 bridge is one party that sees the phone's address, like a
 * guard; Snowflake shows it to a broker, STUN servers and a volunteer proxy,
 * all outside Tor (docs/ROUTE.md §3).
 */
enum class TorTransport(val wire: String) {
    DIRECT("direct"),
    SNOWFLAKE("snowflake"),
    OBFS4("obfs4");

    /**
     * How long a bootstrap that is standing still may take before the next
     * transport is tried. Direct gets three goes of five seconds before any
     * bridge (TorService.deadlineExpired); a moving bootstrap is given longer.
     */
    val patienceMs: Long
        get() = when (this) {
            DIRECT -> 5_000
            SNOWFLAKE -> 90_000
            OBFS4 -> 60_000
        }

    companion object {
        val ORDER = listOf(DIRECT, OBFS4, SNOWFLAKE)

        fun named(name: String?): TorTransport? = values().firstOrNull { it.wire == name }
    }
}

/**
 * The transport that last worked, used first next launch for a day; then
 * direct is tried again, so one bad network does not leave every later launch
 * on a bridge (TorTransport.swift `remembered`, `bridgeMemory`). Kept in
 * `noBackupFilesDir` as "<transport> <since ms>"; a reconnect over the same
 * bridge within the day does not start a new day.
 */
class TransportMemory(private val file: File, private val wallClock: () -> Long = System::currentTimeMillis) {
    companion object {
        const val BRIDGE_MEMORY_MS: Long = 24L * 60 * 60 * 1000
    }

    private fun read(): Pair<TorTransport, Long> {
        val parts = try {
            if (file.exists()) file.readText().trim().split(' ') else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val t = TorTransport.named(parts.getOrNull(0)) ?: TorTransport.DIRECT
        val since = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        return t to since
    }

    var remembered: TorTransport
        get() {
            val (t, since) = read()
            if (t == TorTransport.DIRECT) return TorTransport.DIRECT
            val age = wallClock() - since
            return if (since > 0 && age in 0 until BRIDGE_MEMORY_MS) t else TorTransport.DIRECT
        }
        set(value) {
            val (before, since) = read()
            val text = when {
                value == TorTransport.DIRECT -> "direct"
                value != before || since == 0L || wallClock() - since >= BRIDGE_MEMORY_MS -> "${value.wire} ${wallClock()}"
                else -> "${value.wire} $since"
            }
            try {
                AtomicWrite.write(file, text.toByteArray(Charsets.US_ASCII))
            } catch (_: Exception) {
            }
        }

    /** What is on disk, for the tests: the "since" of a bridge, or null. */
    internal fun since(): Long? = read().second.takeIf { it > 0 }
}

/**
 * When RESTART TOR is offered: only when a restart can help
 * (TorTransport.swift:107-149). A restart helps when Foxy's link to Tor is
 * wedged or nothing at all is happening, and does harm while anything arrives.
 */
object TorStuck {
    const val WEDGED_AFTER_MS: Long = 10_000
    const val SILENT_AFTER_MS: Long = 30_000
    const val SILENT_AFTER_UNCOUNTED_MS: Long = 60_000
    const val SILENT_AFTER_SNOWFLAKE_MS: Long = 120_000

    /** Whether Tor is trying to connect: not while it is off the network on purpose. */
    fun connecting(running: Boolean, ready: Boolean, failed: Boolean, stopped: Boolean, parked: Boolean, offNetwork: Boolean): Boolean =
        running && !ready && !failed && !stopped && !parked && !offNetwork

    fun offered(connecting: Boolean, hasLink: Boolean, quietMs: Long, bytesCounted: Boolean, snowflake: Boolean = false): Boolean {
        if (!connecting) return false
        if (!hasLink) return quietMs >= WEDGED_AFTER_MS
        if (bytesCounted) return quietMs >= SILENT_AFTER_MS
        return quietMs >= if (snowflake) SILENT_AFTER_SNOWFLAKE_MS else SILENT_AFTER_UNCOUNTED_MS
    }
}
