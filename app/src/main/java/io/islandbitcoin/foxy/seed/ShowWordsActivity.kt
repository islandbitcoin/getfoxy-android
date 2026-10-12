package io.islandbitcoin.foxy.seed

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.islandbitcoin.foxy.R
import io.islandbitcoin.foxy.debug.Log

/**
 * seedShow: the words behind TAP TO REVEAL, the verify quiz, and BACKED UP
 * (SeedScreens.swift `SeedShowController`). The words are not drawn at all
 * until the tap, not only veiled; the quiz's tiles are the words scrambled, so
 * they are behind the same tap (L3). DELETE ALL DATA takes a long press, and
 * erases nothing here: the screen closes and the page asks twice before
 * seedWipe asks Android for the device owner.
 *
 * Its answer, once it is gone: whether the quiz was finished, whether DELETE
 * ALL DATA was held, whether the words were revealed.
 */
class ShowWordsActivity : SeedScreenActivity() {
    private enum class Stage { WORDS, QUIZ, BACKED_UP }

    companion object {
        /** DELETE ALL DATA is held this long. */
        const val HOLD_MS = 1_200L
    }

    private lateinit var session: SeedScreenHost.Show
    private lateinit var quiz: SeedQuiz
    private var stage = Stage.WORDS
    private var deleteAsked = false
    private var revealed = false
    private lateinit var page: LinearLayout
    private var quizSubtitle: TextView? = null
    private var slots: FlowLayout? = null
    private var tiles: FlowLayout? = null

    override fun take(): Boolean {
        session = SeedScreenHost.takeShow() ?: return false
        quiz = SeedQuiz(session.words)
        stage = if (session.verify) Stage.QUIZ else Stage.WORDS
        return true
    }

