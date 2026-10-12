package io.islandbitcoin.foxy.keychain

import io.islandbitcoin.foxy.bridge.Json
import io.islandbitcoin.foxy.debug.Log

/**
 * What the seed and counter actions need from the phone (Foxy/Keychain/SeedActions.swift,
 * `SeedEnvironment`). The app's is built in [SeedBridge] from [SeedVault], the
 * counter file and the migration window; the unit tests build one around a
 * vault of their own, so every reply can be checked for words (S2).
 */
class SeedEnvironment(
    /** The saved seed, kept for the visit or read. */
    val savedSeed: () -> SeedVault.SecretsSeed,
    /** Whether a seed is there, reading nothing: true, false, or null when the Keystore would not say. */
    val seedPresent: () -> Boolean?,
    /** SeedVault.write with replace false (P1 replaces nothing): null when written. */
    val write: (words: String) -> String?,
    val counters: CounterStore,
    val candidates: SeedCandidates,
    val wordlist: () -> List<String>?,
    val generate: (wordlist: List<String>) -> String,
    val migrationWindowOpen: () -> Boolean,
    val closeMigrationWindow: (why: String) -> Unit,
    /** Where countersImport records that it has answered. */
    val flags: Flags,
    /** A phone with a screen lock. "passcode": false is said only on one without. */
    var passcodeSet: () -> Boolean = { true },
    /**
     * SeedVault.write with replace (seedAdopt, P3): null when written.
     * `confirm` is asked only when a different seed is saved; the candidate
     * `keep` survives the write's forgetting (review M6).
     */
    val writeReplacing: (words: String, keep: String?, confirm: () -> Boolean) -> String? = { _, _, _ -> "The saved seed was not replaced." },
    /** SeedVault.delete: true only when no seed is left (seedWipe, P3). */
    val delete: () -> Boolean = { false },
    /** What the person asked to stand in front of the seed: "device" or "none" (SeedVault.protection). */
    val protection: () -> String = { SeedVault.NONE },
    /** SeedVault.setProtection: null when the seed is where the mode says. */
    val setProtection: (mode: String) -> String? = { "the seed could not be read" },
    /** SeedVault.wordsForScreen: the words for seedShow's own screen, never the page. */
    val wordsForScreen: () -> SeedVault.Answer = { SeedVault.Answer.Failed("there is no vault") },
    /** SeedVault.unlock: the PIN lock's biometric opening a `device` seed; null when there is none to open. */
    val unlock: (reason: String, credential: Boolean) -> String? = { _, _ -> null },
)

/** An action's answer to the page: JSON text, or a plain error. Never both. */
data class SeedReply(val text: String?, val error: String?) {
    companion object {
        fun answer(text: String) = SeedReply(text, null)
        fun refuse(error: String) = SeedReply(null, error)
    }
}

/**
 * The seed, counter and lock-key actions after their input checks, apart from
 * the bridge: a port of SeedActions.swift's P1 half (status, create, migrate,
 * the counters, restore, P2PK). A reply is one of the agreed JSON objects or a
 * fixed message: no words, and no seed, ever (S2).
 */
object SeedActions {
    data class CounterRequest(val keysetId: String, val version: Int, val start: Long, val count: Int)
    data class RestoreRequest(val range: CounterRequest, val candidate: String?)
    data class AdvanceRequest(val keysetId: String, val next: Long)
    data class P2PKRange(val start: Long, val count: Int)

    class Refusal(message: String) : Exception(message)

    fun json(obj: Map<String, Any?>): String = Json.write(obj)

    fun countersReply(counters: Map<String, Long>): String = json(mapOf("counters" to counters))

    fun problem(error: Throwable): String = when (error) {
        is Refusal -> error.message ?: "refused"
        is CounterStore.Failure -> error.text
        is SeedCandidates.Failure -> error.message ?: "unknown candidate"
        // an index no window reaches, and one past 2^31, are the same answer
        is P2PK.Failure.BadIndex -> "outside the lock-key window"
        // an invalid BIP-32 child or r = 0, about 1 in 2^127, never a different key instead
        else -> "the secrets could not be derived"
    }

