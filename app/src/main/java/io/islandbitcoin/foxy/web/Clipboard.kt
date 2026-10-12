package io.islandbitcoin.foxy.web

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import io.islandbitcoin.foxy.bridge.ClipboardFilter
import io.islandbitcoin.foxy.debug.Log
import java.security.SecureRandom

/**
 * Foxy's side of the clipboard ([ClipboardFilter] holds the rules), for the
 * process: the page's `clipboard` and `copy`, the paste button, the restore
 * screen. Main thread only.
 *
 * Read only when asked ([read]: a tap), never at launch or on focus. Every copy
 * Foxy makes is labelled with a fresh nonce; a sensitive one is marked
 * `android.content.extra.IS_SENSITIVE` and cleared [ClipboardFilter.EXPIRE_MS]
 * later if it is still that copy, by a timer that lives as long as the process
 * and is looked at again whenever Foxy's window gets focus ([clearIfExpired]),
 * the only time Android 10+ lets an app see the clipboard at all (S23).
 */
class Clipboard(context: Context, private val clock: () -> Long = { SystemClock.elapsedRealtime() }) {
    private val manager: ClipboardManager? = context.getSystemService(ClipboardManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val random = SecureRandom()

    /** How many times the clipboard's text was read: the instrumented test of "no read without a tap". */
    @Volatile var reads = 0
        private set

    private var sensitiveNonce: String? = null
    private var copiedAt: Long? = null

    /** The text on the clipboard, read now; "" for none. A content URI is never followed. */
    fun read(): String {
        reads += 1
        val clip = manager?.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).text?.toString() ?: ""
    }

    /** Copies `text`; `sensitive` (tokens, anything that is money): marked, and cleared after 120 s if still Foxy's. */
    fun copy(text: String, sensitive: Boolean) {
        val m = manager ?: return
        val bytes = ByteArray(8)
        random.nextBytes(bytes)
        val nonce = bytes.joinToString("") { "%02x".format(it) }
        val clip = ClipData.newPlainText(ClipboardFilter.label(nonce), text)
        if (sensitive) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipboardFilter.SENSITIVE_EXTRA, true) }
        }
        m.setPrimaryClip(clip)
        if (sensitive) {
            sensitiveNonce = nonce
            copiedAt = clock()
            main.postDelayed({ clearIfExpired() }, ClipboardFilter.EXPIRE_MS)
            Log.line("[foxy] copied ${text.length} chars, marked sensitive, cleared in 120s if still Foxy's")
        } else {
            sensitiveNonce = null
            copiedAt = null
        }
    }

    /** The sensitive copy, cleared if its time is up and it is still the one Foxy made. Needs the window's focus on Android 10+. */
    fun clearIfExpired() {
        val m = manager ?: return
        val label = try {
            m.primaryClipDescription?.label
        } catch (e: RuntimeException) {
            return
        }
        if (!ClipboardFilter.expired(label, sensitiveNonce, copiedAt, clock())) return
        clear()
        Log.line("[foxy] a sensitive copy was cleared from the clipboard after 120s")
    }

    /** Empties the clipboard (API 28+), and forgets the copy it held. */
    fun clear() {
        sensitiveNonce = null
        copiedAt = null
        try {
            manager?.clearPrimaryClip()
        } catch (e: RuntimeException) {
            Log.line("[foxy] the clipboard could not be cleared (${e.javaClass.simpleName})")
        }
    }

    /** Whether the clipboard holds a copy Foxy made (the restore screen refuses it, M8). */
    fun isFoxys(): Boolean = try {
        ClipboardFilter.isFoxys(manager?.primaryClipDescription?.label)
    } catch (e: RuntimeException) {
        false
    }
}
