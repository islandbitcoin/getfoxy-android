package io.islandbitcoin.foxy.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Audit I3: native dialogs wait their turn, and one gone without an answer is a no. Ported from PromptQueueTests.swift. */
class PromptQueueTest {
    /** Stands in for Android: records what is shown, and taps for the test. */
    class Screen {
        val shown = mutableListOf<String>()
        val up = mutableSetOf<String>()
        val dismissed = mutableListOf<String>()
        val taps = mutableMapOf<String, (Boolean) -> Unit>()
        var nothingToShowOn = false

        val show = PromptQueue.Show { prompt, answer ->
            if (nothingToShowOn) return@Show null
            val title = prompt.title
            shown.add(title)
            up.add(title)
            taps[title] = { yes ->
                up.remove(title)
                answer(yes)
            }
            PromptQueue.Shown {
                up.remove(title)
                dismissed.add(title)
                // a dialog taken down without a button: Android's dismiss listener says no
                answer(false)
            }
        }

        fun tap(title: String, yes: Boolean) = taps[title]?.invoke(yes)
    }

    private fun prompt(title: String) = Prompt(title, "", "No", "Yes", false)

    @Test
    fun oneDialogAtATimeInTheOrderAsked() {
        val screen = Screen()
        val q = PromptQueue(screen.show)
        val heard = mutableListOf<String>()
        q.ask(prompt("Allow Foxy to contact x?")) { heard.add("host $it") }
        q.ask(prompt("Replace this wallet's seed?")) { heard.add("replace $it") }
        q.ask(prompt("Continue without Tor?")) { heard.add("tor $it") }
        assertEquals("a second dialog went up over the first", listOf("Allow Foxy to contact x?"), screen.shown)
        assertEquals(2, q.waitingCount)
        screen.tap("Allow Foxy to contact x?", true)
        assertEquals("Replace this wallet's seed?", screen.shown.last())
        assertEquals(1, screen.up.size)
        screen.tap("Replace this wallet's seed?", false)
        screen.tap("Continue without Tor?", true)
        assertEquals(listOf("host true", "replace false", "tor true"), heard)
        assertFalse(q.isShowing)
    }

    @Test
    fun answersCountOnce() {
        val screen = Screen()
        val q = PromptQueue(screen.show)
        val heard = mutableListOf<Boolean>()
        q.ask(prompt("A")) { heard.add(it) }
        screen.tap("A", false)
        screen.taps["A"]?.invoke(true)
        assertEquals(listOf(false), heard)
    }

    @Test
    fun nothingToShowOnIsANo() {
        val screen = Screen().apply { nothingToShowOn = true }
        val q = PromptQueue(screen.show)
        val heard = mutableListOf<Boolean>()
        q.ask(prompt("A")) { heard.add(it) }
        q.ask(prompt("B")) { heard.add(it) }
        assertEquals(listOf(false, false), heard)
        assertFalse(q.isShowing)
    }

    @Test
    fun cancellingOneThatWaitsAnswersNoAndNeverShowsIt() {
        val screen = Screen()
        val q = PromptQueue(screen.show)
        val heard = mutableListOf<String>()
        q.ask(prompt("A")) { heard.add("A $it") }
        val b = q.ask(prompt("B")) { heard.add("B $it") }
        b.cancel()
        assertEquals(listOf("B false"), heard)
        screen.tap("A", true)
        assertEquals(listOf("A"), screen.shown)
        assertEquals(listOf("B false", "A true"), heard)
    }

    @Test
    fun cancellingTheOneShownTakesItDownAndALateTapDoesNotCount() {
        val screen = Screen()
        val q = PromptQueue(screen.show)
        val heard = mutableListOf<String>()
        val a = q.ask(prompt("Replace this wallet's seed?")) { heard.add("replace $it") }
        q.ask(prompt("B")) { heard.add("B $it") }
        a.cancel()
        assertEquals(listOf("Replace this wallet's seed?"), screen.dismissed)
        assertEquals(listOf("replace false"), heard)
        assertEquals("B", screen.shown.last())
        screen.taps["Replace this wallet's seed?"]?.invoke(true)
        assertEquals(listOf("replace false"), heard)
    }

    @Test
    fun aDialogGoneWithoutAButtonIsANoAndTheNextComesUp() {
        val answers = mutableMapOf<String, (Boolean) -> Unit>()
        val shown = mutableListOf<String>()
        val q = PromptQueue { p, answer ->
            shown.add(p.title)
            answers[p.title] = answer
            PromptQueue.Shown {}
        }
        val heard = mutableListOf<String>()
        q.ask(prompt("Continue without Tor?")) { heard.add("tor $it") }
        q.ask(prompt("B")) { heard.add("B $it") }
        // what the dialog's dismiss listener does on Back, a tap outside, or the activity going
        answers.getValue("Continue without Tor?")(false)
        assertEquals(listOf("tor false"), heard)
        assertEquals(listOf("Continue without Tor?", "B"), shown)
        answers.getValue("B")(true)
        assertEquals(listOf("tor false", "B true"), heard)
    }

    @Test
    fun aDangerousYesIsRedAndEveryOtherButtonTheOrange() {
        // ENG-665: the dialog is the page's card (Theme.Foxy.Dialog); the yes of a replace or a delete is the page's red, as iOS's `.destructive`
        assertEquals(io.islandbitcoin.foxy.R.style.Theme_Foxy_Dialog, PromptQueue.THEME)
        val delete = Prompt("Delete all data?", "m", "Cancel", "Delete", destructive = true)
        val allow = Prompt("Allow a new host?", "m", "Cancel", "Allow", destructive = false)
        assertEquals(io.islandbitcoin.foxy.R.color.danger, PromptQueue.yesColour(delete))
        assertEquals(io.islandbitcoin.foxy.R.color.accent, PromptQueue.yesColour(allow))
    }
}