    private fun readSavedSeed(env: SeedEnvironment): NUT13.Seed = when (val s = env.savedSeed()) {
        is SeedVault.SecretsSeed.Found -> s.seed
        SeedVault.SecretsSeed.Absent -> throw Refusal("no seed")
        SeedVault.SecretsSeed.Failed -> throw Refusal("the seed could not be read")
    }

    // MARK: Seed status and creation

    /** seedStatus { quiet } → {"exists": true|false}. Quiet reads nothing (no prompt, ever). */
    fun status(env: SeedEnvironment, quiet: Boolean = false): SeedReply {
        val noPasscode = !env.passcodeSet()
        fun exists(e: Boolean) = SeedReply.answer(json(if (noPasscode) mapOf("exists" to e, "passcode" to false) else mapOf("exists" to e)))
        if (quiet) {
            return when (env.seedPresent()) {
                true -> {
                    env.closeMigrationWindow("a seed is saved")
                    exists(true)
                }
                false -> exists(false)
                null -> SeedReply.refuse("the seed could not be read")
            }
        }
        return when (env.savedSeed()) {
            is SeedVault.SecretsSeed.Found -> {
                env.closeMigrationWindow("a seed is saved")
                exists(true)
            }
            SeedVault.SecretsSeed.Absent -> exists(false)
            SeedVault.SecretsSeed.Failed -> SeedReply.refuse("the seed could not be read")
        }
    }

    /** seedCreate {} → {"created": true}, or "a seed already exists". Never writes over a saved seed. */
    fun create(env: SeedEnvironment): SeedReply {
        when (env.savedSeed()) {
            is SeedVault.SecretsSeed.Found -> return SeedReply.refuse("a seed already exists")
            SeedVault.SecretsSeed.Failed -> return SeedReply.refuse("the seed could not be read")
            SeedVault.SecretsSeed.Absent -> {}
        }
        val wordlist = env.wordlist() ?: return SeedReply.refuse("the wordlist could not be checked")
        // counters with no seed beside them belong to a seed that is gone; the new one starts from zero
        val leftover = try {
            env.counters.snapshot().isNotEmpty()
        } catch (e: Exception) {
            true
        }
        if (leftover) {
            try {
                env.counters.moveAside()
            } catch (e: Exception) {
                return SeedReply.refuse(problem(e))
            }
        }
        val words = try {
            env.generate(wordlist)
        } catch (e: Exception) {
            return SeedReply.refuse("the seed could not be made")
        }
        env.write(words)?.let { return SeedReply.refuse(it) }
        Log.line("[foxy] seed: made on the phone and saved")
        env.closeMigrationWindow("a seed was made on the phone")
        return SeedReply.answer(json(mapOf("created" to true)))
    }

    // MARK: Moving words the page still holds

    /** The decision, apart from the vault: never over a different seed, compared either way, written only with the window open. */
    fun decideMigration(
        phrase: String,
        windowOpen: Boolean,
        saved: SeedVault.SecretsSeed,
        write: (String) -> String?,
    ): SeedMigrationWindow.Outcome {
        val compared = when (saved) {
            SeedVault.SecretsSeed.Failed -> SeedMigrationWindow.Saved.Failed
            SeedVault.SecretsSeed.Absent -> SeedMigrationWindow.Saved.Absent
            is SeedVault.SecretsSeed.Found -> {
                val typed = try {
                    NUT13.seed(phrase)
                } catch (e: NUT13.Failure) {
                    return SeedMigrationWindow.Outcome.Refused("the words could not be read")
                }
                try {
                    SeedMigrationWindow.Saved.Found(saved.seed.sameBytes(typed))
                } finally {
                    typed.wipe()
                }
            }
        }
        return when (val step = SeedMigrationWindow.step(windowOpen, compared)) {
            is SeedMigrationWindow.Step.Answer -> {
                if (step.outcome == SeedMigrationWindow.Outcome.Different) {
                    Log.line("[foxy] seed migrate: the page held words of a different seed than the saved one; nothing was changed")
                }
                step.outcome
            }
            SeedMigrationWindow.Step.Write -> {
                val problem = write(phrase) ?: return SeedMigrationWindow.Outcome.Migrated
                // a seed saved between the read and the write: still the different-seed answer
                if (problem.startsWith("A different seed")) {
                    Log.line("[foxy] seed migrate: a different seed was saved meanwhile; nothing was changed")
                    SeedMigrationWindow.Outcome.Different
                } else {
                    SeedMigrationWindow.Outcome.Refused(problem)
                }
            }
        }
    }

