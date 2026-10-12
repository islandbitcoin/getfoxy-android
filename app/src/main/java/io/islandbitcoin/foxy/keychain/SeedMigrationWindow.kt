package io.islandbitcoin.foxy.keychain

import io.islandbitcoin.foxy.debug.Log
import java.io.File
import java.io.IOException

/**
 * When `seedMigrate` may still write words the page hands over
 * (Foxy/Keychain/SeedMigrationWindow.swift).
 *
 * Only for an install whose page already held storage before this launch, and
 * only until it is used or a seed is there. Writing anywhere else would let a
 * script in the page, on a phone with no seed yet, write words it knows. A
 * fresh install has no page storage, so the window closes for good at its
 * first launch. Closed, a saved seed is still compared (review M4); it never
 * writes.
 *
 * On Android the page's storage is the WebView's, under the app's
 * `app_webview` folder; "closed" is a [Flags] flag in `noBackupFilesDir`.
 */
class SeedMigrationWindow(private val flags: Flags) {
    companion object {
        const val CLOSED_FLAG = "foxy.seedMigrate.closed"
        const val NO_MIGRATION = "no migration here"

        fun open(closed: Boolean, hadPageStorage: Boolean): Boolean = !closed && hadPageStorage

        /** Chromium keeps a page's localStorage under a folder named "Local Storage"; any non-empty file there counts. */
        fun pageStorageExists(webviewDir: File): Boolean {
            if (!webviewDir.isDirectory) return false
            return webviewDir.walkTopDown().any { f ->
                f.isFile && f.length() > 0 && f.path.contains("Local Storage")
            }
        }

        fun step(open: Boolean, saved: Saved): Step = when (saved) {
            Saved.Failed -> Step.Answer(Outcome.Refused("the seed could not be read"))
            is Saved.Found -> Step.Answer(if (saved.same) Outcome.Same else Outcome.Different)
            Saved.Absent -> if (open) Step.Write else Step.Answer(Outcome.Refused(NO_MIGRATION))
        }

        /** Closed only by an answer about the words themselves; an error leaves it for the page to send them again. */
        fun closes(after: Outcome): Boolean = after !is Outcome.Refused
    }

    sealed class Outcome {
        object Migrated : Outcome()
        object Same : Outcome()
        object Different : Outcome()
        data class Refused(val why: String) : Outcome()
    }

    sealed class Saved {
        data class Found(val same: Boolean) : Saved()
        object Absent : Saved()
        object Failed : Saved()
    }

    sealed class Step {
        data class Answer(val outcome: Outcome) : Step()
        object Write : Step()
    }

    private val lock = Any()
    private var noted = false
    private var hadPageStorage = false

    /** Once per process, before the page first loads. */
    fun noteLaunch(webviewDir: File) = synchronized(lock) {
        if (noted) return@synchronized
        noted = true
        hadPageStorage = pageStorageExists(webviewDir)
        if (flags.isSet(CLOSED_FLAG)) return@synchronized
        if (hadPageStorage) {
            Log.line("[foxy] seed migrate: open, page storage from before this launch")
        } else {
            setClosed()
            Log.line("[foxy] seed migrate: closed, no page storage from before (a fresh install)")
        }
    }

    val isOpen: Boolean get() = synchronized(lock) { open(flags.isSet(CLOSED_FLAG), hadPageStorage) }

    /** For good: nothing opens it again on this install. */
    fun close(why: String) {
        if (flags.isSet(CLOSED_FLAG)) return
        setClosed()
        Log.line("[foxy] seed migrate: closed, $why")
    }

    private fun setClosed() {
        try {
            flags.set(CLOSED_FLAG)
        } catch (e: IOException) {
            // not closed on disk: it stays open only where it was open, and is asked again at the next answer
            Log.line("[foxy] seed migrate: the closed flag could not be saved")
        }
    }
}
