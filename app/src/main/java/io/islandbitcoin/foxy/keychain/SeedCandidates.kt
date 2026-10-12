package io.islandbitcoin.foxy.keychain

import java.security.SecureRandom

/**
 * Words typed on the native restore screen (seedEnter), held natively under an
 * opaque id so the page can scan with them and adopt them without ever reading
 * them (Foxy/Keychain/SeedCandidates.swift).
 *
 * In memory only, two at a time. They go when forgotten, and wherever the seed
 * kept for secrets goes ([SeedVault]): the page gone or loaded again, Foxy put
 * away, and any write or delete of the seed. A candidate that seedAdopt has just
 * written stays through that write, so the page's scans still in flight with it
 * can finish (review M6); it goes at the next of those moments.
 *
 * Served ranges are kept per derivation path ([CounterRules.slot]), so a second
 * `00` id with the same index continues the first's window, not a new one.
 */
class SeedCandidates(private val random: SecureRandom = SecureRandom()) {
    sealed class Failure(message: String) : Exception(message) {
        class Unknown : Failure("unknown candidate")
        class Full : Failure("too many candidates")
        class OutsideWindow : Failure("outside the restore window")
        /** Served for more keysets than the counter file holds. */
        class TooManyKeysets : Failure("too many keysets")
        /** Not words NUT13.seed takes. seedEnter checks the phrase first, so not expected. */
        class NotAPhrase : Failure("the words could not be read")
    }

    companion object {
        const val MOST = 2
    }

    private class Candidate(val words: ByteArray) {
        var seed: NUT13.Seed? = null
        /** Per slot, the first id served and the end of the furthest range served. */
        val served = HashMap<String, Pair<String, Long>>()

        /** The words only: a seed handed out may still be deriving on the seed thread, and is dropped, not wiped. */
        fun wipe() {
            words.fill(0)
            seed = null
        }
    }

    private val held = LinkedHashMap<String, Candidate>()
    private val lock = Any()

    val count: Int get() = synchronized(lock) { held.size }

    operator fun contains(id: String): Boolean = synchronized(lock) { id in held }

    /** Keeps a checked phrase and returns its id: 128 random bits as 32 hex digits. */
    fun add(phrase: String): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        val id = Bytes.hex(bytes)
        synchronized(lock) {
            if (held.size >= MOST) throw Failure.Full()
            held[id] = Candidate(phrase.toByteArray(Charsets.UTF_8))
        }
        return id
    }

    /** The words, for seedAdopt's write. */
    fun phrase(id: String): String? = synchronized(lock) { held[id]?.words?.toString(Charsets.UTF_8) }

    /** The BIP-39 seed of the words, made once. */
    fun seed(id: String): NUT13.Seed = synchronized(lock) {
        val candidate = held[id] ?: throw Failure.Unknown()
        seedOf(candidate)
    }

    /** The seed for one restoreSecrets range, if the window allows it; the range then counts as served. */
    fun serve(id: String, keysetId: String, start: Long, count: Int): NUT13.Seed = synchronized(lock) {
        val candidate = held[id] ?: throw Failure.Unknown()
        val slot = CounterRules.slot(keysetId) ?: throw Failure.OutsideWindow()
        val before = candidate.served[slot]
        if (before == null && candidate.served.size >= CounterRules.MOST_KEYSETS) throw Failure.TooManyKeysets()
        val served = before?.second ?: 0
        if (!RestoreWindow.candidateAllowed(start, count, served)) throw Failure.OutsideWindow()
        val seed = seedOf(candidate)
        candidate.served[slot] = (before?.first ?: keysetId.lowercase()) to maxOf(served, start + count)
        seed
    }

    /** Per keyset served (the first id served for each derivation path), the end of the furthest range. */
    fun servedEnds(id: String): Map<String, Long> = synchronized(lock) {
        val candidate = held[id] ?: return@synchronized emptyMap()
        candidate.served.values.associate { it.first to it.second }
    }

    fun forget(id: String) = synchronized(lock) {
        held.remove(id)?.wipe()
        Unit
    }

    /** Every candidate but `kept`, which a seed write has just adopted. */
    fun forgetAll(kept: String? = null) = synchronized(lock) {
        val gone = held.keys.filter { it != kept }
        for (id in gone) held.remove(id)?.wipe()
    }

    /** Lock held. */
    private fun seedOf(candidate: Candidate): NUT13.Seed {
        candidate.seed?.let { return it }
        val seed = try {
            NUT13.seed(candidate.words.toString(Charsets.UTF_8))
        } catch (e: NUT13.Failure) {
            throw Failure.NotAPhrase()
        }
        candidate.seed = seed
        return seed
    }
}