    fun migrateReply(outcome: SeedMigrationWindow.Outcome): SeedReply = when (outcome) {
        SeedMigrationWindow.Outcome.Migrated -> SeedReply.answer(json(mapOf("migrated" to true)))
        SeedMigrationWindow.Outcome.Same -> SeedReply.answer(json(mapOf("migrated" to false, "same" to true)))
        SeedMigrationWindow.Outcome.Different -> SeedReply.answer(json(mapOf("migrated" to false, "different" to true)))
        is SeedMigrationWindow.Outcome.Refused -> SeedReply.refuse(outcome.why)
    }

    /** seedMigrate {words}, checked → migrated, same or different. The counters are left alone. */
    fun migrate(phrase: String, env: SeedEnvironment): SeedReply {
        val outcome = decideMigration(phrase, env.migrationWindowOpen(), env.savedSeed()) { env.write(it) }
        if (outcome == SeedMigrationWindow.Outcome.Migrated) Log.line("[foxy] seed migrate: the page's old words are now the saved seed")
        if (SeedMigrationWindow.closes(outcome)) env.closeMigrationWindow("seedMigrate answered")
        return migrateReply(outcome)
    }

    // MARK: Counters

    fun countersImport(incoming: Map<String, Long>, env: SeedEnvironment): SeedReply = try {
        SeedReply.answer(countersReply(env.counters.importOnce(incoming, env.flags)))
    } catch (e: Exception) {
        SeedReply.refuse(problem(e))
    }

    fun counterSnapshot(env: SeedEnvironment): SeedReply = try {
        SeedReply.answer(countersReply(env.counters.snapshot()))
    } catch (e: Exception) {
        SeedReply.refuse(problem(e))
    }

    fun counterAdvance(request: AdvanceRequest, env: SeedEnvironment): SeedReply = try {
        val stored = env.counters.advance(request.keysetId, request.next)
        SeedReply.answer(json(mapOf("keysetId" to request.keysetId, "next" to stored)))
    } catch (e: Exception) {
        SeedReply.refuse(problem(e))
    }

    /** counterReserve {keysetId, count}: `next ..< next + count`, written before it is answered. */
    fun counterReserve(request: CounterRequest, env: SeedEnvironment): SeedReply = try {
        if (request.count <= 0) {
            // a peek: no seed read, nothing moved
            val next = env.counters.reserve(request.keysetId, request.version, 0)
            SeedReply.answer(NUT13.reply(request.keysetId, next, emptyList()))
        } else {
            // the seed before the counters: without one, none are used up
            val seed = readSavedSeed(env)
            val start = env.counters.reserve(request.keysetId, request.version, request.count)
            val pairs = NUT13.derive(seed, request.keysetId, start, request.count)
            SeedReply.answer(NUT13.reply(request.keysetId, start, pairs))
        }
    } catch (e: Exception) {
        SeedReply.refuse(problem(e))
    }

