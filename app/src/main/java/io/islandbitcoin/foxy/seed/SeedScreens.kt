package io.islandbitcoin.foxy.seed

import io.islandbitcoin.foxy.bridge.ClipboardFilter
import io.islandbitcoin.foxy.keychain.BIP39
import java.security.SecureRandom

/*
 * The pure parts of Foxy's own seed screens (Foxy/Bridge/SeedScreens.swift,
 * "The pure parts"; FoxyTests/NativeSeedTests.swift and SeedScreenTests.swift),
 * apart from the Views so the JVM tests hold them: the verify quiz, the restore
 * screen's twelve cells, the one-at-a-time queue, and the pause after a read of
 * the words that failed.
 */

/** The verify quiz: the words scrambled, and tapped back in order. */
class SeedQuiz(val words: List<String>, random: (below: Int) -> Int = ::systemRandom) {
    sealed class Tap {
        object Right : Tap() {
            override fun toString() = "Right"
        }

        /** Not the next word: the quiz starts again, and `expected` is the word number that was due, from 1. */
        data class Wrong(val expected: Int) : Tap()

        object Done : Tap() {
            override fun toString() = "Done"
        }

        /** A tile already placed, a tile that does not exist, or a finished quiz. */
        object Ignored : Tap() {
            override fun toString() = "Ignored"
        }
    }

    /** The words in the order their tiles are drawn, fixed for a quiz. */
    val pool: List<String> = scramble(words, random)

    /** Per tile, whether it has been placed. */
    var used: BooleanArray = BooleanArray(words.size)
        private set
    val picked = ArrayList<String>()

    val isComplete: Boolean get() = words.isNotEmpty() && picked.size == words.size

    /** Tiles are tracked by place, not by word, so a phrase with a repeated word can still be finished. */
    fun tap(tile: Int): Tap {
        if (tile !in pool.indices || used[tile] || isComplete) return Tap.Ignored
        if (pool[tile] != words[picked.size]) {
            val expected = picked.size + 1
            picked.clear()
            used = BooleanArray(words.size)
            return Tap.Wrong(expected)
        }
        used[tile] = true
        picked.add(pool[tile])
        return if (isComplete) Tap.Done else Tap.Right
    }

    companion object {
        /**
         * The tiles' order: a Fisher–Yates shuffle of places, drawn afresh for
         * every quiz (review L3: a photo of tiles in a fixed order gave the
         * phrase back). Places are shuffled, not words, so a repeated word
         * stays two tiles.
         */
        fun scramble(words: List<String>, random: (below: Int) -> Int): List<String> {
            val out = words.toMutableList()
            var i = out.size - 1
            while (i > 0) {
                val bound = i + 1
                val j = Math.floorMod(random(bound), bound)
                val t = out[i]
                out[i] = out[j]
                out[j] = t
                i -= 1
            }
            return out
        }

        private val secure = SecureRandom()

        /** A number below `n` from the platform's CSPRNG (SecureRandom.nextInt has no modulo bias). */
        fun systemRandom(n: Int): Int = if (n <= 1) 0 else secure.nextInt(n)
    }
}

/** The restore screen's twelve cells. */
object SeedEntry {
    const val CELLS = 12

    /** The words in what was typed, as the page reads a cell: lowercased, and split at anything that is not a letter. */
    fun parts(typed: String): List<String> = ClipboardFilter.parts(typed)

    /** Cell `index` after `typed`. Several words, a pasted phrase, fill that cell and the ones after it. */
    fun apply(typed: String, index: Int, cells: List<String>): List<String> {
        val out = cells.toMutableList()
        if (index !in out.indices) return out
        val words = parts(typed)
        if (words.size > 1) {
            for ((k, word) in words.withIndex()) if (index + k < out.size) out[index + k] = word
        } else {
            out[index] = words.firstOrNull() ?: ""
        }
        return out
    }

    data class Check(
        /** Cells, from 0, holding something that is not on the list. */
        val unknown: List<Int>,
        val filled: Int,
        /** Twelve words on the list, with the checksum right. */
        val valid: Boolean,
    )

