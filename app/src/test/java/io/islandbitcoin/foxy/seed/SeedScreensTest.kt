package io.islandbitcoin.foxy.seed

import io.islandbitcoin.foxy.TestSupport
import io.islandbitcoin.foxy.bridge.SeedBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seed screens' pure parts: the verify quiz (L3), the restore screen's
 * cells, the one-at-a-time queue (L1) and the pause after a failed read.
 * Ported from FoxyTests/NativeSeedTests.swift and SeedScreenTests.swift.
 */
class SeedScreensTest {
    private val phrase = TestSupport.SAVED.split(" ")
    private val other = TestSupport.TYPED_OTHER.split(" ")

    private fun tile(quiz: SeedQuiz, word: String): Int = quiz.pool.indices.firstOrNull { quiz.pool[it] == word && !quiz.used[it] } ?: -1

    // MARK: The quiz

    @Test
    fun tappingTheWordsInOrderFinishes() {
        val quiz = SeedQuiz(phrase)
        assertEquals(phrase.toSet(), quiz.pool.toSet())
        for ((n, word) in phrase.withIndex()) {
            assertFalse(quiz.isComplete)
            assertEquals(if (n == phrase.size - 1) SeedQuiz.Tap.Done else SeedQuiz.Tap.Right, quiz.tap(tile(quiz, word)))
        }
        assertTrue(quiz.isComplete)
        assertEquals(phrase, quiz.picked)
        assertEquals("a finished quiz takes no more taps", SeedQuiz.Tap.Ignored, quiz.tap(0))
    }

    @Test
    fun aWrongTapIsFlaggedAndStartsAgain() {
        val quiz = SeedQuiz(other)
        val first = tile(quiz, other[0])
        assertEquals(SeedQuiz.Tap.Right, quiz.tap(first))
        assertEquals("a placed tile", SeedQuiz.Tap.Ignored, quiz.tap(first))
        assertEquals(SeedQuiz.Tap.Right, quiz.tap(tile(quiz, other[1])))
        assertEquals(SeedQuiz.Tap.Wrong(3), quiz.tap(tile(quiz, other[7])))
        assertEquals(emptyList<String>(), quiz.picked)
        assertFalse(quiz.used.any { it })
        assertEquals("and it starts again from the first word", SeedQuiz.Tap.Right, quiz.tap(tile(quiz, other[0])))
        assertEquals(SeedQuiz.Tap.Ignored, quiz.tap(-1))
        assertEquals(SeedQuiz.Tap.Ignored, quiz.tap(12))
        assertFalse(SeedQuiz(emptyList()).isComplete)
    }

    @Test
    fun aRepeatedWordCanBeFinishedInAnyOrder() {
        val list = TestSupport.ABANDON_ABOUT.split(" ")
        repeat(10) {
            val quiz = SeedQuiz(list)
            var last: SeedQuiz.Tap = SeedQuiz.Tap.Ignored
            for (word in list) last = quiz.tap(tile(quiz, word))
            assertEquals(SeedQuiz.Tap.Done, last)
        }
    }

    @Test
    fun theQuizOrderComesFromTheDrawsNotTheWords() {
        val draws = intArrayOf(3, 1, 4, 1, 5, 9, 2, 6, 5, 3, 5)
        fun source(): (Int) -> Int {
            var i = 0
            return { n -> draws[i++ % draws.size] % n }
        }
        val places = (0 until 12).map { it.toString() }
        val order = SeedQuiz.scramble(places, source()).map { it.toInt() }
        for (list in listOf(phrase, other)) {
            assertEquals("the same draws put any twelve words in the same places", order.map { list[it] }, SeedQuiz.scramble(list, source()))
        }
        assertEquals(emptyList<String>(), SeedQuiz.scramble(emptyList(), source()))
        assertEquals(listOf("one"), SeedQuiz.scramble(listOf("one"), source()))
    }

    @Test
    fun everyQuizOfTheSameWordsIsDrawnAfresh() {
        val orders = (0 until 20).map { SeedQuiz(other).pool }.toSet()
        assertTrue("the words alone do not decide the order", orders.size > 1)
        for (order in orders) assertEquals(other.sorted(), order.sorted())
        for (n in listOf(1, 2, 3, 12, 24)) repeat(64) { assertTrue(SeedQuiz.systemRandom(n) in 0 until n) }
    }

