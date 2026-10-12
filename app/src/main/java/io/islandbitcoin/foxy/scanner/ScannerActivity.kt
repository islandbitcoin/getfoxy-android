package io.islandbitcoin.foxy.scanner

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import io.islandbitcoin.foxy.R
import io.islandbitcoin.foxy.bridge.PlatformRules
import io.islandbitcoin.foxy.debug.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The full-screen scanner (QRScannerViewController.swift): the camera, a frame
 * to aim with, the prompt from native's fixed list or "Point at the code"
 * (S24), and Cancel. A plain code answers at once; an animated one keeps the
 * camera open, showing how much is in, until it is whole ([ScanSession]).
 * FLAG_SECURE like every window of Foxy's (S19). It finishes when it is left,
 * so the camera never runs behind anything else.
 *
 * The answer goes back in this process ([pending]), never through the Intent.
 */
class ScannerActivity : ComponentActivity() {
    companion object {
        const val PROMPT = "prompt"

        /** The one scan waiting for its answer. Main thread. */
        private var pending: ((String?, String?) -> Unit)? = null

        /**
         * Opens the scanner from `from`; `done(text, null)` or `done(null, why)`
         * once it is gone. False when a scanner is already open or there is
         * nothing to open it from.
         */
        fun start(from: Activity, prompt: String, done: (String?, String?) -> Unit): Boolean {
            if (pending != null || from.isFinishing || from.isDestroyed) return false
            pending = done
            try {
                from.startActivity(Intent(from, ScannerActivity::class.java).putExtra(PROMPT, prompt))
            } catch (e: RuntimeException) {
                pending = null
                return false
            }
            return true
        }

        /** For the instrumented tests: whether a scan is waiting. */
        val waiting: Boolean get() = pending != null
    }

    private var result: Pair<String?, String?> = null to PlatformRules.SCAN_CANCELLED
    private var answered = false
    private var finished = false
    private lateinit var label: TextView
    private lateinit var preview: PreviewView
    private var provider: ProcessCameraProvider? = null
    private val analysis: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "foxy-scan") }
    private val session = ScanSession({ SystemClock.elapsedRealtime() }, live = false)
    private val decoder by lazy { QrDecoder() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (savedInstanceState != null || pending == null) {
            super.finish()
            return
        }
        val prompt = intent.getStringExtra(PROMPT)?.takeIf { it in PlatformRules.SCANNER_PROMPTS } ?: ""
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val side = (resources.displayMetrics.widthPixels * 0.7).toInt()
        val aim = android.view.View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(16f).toFloat()
                setStroke(dp(2f), Color.argb(230, 255, 255, 255))
            }
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(aim, FrameLayout.LayoutParams(side, side, Gravity.CENTER))
        label = TextView(this).apply {
            text = prompt.ifEmpty { getString(R.string.scan_point_at_code) }
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            gravity = Gravity.CENTER
        }
        val labelLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        // centred, then lifted: its middle sits above the frame (a centred view moves by half its margin)
        labelLp.bottomMargin = side + dp(96f)
        labelLp.leftMargin = dp(32f)
        labelLp.rightMargin = dp(32f)
        root.addView(label, labelLp)
        val cancel = Button(this).apply {
            isAllCaps = false
            text = getString(R.string.scan_cancel)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { answer(null, PlatformRules.SCAN_CANCELLED) }
        }
        val cancelLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        cancelLp.bottomMargin = dp(48f)
        root.addView(cancel, cancelLp)
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = answer(null, PlatformRules.SCAN_CANCELLED)
        })
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return answer(null, PlatformRules.CAMERA_DENIED)
        }
        startCamera()
    }

    private fun dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (finished) return@addListener
            val p = try {
                future.get()
            } catch (e: Exception) {
                return@addListener answer(null, PlatformRules.NO_CAMERA)
            }
            provider = p
            val usePreview = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            val useAnalysis = ScanCamera.analysis()
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
                    runOnUiThread { handle(next) }
                }
            }
            try {
                if (!p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) return@addListener answer(null, PlatformRules.NO_CAMERA)
                p.unbindAll()
                val camera = p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, usePreview, useAnalysis)
                ScanCamera.focusClose(camera)
            } catch (e: Exception) {
                Log.line("[scan] the camera would not open (${e.javaClass.simpleName})")
                answer(null, PlatformRules.NO_CAMERA)
            }
        }, mainExecutor)
    }

    private fun handle(next: ScanSession.Next) {
        if (finished) return
        when (next) {
            ScanSession.Next.Nothing -> {}
            is ScanSession.Next.Hand -> {
                label.performHapticFeedback(io.islandbitcoin.foxy.web.Haptics.constant("success"))
                answer(next.text, null)
            }
            is ScanSession.Next.Progress -> {
                // the camera stays open until every frame needed is in
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
                label.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                label.text = getString(R.string.scan_progress, next.percent)
            }
            ScanSession.Next.Rejected -> label.text = getString(R.string.scan_animated_unread)
        }
    }

    /** Takes the scanner down, then answers once it is gone. A steady hand reads twice: only the first counts. */
    private fun answer(text: String?, error: String?) {
        if (finished) return
        finished = true
        result = text to error
        provider?.unbindAll()
        finish()
    }

    /** Left (Foxy put away, or anything over it): cancelled, and the camera let go. */
    override fun onStop() {
        super.onStop()
        if (!finished) answer(null, PlatformRules.SCAN_CANCELLED)
    }

    override fun onDestroy() {
        super.onDestroy()
        analysis.shutdown()
        if (!answered && pending != null && isFinishing) {
            answered = true
            val done = pending
            pending = null
            done?.invoke(result.first, result.second)
        }
    }
}
