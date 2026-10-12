package io.islandbitcoin.foxy.bridge

import io.islandbitcoin.foxy.TestSupport
import io.islandbitcoin.foxy.keychain.BIP39
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clipboard's rules (S23; SeedPasteboard.swift, ported with
 * SeedScreenTests.swift `testThePageIsNeverHandedAPhrase`): a seed phrase is
 * never handed to the page; Foxy's copies are labelled and only those are
 * cleared, and only once their 120 s are up.
 */
class ClipboardFilterTest {
    private val wordlist = TestSupport.wordlist

    /** A valid phrase of every length BIP-39 allows, from fixed entropy. */
    private val phrases: Map<Int, String> = listOf(16, 20, 24, 28, 32).associate { bytes ->
        val words = BIP39.words(ByteArray(bytes) { (it * 7 + bytes).toByte() }, wordlist)
        words.size to words.joinToString(" ")
    }

    private fun holds(text: String) = ClipboardFilter.holdsSeedPhrase(text, wordlist)

    @Test
    fun aValidPhraseOfEveryLengthReadsEmpty() {
        assertEquals(setOf(12, 15, 18, 21, 24), phrases.keys)
        for ((length, phrase) in phrases) {
            assertTrue("$length words", holds(phrase))
            assertEquals("$length words", "", ClipboardFilter.forPage(phrase, wordlist))
        }
        assertTrue(holds(TestSupport.SAVED.uppercase()))
        assertTrue("a numbered list", holds(TestSupport.SAVED.split(" ").mapIndexed { i, w -> "${i + 1}. $w" }.joinToString("\n")))
        assertTrue("inside a note", holds("my backup, keep safe: ${TestSupport.TYPED_OTHER} (paper copy in the drawer)"))
    }

    @Test
    fun whatIsNotAPhraseIsPassedThrough() {
        val bad = "legal winner thank year wave sausage worth useful legal winner thank year"
        assertFalse("a wrong checksum", holds(bad))
        val eleven = TestSupport.SAVED.split(" ").drop(1).joinToString(" ")
        assertFalse("eleven words", holds(eleven))
        val prose = "The mint at https://testnut.cashu.space answered with a new keyset, please try again."
        val token = "cashuBo2FteBtodHRwczovL3Rlc3RudXQuY2FzaHUuc3BhY2VhdWNzYXRhdIGiYWlIAJofKTJT5B5hcIOkYWEQYXN4QDEzYjM"
        val invoice = "lnbc10u1pjzq2jhpp5r2fz5w0kx6gl9x3dj8zqvm4f6mq0g2y0q2e8yq8a0s6rd9zv9lqsdqqcqzzsxqyz5vqsp5"
        for (text in listOf(bad, eleven, prose, token, invoice)) assertEquals(text, ClipboardFilter.forPage(text, wordlist))
        assertFalse(holds(""))
        assertEquals("", ClipboardFilter.forPage("", wordlist))
    }

    @Test
    fun withoutTheWordlistNothingIsHandedOver() {
        assertEquals("", ClipboardFilter.forPage("cashuBxyz", null))
        assertFalse("a list that is not 2048 words checks nothing", ClipboardFilter.holdsSeedPhrase(TestSupport.SAVED, wordlist.take(100)))
    }

    @Test
    fun foxysCopiesAreLabelledWithTheirNonce() {
        val label = ClipboardFilter.label("0a1b2c")
        assertEquals("foxy:0a1b2c", label)
        assertTrue(ClipboardFilter.isFoxys(label))
        assertFalse(ClipboardFilter.isFoxys("Copied text"))
        assertFalse(ClipboardFilter.isFoxys(null))
        assertEquals("android.content.extra.IS_SENSITIVE", ClipboardFilter.SENSITIVE_EXTRA)
    }

    @Test
    fun aSensitiveCopyExpiresAfter120sOnlyWhileItIsStillFoxys() {
        val at = 1_000_000L
        val label = ClipboardFilter.label("feed")
        assertEquals(120_000L, ClipboardFilter.EXPIRE_MS)
        assertFalse("not yet", ClipboardFilter.expired(label, "feed", at, at + 119_999))
        assertTrue(ClipboardFilter.expired(label, "feed", at, at + 120_000))
        assertTrue(ClipboardFilter.expired(label, "feed", at, at + 3_600_000))
        assertFalse("another of Foxy's copies since", ClipboardFilter.expired(ClipboardFilter.label("beef"), "feed", at, at + 200_000))
        assertFalse("something else copied since", ClipboardFilter.expired("Copied text", "feed", at, at + 200_000))
        assertFalse("an empty clipboard", ClipboardFilter.expired(null, "feed", at, at + 200_000))
        assertFalse("no sensitive copy", ClipboardFilter.expired(label, null, null, at + 200_000))
    }

    @Test
    fun theRestoreScreensPartsAreLowerCaseLettersOnly() {
        assertEquals(listOf("legal", "winner", "thank"), ClipboardFilter.parts("1. Legal\n2. WINNER, thank!"))
        assertEquals(emptyList<String>(), ClipboardFilter.parts("123 456"))
    }
}
