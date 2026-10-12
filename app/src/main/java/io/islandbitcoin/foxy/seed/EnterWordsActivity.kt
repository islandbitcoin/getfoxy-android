package io.islandbitcoin.foxy.seed

import android.content.ClipboardManager
import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.ActionMode
import android.view.DragEvent
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import io.islandbitcoin.foxy.FoxyApplication
import io.islandbitcoin.foxy.R
import io.islandbitcoin.foxy.bridge.ClipboardFilter
import io.islandbitcoin.foxy.debug.Log
import io.islandbitcoin.foxy.keychain.BIP39

/**
 * seedEnter: twelve words typed or pasted for a restore, checked against the
 * wordlist and the checksum before they leave this screen
 * (SeedScreens.swift `SeedEnterController`). The answer, once it is gone: the
 * checked phrase, or null when closed. The page is told only a candidate id.
 *
 * What the keyboard may do to a seed word: nothing. No suggestions, no
 * learning (`IME_FLAG_NO_PERSONALIZED_LEARNING`, S30), no autofill; a cell's
 * words cannot be copied, cut, shared or dragged out ([SeedField]); words Foxy
 * itself copied are refused (M8), and a pasted phrase is cleared from the
 * clipboard once it is in, unless something was copied since (L7).
 */
class EnterWordsActivity : SeedScreenActivity() {
    private lateinit var session: SeedScreenHost.Enter
    private val cells = MutableList(SeedEntry.CELLS) { "" }
    private val fields = ArrayList<SeedField>()
    private val frames = ArrayList<View>()
    private var focused: Int? = null
    private var pasteNotice: String? = null
    /** An allowed paste on its way into the cells: the clip's timestamp then, and when. */
    private var pendingPaste: Pair<Long?, Long>? = null
    private var editing = false
    private lateinit var subtitle: TextView
    private lateinit var note: TextView
    private lateinit var cta: Button
    private lateinit var bar: LinearLayout

    override fun take(): Boolean {
        session = SeedScreenHost.takeEnter() ?: return false
        return true
    }

