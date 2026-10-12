package io.islandbitcoin.foxy.bridge

import io.islandbitcoin.foxy.debug.Log
import io.islandbitcoin.foxy.keychain.BIP39
import io.islandbitcoin.foxy.keychain.CardOwner
import io.islandbitcoin.foxy.keychain.CounterRules
import io.islandbitcoin.foxy.keychain.NUT13
import io.islandbitcoin.foxy.keychain.P2PK
import io.islandbitcoin.foxy.keychain.SeedActions
import io.islandbitcoin.foxy.keychain.SeedEnvironment
import io.islandbitcoin.foxy.keychain.SeedCandidates
import io.islandbitcoin.foxy.keychain.SeedReply
import io.islandbitcoin.foxy.keychain.SeedVault
import io.islandbitcoin.foxy.seed.RetryPause
import io.islandbitcoin.foxy.seed.ScreenQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The seed, counter and lock-key actions on the bridge (Foxy/Bridge/NativeSeedBridge.swift):
 * the argument checks, ported with their tests, then the action on ONE
 * single-thread executor, which is what makes a reservation atomic and keeps a
 * seed change from interleaving with a reservation. Every reservation is
 * written before its reply (CounterStore). One seed change at a time.
 *
 * The executor is made before the message listener is registered (MainActivity),
 * so no message can arrive before there is somewhere to run it.
 *
 * P3 adds the seed's own screens (seedShow, seedEnter: one at a time, none while
 * a native question is up, none within 10 s of a read of the words that
 * failed), adopting typed words and wiping (each behind a native question and
 * then the device owner, one seed change at a time), and the protection in
 * front of the seed (NativeSeedBridge.swift:260-270, 425-604).
 */
