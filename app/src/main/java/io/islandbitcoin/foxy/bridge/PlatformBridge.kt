package io.islandbitcoin.foxy.bridge

import io.islandbitcoin.foxy.debug.Log
import io.islandbitcoin.foxy.keychain.SeedEnvironment
import java.util.concurrent.Executor

/**
 * The P3 platform actions (FoxyBridge.swift): the camera (full screen and the
 * live pane), the paste button and the clipboard, copy, share, notifications,
 * the PIN lock's biometric, this app's settings and the four company sites, and
 * whether the screen is captured. The rules are [PlatformRules] and
 * [ClipboardFilter]; the doors are [PageHost]'s (WebHost on a phone, FakeHost
 * in the tests). Nothing the page writes is shown, opened or notified: it picks
 * from native's fixed lists or gets the default or a refusal (S24), and a
 * native surface it positions must sit where it is allowed (S25).
 */
class PlatformBridge(
    private val host: PageHost,
    private val resolve: (id: String, text: String?, error: String?) -> Unit,
    private val environment: () -> SeedEnvironment,
    private val seedExecutor: Executor,
    clock: () -> Long,
) {
    companion object {
        val ACTIONS = setOf(
            "captured", "scan", "previewStart", "previewStop", "pasteStart", "pasteStop",
            "clipboard", "copy", "share", "notify", "biometric", "openSettings", "openCompany",
        )

        /** A push into the page, guarded so a page still booting or gone does nothing. */
        fun push(name: String, arg: String?): String =
            "window.FoxyWallet && window.FoxyWallet.$name && window.FoxyWallet.$name(${FoxyBridge.literal(arg)})"

        fun pushNoArgument(name: String): String = "window.FoxyWallet && window.FoxyWallet.$name && window.FoxyWallet.$name()"
    }

    private val throttle = PlatformRules.NotifyThrottle(clock)

    private fun evaluate(js: String) = host.post { host.evaluate(js) }

    private fun wordlist(): List<String>? = try {
        environment().wordlist()
    } catch (e: Exception) {
        null
    }

    /** A screenshot was taken (Activity.ScreenCaptureCallback, API 34+): the page can say what it holds. */
    fun pushScreenshot() = evaluate(pushNoArgument("_screenshot"))

    /** The page went, or Foxy was put away: no camera pane or paste button of its stays over what comes next (I7). Main thread. */
    fun letGo() {
        host.previewStop()
        host.pasteStop()
    }

    fun handle(action: String, id: String, body: Map<String, Any?>) {
        when (action) {
            "captured" -> host.post { resolve(id, if (host.captured()) "yes" else "no", null) }
            "scan" -> {
                val prompt = PlatformRules.scannerPrompt(body["prompt"])
                host.post { host.scan(prompt) { text, error -> resolve(id, text, error) } }
            }
            "previewStart" -> previewStart(id, PlatformRules.rect(body))
            "previewStop" -> host.post {
                host.previewStop()
                resolve(id, "", null)
            }
            "pasteStart" -> pasteStart(id, PlatformRules.rect(body))
            "pasteStop" -> host.post {
                host.pasteStop()
                resolve(id, "", null)
            }
            // read only now, because the page asked (a tap), never at launch or on focus; never a seed phrase
            "clipboard" -> host.post { resolve(id, ClipboardFilter.forPage(host.clipboardText(), wordlist()), null) }
            "copy" -> {
                val text = body["text"] as? String ?: ""
                val sensitive = body["sensitive"] as? Boolean ?: false
                if (text.isEmpty()) return resolve(id, null, ClipboardFilter.NOTHING_TO_COPY)
                host.post {
                    host.copy(text, sensitive)
                    resolve(id, "ok", null)
                }
            }
            "share" -> {
                val text = body["text"] as? String ?: ""
                if (text.isEmpty()) return resolve(id, null, PlatformRules.NOTHING_TO_SHARE)
                host.post {
                    val refused = host.share(text)
                    if (refused == null) resolve(id, "ok", null) else resolve(id, null, refused)
                }
            }
            "notify" -> notify(id, body["kind"])
            "biometric" -> biometric(id, PlatformRules.biometricReason(body["reason"]), body["passcode"] as? Boolean ?: false)
            "openSettings" -> host.post {
                val refused = host.openSettings()
                if (refused == null) resolve(id, "ok", null) else resolve(id, null, refused)
            }
            "openCompany" -> {
                val site = PlatformRules.companySite(body["which"]) ?: return resolve(id, null, PlatformRules.NOT_A_COMPANY)
                host.post {
                    val refused = host.openUrl(site)
                    if (refused == null) resolve(id, "ok", null) else resolve(id, null, refused)
                }
            }
            else -> resolve(id, null, "Unknown action: $action")
        }
    }

    /**
     * The live camera over the page's pane (FoxyBridge.swift `startPreview`): a
     * refusal is answered and pushed (`_previewRefused`), so a page that never
     * awaits the answer still hears it; each code read is pushed (`_liveScan`).
     */
    private fun previewStart(id: String, rect: PlatformRules.Rect) = host.post {
        fun refuse(why: String) {
            Log.line("[scan] the camera pane was refused: $why")
            resolve(id, null, why)
            host.evaluate(push("_previewRefused", why))
        }
        val size = host.pageSize() ?: return@post refuse(PlatformRules.NO_CAMERA_VIEW)
        PlatformRules.previewProblem(rect, size.first, size.second)?.let { return@post refuse(it) }
        val shown = host.previewShow(rect) { code -> evaluate(push("_liveScan", code)) }
        if (!shown) return@post refuse(PlatformRules.NO_CAMERA_VIEW)
        resolve(id, "ok", null)
    }

    /**
     * A native paste button over the page's PASTE button (ENG-649 decision 2,
     * as iOS lays its UIPasteControl): the clipboard is read only in its tap,
     * never a seed phrase, and the text pushed with `_pasted`. Refused anywhere
     * but where Foxy's paste buttons are (S25).
     */
    private fun pasteStart(id: String, rect: PlatformRules.Rect) = host.post {
        val size = host.pageSize() ?: return@post resolve(id, null, PlatformRules.NO_PASTE_VIEW)
        if (!PlatformRules.pasteAllowed(rect, size.first, size.second)) {
            Log.line("[foxy] paste control refused at ${rect.x.toInt()},${rect.y.toInt()} ${rect.w.toInt()}x${rect.h.toInt()} in ${size.first.toInt()}x${size.second.toInt()}")
            return@post resolve(id, null, PlatformRules.PASTE_REFUSED)
        }
        val shown = host.pasteShow(rect) { text -> evaluate(push("_pasted", ClipboardFilter.forPage(text, wordlist()))) }
        if (shown) resolve(id, "ok", null) else resolve(id, null, PlatformRules.NO_PASTE_VIEW)
    }

    /** A kind native knows, native's words, at most one every 3 s (FoxyBridge.swift `notify`). */
    private fun notify(id: String, kind: Any?) {
        val text = PlatformRules.notificationText(kind) ?: run {
            Log.line("[foxy] notification refused — unknown kind")
            return resolve(id, null, PlatformRules.UNKNOWN_NOTIFICATION)
        }
        if (!throttle.allow()) {
            Log.line("[foxy] notification dropped — too soon after the last")
            return resolve(id, "throttled", null)
        }
        // the permission, if Android asks it, is asked here; refused, the answer is still ok (iOS: a notification not shown)
        host.post { host.notify(PlatformRules.NOTIFY_TITLE, text) }
        resolve(id, "ok", null)
    }

    /**
     * The PIN lock (FoxyBridge.swift `handleBiometric`): a biometric, or with
     * `passcode` the screen lock too. With the seed behind the screen lock and
     * not yet opened, the prompt is the seed's own, so its yes opens the seed
     * and the read that follows asks nothing more; otherwise a prompt of its own.
     */
    private fun biometric(id: String, reason: String, passcode: Boolean) {
        seedExecutor.execute {
            val opened = try {
                environment().unlock(reason, passcode)
            } catch (e: Throwable) {
                Log.line("[foxy] biometric: the seed's own prompt failed (${e.javaClass.simpleName})")
                null
            }
            if (opened != null) {
                resolve(id, opened, null)
            } else {
                host.post { host.biometric(reason, passcode) { resolve(id, it, null) } }
            }
        }
    }
}
