package io.islandbitcoin.foxy.keychain

/**
 * Where a NUT-13 counter is stored and how far it may move
 * (Foxy/Keychain/CounterRules.swift).
 *
 * One counter per derivation path: a `00` keyset's path is
 * m/129372'/0'/(id mod (2^31 − 1))'/counter', so many ids reach the same
 * secrets; its counter is kept under its derivation index (review H1). A `01`
 * keyset's path is its whole id. Moves are capped (review M1).
 */
object CounterRules {
    const val REACH: Long = 100
    const val MOST_KEYSETS = 256

    /** One past a version's last counter: 2^31 for `00`, 2^53 for `01`. */
    fun ceiling(version: Int): Long = if (version == 0) 1L shl 31 else 1L shl 53

    /** `00:<index>` for a `00` keyset, the lowercased id for a `01` keyset, null otherwise. */
    fun slot(keysetId: String): String? = when (NUT13.keysetVersion(keysetId)) {
        0 -> NUT13.derivationIndex(keysetId)?.let { "00:$it" }
        1 -> keysetId.lowercase()
        else -> null
    }

    /** The stored id with the same slot if there is one, otherwise the id itself, lowercased. */
    fun storedKey(keysetId: String, keys: Iterable<String>): String {
        val id = keysetId.lowercase()
        val wanted = slot(id) ?: return id
        if (NUT13.keysetVersion(id) == 1) return id
        return keys.firstOrNull { slot(it) == wanted } ?: id
    }

    enum class Move { ALLOWED, TOO_FAR_AHEAD }

    /** counterAdvance: up to 100 past the counter, or to the furthest restore end, never past the ceiling. */
    fun advance(current: Long, next: Long, highest: Long, version: Int): Move {
        if (next <= current) return Move.ALLOWED
        if (next > ceiling(version)) return Move.TOO_FAR_AHEAD
        val near = Bytes.add(current, REACH) ?: return Move.ALLOWED
        return if (next <= maxOf(near, highest)) Move.ALLOWED else Move.TOO_FAR_AHEAD
    }

    /** counterReserveAt: at most 100 past the counter. */
    fun reserveAt(current: Long, start: Long): Move {
        val near = Bytes.add(current, REACH) ?: return Move.ALLOWED
        return if (start <= near) Move.ALLOWED else Move.TOO_FAR_AHEAD
    }

    fun capped(value: Long, version: Int): Long = minOf(value, ceiling(version))

    /** Entries of one derivation path merged at the largest value, under the id that held it (smallest on a tie). */
    fun merged(counters: Map<String, Long>): Map<String, Long> {
        val bySlot = HashMap<String, kotlin.Pair<String, Long>>()
        for ((id, next) in counters) {
            val key = slot(id) ?: id
            val held = bySlot[key]
            if (held == null || next > held.second || (next == held.second && id < held.first)) {
                bySlot[key] = id to next
            }
        }
        return bySlot.values.associate { it.first to it.second }
    }
}