    override fun build() {
        if (!::page.isInitialized) {
            page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            root.addView(page, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        page.removeAllViews()
        quizSubtitle = null
        slots = null
        tiles = null
        when (stage) {
            Stage.WORDS -> buildWords()
            Stage.QUIZ -> buildQuiz()
            Stage.BACKED_UP -> buildBackedUp()
        }
    }

    override fun close() {
        val verified = quiz.isComplete
        val delete = deleteAsked
        val shown = revealed
        finish { session.done(verified, delete, shown) }
    }

    /** Back from the quiz is back to the words, with what was tapped kept; from the words or BACKED UP it leaves. */
    override fun backTapped() {
        if (stage == Stage.QUIZ) show(Stage.WORDS) else close()
    }

    private fun show(next: Stage) {
        stage = next
        build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildWords() {
        val verify = SeedStyle.primaryButton(this, getString(R.string.seed_verify_words))
        verify.setOnClickListener { show(Stage.QUIZ) }
        val delete = Button(this).apply {
            isAllCaps = false
            stateListAnimator = null
            text = getString(R.string.seed_hold_to_delete)
            setTextColor(SeedStyle.RED)
            SeedType.DELETE.on(this)
            background = SeedStyle.rounded(this@ShowWordsActivity, SeedStyle.withAlpha(SeedStyle.RED, 0.1), 28f, SeedStyle.withAlpha(SeedStyle.RED, 0.5), 1.5f)
            contentDescription = getString(R.string.seed_hold_to_delete_hint)
        }
        holdToDelete(delete)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        buttons.addView(verify)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, SeedStyle.dp(this, 56f))
        lp.topMargin = SeedStyle.dp(this, 12f)
        buttons.addView(delete, lp)
        val stack = column(page, buttons)
        spaced(stack, SeedStyle.title(this, getString(R.string.seed_your_phrase)), 10f)
        spaced(stack, SeedStyle.body(this, getString(R.string.seed_your_phrase_body)), 16f)
        // no screen lock: nothing asked before this screen opens, and the person should know
        if (!session.passcodeSet) spaced(stack, SeedStyle.body(this, getString(R.string.seed_no_screen_lock)), 16f)
        spaced(stack, SeedStyle.rule(this), 16f)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (first in session.words.indices step 2) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(wordCell(first), LinearLayout.LayoutParams(0, SeedStyle.dp(this, 54f), 1f))
            val second = if (first + 1 < session.words.size) wordCell(first + 1) else View(this)
            val lp2 = LinearLayout.LayoutParams(0, SeedStyle.dp(this, 54f), 1f)
            lp2.marginStart = SeedStyle.dp(this, 9f)
            row.addView(second, lp2)
            val rowLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            rowLp.bottomMargin = SeedStyle.dp(this, 9f)
            grid.addView(row, rowLp)
        }
        stack.addView(veiled(grid))
    }

    /** DELETE ALL DATA: a tap in passing does nothing; held for [HOLD_MS], the screen closes with the ask. */
    @SuppressLint("ClickableViewAccessibility")
    private fun holdToDelete(button: Button) {
        val held = Runnable {
            if (deleteAsked) return@Runnable
            deleteAsked = true
            button.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            close()
        }
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    v.postDelayed(held, HOLD_MS)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.removeCallbacks(held)
            }
            false
        }
        // a screen reader's long press does the same
        button.setOnLongClickListener { true }
        button.setOnClickListener { }
    }

    private fun wordCell(i: Int): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = SeedStyle.rounded(this@ShowWordsActivity, SeedStyle.ink(0.05), 15f, SeedStyle.ink(0.1))
            setPadding(SeedStyle.dp(this@ShowWordsActivity, 13f), 0, SeedStyle.dp(this@ShowWordsActivity, 10f), 0)
        }
        val number = TextView(this).apply {
            text = (i + 1).toString()
            setTextColor(SeedStyle.ink(0.38))
            SeedType.CELL_NUMBER.on(this)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        cell.addView(number, LinearLayout.LayoutParams(SeedStyle.dp(this, 22f), ViewGroup.LayoutParams.WRAP_CONTENT))
        val word = TextView(this).apply {
            text = if (revealed) session.words[i] else "••••••"
            setTextColor(if (revealed) SeedStyle.INK else SeedStyle.ink(0.3))
            SeedType.CELL_WORD.on(this)
            maxLines = 1
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginStart = SeedStyle.dp(this, 10f)
        cell.addView(word, lp)
        if (revealed) {
            cell.contentDescription = "${i + 1}, ${session.words[i]}"
        } else {
            cell.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        return cell
    }

    /** TAP TO REVEAL over `content`, until it is tapped once for the screen. What is under it is stand-ins, never the words. */
    private fun veiled(content: View): View {
        if (revealed) return content
        val box = FrameLayout(this)
        box.addView(content)
        val shade = View(this).apply { background = SeedStyle.rounded(this@ShowWordsActivity, Color.argb(200, 10, 10, 10), 15f) }
        box.addView(shade, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val pill = TextView(this).apply {
            text = getString(R.string.seed_tap_to_reveal)
            setTextColor(SeedStyle.INK)
            SeedType.REVEAL.on(this)
            gravity = Gravity.CENTER
            background = SeedStyle.rounded(this@ShowWordsActivity, Color.argb(158, 0, 0, 0), 24f, SeedStyle.ink(0.16))
            setPadding(SeedStyle.dp(this@ShowWordsActivity, 24f), 0, SeedStyle.dp(this@ShowWordsActivity, 24f), 0)
        }
        box.addView(pill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, SeedStyle.dp(this, 48f), Gravity.CENTER))
        val veil = View(this).apply {
            contentDescription = getString(R.string.seed_tap_to_reveal_label)
            isClickable = true
            setOnClickListener { reveal() }
        }
        box.addView(veil, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return box
    }

    private fun reveal() {
        revealed = true
        build()
    }

    private fun buildQuiz() {
        val tiles = FlowLayout(this)
        val stack = column(page, veiled(tiles))
        spaced(stack, SeedStyle.title(this, getString(R.string.seed_verify)), 8f)
        val sub = SeedStyle.body(this, getString(R.string.seed_verify_body), color = SeedStyle.INK)
        spaced(stack, sub, 14f)
        spaced(stack, SeedStyle.rule(this), 16f)
        val slots = FlowLayout(this)
        stack.addView(slots)
        quizSubtitle = sub
        this.slots = slots
        this.tiles = tiles
        drawQuiz()
    }

    private fun drawQuiz() {
        val placed = ArrayList<View>()
        for ((n, word) in quiz.picked.withIndex()) placed.add(SeedStyle.chip(this, word, n + 1, SeedStyle.Chip.PLACED))
        for (k in quiz.picked.size until session.words.size) placed.add(SeedStyle.chip(this, (k + 1).toString(), null, SeedStyle.Chip.EMPTY))
        slots?.setItems(placed)
        tiles?.setItems(quiz.pool.mapIndexed { tile, word ->
            if (!revealed) {
                // a stand-in under the veil: nothing of a word is drawn
                SeedStyle.chip(this, "••••••", null, SeedStyle.Chip.TILE).apply {
                    isEnabled = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            } else {
                SeedStyle.chip(this, word, null, if (quiz.used[tile]) SeedStyle.Chip.USED else SeedStyle.Chip.TILE).apply {
                    setOnClickListener { tap(tile) }
                }
            }
        })
    }

    private fun tap(tile: Int) {
        when (val t = quiz.tap(tile)) {
            SeedQuiz.Tap.Ignored -> return
            is SeedQuiz.Tap.Wrong -> {
                quizSubtitle?.text = getString(R.string.seed_wrong_word, t.expected)
                quizSubtitle?.setTextColor(SeedStyle.RED)
                quizSubtitle?.performHapticFeedback(io.islandbitcoin.foxy.web.Haptics.constant("error"))
            }
            SeedQuiz.Tap.Right -> {
                quizSubtitle?.text = getString(R.string.seed_verify_body)
                quizSubtitle?.setTextColor(SeedStyle.INK)
            }
            SeedQuiz.Tap.Done -> {
                Log.line("[foxy] seed phrase verified")
                show(Stage.BACKED_UP)
                return
            }
        }
        drawQuiz()
    }

    private fun buildBackedUp() {
        val done = SeedStyle.primaryButton(this, getString(R.string.seed_done))
        done.setOnClickListener {
            val verified = quiz.isComplete
            val shown = revealed
            finish { session.done(verified, false, shown) }
        }
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val pad = SeedStyle.dp(this@ShowWordsActivity, 22f)
            setPadding(pad, 0, pad, 0)
        }
        val tick = TextView(this).apply {
            text = "✓"
            setTextColor(SeedStyle.ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = SeedStyle.rounded(this@ShowWordsActivity, SeedStyle.withAlpha(SeedStyle.ACCENT, 0.16), 44f)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val ring = SeedStyle.dp(this, 88f)
        val tickLp = LinearLayout.LayoutParams(ring, ring)
        tickLp.bottomMargin = SeedStyle.dp(this, 16f)
        holder.addView(tick, tickLp)
        spaced(holder, SeedStyle.title(this, getString(R.string.seed_backed_up)), 6f)
        holder.addView(SeedStyle.body(this, getString(R.string.seed_backed_up_body)))
        page.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val bottom = FrameLayout(this)
        val pad = SeedStyle.dp(this, 22f)
        bottom.setPadding(pad, 0, pad, SeedStyle.dp(this, 12f))
        bottom.addView(done)
        page.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}
