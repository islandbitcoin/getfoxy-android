package io.islandbitcoin.foxy.bridge

import io.islandbitcoin.foxy.keychain.BIP39

/**
 * The clipboard around the seed and the money (Foxy/Bridge/SeedPasteboard.swift
 * and FoxyBridge.swift `handleCopy`), the rules apart from Android's
 * ClipboardManager so the JVM tests hold them:
 *
 * - the page is never handed a seed phrase from the clipboard: text that holds
 *   one reads as "" ([forPage]);
 * - Foxy's own copies are labelled `foxy:<nonce>`, so the restore screen can
 *   refuse words Foxy itself put there ([isFoxys]: a page could otherwise copy
 *   words it knows and lead the person to restore them as their own, review M8),
 *   and a sensitive copy is cleared after [EXPIRE_MS] only while it is still the
 *   copy Foxy made ([expired]);
 * - a sensitive copy carries `android.content.extra.IS_SENSITIVE`, which keeps
 *   it out of the clipboard preview and keyboard suggestions (the string key,
 *   which Android 12L and earlier read too, rather than the API 33 constant).
 *
 * What Android cannot do, said in SECURITY-INVARIANTS.md S23: no device-only
 * flag like iOS's `.localOnly`, so a vendor's clipboard sync may carry a copy
 * off the phone; the clear is Foxy's own timer, which dies with the process
 * (it is checked again when Foxy has focus); and the share sheet's own Copy has
 * no callback, so a token copied there is not Foxy's label and never expires.
 */
object ClipboardFilter {
    const val SENSITIVE_EXTRA = "android.content.extra.IS_SENSITIVE"

    /** A sensitive copy is cleared this long after it was made, if it is still Foxy's. */
    const val EXPIRE_MS = 120_000L

    const val LABEL_PREFIX = "foxy:"

    /** Said on the restore screen when the clipboard holds words Foxy copied. */
    const val PASTE_REFUSAL = "These words were copied inside Foxy. Type or paste words you wrote down yourself."

    /** The reply to `copy` with nothing to copy (contract `copy` errors). */
    const val NOTHING_TO_COPY = "nothing to copy"

    private val LENGTHS = listOf(12, 15, 18, 21, 24)

    /**
     * The words in a text as the restore screen reads a cell
     * (SeedScreens.swift `SeedEntry.parts`): lowercased, split at anything that
     * is not a letter a to z.
     */
    fun parts(typed: String): List<String> =
        typed.lowercase(java.util.Locale.ROOT).split(Regex("[^a-z]+")).filter { it.isNotEmpty() }

    /**
     * Whether `text` holds a BIP-39 phrase: 12, 15, 18, 21 or 24 words in a row
     * that are on the list and have a right checksum, read as the restore
     * screen reads words, so a numbered list or a phrase inside a note counts.
     */
    fun holdsSeedPhrase(text: String, wordlist: List<String>): Boolean {
        if (wordlist.size != 2048) return false
        val run = ArrayList<String>()
        fun runHoldsOne(): Boolean {
            for (length in LENGTHS) {
                if (run.size < length) continue
                for (start in 0..(run.size - length)) {
                    if (BIP39.isValid(run.subList(start, start + length), wordlist)) return true
                }
            }
            return false
        }
        for (part in parts(text)) {
            if (BIP39.index(part, wordlist) != null) {
                run.add(part)
            } else {
                if (runHoldsOne()) return true
                run.clear()
            }
        }
        return runHoldsOne()
    }

    /**
     * What the page may be handed of clipboard text: nothing, when it holds a
     * seed phrase, as if the clipboard held nothing usable. Without the pinned
     * wordlist nothing can be checked, so nothing is handed over.
     */
    fun forPage(text: String, wordlist: List<String>?): String {
        if (text.isEmpty()) return text
        if (wordlist == null) return ""
        return if (holdsSeedPhrase(text, wordlist)) "" else text
    }

    /** The clip label of one of Foxy's copies. */
    fun label(nonce: String): String = LABEL_PREFIX + nonce

    /** Whether a clip label is one of Foxy's copies. */
    fun isFoxys(label: CharSequence?): Boolean = label != null && label.startsWith(LABEL_PREFIX)

    /**
     * Whether the clip now on the clipboard should be cleared: it is still the
     * sensitive copy Foxy made (its label carries that copy's nonce) and
     * [EXPIRE_MS] have passed since. Anything copied since is never touched.
     * `now` and `copiedAt` are the same monotonic clock.
     */
    fun expired(currentLabel: CharSequence?, nonce: String?, copiedAt: Long?, now: Long): Boolean {
        if (nonce == null || copiedAt == null) return false
        if (currentLabel?.toString() != label(nonce)) return false
        return now - copiedAt >= EXPIRE_MS
    }
}
