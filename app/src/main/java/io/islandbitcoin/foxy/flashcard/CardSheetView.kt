package io.islandbitcoin.foxy.flashcard

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import io.islandbitcoin.foxy.R

/**
 * Foxy's card sheet, drawn over the page: Android has no system NFC sheet, so
 * this stands where iOS's CoreNFC sheet stands, in its states and with its
 * words ([CardSheetModel]): a panel from the bottom over a dimmed page, "Ready
 * to Scan", a phone and a card, the page's line, and Cancel; a tick and a word,
 * or the reason in red, before it goes.
 *
 * It takes every touch while it is up (the page behind it is not to be used
 * mid-tap, as it cannot be under iOS's sheet), and Back is its Cancel. The
 * line is a polite live region, so TalkBack reads each new line as iOS's
 * VoiceOver reads the sheet's. Main thread only.
 */
class CardSheetView(
    private val context: Context,
    private val container: ViewGroup,
    private val back: OnBackPressedDispatcher?,
) : CardSheet {
    private val main = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var title: TextView? = null
    private var mark: ImageView? = null
    private var line: TextView? = null
    private var cancelButton: Button? = null
    private var model: CardSheetModel? = null
    private var cancel: (() -> Unit)? = null
    private var backCallback: OnBackPressedCallback? = null

    /** Whether the sheet is up (the instrumented tests). */
    val showing: Boolean get() = root != null

    private fun dp(value: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    override fun open(text: String, cancel: () -> Unit) {
        remove()
        this.cancel = cancel
        build()
        render(CardSheetModel.looking(text))
        backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = cancelled()
        }.also { back?.addCallback(it) }
    }

    override fun show(text: String) {
        model?.let { render(it.saying(text)) }
    }

    override fun finish(text: String) = closing(model?.finished(text))

    override fun fail(text: String) = closing(model?.failed(text))

    override fun dismiss() = remove()

    private fun closing(next: CardSheetModel?) {
        if (next == null) return
        cancel = null
        backCallback?.remove()
        backCallback = null
        render(next)
        val shown = root
        main.postDelayed({ if (root === shown) remove() }, next.holdMs ?: 0L)
    }

    private fun cancelled() {
        val c = cancel ?: return
        cancel = null
        c()
    }

    private fun build() {
        val ink = Color.rgb(245, 241, 236)
        val scrim = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            isClickable = true
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24f), dp(28f), dp(24f), dp(24f))
            background = GradientDrawable().apply {
                setColor(Color.rgb(28, 28, 30))
                cornerRadii = floatArrayOf(dp(28f).toFloat(), dp(28f).toFloat(), dp(28f).toFloat(), dp(28f).toFloat(), 0f, 0f, 0f, 0f)
            }
            isClickable = true
        }
        val t = TextView(context).apply {
            setTextColor(Color.argb(150, 245, 241, 236))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            isAccessibilityHeading = true
        }
        val m = ImageView(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val l = TextView(context).apply {
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            gravity = Gravity.CENTER
            maxLines = 3
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val c = Button(context).apply {
            text = CardSheetModel.CANCEL
            isAllCaps = false
            stateListAnimator = null
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.rgb(58, 58, 60))
                cornerRadius = dp(14f).toFloat()
            }
            setOnClickListener { cancelled() }
        }
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        val full = LinearLayout.LayoutParams.MATCH_PARENT
        panel.addView(t, LinearLayout.LayoutParams(full, wrap))
        panel.addView(m, LinearLayout.LayoutParams(dp(96f), dp(96f)).apply { topMargin = dp(22f); bottomMargin = dp(22f) })
        panel.addView(l, LinearLayout.LayoutParams(full, wrap).apply { bottomMargin = dp(24f) })
        panel.addView(c, LinearLayout.LayoutParams(full, dp(54f)))
        scrim.addView(panel, FrameLayout.LayoutParams(full, wrap, Gravity.BOTTOM))
        container.addView(scrim, ViewGroup.LayoutParams(full, full))
        panel.translationY = dp(360f).toFloat()
        panel.animate().translationY(0f).setDuration(220).start()
        root = scrim
        title = t
        mark = m
        line = l
        cancelButton = c
    }

    private fun render(next: CardSheetModel) {
        model = next
        title?.text = next.title
        line?.text = next.line
        line?.setTextColor(if (next.mark == CardSheetModel.Mark.FAILED) Color.rgb(255, 92, 92) else Color.rgb(245, 241, 236))
        mark?.setImageResource(
            when (next.mark) {
                CardSheetModel.Mark.LOOKING -> R.drawable.ic_card_sheet
                CardSheetModel.Mark.DONE -> R.drawable.ic_card_done
                CardSheetModel.Mark.FAILED -> R.drawable.ic_card_failed
            },
        )
        cancelButton?.visibility = if (next.cancellable) View.VISIBLE else View.INVISIBLE
    }

    private fun remove() {
        main.removeCallbacksAndMessages(null)
        backCallback?.remove()
        backCallback = null
        cancel = null
        root?.let { container.removeView(it) }
        root = null
        title = null
        mark = null
        line = null
        cancelButton = null
        model = null
    }
}
