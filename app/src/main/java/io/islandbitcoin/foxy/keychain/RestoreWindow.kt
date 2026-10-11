package io.islandbitcoin.foxy.keychain

/** How far restoreSecrets reaches (Foxy/Keychain/SeedCandidates.swift, RestoreWindow). */
object RestoreWindow {
    const val BEYOND: Long = 1000
    const val CANDIDATE_CAP: Long = 20_000

    /** The saved seed: the range ends at most 1000 past the keyset's next counter. */
    fun savedSeedAllowed(start: Long, count: Int, next: Long): Boolean {
        if (count <= 0 || start < 0) return false
        val end = Bytes.add(start, count.toLong()) ?: return false
        val limit = Bytes.add(next, BEYOND) ?: return true
        return end <= limit
    }

    /** What seedAdopt (P3) raises each counter to: 1000 short of the end served. */
    fun adoptedCounters(servedEnds: Map<String, Long>): Map<String, Long> =
        servedEnds.mapValues { if (it.value > BEYOND) it.value - BEYOND else 0 }

    /** Typed words: from 0, each request no more than 1000 past the furthest served, none past 20,000. */
    fun candidateAllowed(start: Long, count: Int, servedEnd: Long): Boolean {
        if (count <= 0 || start < 0) return false
        val end = Bytes.add(start, count.toLong()) ?: return false
        return end <= CANDIDATE_CAP && start <= servedEnd + BEYOND
    }
}
