package io.islandbitcoin.foxy.seed

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.islandbitcoin.foxy.R

/**
 * Foxy's look on its own seed screens, as the page draws it
 * (SeedScreens.swift `SeedStyle`): near-black, warm white, the orange, and bold
 * spaced capitals for titles and buttons. Plain Views (ENG-649 decision 4).
 */
object SeedStyle {
    val BACKGROUND = Color.rgb(10, 10, 10)
    val INK = Color.rgb(245, 241, 236)
    val ACCENT = Color.rgb(242, 128, 46)
    val RED = Color.rgb(255, 92, 92)
    val TILE = Color.rgb(23, 23, 26)
    val PLACED = Color.rgb(42, 42, 46)

    fun ink(alpha: Double): Int = Color.argb((alpha * 255).toInt(), 245, 241, 236)

    fun withAlpha(color: Int, alpha: Double): Int = Color.argb((alpha * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    fun rounded(context: Context, fill: Int, radiusDp: Float, stroke: Int? = null, strokeDp: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(context, radiusDp).toFloat()
            if (stroke != null) setStroke(dp(context, strokeDp), stroke)
        }

    fun title(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text.uppercase()
        setTextColor(INK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.06f
        gravity = Gravity.CENTER
        isFocusable = true
        isAccessibilityHeading = true
    }

    fun body(context: Context, text: String, sizeSp: Float = 18f, color: Int = ink(0.62)): TextView = TextView(context).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.CENTER
    }

    fun rule(context: Context): View = View(context).apply {
        setBackgroundColor(ink(0.12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1f))
    }

    /** The page's main button: full width, 60 dp, the orange. */
    fun primaryButton(context: Context, title: String): Button = Button(context).apply {
        isAllCaps = false
        stateListAnimator = null
        setPrimary(this, title, true)
        minHeight = dp(context, 60f)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 60f))
    }

    fun setPrimary(button: Button, title: String, enabled: Boolean) {
        val context = button.context
        button.isEnabled = enabled
        button.background = rounded(context, if (enabled) ACCENT else ink(0.14), 30f)
        button.text = title.uppercase()
        button.setTextColor(if (enabled) Color.WHITE else ink(0.4))
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        button.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        button.letterSpacing = 0.06f
    }

    fun backButton(context: Context): Button = Button(context).apply {
        isAllCaps = false
        stateListAnimator = null
        text = "←"
        setTextColor(INK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        background = rounded(context, ink(0.07), 26f, ink(0.16))
        contentDescription = context.getString(R.string.seed_back)
        val size = dp(context, 52f)
        minWidth = size
        minHeight = size
        layoutParams = LinearLayout.LayoutParams(size, size)
    }

    /**
     * The top of every seed screen: a lock and where the screen is. The page
     * draws nothing like it (review L8).
     */
    fun nativeBar(context: Context): TextView = TextView(context).apply {
        text = context.getString(R.string.seed_native_bar)
        setTextColor(INK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.CENTER
        setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_lock, 0, 0, 0)
        compoundDrawablePadding = dp(context, 7f)
        setPadding(dp(context, 16f), dp(context, 14f), dp(context, 16f), dp(context, 11f))
        setBackgroundColor(Color.rgb(28, 28, 30))
    }

    enum class Chip { TILE, USED, PLACED, EMPTY }

    /** A word in the quiz: a tile to tap, a tile used, a word placed with its number, or an empty numbered place. */
    fun chip(context: Context, text: String, number: Int?, kind: Chip): Button = Button(context).apply {
        isAllCaps = false
        stateListAnimator = null
        val label = SpannableStringBuilder()
        if (number != null) {
            label.append("$number  ")
            label.setSpan(ForegroundColorSpan(ink(0.45)), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            label.setSpan(RelativeSizeSpan(0.78f), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val start = label.length
        label.append(text)
        val colour = when (kind) {
            Chip.TILE, Chip.PLACED -> INK
            Chip.USED -> ink(0.24)
            Chip.EMPTY -> ink(0.38)
        }
        label.setSpan(ForegroundColorSpan(colour), start, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        this.text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(context, 14f), dp(context, 11f), dp(context, 14f), dp(context, 11f))
        background = when (kind) {
            Chip.TILE -> rounded(context, TILE, 16f, ink(0.16), 1.5f)
            Chip.USED -> rounded(context, ink(0.06), 16f)
            Chip.PLACED -> rounded(context, PLACED, 16f)
            Chip.EMPTY -> rounded(context, Color.TRANSPARENT, 16f, ink(0.22), 1.5f)
        }
        isEnabled = kind == Chip.TILE
    }
}

/** Views in centred rows that wrap, as the page's flex-wrap does (SeedScreens.swift `FlowView`). */
class FlowLayout(context: Context) : ViewGroup(context) {
    private val spacing = SeedStyle.dp(context, 8f)

    fun setItems(items: List<View>) {
        removeAllViews()
        items.forEach { addView(it) }
    }

    private class Row(val views: MutableList<View> = ArrayList(), var width: Int = 0, var height: Int = 0)

    private fun rows(width: Int): List<Row> {
        val rows = arrayListOf(Row())
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val w = minOf(child.measuredWidth, width)
            val row = rows.last()
            if (row.views.isNotEmpty() && row.width + spacing + w > width) rows.add(Row())
            val current = rows.last()
            current.width += (if (current.views.isEmpty()) 0 else spacing) + w
            current.height = maxOf(current.height, child.measuredHeight)
            current.views.add(child)
        }
        return rows
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        for (i in 0 until childCount) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        }
        val rows = rows(width)
        val height = if (childCount == 0) 0 else rows.sumOf { it.height } + spacing * (rows.size - 1)
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        var y = 0
        for (row in rows(width)) {
            var x = (width - row.width) / 2
            for (child in row.views) {
                val w = minOf(child.measuredWidth, width)
                val top = y + (row.height - child.measuredHeight) / 2
                child.layout(x, top, x + w, top + child.measuredHeight)
                x += w + spacing
            }
            y += row.height + spacing
        }
    }
}
