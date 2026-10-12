package io.islandbitcoin.foxy.keychain

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * BIP-39's English words, natively (Foxy/Keychain/BIP39.swift): a new seed's
 * twelve words, and a check of words the page hands over (seedMigrate).
 *
 * The list ships as `assets/bip39-english.txt` and its SHA-256 is checked every
 * time it is loaded: a list with one word changed would make seeds no other
 * wallet can restore.
 */
object BIP39 {
    const val ENGLISH_SHA256 = "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda"
    const val ASSET = "bip39-english.txt"

    sealed class Failure(message: String) : Exception(message) {
        class BadEntropy : Failure("bad entropy")
        class Random : Failure("random")
    }

    /** The words of a list file, only if its SHA-256 is the pinned one. */
    fun wordlist(data: ByteArray): List<String>? {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        if (Bytes.hex(digest) != ENGLISH_SHA256) return null
        val lines = String(data, Charsets.UTF_8).split("\n").toMutableList()
        if (lines.lastOrNull() != "") return null
        lines.removeAt(lines.size - 1)
        return if (lines.size == 2048) lines else null
    }

    /** The words for `entropy`: the entropy then the first ENT/32 bits of its SHA-256, eleven bits at a time. */
    fun words(entropy: ByteArray, wordlist: List<String>): List<String> {
        if (entropy.size !in setOf(16, 20, 24, 28, 32) || wordlist.size != 2048) throw Failure.BadEntropy()
        val digest = MessageDigest.getInstance("SHA-256").digest(entropy)
        try {
            val entropyBits = entropy.size * 8
            val totalBits = entropyBits + entropy.size / 4
            fun bit(i: Int): Int {
                val byte = if (i < entropyBits) entropy[i / 8] else digest[0]
                val offset = if (i < entropyBits) i % 8 else i - entropyBits
                return ((byte.toInt() and 0xff) ushr (7 - offset)) and 1
            }
            val out = ArrayList<String>(totalBits / 11)
            var first = 0
            while (first < totalBits) {
                var index = 0
                for (k in 0 until 11) index = (index shl 1) or bit(first + k)
                out.add(wordlist[index])
                first += 11
            }
            return out
        } finally {
            Bytes.wipe(digest)
        }
    }

    /** Twelve new words from 128 bits of the platform's CSPRNG. */
    fun generate(wordlist: List<String>, random: SecureRandom = SecureRandom()): String {
        val entropy = ByteArray(16)
        try {
            try {
                random.nextBytes(entropy)
            } catch (e: Exception) {
                throw Failure.Random()
            }
            return words(entropy, wordlist).joinToString(" ")
        } finally {
            Bytes.wipe(entropy)
        }
    }

    /** A word's place in the (sorted) list. */
    fun index(word: String, wordlist: List<String>): Int? {
        val i = lowerBound(word, wordlist)
        return if (i < wordlist.size && wordlist[i] == word) i else null
    }

    private fun lowerBound(word: String, list: List<String>): Int {
        var low = 0
        var high = list.size
        while (low < high) {
            val mid = (low + high) / 2
            if (list[mid] < word) low = mid + 1 else high = mid
        }
        return low
    }

    /** A length BIP-39 allows, every word on the list, and the checksum right. */
    fun isValid(words: List<String>, wordlist: List<String>): Boolean {
        if (words.size !in setOf(12, 15, 18, 21, 24) || wordlist.size != 2048) return false
        val bits = ByteArray(words.size * 11)
        val checksumBits = bits.size / 33
        val entropyBytes = (bits.size - checksumBits) / 8
        val entropy = ByteArray(entropyBytes)
        try {
            for ((n, word) in words.withIndex()) {
                val i = index(word, wordlist) ?: return false
                for (k in 0 until 11) bits[n * 11 + k] = ((i ushr (10 - k)) and 1).toByte()
            }
            for (b in 0 until entropyBytes * 8) {
                entropy[b / 8] = (entropy[b / 8].toInt() or (bits[b].toInt() shl (7 - b % 8))).toByte()
            }
            val first = MessageDigest.getInstance("SHA-256").digest(entropy)[0].toInt() and 0xff
            var mismatch = 0
            for (c in 0 until checksumBits) {
                mismatch = mismatch or (bits[entropyBytes * 8 + c].toInt() xor ((first ushr (7 - c)) and 1))
            }
            return mismatch == 0
        } finally {
            Bytes.wipe(bits)
            Bytes.wipe(entropy)
        }
    }

    /** Up to `limit` words that start with `prefix`, in list order. */
    fun suggestions(prefix: String, wordlist: List<String>, limit: Int = 4): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        var i = lowerBound(prefix, wordlist)
        while (i < wordlist.size && out.size < limit && wordlist[i].startsWith(prefix)) {
            out.add(wordlist[i])
            i++
        }
        return out
    }
}