class SeedBridge(
    private val environment: () -> SeedEnvironment,
    private val executor: Executor,
    private val resolve: (id: String, text: String?, error: String?) -> Unit,
    private val host: PageHost? = null,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
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

        /** The P3 seed actions: the screens, adopting and wiping, the protection. */
        val P3_ACTIONS = setOf(
            "seedShow", "seedEnter", "seedAdopt", "seedCandidateForget", "seedWipe", "seedProtection", "seedProtect",
        )

        /**
         * A card's owner key (ENG-666): its public half, and signatures for a fixed list
         * of labels ([CardOwner]). Seed actions, on the seed executor, as iOS has them
         * in `nativeSeedActions`; served from P3 on. The card's time needs no seed and
         * is FoxyBridge's (`cardTime`).
         */
        val CARD_ACTIONS = setOf("cardOwnerKey", "cardOwnerSign")

        const val BUSY_SCREEN = "a seed screen is already open"
        const val PAUSED = "try again in a moment"
        const val NO_PRESENTER = "Nothing to present the screen from."

        /** How long seedAdopt waits for a yes to replacing the seed; no answer by then is no (audit I3). */
        const val SEED_ANSWER_WAIT_MS = 120_000L

        /** After a read of the words that failed, seedShow is refused this long (review L1). */
        const val SHOW_PAUSE_MS = 10_000L

        /**
         * The two seed alerts, word for word as iOS has them (FoxyBridge.swift:177-185),
         * "iPhone" made "phone". seedAdopt asks the first before typed words replace a
         * saved seed, seedWipe the second before the seed is deleted and a new one made.
         */
        const val REPLACE_TITLE = "Replace this wallet's seed?"
        const val REPLACE_MESSAGE = "Only replace it with words you wrote down yourself. Whoever gave you these words can take everything this wallet receives."
        const val DELETE_TITLE = "Delete this wallet's seed?"
        const val DELETE_MESSAGE = "The twelve words are removed from this phone, with everything else Foxy holds. Only a written copy of them can bring the money back."

        /** What Android says when it asks for the device owner after the alert's yes (M8). */
        const val REPLACE_REASON = "Replace this wallet's seed"
        const val DELETE_REASON = "Delete this wallet's seed"

        /**
         * A seed alert's message, and on a phone with no screen lock a sentence
         * saying nothing else will be asked: there is nothing to ask there, so the
         * change goes ahead on the alert alone (a deliberate decision, as iOS).
         */
        fun noPasscodeNote(message: String, what: String, passcodeSet: Boolean): String =
            if (passcodeSet) message else "$message This phone has no screen lock, so nothing else will be asked before the seed is $what."

        /** seedAdopt and seedCandidateForget {candidate}: a non-empty string of at most 64 bytes. */
        fun candidateCheck(body: Map<String, Any?>): Check<String> {
            val candidate = body["candidate"] as? String ?: return Check.Refuse("bad request")
            if (candidate.isEmpty() || candidate.toByteArray(Charsets.UTF_8).size > 64) return Check.Refuse("bad request")
            return Check.Ok(candidate)
        }

        /** seedProtect {mode}: "device" or "none". */
        fun protectCheck(body: Map<String, Any?>): Check<String> = when (val mode = body["mode"]) {
            SeedVault.DEVICE, SeedVault.NONE -> Check.Ok(mode as String)
            else -> Check.Refuse("bad request")
        }

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

        /**
         * cardOwnerKey {key}: a card's compressed public key, 66 hex characters (either
         * case) for 33 bytes that start 02 or 03. Nothing is read until it has passed
         * (NativeSeedBridge.swift `cardOwnerKeyCheck`).
         */
        fun cardOwnerKeyCheck(body: Map<String, Any?>): Check<ByteArray> {
            val key = CardOwner.key(body["key"]) ?: return Check.Refuse("bad request")
            return Check.Ok(key)
        }

        /**
         * cardOwnerSign {key, label, nonce, value}: the key as above; the label exactly one
         * of change-pin, set-limit, set-owner, set-card, load and reset; the nonce 32 hex
         * characters; the value hex of the shape that label takes (empty for load and
         * reset). [CardOwner.signRequest] holds the rules.
         */
        fun cardOwnerSignCheck(body: Map<String, Any?>): Check<CardOwner.SignRequest> {
            val request = CardOwner.signRequest(body) ?: return Check.Refuse("bad request")
            return Check.Ok(request)
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

    /**
     * The words screen's pause after a failed read (L1). One bridge serves a
     * WebView for its life, and a page loaded again in it keeps the bridge,
     * so a script cannot reset the pause by reloading. Main thread.
     */
    private val showPause = RetryPause(SHOW_PAUSE_MS)

    /** The seed screens, one at a time, none while a native question is up. Main thread. */
    private val screens = ScreenQueue({ host?.alertShowing() ?: false }, { ms, r -> host?.later(ms) { r() } })

    fun handles(action: String) = action in ACTIONS || action in P3_ACTIONS || action in CARD_ACTIONS

    /** Foxy went away, the page went, or the seed changed: every seed screen closes, and those waiting are answered as closed. Main thread. */
    fun closeScreens() {
        screens.cancelAll()
        host?.closeSeedScreen()
    }

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
            "seedShow" -> seedShow(id, body["verify"] as? Boolean ?: false)
            "seedEnter" -> seedEnter(id)
            "seedAdopt" -> seedAdopt(id, body)
            "seedCandidateForget" -> when (val c = candidateCheck(body)) {
                is Check.Refuse -> resolve(id, null, c.why)
                is Check.Ok -> {
                    val reply = SeedActions.candidateForget(c.value, environment())
                    resolve(id, reply.text, reply.error)
                }
            }
            "seedWipe" -> seedWipe(id)
            "seedProtection" -> onSeedQueue(id) { SeedActions.protection(it) }
            "seedProtect" -> run(id, protectCheck(body)) { mode, env -> SeedActions.setProtection(env, mode) }
            "cardOwnerKey" -> run(id, cardOwnerKeyCheck(body)) { key, env -> SeedActions.cardOwnerKey(key, env) }
            "cardOwnerSign" -> run(id, cardOwnerSignCheck(body)) { request, env -> SeedActions.cardOwnerSign(request, env) }
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

    // MARK: The seed's own screens (P3)

    private fun verified(v: Boolean, delete: Boolean? = null, revealed: Boolean? = null): String =
        SeedActions.json(buildMap {
            put("verified", v)
            if (delete != null) put("delete", delete)
            if (revealed != null) put("revealed", revealed)
        })

    /**
     * {verify} → {"verified", "delete", "revealed"} once the screen is closed
     * (NativeSeedBridge.swift `handleSeedShow`). The words are read on the seed
     * executor, under the device protection's prompt, and go only to the
     * screen. Refused while a seed screen waits or is open, and for 10 s after
     * a read that failed, so a script cannot line up prompts (L1).
     */
    private fun seedShow(id: String, verify: Boolean) {
        val h = host ?: return resolve(id, null, NO_PRESENTER)
        if (screens.busy) return resolve(id, null, BUSY_SCREEN)
        if (showPause.refuses(clock())) return resolve(id, null, PAUSED)
        screens.enqueue(onCancel = { resolve(id, verified(false), null) }) { finished ->
            val turn = screens.generation
            // on the queue every seed change runs on, so the read never races a replace or a wipe (L4)
            executor.execute {
                val saved = try {
                    environment().wordsForScreen()
                } catch (e: Throwable) {
                    Log.line("[foxy] seed screen: the words could not be read (${e.javaClass.simpleName})")
                    SeedVault.Answer.Failed("the seed could not be read")
                }
                h.post {
                    // closed while it read (Foxy put away, the page gone, a seed change): no screen
                    if (screens.generation != turn) {
                        resolve(id, verified(false), null)
                        return@post finished()
                    }
                    val words = when (saved) {
                        SeedVault.Answer.Absent -> {
                            resolve(id, null, "no seed")
                            return@post finished()
                        }
                        is SeedVault.Answer.Failed -> {
                            showPause.start(clock())
                            resolve(id, null, "the seed could not be read")
                            return@post finished()
                        }
                        is SeedVault.Answer.Found -> saved.words.split(" ")
                    }
                    if (words.size !in setOf(12, 15, 18, 21, 24)) {
                        resolve(id, null, "the seed could not be read")
                        return@post finished()
                    }
                    val shown = h.showWords(words, verify) { done, deleteAsked, revealed ->
                        resolve(id, verified(done, deleteAsked, revealed), null)
                        finished()
                    }
                    if (!shown) {
                        resolve(id, null, NO_PRESENTER)
                        finished()
                    }
                }
            }
        }
    }

    /** {} → {"candidate": id} for a checked phrase, or "cancelled" (NativeSeedBridge.swift `handleSeedEnter`). */
    private fun seedEnter(id: String) {
        val h = host ?: return resolve(id, null, NO_PRESENTER)
        if (screens.busy) return resolve(id, null, BUSY_SCREEN)
        val env = environment()
        // refused before the screen, rather than after the words are typed
        if (env.candidates.count >= SeedCandidates.MOST) return resolve(id, null, "too many candidates")
        val wordlist = env.wordlist() ?: return resolve(id, null, "the wordlist could not be checked")
        screens.enqueue(onCancel = { resolve(id, null, "cancelled") }) { finished ->
            val shown = h.enterWords(wordlist) { phrase ->
                try {
                    if (phrase == null) return@enterWords resolve(id, null, "cancelled")
                    // checked on the screen; checked again before it is kept
                    if (!BIP39.isValid(phrase.split(" "), wordlist)) return@enterWords resolve(id, null, "the words could not be read")
                    val candidate = try {
                        environment().candidates.add(phrase)
                    } catch (e: Exception) {
                        return@enterWords resolve(id, null, SeedActions.problem(e))
                    }
                    resolve(id, SeedActions.json(mapOf("candidate" to candidate)), null)
                } finally {
                    finished()
                }
            }
            if (!shown) {
                resolve(id, null, NO_PRESENTER)
                finished()
            }
        }
    }

    // MARK: Adopting typed words, and wiping (P3)

    /**
     * {candidate} → {"adopted": false, "same": true} for the saved words;
     * otherwise the Replace alert (no answer in 120 s is no) and then the device
     * owner, then {"adopted": true} or "Nothing was changed.". On the seed
     * executor for the whole change, alert included: no counter is reserved
     * against either seed between the yes and the counters going aside.
     */
    private fun seedAdopt(id: String, body: Map<String, Any?>) {
        val candidate = when (val c = candidateCheck(body)) {
            is Check.Refuse -> return resolve(id, null, c.why)
            is Check.Ok -> c.value
        }
        if (candidate !in environment().candidates) return resolve(id, null, "unknown candidate")
        if (!beginSeedChange(id)) return
        executor.execute {
            val reply = guarded {
                val env = environment()
                SeedActions.adopt(candidate, env) {
                    val asked = Prompt(REPLACE_TITLE, noPasscodeNote(REPLACE_MESSAGE, "replaced", env.passcodeSet()), "Cancel", "Replace", destructive = true)
                    // a yes a pasted lure led someone to is not enough: the device owner, asked by Android (M8)
                    confirmAndWait(asked) && (host?.deviceOwnerApproves(REPLACE_REASON) ?: false)
                }
            }
            endSeedChange(id, reply)
        }
    }

    /**
     * {} → the Delete alert and then the device owner, then
     * {"wiped": true, "created": true}; no to either: "Nothing was erased."
     * (NativeSeedBridge.swift `handleSeedWipe`). The only delete the bridge
     * has, and it always makes a new seed: a delete with no new seed beside it
     * would let a script follow it with a seed of its own choosing.
     */
    private fun seedWipe(id: String) {
        val h = host ?: return resolve(id, null, "Nothing was erased.")
        if (!beginSeedChange(id)) return
        val asked = Prompt(DELETE_TITLE, noPasscodeNote(DELETE_MESSAGE, "deleted", environment().passcodeSet()), "Cancel", "Delete", destructive = true)
        h.ask(asked) { yes ->
            if (!yes) return@ask endSeedChange(id, SeedReply.refuse("Nothing was erased."))
            executor.execute {
                // the words are recoverable only from paper once they are gone: the device owner too, as for a replace
                if (!h.deviceOwnerApproves(DELETE_REASON)) return@execute endSeedChange(id, SeedReply.refuse("Nothing was erased."))
                val reply = guarded { SeedActions.wipe(environment()) }
                endSeedChange(id, reply)
            }
        }
    }

    /**
     * The Replace alert, from the seed executor, which waits for the answer
     * up to [SEED_ANSWER_WAIT_MS]; after that the answer is no and the alert
     * is taken down so a late tap cannot count (audit I3). Never on the main
     * thread, which draws it.
     */
    private fun confirmAndWait(prompt: Prompt): Boolean {
        val h = host ?: return false
        val answered = CountDownLatch(1)
        val yes = AtomicBoolean(false)
        val cancel = AtomicReference<(() -> Unit)?>(null)
        h.post {
            cancel.set(h.ask(prompt) { said ->
                yes.set(said)
                answered.countDown()
            })
        }
        if (!answered.await(SEED_ANSWER_WAIT_MS, TimeUnit.MILLISECONDS)) {
            h.post { cancel.get()?.invoke() }
            Log.line("[foxy] seed change: no answer in ${SEED_ANSWER_WAIT_MS / 1000}s, taken as no")
            return false
        }
        // which button, in the log: the answer carries no secret
        Log.line("[foxy] seed change: \"${prompt.title}\" answered ${if (yes.get()) prompt.yes else prompt.no}")
        return yes.get()
    }
}
