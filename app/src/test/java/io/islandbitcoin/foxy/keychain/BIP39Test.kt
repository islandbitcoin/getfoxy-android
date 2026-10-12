package io.islandbitcoin.foxy.keychain

import io.islandbitcoin.foxy.TestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** BIP-39 made natively: the wordlist is the page's and the pinned one, the vectors, a new seed's checksum. Ported from BIP39Tests.swift. */
class BIP39Test {
    private val file = File("src/main/assets/bip39-english.txt")
    private val list get() = TestSupport.wordlist

    /** The English list inside the page's bundled @scure/bip39 (core/web/bip39.js). */
    private fun pageWordlist(): List<String> {
        val js = File(TestSupport.core, "web/bip39.js").readText()
        val start = js.indexOf("Object.freeze(`abandon\n")
        assertTrue(start >= 0)
        val from = start + "Object.freeze(`".length
        val end = js.indexOf("\nzoo`", from)
        return js.substring(from, end + "\nzoo".length).split("\n")
    }

    @Test
    fun theBundledWordlistIsThePagesWordlist() {
        val page = pageWordlist()
        assertEquals(2048, page.size)
        assertEquals(page, list)
        assertEquals("abandon", page.first())
        assertEquals("zoo", page.last())
    }

    @Test
    fun theWordlistsHashIsThePinnedHash() {
        val sha = { b: ByteArray -> Bytes.hex(MessageDigest.getInstance("SHA-256").digest(b)) }
        assertEquals(BIP39.ENGLISH_SHA256, sha(file.readBytes()))
        assertEquals(BIP39.ENGLISH_SHA256, sha((pageWordlist().joinToString("\n") + "\n").toByteArray()))
        assertEquals(list, BIP39.wordlist(file.readBytes()))
    }

    @Test
    fun aListWithOneWordChangedIsRefused() {
        val words = list.toMutableList()
        words[1000] = "foxy"
        assertNull(BIP39.wordlist((words.joinToString("\n") + "\n").toByteArray()))
        assertNull("no final newline, another digest", BIP39.wordlist(list.joinToString("\n").toByteArray()))
        assertNull(BIP39.wordlist(ByteArray(0)))
    }

    @Test
    fun theStandardsVectors() {
        val vectors = listOf(
            "00000000000000000000000000000000" to "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            "7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f" to "legal winner thank year wave sausage worth useful legal winner thank yellow",
            "80808080808080808080808080808080" to "letter advice cage absurd amount doctor acoustic avoid letter advice cage above",
            "ffffffffffffffffffffffffffffffff" to "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong",
            "9e885d952ad362caeb4efe34a8e91bd2" to "ozone drill grab fiber curtain grace pudding thank cruise elder eight picnic",
            "0000000000000000000000000000000000000000000000000000000000000000" to
                ("abandon ".repeat(23) + "art"),
        )
        for ((entropy, phrase) in vectors) {
            val words = BIP39.words(Bytes.fromHex(entropy)!!, list)
            assertEquals(phrase, words.joinToString(" "))
            assertTrue(phrase, BIP39.isValid(words, list))
        }
    }

    @Test
    fun entropyOfOtherLengthsIsRefused() {
        for (count in listOf(0, 15, 17, 33)) {
            try {
                BIP39.words(ByteArray(count) { 1 }, list)
                fail("$count bytes")
            } catch (e: BIP39.Failure.BadEntropy) {
            }
        }
        try {
            BIP39.words(ByteArray(16) { 1 }, listOf("abandon"))
            fail("a short list")
        } catch (e: BIP39.Failure.BadEntropy) {
        }
    }

    @Test
    fun aNewSeedIsTwelveWordsWithTheChecksumRight() {
        val seen = HashSet<String>()
        repeat(50) {
            val phrase = BIP39.generate(list)
            val words = phrase.split(" ")
            assertEquals(12, words.size)
            assertTrue(phrase, BIP39.isValid(words, list))
            NUT13.seed(phrase)
            seen.add(phrase)
        }
        assertEquals("128 random bits never repeat in fifty", 50, seen.size)
    }

    @Test
    fun wordsThatAreNotAPhraseAreRefused() {
        val valid = TestSupport.SAVED.split(" ")
        assertTrue(BIP39.isValid(valid, list))
        assertFalse(BIP39.isValid(valid.toMutableList().also { it[11] = "year" }, list))
        assertFalse(BIP39.isValid(List(12) { "abandon" }, list))
        assertFalse(BIP39.isValid(valid.toMutableList().also { it[3] = "foxy" }, list))
        assertFalse("the list is lower case", BIP39.isValid(valid.toMutableList().also { it[0] = "Legal" }, list))
        assertFalse(BIP39.isValid(valid.take(11), list))
        assertFalse(BIP39.isValid(emptyList(), list))
    }

    @Test
    fun indexAndSuggestions() {
        assertEquals(0, BIP39.index("abandon", list))
        assertEquals(2047, BIP39.index("zoo", list))
        assertEquals(2037, BIP39.index("wrong", list))
        assertNull(BIP39.index("aband", list))
        assertNull(BIP39.index("", list))
        assertEquals(listOf("abandon", "ability", "able", "about"), BIP39.suggestions("ab", list))
        assertEquals(listOf("zoo"), BIP39.suggestions("zoo", list))
        assertEquals(listOf("wrong"), BIP39.suggestions("wro", list))
        assertEquals(emptyList<String>(), BIP39.suggestions("xyz", list))
        assertEquals(emptyList<String>(), BIP39.suggestions("", list))
        assertEquals(listOf("abandon", "ability"), BIP39.suggestions("a", list, 2))
    }
}