    // MARK: The restore screen's cells

    @Test
    fun typingIsLowerCaseLettersAndAPastedPhraseSpreads() {
        val empty = List(12) { "" }
        assertEquals("abandon", SeedEntry.apply("Abandon", 0, empty)[0])
        assertEquals("zoo", SeedEntry.apply(" zoo1 ", 3, empty)[3])
        assertEquals("", SeedEntry.apply("", 3, SeedEntry.apply("zoo", 3, empty))[3])
        assertEquals(listOf("", "", "legal", "winner", "thank", "year", "", "", "", "", "", ""),
            SeedEntry.apply("1. Legal 2. winner\nthank, year", 2, empty))
        val late = SeedEntry.apply(TestSupport.SAVED, 10, empty)
        assertEquals(12, late.size)
        assertEquals("a phrase pasted near the end stops at the twelfth cell", listOf("legal", "winner"), late.subList(10, 12))
        assertEquals(phrase, SeedEntry.apply(TestSupport.SAVED, 0, empty))
        assertEquals(empty, SeedEntry.apply("zoo", 12, empty))
        assertEquals("only a to z are letters of a seed word", listOf("ber", "ozone"), SeedEntry.parts("über ozone"))
    }

    @Test
    fun theCheckOfTypedWords() {
        val wordlist = TestSupport.wordlist
        assertEquals(SeedEntry.Check(emptyList(), 12, true), SeedEntry.check(other, wordlist))
        val typo = other.toMutableList().also { it[4] = "wav" }
        assertEquals(SeedEntry.Check(listOf(4), 12, false), SeedEntry.check(typo, wordlist))
        val checksum = phrase.toMutableList().also { it[11] = "year" }
        assertEquals(SeedEntry.Check(emptyList(), 12, false), SeedEntry.check(checksum, wordlist))
        val partial = other.toMutableList().also { it[7] = "" }
        assertEquals(SeedEntry.Check(emptyList(), 11, false), SeedEntry.check(partial, wordlist))
        assertEquals(SeedEntry.Check(emptyList(), 0, false), SeedEntry.check(List(12) { "" }, wordlist))
        assertFalse("twelve cells, not thirteen", SeedEntry.check(phrase + "zoo", wordlist).valid)
        assertEquals(TestSupport.TYPED_OTHER, SeedEntry.phrase(other))
    }

    @Test
    fun theNoteSaysWhatIsWrongAndNotTheWordBeingTyped() {
        val wordlist = TestSupport.wordlist
        val typo = other.toMutableList().also { it[4] = "wav" }
        val check = SeedEntry.check(typo, wordlist)
        assertEquals("Word 5 is not one of the 2048 seed words.", SeedEntry.note(check, null, null))
        assertEquals("the word being typed is not wrong yet", "", SeedEntry.note(check, 4, null))
        val bad = phrase.toMutableList().also { it[11] = "year" }
        assertEquals("These twelve words are not a valid seed phrase. Check each one.", SeedEntry.note(SeedEntry.check(bad, wordlist), null, null))
        assertEquals("These words were copied inside Foxy. Type or paste words you wrote down yourself.",
            SeedEntry.note(SeedEntry.check(other, wordlist), null, io.islandbitcoin.foxy.bridge.ClipboardFilter.PASTE_REFUSAL))
        assertEquals("Twelve words in. Look for balances, then tap a mint.", SeedEntry.subtitle(SeedEntry.check(other, wordlist)))
        assertEquals("Type or paste the twelve words from your other wallet.", SeedEntry.subtitle(check))
    }

    @Test
    fun aPastedPhraseIsClearedOnlyWhileTheClipboardStillHoldsIt() {
        assertTrue(SeedEntry.clearAfterPaste(12, 5L, 5L))
        assertFalse("one word is typing, not a phrase", SeedEntry.clearAfterPaste(1, 5L, 5L))
        assertFalse("something was copied since", SeedEntry.clearAfterPaste(12, 5L, 6L))
        assertFalse("the clipboard would not say", SeedEntry.clearAfterPaste(12, null, null))
    }