    override fun build() {
        cta = SeedStyle.primaryButton(this, getString(R.string.seed_look_for_balances))
        cta.setOnClickListener { submit() }
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(SeedStyle.dp(this@EnterWordsActivity, 6f), SeedStyle.dp(this@EnterWordsActivity, 6f), SeedStyle.dp(this@EnterWordsActivity, 6f), SeedStyle.dp(this@EnterWordsActivity, 6f))
        }
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bottom.addView(HorizontalScrollView(this).apply { addView(bar) })
        bottom.addView(cta)
        val stack = column(root, bottom)
        spaced(stack, SeedStyle.title(this, getString(R.string.seed_restore)), 8f)
        subtitle = SeedStyle.body(this, "")
        spaced(stack, subtitle, 16f)
        spaced(stack, SeedStyle.rule(this), 16f)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (first in 0 until SeedEntry.CELLS step 2) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(cell(first), LinearLayout.LayoutParams(0, SeedStyle.dp(this, 54f), 1f))
            val lp = LinearLayout.LayoutParams(0, SeedStyle.dp(this, 54f), 1f)
            lp.marginStart = SeedStyle.dp(this, 9f)
            row.addView(cell(first + 1), lp)
            val rowLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            rowLp.bottomMargin = SeedStyle.dp(this, 9f)
            grid.addView(row, rowLp)
        }
        spaced(stack, grid, 12f)
        note = SeedStyle.body(this, "", sizeSp = 16f, color = SeedStyle.RED)
        stack.addView(note)
        refresh()
    }

    override fun close() {
        clear()
        finish { session.done(null) }
    }

    private fun cell(i: Int): View {
        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(SeedStyle.dp(this@EnterWordsActivity, 13f), 0, SeedStyle.dp(this@EnterWordsActivity, 10f), 0)
        }
        val number = TextView(this).apply {
            text = (i + 1).toString()
            setTextColor(SeedStyle.ink(0.38))
            SeedType.CELL_NUMBER.on(this)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        frame.addView(number, LinearLayout.LayoutParams(SeedStyle.dp(this, 22f), ViewGroup.LayoutParams.WRAP_CONTENT))
        val field = SeedField(this, i)
        field.contentDescription = getString(R.string.seed_word_label, i + 1)
        field.imeOptions = (if (i == SeedEntry.CELLS - 1) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT) or
            SeedField.NO_LEARNING
        field.setOnEditorActionListener { _, _, _ ->
            if (i + 1 < SeedEntry.CELLS) fields[i + 1].requestFocus() else field.clearFocus()
            true
        }
        field.setOnFocusChangeListener { _, has ->
            if (has) focused = i else if (focused == i) focused = null
            refresh()
        }
        field.shouldPaste = { pasteAllowed() }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!editing) typed(i, s?.toString() ?: "")
            }
        })
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        lp.marginStart = SeedStyle.dp(this, 8f)
        frame.addView(field, lp)
        fields.add(field)
        frames.add(frame)
        return frame
    }

    /** A cell's text changed: typed, or pasted. Several words fill this cell and the ones after it. */
    private fun typed(index: Int, proposed: String) {
        pasteNotice = null
        val updated = SeedEntry.apply(proposed, index, cells)
        for (k in cells.indices) cells[k] = updated[k]
        editing = true
        for ((k, field) in fields.withIndex()) {
            if (field.text.toString() != cells[k]) {
                field.setText(cells[k])
                field.setSelection(cells[k].length)
            }
        }
        editing = false
        val count = SeedEntry.parts(proposed).size
        val paste = pendingPaste
        if (count > 1 && paste != null && System.currentTimeMillis() - paste.second < 2_000) {
            pendingPaste = null
            val filled = minOf(count, SeedEntry.CELLS - index)
            // the phrase is in the cells; off the clipboard, unless something else was copied since (L7)
            if (SeedEntry.clearAfterPaste(filled, paste.first, clipStamp())) {
                (application as FoxyApplication).services.clipboard.clear()
                Log.line("[foxy] a phrase pasted into the restore screen was cleared from the clipboard")
            }
        }
        if (count > 1) {
            // a pasted phrase: on to the cell after its last word
            fields[minOf(index + count, SeedEntry.CELLS - 1)].requestFocus()
        } else if (proposed.endsWith(" ") && cells[index].isNotEmpty() && index + 1 < SeedEntry.CELLS) {
            // a space after a word means the next one
            fields[index + 1].requestFocus()
        }
        refresh()
    }

    private fun clipStamp(): Long? = getSystemService(ClipboardManager::class.java)?.primaryClipDescription?.timestamp

    /**
     * Asked by a cell before a paste. Words Foxy put on the clipboard itself (a
     * copy the page asked for) are refused: a page could otherwise copy words it
     * knows and lead the person to restore them as their own (M8). Typing is
     * never asked about.
     */
    private fun pasteAllowed(): Boolean {
        val clipboard = (application as FoxyApplication).services.clipboard
        if (clipboard.isFoxys()) {
            Log.line("[foxy] a paste of text Foxy copied was refused on the restore screen")
            pasteNotice = ClipboardFilter.PASTE_REFUSAL
            pendingPaste = null
            note.performHapticFeedback(io.islandbitcoin.foxy.web.Haptics.constant("error"))
            refresh()
            return false
        }
        pasteNotice = null
        pendingPaste = clipStamp() to System.currentTimeMillis()
        return true
    }

    private fun pick(word: String) {
        val i = focused ?: return
        cells[i] = word
        editing = true
        fields[i].setText(word)
        editing = false
        if (i + 1 < SeedEntry.CELLS) fields[i + 1].requestFocus() else fields[i].clearFocus()
        refresh()
    }

    private fun refresh() {
        if (!::note.isInitialized) return
        val check = SeedEntry.check(cells, session.wordlist)
        for ((i, frame) in frames.withIndex()) {
            // the word being typed is not wrong yet
            val wrong = i in check.unknown && focused != i
            val stroke = when {
                wrong -> SeedStyle.RED
                cells[i].isEmpty() -> SeedStyle.ink(0.12)
                else -> SeedStyle.withAlpha(SeedStyle.ACCENT, 0.45)
            }
            frame.background = SeedStyle.rounded(this, SeedStyle.ink(0.05), 15f, stroke)
        }
        note.text = SeedEntry.note(check, focused, pasteNotice)
        subtitle.text = SeedEntry.subtitle(check)
        SeedStyle.setPrimary(cta, getString(R.string.seed_look_for_balances), check.valid)
        cta.visibility = if (check.valid) View.VISIBLE else View.GONE
        // the words from the list that start with what is typed, above the keyboard
        bar.removeAllViews()
        val typed = focused?.let { cells[it] } ?: ""
        for (word in BIP39.suggestions(typed, session.wordlist)) {
            val b = Button(this).apply {
                isAllCaps = false
                text = word
                setTextColor(SeedStyle.INK)
                SeedType.SUGGESTION.on(this)
                background = SeedStyle.rounded(this@EnterWordsActivity, SeedStyle.ink(0.12), 8f)
                setOnClickListener { pick(word) }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, SeedStyle.dp(this, 40f))
            lp.marginEnd = SeedStyle.dp(this, 6f)
            bar.addView(b, lp)
        }
    }

    private fun submit() {
        if (!SeedEntry.check(cells, session.wordlist).valid) return
        val phrase = SeedEntry.phrase(cells)
        clear()
        finish { session.done(phrase) }
    }

    /** The typed words off the screen and out of this screen's cells. */
    private fun clear() {
        for (k in cells.indices) cells[k] = ""
        editing = true
        fields.forEach { it.setText("") }
        editing = false
        pendingPaste = null
        pasteNotice = null
    }
}