    fun check(cells: List<String>, wordlist: List<String>): Check {
        val unknown = cells.indices.filter { cells[it].isNotEmpty() && BIP39.index(cells[it], wordlist) == null }
        val filled = cells.count { it.isNotEmpty() }
        val valid = cells.size == CELLS && filled == CELLS && unknown.isEmpty() && BIP39.isValid(cells, wordlist)
        return Check(unknown, filled, valid)
    }

    fun phrase(cells: List<String>): String = cells.joinToString(" ")

    /** The note under the cells (SeedScreens.swift `refresh`): a paste refused, a word not on the list, a bad checksum, or nothing. */
    fun note(check: Check, focused: Int?, pasteNotice: String?): String = when {
        pasteNotice != null -> pasteNotice
        check.unknown.any { it != focused } -> "Word ${check.unknown.first { it != focused } + 1} is not one of the 2048 seed words."
        check.filled == CELLS && check.unknown.isEmpty() && !check.valid -> "These twelve words are not a valid seed phrase. Check each one."
        else -> ""
    }

    fun subtitle(check: Check): String =
        if (check.valid) "Twelve words in. Look for balances, then tap a mint." else "Type or paste the twelve words from your other wallet."

    /** After a paste that filled `filled` cells: clear the clipboard only if it still holds what was pasted, not something copied since. */
    fun clearAfterPaste(filled: Int, stampAtPaste: Long?, stampNow: Long?): Boolean =
        filled > 1 && stampAtPaste != null && stampAtPaste == stampNow
}

/**
 * One job at a time, and none started while a native question is up, so a seed
 * screen never draws over another or over a question (SeedScreens.swift
 * `ScreenQueue`). A job calls `finished` once its screen is gone. Main thread only.
 */
class ScreenQueue(
    private val alertShowing: () -> Boolean,
    /** Look again in this many ms while an alert is up; null, never (the tests call [pump]). */
    private val retry: ((Long, () -> Unit) -> Unit)?,
) {
    companion object {
        const val RETRY_MS = 500L
    }

    private class Job(val run: (finished: () -> Unit) -> Unit, val cancel: () -> Unit)

    private val jobs = ArrayDeque<Job>()
    var running = false
        private set

    /** Moves on at every [cancelAll]: a job already running whose screen is not up yet answers as cancelled. */
    var generation = 0
        private set

    val waitingCount: Int get() = jobs.size

    /** A job waiting or running: seedShow and seedEnter are refused then (review L1). */
    val busy: Boolean get() = running || jobs.isNotEmpty()

    /** `onCancel` answers the job's request if [cancelAll] drops it before it starts. */
    fun enqueue(onCancel: () -> Unit = {}, job: (finished: () -> Unit) -> Unit) {
        jobs.addLast(Job(job, onCancel))
        pump()
    }

    /** Drops every waiting job, answering each as cancelled, and moves [generation] on for the running one. */
    fun cancelAll() {
        generation += 1
        val dropped = jobs.toList()
        jobs.clear()
        dropped.forEach { it.cancel() }
    }

    fun pump() {
        if (running || jobs.isEmpty()) return
        if (alertShowing()) {
            retry?.invoke(RETRY_MS) { pump() }
            return
        }
        running = true
        val job = jobs.removeFirst()
        var over = false
        job.run {
            if (!over) {
                over = true
                running = false
                pump()
            }
        }
    }
}

/**
 * A pause after a seedShow whose read of the words failed, most often the
 * biometric or screen lock cancelled, so a script cannot put the prompt
 * straight back up, over and over (review L1). A clock set back never refuses
 * for good. Main thread only.
 */
class RetryPause(private val spanMs: Long) {
    private var since: Long? = null

    fun refuses(now: Long): Boolean {
        val s = since ?: return false
        if (now < s) return false
        return now - s < spanMs
    }

    fun start(now: Long) {
        since = now
    }
}
