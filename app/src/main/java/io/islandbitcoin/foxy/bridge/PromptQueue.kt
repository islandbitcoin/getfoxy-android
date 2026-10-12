package io.islandbitcoin.foxy.bridge

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.view.WindowManager
import io.islandbitcoin.foxy.R
import io.islandbitcoin.foxy.debug.Log

/** One yes-or-no question in a dialog Android draws (NativePrompts.swift `NativePrompt`). */
data class Prompt(
    val title: String,
    val message: String,
    /** The cancel button: a no. */
    val no: String,
    val yes: String,
    /** The yes is the dangerous choice (iOS `.destructive`). */
    val destructive: Boolean,
)

/**
 * Every yes-or-no Foxy asks in a native dialog, one at a time
 * (Foxy/Bridge/NativePrompts.swift, audit I3): allowing a new host, continuing
 * without Tor, and (P3) replacing or deleting the seed. Each waits its turn; a
 * dialog gone without an answer (Back, a tap outside, the activity going) is a
 * no; with nothing to show it on, the answer is no. Main thread only.
 *
 * How a prompt is shown is passed in, so the JVM tests run the queue with no
 * Android drawing anything.
 */
class PromptQueue(private val show: Show) {
    /** A prompt on screen: how to take it down. */
    class Shown(val dismiss: () -> Unit)

    /** Show `prompt`, calling `answer` once with the person's answer; null when there is nothing to show it on. */
    fun interface Show {
        fun show(prompt: Prompt, answer: (Boolean) -> Unit): Shown?
    }

    /** A prompt asked for. [cancel] answers it no, if it has no answer yet, and takes its dialog down. */
    inner class Ticket internal constructor(private val entry: Entry) {
        fun cancel() = cancel(entry)
    }

    internal class Entry(val prompt: Prompt, val done: (Boolean) -> Unit) {
        var answered = false
        var shown: Shown? = null
    }

    private val waiting = ArrayDeque<Entry>()
    private var current: Entry? = null

    val waitingCount: Int get() = waiting.size
    val isShowing: Boolean get() = current != null

    fun ask(prompt: Prompt, done: (Boolean) -> Unit): Ticket {
        val entry = Entry(prompt, done)
        waiting.addLast(entry)
        pump()
        return Ticket(entry)
    }

    private fun pump() {
        while (current == null && waiting.isNotEmpty()) {
            val entry = waiting.removeFirst()
            current = entry
            val shown = show.show(entry.prompt) { yes -> answer(entry, yes) }
            if (shown == null) {
                answer(entry, false)
                continue
            }
            if (current === entry) entry.shown = shown
        }
    }

    private fun answer(entry: Entry, yes: Boolean) {
        if (entry.answered) return
        entry.answered = true
        if (current === entry) current = null
        entry.done(yes)
        pump()
    }

    private fun cancel(entry: Entry) {
        if (entry.answered) return
        if (!waiting.remove(entry) && current === entry) entry.shown?.dismiss()
        answer(entry, false)
    }

    companion object {
        /** How each dialog is drawn (ENG-665): the page's card, heavy spaced capitals, the orange (themes.xml). */
        val THEME: Int = R.style.Theme_Foxy_Dialog

        /** The yes button's colour: the page's red for a dangerous yes (iOS `.destructive`), else the orange. The no is always the orange. */
        fun yesColour(prompt: Prompt): Int = if (prompt.destructive) R.color.danger else R.color.accent

        /**
         * The dialog on screen now, for the instrumented tests, which press its
         * buttons as a person would (TorRouteTest). Main thread.
         */
        @Volatile var showing: AlertDialog? = null
            private set

        /**
         * The real thing: an AlertDialog on the activity in front. Cancelable;
         * any way it goes down without a button is a no. Nothing in front, or
         * an activity finishing, is a no.
         */
        fun dialogs(activity: () -> Activity?): Show = Show { prompt, answer ->
            val a = activity()
            if (a == null || a.isFinishing || a.isDestroyed) return@Show null
            var said = false
            val dialog = AlertDialog.Builder(a, THEME)
                .setTitle(prompt.title)
                .setMessage(prompt.message)
                .setNegativeButton(prompt.no) { _, _ -> said = true; answer(false) }
                .setPositiveButton(prompt.yes) { _, _ -> said = true; answer(true) }
                .setCancelable(true)
                .create()
            dialog.setCanceledOnTouchOutside(true)
            // a dialog is a window of its own: as secure as the activity's (S19)
            dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            dialog.setOnDismissListener {
                if (showing === dialog) showing = null
                if (!said) {
                    Log.line("[foxy] prompt gone without an answer, taken as no: ${prompt.title}")
                    answer(false)
                }
            }
            try {
                dialog.show()
                // the buttons exist once it is shown; no on the left, yes on the right, as iOS's Cancel and its yes
                dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setTextColor(a.getColor(yesColour(prompt)))
                showing = dialog
            } catch (e: RuntimeException) {
                // the window it was to sit on is gone
                return@Show null
            }
            Shown { if (dialog.isShowing) dialog.dismiss() }
        }
    }
}
