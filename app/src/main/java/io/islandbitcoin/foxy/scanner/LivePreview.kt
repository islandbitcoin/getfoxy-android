package io.islandbitcoin.foxy.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import io.islandbitcoin.foxy.R
import io.islandbitcoin.foxy.debug.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A camera pane laid over the page at a rectangle the page measured, reading
 * QR codes continuously without taking over the screen (LiveScanner.swift).
 * Above the WebView rather than behind it: a hole through the page would make
 * every ancestor of the pane transparent, which is fragile; covering the pane
 * is not. It takes no touches, so taps still reach the page.
 *
 * The camera is let go explicitly ([stop]) when the page says so, when Foxy is
 * put away and when the activity stops, so the camera light never stays on in
 * the background; a stop with nothing up, or after the rectangle has gone
 * stale, does nothing. Main thread only.
 */
class LivePreview(private val activity: ComponentActivity, private val container: ViewGroup, private val above: View) {
    private var pane: FrameLayout? = null
    private lateinit var preview: PreviewView
    private lateinit var shade: FrameLayout
    private lateinit var words: TextView
    private lateinit var bar: ProgressBar
    private var provider: ProcessCameraProvider? = null
    private var useCases: List<UseCase> = emptyList()
    private var wanted = false
    private var onCode: ((String) -> Unit)? = null
    private var shownAt = 0L
    private var sawAnything = false
    private val session = ScanSession({ SystemClock.elapsedRealtime() }, live = true)
    private val analysis: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "foxy-live-scan") }
    private val decoder by lazy { QrDecoder() }

    /** Whether the camera is bound now (CameraUnbindTest). */
    val bound: Boolean get() = provider?.let { p -> useCases.any { p.isBound(it) } } ?: false

    /** Whether the pane is on screen. */
    val showing: Boolean get() = pane?.visibility == View.VISIBLE

    private fun build(): FrameLayout {
        pane?.let { return it }
        val context = activity
        val p = FrameLayout(context)
        p.setBackgroundColor(Color.BLACK)
        p.isClickable = false
        p.isFocusable = false
        p.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        p.clipToOutline = true
        preview = PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        p.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        shade = FrameLayout(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.argb(153, 0, 0, 0))
                cornerRadius = 22 * context.resources.displayMetrics.density
            }
            visibility = View.GONE
        }
        words = TextView(context).apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(242, 128, 46))
        }
        shade.addView(words, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        val barLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (10 * context.resources.displayMetrics.density).toInt(), Gravity.BOTTOM)
        val inset = (22 * context.resources.displayMetrics.density).toInt()
        barLp.setMargins(inset, 0, inset, (18 * context.resources.displayMetrics.density).toInt())
        shade.addView(bar, barLp)
        p.addView(shade)
        // above the page, in the page's container
        container.addView(p, container.indexOfChild(above) + 1, FrameLayout.LayoutParams(0, 0))
        p.visibility = View.GONE
        pane = p
        return p
    }

    /** The pane at `left, top, width, height` in pixels of the container; each code to `onCode`, on the main thread. */
    fun show(left: Int, top: Int, width: Int, height: Int, onCode: (String) -> Unit) {
        val p = build()
        val lp = FrameLayout.LayoutParams(width, height)
        lp.leftMargin = left
        lp.topMargin = top
        p.layoutParams = lp
        p.visibility = View.VISIBLE
        val density = activity.resources.displayMetrics.density
        val w = minOf(width - (40 * density).toInt(), (300 * density).toInt())
        val h = minOf(height - (24 * density).toInt(), (190 * density).toInt())
        shade.layoutParams = FrameLayout.LayoutParams(maxOf(w, 1), maxOf(h, 1), Gravity.CENTER)
        shade.visibility = View.GONE
        this.onCode = onCode
        // each pane starts over: an animated code half read for the last one is not this one's
        startOver()
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.line("[scan] the pane is up and camera access is off")
            return
        }
        shownAt = SystemClock.elapsedRealtime()
        sawAnything = false
        wanted = true
        Log.line("[scan] pane up, ${width}x$height px")
        if (bound) return
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            if (!wanted || bound) return@addListener
            val pr = try {
                future.get()
            } catch (e: Exception) {
                Log.line("[scan] no camera could be opened")
                return@addListener
            }
            provider = pr
            val usePreview = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            val useAnalysis: ImageAnalysis = ScanCamera.analysis()
            useAnalysis.setAnalyzer(analysis) { image ->
                val text = try {
                    decoder.decode(image)
                } catch (e: RuntimeException) {
                    null
                } finally {
                    image.close()
                }
                if (text != null) {
                    val next = session.read(text)
                    activity.runOnUiThread { handle(next) }
                }
            }
            try {
                if (!pr.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    Log.line("[scan] no camera could be opened")
                    return@addListener
                }
                val camera = pr.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, usePreview, useAnalysis)
                useCases = listOf(usePreview, useAnalysis)
                ScanCamera.focusClose(camera)
                Log.line("[scan] camera running")
            } catch (e: Exception) {
                Log.line("[scan] the camera would not open (${e.javaClass.simpleName})")
            }
        }, activity.mainExecutor)
    }

    /** The camera permission came after the pane went up: start it now, if the pane is still wanted there. */
    fun permissionGranted() {
        val p = pane ?: return
        if (p.visibility != View.VISIBLE || bound) return
        val lp = p.layoutParams as FrameLayout.LayoutParams
        onCode?.let { show(lp.leftMargin, lp.topMargin, lp.width, lp.height, it) }
    }

    private fun handle(next: ScanSession.Next) {
        if (!wanted) return
        if (!sawAnything) {
            sawAnything = true
            Log.line("[scan] first code read ${SystemClock.elapsedRealtime() - shownAt}ms after the pane went up")
        }
        when (next) {
            ScanSession.Next.Nothing -> {}
            is ScanSession.Next.Hand -> {
                shade.visibility = View.GONE
                pane?.performHapticFeedback(io.islandbitcoin.foxy.web.Haptics.constant("success"))
                Log.line("[scan] handing the page a code, ${next.text.length} chars")
                onCode?.invoke(next.text)
            }
            is ScanSession.Next.Progress -> say(next.percent, activity.getString(R.string.scan_live_progress))
            ScanSession.Next.Rejected -> say(null, activity.getString(R.string.scan_live_unread))
        }
    }

    /** The number, large, with what to do under it, and the same number as a bar. */
    private fun say(percent: Int?, text: String) {
        val out = SpannableStringBuilder()
        val density = activity.resources.displayMetrics.scaledDensity
        if (percent != null) {
            out.append("$percent%\n")
            out.setSpan(AbsoluteSizeSpan((64 * density).toInt()), 0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(StyleSpan(Typeface.BOLD), 0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            bar.progress = percent
            bar.visibility = View.VISIBLE
        } else {
            bar.visibility = View.GONE
        }
        val start = out.length
        out.append(text)
        out.setSpan(AbsoluteSizeSpan((16 * density).toInt()), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        words.text = out
        shade.visibility = View.VISIBLE
    }

    /** The animated-code reader starts over, on the decoder's own thread. */
    private fun startOver() {
        if (!analysis.isShutdown) analysis.execute { session.reset() }
    }

    /** The pane hidden and the camera let go. Idempotent; nothing to do when nothing is up. */
    fun stop() {
        val wasUp = pane?.visibility == View.VISIBLE
        pane?.visibility = View.GONE
        if (::shade.isInitialized) shade.visibility = View.GONE
        wanted = false
        onCode = null
        startOver()
        val p = provider
        if (p != null && useCases.isNotEmpty()) {
            p.unbind(*useCases.toTypedArray())
            useCases = emptyList()
        }
        if (wasUp) {
            Log.line("[scan] pane down after ${SystemClock.elapsedRealtime() - shownAt}ms, " + if (sawAnything) "having read a code" else "having read NOTHING")
        }
    }

    /** The page went: the camera let go, the pane taken out, the decoder's thread ended. */
    fun destroy() {
        stop()
        pane?.let { container.removeView(it) }
        pane = null
        analysis.shutdown()
    }
}