    // MARK: One screen at a time (L1)

    @Test
    fun aSeedScreenQueuedOrOpenMakesTheQueueBusy() {
        var alert = true
        val queue = ScreenQueue({ alert }, null)
        assertFalse(queue.busy)
        var finish: (() -> Unit)? = null
        queue.enqueue { done -> finish = done }
        assertTrue("queued behind an alert", queue.busy)
        assertNull(finish)
        alert = false
        queue.pump()
        assertNotNull(finish)
        assertTrue("open", queue.busy)
        finish!!()
        assertFalse(queue.busy)
        assertEquals("a seed screen is already open", SeedBridge.BUSY_SCREEN)
    }

    @Test
    fun anAlertUpLooksAgainLater() {
        var alert = true
        val asked = ArrayList<Long>()
        var again: (() -> Unit)? = null
        val queue = ScreenQueue({ alert }, { ms, r ->
            asked.add(ms)
            again = r
        })
        var shown = 0
        queue.enqueue { shown++ }
        assertEquals(listOf(ScreenQueue.RETRY_MS), asked)
        alert = false
        again!!()
        assertEquals(1, shown)
    }

    @Test
    fun cancellingAnswersWaitingScreensAndTellsTheRunningOne() {
        val queue = ScreenQueue({ false }, null)
        val started = ArrayList<Int>()
        val cancelled = ArrayList<Int>()
        val finish = ArrayList<() -> Unit>()
        var turn = -1
        queue.enqueue(onCancel = { cancelled.add(1) }) { done ->
            started.add(1)
            turn = queue.generation
            finish.add(done)
        }
        for (n in 2..3) {
            queue.enqueue(onCancel = { cancelled.add(n) }) { done ->
                started.add(n)
                finish.add(done)
            }
        }
        assertEquals(listOf(1), started)
        assertEquals(2, queue.waitingCount)
        queue.cancelAll()
        assertEquals("the waiting ones are answered as cancelled", listOf(2, 3), cancelled)
        assertEquals(0, queue.waitingCount)
        assertNotEquals("the running one sees it was cancelled", turn, queue.generation)
        assertTrue("until it finishes", queue.busy)
        finish[0]()
        assertFalse(queue.busy)
        assertEquals("a cancelled screen never starts", listOf(1), started)
        queue.enqueue { done ->
            started.add(4)
            done()
        }
        assertEquals(listOf(1, 4), started)
        assertFalse(queue.busy)
    }

    @Test
    fun screensComeOneAtATimeAndNeverOverAnAlert() {
        var alert = true
        val queue = ScreenQueue({ alert }, null)
        val shown = ArrayList<Int>()
        val finish = ArrayList<() -> Unit>()
        for (n in 1..3) {
            queue.enqueue { done ->
                shown.add(n)
                finish.add(done)
            }
        }
        assertEquals("an alert is up", emptyList<Int>(), shown)
        assertEquals(3, queue.waitingCount)
        alert = false
        queue.pump()
        assertEquals(listOf(1), shown)
        assertTrue(queue.running)
        finish[0]()
        assertEquals(listOf(1, 2), shown)
        finish[0]()
        assertEquals("a screen that answers twice lets one more through, not two", listOf(1, 2), shown)
        alert = true
        finish[1]()
        assertEquals("the next waits for the alert", listOf(1, 2), shown)
        assertFalse(queue.running)
        alert = false
        queue.pump()
        assertEquals(listOf(1, 2, 3), shown)
        finish[2]()
        assertFalse(queue.running)
        assertEquals(0, queue.waitingCount)
    }

    @Test
    fun aFailedReadPausesTheWordsScreenForTenSeconds() {
        assertEquals(10_000L, SeedBridge.SHOW_PAUSE_MS)
        assertEquals("try again in a moment", SeedBridge.PAUSED)
        val pause = RetryPause(10_000)
        val then = 1_800_000_000_000L
        assertFalse(pause.refuses(then))
        pause.start(then)
        assertTrue(pause.refuses(then))
        assertTrue(pause.refuses(then + 9_900))
        assertFalse(pause.refuses(then + 10_000))
        assertFalse("a clock set back does not refuse for good", pause.refuses(then - 60_000))
    }
}