    /** counterReserveAt {keysetId, start, count}: exactly that range, checked before the seed is read. */
    fun counterReserveAt(request: CounterRequest, env: SeedEnvironment): SeedReply = try {
        val current = env.counters.next(request.keysetId)
        if (current > request.start) throw CounterStore.Failure.AlreadyIssued()
        if (CounterRules.reserveAt(current, request.start) != CounterRules.Move.ALLOWED) throw CounterStore.Failure.TooFarAhead()
        val seed = readSavedSeed(env)
        env.counters.reserveAt(request.keysetId, request.version, request.start, request.count)
        val pairs = NUT13.derive(seed, request.keysetId, request.start, request.count)
        SeedReply.answer(NUT13.reply(request.keysetId, request.start, pairs))
    } catch (e: Exception) {
        SeedReply.refuse(problem(e))
    }

    // MARK: Restore

    /** restoreSecrets: counters unchanged; the saved seed only inside the restore window. */
    fun restoreSecrets(request: RestoreRequest, env: SeedEnvironment): SeedReply {
        val range = request.range
        return try {
            val seed = if (request.candidate != null) {
                env.candidates.serve(request.candidate, range.keysetId, range.start, range.count)
            } else {
                val next = env.counters.next(range.keysetId)
                if (!RestoreWindow.savedSeedAllowed(range.start, range.count, next)) throw Refusal("outside the restore window")
                readSavedSeed(env)
            }
            val pairs = NUT13.derive(seed, range.keysetId, range.start, range.count)
            env.counters.noteServed(range.keysetId, range.start + range.count)
            SeedReply.answer(NUT13.reply(range.keysetId, range.start, pairs))
        } catch (e: Exception) {
            SeedReply.refuse(problem(e))
        }
    }

    // MARK: The keys a payment request locks ecash to

    fun p2pkReserve(count: Int, env: SeedEnvironment): SeedReply = try {
        if (count <= 0) {
            val next = env.counters.p2pkReserve(0)
            SeedReply.answer(P2PK.pubkeysReply(next, next, emptyList()))
        } else {
            // the seed before the index: without one, no index is used up
            val seed = readSavedSeed(env)
            val start = env.counters.p2pkReserve(count)
            val keys = P2PK.publicKeys(seed, start, count)
            SeedReply.answer(P2PK.pubkeysReply(start, start + count, keys))
        }
    } catch (e: Exception) {
        SeedReply.refuse(problem(e))
    }

    /** p2pkPubkeys: moving nothing, and not stopped by an index file that cannot be read. */
    fun p2pkPubkeys(range: P2PKRange, env: SeedEnvironment): SeedReply = try {
        val seed = readSavedSeed(env)
        val keys = P2PK.publicKeys(seed, range.start, range.count)
        val next = try {
            env.counters.p2pkNext()
        } catch (e: Exception) {
            0L
        }
        env.counters.noteP2PKServed(range.start + range.count)
        SeedReply.answer(P2PK.pubkeysReply(range.start, next, keys))
    } catch (e: Exception) {
        SeedReply.refuse(problem(e))
    }

    /** p2pkKey: the private half, inside the window; a scan's half of it needs no file. */
    fun p2pkKey(index: Long, env: SeedEnvironment): SeedReply = try {
        val served = env.counters.highestP2PKServed()
        if (!P2PK.keyAllowed(index, 0, served)) {
            val next = env.counters.p2pkNext()
            if (!P2PK.keyAllowed(index, next, served)) throw Refusal("outside the lock-key window")
        }
        val seed = readSavedSeed(env)
        val pair = P2PK.key(seed, index)
        try {
            SeedReply.answer(P2PK.keyReply(pair))
        } finally {
            pair.wipe()
        }
    } catch (e: Exception) {
        SeedReply.refuse(problem(e))
    }

    // MARK: Adopting typed words, and wiping (P3)

