package io.islandbitcoin.foxy.web

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.islandbitcoin.foxy.R
import io.islandbitcoin.foxy.debug.Log

/**
 * A native button laid over the page's PASTE button (PasteHost.swift; ENG-649
 * decision 2): see-through, so the page's own button shows, and it takes the
 * tap. The clipboard is read in its tap and nowhere else, then handed on
 * ([onPaste], which [io.islandbitcoin.foxy.bridge.ClipboardFilter] filters
 * before the page sees it). Placed only where PlatformRules.pasteAllowed says
 * (S25); a rectangle unchanged since the last ask leaves the button alone, so
 * the button under the person's finger is not replaced while they use it.
 * Main thread only.
 */
class PasteButton(private val context: Context, private val container: ViewGroup, private val above: View, private val clipboard: Clipboard) {
    private var view: View? = null
    private var at: IntArray? = null
    private var onPaste: ((String) -> Unit)? = null

    /** Whether a button is up (the instrumented tests). */
    val showing: Boolean get() = view != null

    /** The button itself, for the instrumented tests' tap. */
    val button: View? get() = view

    fun show(left: Int, top: Int, width: Int, height: Int, onPaste: (String) -> Unit) {
        this.onPaste = onPaste
        val place = intArrayOf(left, top, width, height)
        if (view != null && at.contentEquals(place)) return
        remove()
        val b = View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = context.getString(R.string.paste)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                // the one place the clipboard is read for the page's PASTE
                val text = clipboard.read()
                Log.line("[foxy] paste button: ${text.length} chars handed on")
                this@PasteButton.onPaste?.invoke(text)
            }
        }
        val lp = FrameLayout.LayoutParams(width, height)
        lp.leftMargin = left
        lp.topMargin = top
        container.addView(b, container.indexOfChild(above) + 1, lp)
        view = b
        at = place
        Log.line("[foxy] paste control placed at $left,$top ${width}x$height px")
    }

    fun remove() {
        view?.let { container.removeView(it) }
        view = null
        at = null
    }
}
