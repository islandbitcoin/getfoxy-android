package io.islandbitcoin.foxy.seed

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import io.islandbitcoin.foxy.debug.Log
import java.lang.ref.WeakReference

/**
 * What every seed screen shares (SeedScreens.swift `SeedScreenController`):
 * its own window, FLAG_SECURE like the activity's (S19), the native bar on top,
 * Foxy's colours inside, and one answer, given once the screen is gone so the
 * next one in the queue never draws over it. A screen is finished when it stops
 * (S20): one left up would sit over the page's PIN lock when Foxy came back,
 * showing the words to whoever holds the phone, so it closes with the answer its
 * back button gives.
 *
 * What a screen shows is handed over in this process ([SeedScreenHost]), never
 * in the Intent: the words never pass through Android's system server.
 */
abstract class SeedScreenActivity : ComponentActivity() {
    private var answer: (() -> Unit)? = null
    private var answered = false
    protected lateinit var root: LinearLayout
        private set

    /** The screen's own state from [SeedScreenHost]; false when there is none (it was closed before it opened). */
    protected abstract fun take(): Boolean

    /** Closed without finishing: the back button, Foxy going away, or the page going. Each screen gives its own answer. */
    abstract fun close()

    /** The back button: leaves the screen, unless a screen has somewhere of its own to go back to first. */
    open fun backTapped() = close()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.setBackgroundColor(SeedStyle.BACKGROUND)
        if (savedInstanceState != null || !take()) {
            // a screen Android brought back by itself, or one closed before it opened: nothing to show
            Log.line("[foxy] a seed screen opened with nothing to show; closed")
            super.finish()
            return
        }
        SeedScreenHost.opened(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(SeedStyle.nativeBar(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = backTapped()
        })
        build()
        // felt as the screen opens (L8)
        root.performHapticFeedback(io.islandbitcoin.foxy.web.Haptics.constant("success"))
    }

    protected abstract fun build()

    /** Foxy went to the background, or something covered the screen: it closes (S20). */
    override fun onStop() {
        super.onStop()
        if (!isFinishing) {
            Log.line("[foxy] a seed screen was left; closed")
            close()
        }
    }

    /** Takes the screen down, then answers once it is gone. Only the first call counts. */
    protected fun finish(answer: () -> Unit) {
        if (this.answer != null || answered) return
        this.answer = answer
        currentFocus?.clearFocus()
        super.finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        SeedScreenHost.gone(this)
        if (!answered) {
            answered = true
            answer?.invoke()
        }
    }

    /** A back button at the top, a scrolling column under it, and `bottom` pinned below it. */
    protected fun column(into: LinearLayout, bottom: android.view.View?): LinearLayout {
        val pad = SeedStyle.dp(this, 22f)
        val back = SeedStyle.backButton(this)
        back.setOnClickListener { backTapped() }
        val top = FrameLayout(this)
        top.setPadding(pad, SeedStyle.dp(this, 8f), pad, SeedStyle.dp(this, 6f))
        top.addView(back)
        into.addView(top)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val stack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, SeedStyle.dp(this@SeedScreenActivity, 8f), pad, SeedStyle.dp(this@SeedScreenActivity, 16f))
        }
        scroll.addView(stack, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        into.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (bottom != null) {
            val holder = FrameLayout(this)
            holder.setPadding(pad, SeedStyle.dp(this, 10f), pad, SeedStyle.dp(this, 12f))
            holder.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            into.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return stack
    }

    protected fun spaced(stack: LinearLayout, view: android.view.View, afterDp: Float) {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = SeedStyle.dp(this, afterDp)
        if (view.layoutParams is LinearLayout.LayoutParams) {
            val given = view.layoutParams as LinearLayout.LayoutParams
            given.bottomMargin = lp.bottomMargin
            stack.addView(view, given)
        } else {
            stack.addView(view, lp)
        }
    }
}

/**
 * Where a seed screen's state waits between the bridge asking for it and its
 * activity opening, in this process only, and the one screen up now. Main
 * thread only.
 */
object SeedScreenHost {
    class Show(val words: List<String>, val verify: Boolean, val passcodeSet: Boolean, val done: (Boolean, Boolean, Boolean) -> Unit)
    class Enter(val wordlist: List<String>, val done: (String?) -> Unit)

    /** A screen asked for and not opened yet. */
    private var waiting: Any? = null
    private var current: WeakReference<SeedScreenActivity>? = null
    private val main = Handler(Looper.getMainLooper())

    /** How long a screen may take to open before it is answered as closed (SeedScreens.swift `present`). */
    const val OPEN_WAIT_MS = 2_000L

    /** For the instrumented tests: the seed screen up now. */
    val shown: SeedScreenActivity? get() = current?.get()

    fun showWords(from: Activity, session: Show): Boolean = start(from, session, ShowWordsActivity::class.java)

    fun enterWords(from: Activity, session: Enter): Boolean = start(from, session, EnterWordsActivity::class.java)

    private fun start(from: Activity, session: Any, screen: Class<out SeedScreenActivity>): Boolean {
        if (from.isFinishing || from.isDestroyed) return false
        waiting = session
        try {
            from.startActivity(Intent(from, screen))
        } catch (e: RuntimeException) {
            waiting = null
            return false
        }
        // an activity Android would not open would leave the queue waiting for good, and the page for its answer
        main.postDelayed({
            if (waiting === session) {
                Log.line("[foxy] a seed screen did not appear; answered as closed")
                answerClosed(session)
            }
        }, OPEN_WAIT_MS)
        return true
    }

    internal fun takeShow(): Show? = (waiting as? Show).also { if (it != null) waiting = null }

    internal fun takeEnter(): Enter? = (waiting as? Enter).also { if (it != null) waiting = null }

    internal fun opened(screen: SeedScreenActivity) {
        current = WeakReference(screen)
    }

    internal fun gone(screen: SeedScreenActivity) {
        if (current?.get() === screen) current = null
    }

    private fun answerClosed(session: Any?) {
        if (waiting === session) waiting = null
        when (session) {
            is Show -> session.done(false, false, false)
            is Enter -> session.done(null)
        }
    }

    /** Foxy went away, the page went, or the seed changed: the screen up closes, and one not open yet is answered as closed. */
    fun closeAll() {
        answerClosed(waiting)
        current?.get()?.close()
    }
}