    /**
     * seedAdopt {candidate} → {"adopted": false, "same": true} for the saved
     * seed's words; otherwise, where a different seed is saved,
     * `confirmReplace` (the Replace alert, then the device owner), then
     * {"adopted": true} or "Nothing was changed." (SeedActions.swift:517-563).
     *
     * Either way each counter is raised to at least 1000 short of the end the
     * typed words were served for its keyset (review M2): the end of the last
     * batch of a scan that held a signature. The candidate is kept, so scans
     * still running with it can finish (M6).
     */
    fun adopt(candidate: String, env: SeedEnvironment, confirmReplace: () -> Boolean): SeedReply {
        val words = env.candidates.phrase(candidate) ?: return SeedReply.refuse("unknown candidate")
        val typed = try {
            env.candidates.seed(candidate)
        } catch (e: SeedCandidates.Failure) {
            return SeedReply.refuse("unknown candidate")
        }
        val served = RestoreWindow.adoptedCounters(env.candidates.servedEnds(candidate))
        when (val saved = env.savedSeed()) {
            SeedVault.SecretsSeed.Failed -> return SeedReply.refuse("the seed could not be read")
            is SeedVault.SecretsSeed.Found -> if (saved.seed.sameBytes(typed)) {
                try {
                    env.counters.raise(served)
                } catch (e: Exception) {
                    return SeedReply.refuse(problem(e))
                }
                return SeedReply.answer(json(mapOf("adopted" to false, "same" to true)))
            }
            SeedVault.SecretsSeed.Absent -> {}
        }
        // SeedVault.write asks only when a different seed is saved; a page script can ask for this, not answer it
        var declined = false
        val written = env.writeReplacing(words, candidate) {
            val yes = confirmReplace()
            declined = !yes
            yes
        }
        if (declined) return SeedReply.refuse("Nothing was changed.")
        if (written != null) return SeedReply.refuse(written)
        env.closeMigrationWindow("typed words were adopted")
        try {
            env.counters.moveAside()
        } catch (e: Exception) {
            return SeedReply.refuse("the seed was replaced, but its counters could not be set aside")
        }
        try {
            env.counters.raise(served)
        } catch (e: Exception) {
            return SeedReply.refuse("the seed was replaced, but its counters could not be raised")
        }
        Log.line("[foxy] seed: replaced by typed words; the old counters set aside, the new ones raised to what was served")
        return SeedReply.answer(json(mapOf("adopted" to true)))
    }

    /** seedCandidateForget {candidate} → {"forgotten": true}, known or not. */
    fun candidateForget(candidate: String, env: SeedEnvironment): SeedReply {
        env.candidates.forget(candidate)
        return SeedReply.answer(json(mapOf("forgotten" to true)))
    }

    /**
     * seedWipe, after both yeses: the seed deleted and checked gone, then its
     * counters set aside, every candidate dropped and a new seed made, in that
     * order (SeedActions.swift:586-600). A seed that would not go stops it
     * before anything else moves.
     */
    fun wipe(env: SeedEnvironment): SeedReply {
        if (!env.delete()) return SeedReply.refuse("the seed could not be deleted")
        env.candidates.forgetAll()
        try {
            env.counters.moveAside()
        } catch (e: Exception) {
            return SeedReply.refuse("the seed was deleted, but its counters could not be set aside")
        }
        val made = create(env)
        made.error?.let { return SeedReply.refuse("the seed was deleted, but a new one was not made: $it") }
        return SeedReply.answer(json(mapOf("wiped" to true, "created" to true)))
    }

    // MARK: What stands in front of the seed (P3)

    /** seedProtection {} → {"mode": "device"|"none", "passcode": true|false}. Asks the person for nothing. */
    fun protection(env: SeedEnvironment): SeedReply =
        SeedReply.answer(json(mapOf("mode" to env.protection(), "passcode" to env.passcodeSet())))

    /** seedProtect {mode}, checked → {"mode": …}: the SECURE FOXY card's answer. */
    fun setProtection(env: SeedEnvironment, mode: String): SeedReply {
        env.setProtection(mode)?.let { return SeedReply.refuse(it) }
        return SeedReply.answer(json(mapOf("mode" to env.protection())))
    }
}
