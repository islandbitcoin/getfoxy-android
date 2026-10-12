package io.islandbitcoin.foxy.bridge

import io.islandbitcoin.foxy.debug.Log
import io.islandbitcoin.foxy.keychain.SeedEnvironment
import java.util.concurrent.Executor

/**
 * The P3 platform actions (FoxyBridge.swift): the camera (full screen and the
 * live pane), the paste button and the clipboard, copy, share, notifications,
 * the PIN lock's biometric, this app's settings and the four company sites, and
 * whether the screen is captured; and the first-install Bluetooth screen's
 * APPROVE (`bluetoothAsk`) and the receive screen's TAP (`tapReceiveStart`,
 * `tapReceiveStop`), answered "unsupported" until tap to pay is built
 * (ENG-650, ENG-656). The rules are [PlatformRules], [TapRules] and
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
            "bluetoothAsk", "tapReceiveStart", "tapReceiveStop",
        )

        /**
         * What `bluetoothAsk` answers until BLE tap to pay lands (ENG-650): one of
         * the contract's states, and the one that asks nothing of the phone.
         */
        const val BLUETOOTH_UNSUPPORTED = "unsupported"

        /** The gate's own global, not a FoxyWallet member: the gate is up before the wallet (FoxyBridge+Tap.swift `handleBluetoothAsk`). */
        fun bluetoothAnswer(state: String): String =
            "window.__foxyBluetoothAnswer && window.__foxyBluetoothAnswer(${FoxyBridge.literal(state)})"

        /** A push into the page, guarded so a page still booting or gone does nothing. */
        fun push(name: String, arg: String?): String =
            "window.FoxyWallet && window.FoxyWallet.$name && window.FoxyWallet.$name(${FoxyBridge.literal(arg)})"

        fun pushNoArgument(name: String): String = "window.FoxyWallet && window.FoxyWallet.$name && window.FoxyWallet.$name()"
    }

    private val throttle = PlatformRules.NotifyThrottle(clock)

    /**
     * Whether the page holds a receiver: set by a `tapReceiveStart` that was not
     * refused, cleared by `tapReceiveStop` and when the page goes. iOS's
     * `tapReceiver` (FoxyBridge+Tap.swift), which on a phone without Bluetooth
     * says `unsupported` once, when it is made. Main thread only.
     */
    private var receiverHeld = false

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
        // and no receiver held for a page that is not there (FoxyBridge.swift `pageGone` → `stopTap`)
        receiverHeld = false
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
            "bluetoothAsk" -> bluetoothAsk(id)
            "tapReceiveStart" -> tapReceiveStart(id, body["payload"] as? String ?: "")
            "tapReceiveStop" -> host.post {
                receiverHeld = false
                resolve(id, "ok", null)
            }
            else -> resolve(id, null, "Unknown action: $action")
        }
    }

    /**
     * The first-install Bluetooth screen's APPROVE (foxy-tor-gate.js): the page
     * sets `asking` and leaves only when `__foxyBluetoothAnswer(state)` is
     * called, so a reply alone keeps the screen up for good (ENG-656). Answered
     * twice, in iOS's order (FoxyBridge+Tap.swift `handleBluetoothAsk`): the
     * gate's global first, then the action's own id. Android has no tap to pay
     * yet (ENG-650), so the state is always "unsupported": no Bluetooth
     * permission is asked or declared, nothing scans or advertises, and the
     * page writes `foxy.bt.asked` and moves on.
     */
    private fun bluetoothAsk(id: String) = host.post {
        Log.line("[foxy] bluetooth: asked; no tap to pay on Android yet, answering $BLUETOOTH_UNSUPPORTED")
        host.evaluate(bluetoothAnswer(BLUETOOTH_UNSUPPORTED))
        resolve(id, BLUETOOTH_UNSUPPORTED, null)
    }

    /**
     * The receive screen's TAP (FoxyBridge+Tap.swift `handleTapReceiveStart`),
     * which the page also asks by itself on every receive screen (auto-arm,
     * `foxy.tap.auto` on by default). An "Unknown action" here was a refusal
     * the page retries every 1.2 s while the screen is up, with the error in
     * the TAP card (ENG-656). Android answers what iOS answers on an iPhone
     * whose Bluetooth is `unsupported`: a payload iOS would refuse is refused
     * with its words and nothing else; otherwise "ok", and when this makes the
     * receiver (none held yet) `_tap({side:"receive",stage:"unsupported"})`
     * after the reply, as iOS's peripheral manager reports its state after the
     * handler has answered. A receiver already held answers "ok" alone, as
     * iOS's `update` and `arm` do. Nothing is advertised, no Bluetooth
     * permission is asked or declared, until BLE tap to pay (ENG-650).
     */
    private fun tapReceiveStart(id: String, payload: String) {
        if (payload.isNotEmpty() && !TapRules.isPayload(payload)) return resolve(id, null, TapRules.NOT_PAYABLE)
        host.post {
            val made = !receiverHeld
            receiverHeld = true
            resolve(id, "ok", null)
            if (made) {
                Log.line("[tap] receive: no tap to pay on Android yet; telling the page ${TapRules.UNSUPPORTED}")
                // posted after the reply's own post, so the page hears the answer first
                evaluate(TapRules.push("receive", TapRules.UNSUPPORTED))
            }
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
