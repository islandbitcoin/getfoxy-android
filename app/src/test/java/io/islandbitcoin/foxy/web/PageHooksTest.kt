package io.islandbitcoin.foxy.web

import io.islandbitcoin.foxy.flashcard.CardTapDelivery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The page's process ended (WebHost.recreate, ENG-668, review of PR #15): the page's
 * activity hears it is gone before the old page is dropped and the new one made, so a
 * card-tap question still out to the dead page is forgotten and the new page is asked.
 */
class PageHooksTest {
    @Test
    fun goneIsHeardBeforeTheOldPageGoesAndTheNewOneIsMade() {
        val order = mutableListOf<String>()
        val hooks = PageHooks()
        hooks.gone = { order.add("gone") }
        hooks.processEnded(destroy = { order.add("destroy") }, create = { order.add("create") })
        assertEquals(listOf("gone", "destroy", "create"), order)
    }

    /** Wired as MainActivity wires it: a question out across the reload is asked once more of the new page. */
    @Test
    fun aCardTapOutAcrossAReloadIsAskedOfTheNewPage() {
        val held = mutableListOf<(Boolean) -> Unit>()
        val delivery = CardTapDelivery(now = { 0L }, wait = { _, _ -> }, say = {})
        delivery.tell = { held.add(it) }
        val hooks = PageHooks()
        hooks.loaded = { delivery.pageLoaded() }
        hooks.gone = { delivery.pageGone() }
        hooks.loaded()
        delivery.coverChanged(up = false)
        delivery.cardTapped()
        assertEquals(1, held.size)
        // the new page loads only after create, as onPageFinished comes later
        hooks.processEnded(destroy = {}, create = {})
        hooks.loaded()
        assertEquals("the new page is asked", 2, held.size)
        held[0](true)
        held[1](true)
        assertNull(delivery.since)
    }

    /**
     * The Android-only wiring the JVM cannot run (no Robolectric here): WebHost reloads
     * through [PageHooks.processEnded], says loaded and reader-off through the hooks, and
     * MainActivity hands all three, and the splash cover's coming down, to the card tap.
     * A refactor that drops one fails here, not on a phone with a card left on it.
     */
    @Test
    fun theHooksAreWiredWhereTheJvmCannotRunThem() {
        val host = File("src/main/java/io/islandbitcoin/foxy/web/WebHost.kt").readText()
        val recreate = host.substringAfter("private fun recreate()").substringBefore("\n    }")
        assertTrue("recreate reloads through the hooks", "hooks.processEnded(::destroy, ::create)" in recreate)
        val finished = host.substringAfter("override fun onPageFinished").substringBefore("\n        }")
        assertTrue("a finished page is said to be loaded", "hooks.loaded()" in finished)
        assertTrue("reader mode going off is said", "ReaderModeRadio(activity) { hooks.readerOff() }" in host)
        val main = File("src/main/java/io/islandbitcoin/foxy/MainActivity.kt").readText()
        for (wire in listOf(
            "hooks.loaded = { cardTaps.pageLoaded() }",
            "hooks.gone = { cardTaps.pageGone() }",
            "hooks.readerOff = { cardIntake.readerOff() }",
            "SplashCover(uncovered = { cardTaps.coverChanged(up = false) })",
            "cardIntake.tap(CardTapIntake.replayed(restored, i.flags))",
        )) {
            assertTrue("MainActivity: $wire", wire in main)
        }
        // no timer on the page's answer (iOS has none): a slow page taken for a no would be asked again and act twice
        val ask = main.substringAfter("private fun askPageAboutCardTap").substringBefore("\n    }")
        assertTrue("the question to the page", "CardAddress.PAGE_CALL" in ask)
        for (timer in listOf("postDelayed", "postAtTime", "wait(")) assertTrue("no $timer on the answer", timer !in ask)
    }
}