/**
 * A restore cell (SeedScreens.swift `SeedField`): what it holds cannot be
 * copied, cut, shared or dragged anywhere, nothing is dropped into it, and a
 * paste asks [shouldPaste] first.
 */
class SeedField(context: Context, index: Int) : EditText(context) {
    companion object {
        /** EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING (API 26). */
        const val NO_LEARNING = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING

        /** Lowercase letters, nothing suggested or corrected, never learned. */
        const val INPUT = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD

        private val REFUSED = setOf(android.R.id.copy, android.R.id.cut, android.R.id.shareText)
    }

    var shouldPaste: () -> Boolean = { true }

    init {
        id = View.generateViewId()
        tag = index
        inputType = INPUT
        imeOptions = NO_LEARNING
        isSingleLine = true
        background = null
        setTextColor(SeedStyle.INK)
        // the system font, 20 bold, as iOS's field (SeedScreens.swift:1255), not a monospace one
        SeedType.CELL_WORD.on(this)
        highlightColor = SeedStyle.withAlpha(SeedStyle.ACCENT, 0.4)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        isSaveEnabled = false
        val strip = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                strip(menu)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                strip(menu)
                return true
            }

            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = false
            override fun onDestroyActionMode(mode: ActionMode?) {}
        }
        customSelectionActionModeCallback = strip
        customInsertionActionModeCallback = strip
    }

    /** The edit menu without copy, cut, share, or any app's text actions (translate, look up). */
    private fun strip(menu: Menu?) {
        menu ?: return
        val keep = setOf(android.R.id.paste, android.R.id.pasteAsPlainText, android.R.id.selectAll)
        for (k in menu.size() - 1 downTo 0) {
            val item = menu.getItem(k)
            if (item.itemId !in keep) menu.removeItem(item.itemId)
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): android.view.inputmethod.InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs)
        outAttrs.imeOptions = outAttrs.imeOptions or NO_LEARNING
        return connection
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id in REFUSED) return false
        if ((id == android.R.id.paste || id == android.R.id.pasteAsPlainText) && !shouldPaste()) return false
        return super.onTextContextMenuItem(if (id == android.R.id.paste) android.R.id.pasteAsPlainText else id)
    }

    override fun onDragEvent(event: DragEvent?): Boolean = false
}
