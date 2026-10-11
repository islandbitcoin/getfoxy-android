package io.islandbitcoin.foxy.bridge

import io.islandbitcoin.foxy.debug.Log
import io.islandbitcoin.foxy.keychain.BIP39
import io.islandbitcoin.foxy.keychain.CounterRules
import io.islandbitcoin.foxy.keychain.NUT13
import io.islandbitcoin.foxy.keychain.P2PK
import io.islandbitcoin.foxy.keychain.SeedActions
import io.islandbitcoin.foxy.keychain.SeedEnvironment
import io.islandbitcoin.foxy.keychain.SeedReply
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The seed, counter and lock-key actions on the bridge (Foxy/Bridge/NativeSeedBridge.swift):
 * the argument checks, ported with their tests, then the action on ONE
 * single-thread executor, which is what makes a reservation atomic and keeps a
 * seed change from interleaving with a reservation. Every reservation is
 * written before its reply (CounterStore). One seed change at a time.
 *
 * The executor is made before the message listener is registered (MainActivity),
 * so no message can arrive before there is somewhere to run it.
 */
class SeedBridge(
    private val environment: () -> SeedEnvironment,
    private val executor: Executor,
    private val resolve: (id: String, text: String?, error: String?) -> Unit,
) {
    sealed class Check<out T> {
        data class Ok<T>(val value: T) : Check<T>()
        data class Refuse(val why: String) : Check<Nothing>()
    }

    companion object {
        /** The P1 seed actions: every one answers in every build. */
        val ACTIONS = setOf(
            "seedStatus", "seedCreate", "seedMigrate",
            "countersImport", "counterReserve", "counterReserveAt", "counterAdvance", "counterSnapshot",
            "restoreSecrets",
            "p2pkReserve", "p2pkPubkeys", "p2pkKey",
        )

        const val LARGEST_SECRETS_BATCH = 1000
        const val MOST_MIGRATE_BYTES = 1000
        const val BUSY = "Another change to the seed is still waiting. Nothing was changed."

        /**
         * A JavaScript number that is a whole number JavaScript holds exactly. Not a
         * boolean, and not a string: [Json] makes every number a Double and every
         * boolean a Boolean, as WebKit's NSNumber told them apart.
         */
        fun exactInteger(value: Any?): Long? {
            val d = value as? Double ?: return null
            if (!d.isFinite() || d != Math.floor(d) || Math.abs(d) > 9_007_199_254_740_991.0) return null
            return d.toLong()
        }

        /** A hex keyset id, 00 and 16 characters or 01 and 66, lowercased, with its version. */
        fun keyset(value: Any?): Pair<String, Int>? {
            val id = value as? String ?: return null
            val version = NUT13.keysetVersion(id) ?: return null
            return id.lowercase() to version
        }

        /** A counter from the page: a whole number from 0 to 2^53 − 1. */
        fun counterValue(value: Any?): Long? {
            val n = exactInteger(value) ?: return null
            return if (n >= 0) n else null
        }

        private fun count(value: Any?, range: IntRange): Int? {
            val n = exactInteger(value) ?: return null
            return if (n >= range.first && n <= range.last) n.toInt() else null
        }

        /** counterReserve {keysetId, count}: count 0 (a peek) to 1000. */
        fun counterReserveCheck(body: Map<String, Any?>): Check<SeedActions.CounterRequest> {
            val k = keyset(body["keysetId"]) ?: return Check.Refuse("bad request")
            val n = count(body["count"], 0..LARGEST_SECRETS_BATCH) ?: return Check.Refuse("bad request")
            return Check.Ok(SeedActions.CounterRequest(k.first, k.second, 0, n))
        }

        /** counterReserveAt's and restoreSecrets' range: count 1 to 1000, every counter one the version has. */
        fun counterRangeCheck(body: Map<String, Any?>): Check<SeedActions.CounterRequest> {
            val k = keyset(body["keysetId"]) ?: return Check.Refuse("bad request")
            val start = counterValue(body["start"]) ?: return Check.Refuse("bad request")
            val n = count(body["count"], 1..LARGEST_SECRETS_BATCH) ?: return Check.Refuse("bad request")
            if (!NUT13.countersFit(k.second, start, n)) return Check.Refuse("bad request")
            return Check.Ok(SeedActions.CounterRequest(k.first, k.second, start, n))
        }

        fun counterReserveAtCheck(body: Map<String, Any?>) = counterRangeCheck(body)

        /** restoreSecrets {keysetId, start, count, candidate?}; a null candidate is none. */
        fun restoreSecretsCheck(body: Map<String, Any?>): Check<SeedActions.RestoreRequest> {
            val range = when (val r = counterRangeCheck(body)) {
                is Check.Refuse -> return r
                is Check.Ok -> r.value
            }
            val candidate = body["candidate"]
            return when {
                candidate == null -> Check.Ok(SeedActions.RestoreRequest(range, null))
                candidate is String && candidate.isNotEmpty() && candidate.toByteArray(Charsets.UTF_8).size <= 64 ->
                    Check.Ok(SeedActions.RestoreRequest(range, candidate))
                else -> Check.Refuse("bad request")
            }
        }

        /** counterAdvance {keysetId, next}. */
        fun counterAdvanceCheck(body: Map<String, Any?>): Check<SeedActions.AdvanceRequest> {
            val k = keyset(body["keysetId"]) ?: return Check.Refuse("bad request")
            val next = counterValue(body["next"]) ?: return Check.Refuse("bad request")
            return Check.Ok(SeedActions.AdvanceRequest(k.first, next))
        }

        /** p2pkReserve {count}: 0 (a peek) to 64. */
        fun p2pkReserveCheck(body: Map<String, Any?>): Check<Int> {
            val n = count(body["count"], 0..P2PK.MOST_RESERVED) ?: return Check.Refuse("bad request")
            return Check.Ok(n)
        }

        /** p2pkPubkeys {start, count}: count 1 to 300, none past 20,000. */
        fun p2pkPubkeysCheck(body: Map<String, Any?>): Check<SeedActions.P2PKRange> {
            val start = counterValue(body["start"]) ?: return Check.Refuse("bad request")
            val n = count(body["count"], 1..P2PK.MOST_SCANNED) ?: return Check.Refuse("bad request")
            if (!P2PK.scanFits(start, n)) return Check.Refuse("bad request")
            return Check.Ok(SeedActions.P2PKRange(start, n))
        }

        /** p2pkKey {index}: an index a normal BIP-32 child has. */
        fun p2pkKeyCheck(body: Map<String, Any?>): Check<Long> {
            val index = counterValue(body["index"]) ?: return Check.Refuse("bad request")
            if (index > P2PK.LAST_INDEX) return Check.Refuse("bad request")
            return Check.Ok(index)
        }

        /** countersImport {counters: {id: n}}: every entry good, or the whole import refused. */
        fun countersImportCheck(body: Map<String, Any?>): Check<Map<String, Long>> {
            val given = body["counters"] as? Map<*, *> ?: return Check.Refuse("bad request")
            if (given.size > CounterRules.MOST_KEYSETS) return Check.Refuse("bad request")
            val out = HashMap<String, Long>()
            for ((id, value) in given) {
                val k = keyset(id) ?: return Check.Refuse("bad request")
                val n = counterValue(value) ?: return Check.Refuse("bad request")
                out[k.first] = maxOf(out[k.first] ?: 0, n)
            }
            return Check.Ok(out)
        }

        /** seedMigrate {words}: a BIP-39 phrase, one string or an array, joined with single spaces. */
        fun seedMigrateCheck(body: Map<String, Any?>, wordlist: List<String>): Check<String> {
            val words: List<String> = when (val given = body["words"]) {
                is String -> {
                    if (given.toByteArray(Charsets.UTF_8).size > MOST_MIGRATE_BYTES) return Check.Refuse("bad request")
                    given.split(Regex("(?U)\\s+")).filter { it.isNotEmpty() }.map { it.lowercase() }
                }
                is List<*> -> {
                    if (given.size > 24) return Check.Refuse("bad request")
                    given.map { (it as? String ?: return Check.Refuse("bad request")).trim().lowercase() }
                }
                else -> return Check.Refuse("bad request")
            }
            if (!BIP39.isValid(words, wordlist)) return Check.Refuse("bad request")
            return Check.Ok(words.joinToString(" "))
        }
    }

    private val seedChanging = AtomicBoolean(false)

    fun handles(action: String) = action in ACTIONS

    fun handle(action: String, id: String, body: Map<String, Any?>) {
        when (action) {
            "seedStatus" -> {
                val quiet = body["quiet"] as? Boolean ?: false
                onSeedQueue(id) { SeedActions.status(it, quiet) }
            }
            "seedCreate" -> {
                if (!beginSeedChange(id)) return
                executor.execute {
                    val made = guarded { SeedActions.create(environment()) }
                    endSeedChange(id, made)
                }
            }
            "seedMigrate" -> {
                val wordlist = environment().wordlist() ?: return resolve(id, null, "the wordlist could not be checked")
                val phrase = when (val c = seedMigrateCheck(body, wordlist)) {
                    is Check.Refuse -> return resolve(id, null, c.why)
                    is Check.Ok -> c.value
                }
                if (!beginSeedChange(id)) return
                executor.execute {
                    val reply = guarded { SeedActions.migrate(phrase, environment()) }
                    endSeedChange(id, reply)
                }
            }
            "countersImport" -> run(id, countersImportCheck(body)) { r, env -> SeedActions.countersImport(r, env) }
            "counterSnapshot" -> onSeedQueue(id) { SeedActions.counterSnapshot(it) }
            "counterAdvance" -> run(id, counterAdvanceCheck(body)) { r, env -> SeedActions.counterAdvance(r, env) }
            "counterReserve" -> run(id, counterReserveCheck(body)) { r, env -> SeedActions.counterReserve(r, env) }
            "counterReserveAt" -> run(id, counterReserveAtCheck(body)) { r, env -> SeedActions.counterReserveAt(r, env) }
            "restoreSecrets" -> run(id, restoreSecretsCheck(body)) { r, env -> SeedActions.restoreSecrets(r, env) }
            "p2pkReserve" -> run(id, p2pkReserveCheck(body)) { r, env -> SeedActions.p2pkReserve(r, env) }
            "p2pkPubkeys" -> run(id, p2pkPubkeysCheck(body)) { r, env -> SeedActions.p2pkPubkeys(r, env) }
            "p2pkKey" -> run(id, p2pkKeyCheck(body)) { r, env -> SeedActions.p2pkKey(r, env) }
            else -> resolve(id, null, "Unknown action: $action")
        }
    }

    private fun <T> run(id: String, check: Check<T>, action: (T, SeedEnvironment) -> SeedReply) {
        when (check) {
            is Check.Refuse -> resolve(id, null, check.why)
            is Check.Ok -> onSeedQueue(id) { action(check.value, it) }
        }
    }

    private fun onSeedQueue(id: String, action: (SeedEnvironment) -> SeedReply) {
        executor.execute {
            val reply = guarded { action(environment()) }
            resolve(id, reply.text, reply.error)
        }
    }

    /** No action may leave a request unanswered: anything thrown is a fixed refusal, never its message. */
    private fun guarded(action: () -> SeedReply): SeedReply = try {
        action()
    } catch (e: Throwable) {
        Log.line("[foxy] seed action failed (${e.javaClass.simpleName})")
        SeedReply.refuse("the seed could not be read")
    }

    /** One seed change at a time: a second, while one waits, is refused rather than stacked. */
    private fun beginSeedChange(id: String): Boolean {
        if (!seedChanging.compareAndSet(false, true)) {
            Log.line("[foxy] seed change refused: another is still waiting")
            resolve(id, null, BUSY)
            return false
        }
        return true
    }

    /** The change is over: the next may begin, and then the page hears. */
    private fun endSeedChange(id: String, reply: SeedReply) {
        seedChanging.set(false)
        resolve(id, reply.text, reply.error)
    }
}
