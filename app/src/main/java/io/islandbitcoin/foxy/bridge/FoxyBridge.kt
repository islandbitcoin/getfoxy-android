package io.islandbitcoin.foxy.bridge

import io.islandbitcoin.foxy.debug.FieldLog
import io.islandbitcoin.foxy.debug.Log

/**
 * What the bridge needs from the WebView's side: everything here runs on the
 * main thread, and [evaluate] does nothing once the WebView is gone.
 */
interface PageHost {
    /** Posts to the main looper. */
    fun post(action: Runnable)

    /** evaluateJavascript in the page's main frame; a no-op once the WebView is destroyed. */
    fun evaluate(js: String)

    /** The page has the screen covered: the splash can come down. */
    fun covered()

    /** Keep the screen lit while a screen is looked at (FLAG_KEEP_SCREEN_ON). */
    fun keepAwake(on: Boolean)

    fun haptic(kind: String)

    // MARK: The platform's doors (P3). Main thread unless said; each has a
    // default that refuses or does nothing, which is what a host with nothing
    // to show them on answers.

    /** Posts to the main looper after `ms`. */
    fun later(ms: Long, action: Runnable) {}

    /** The page's size in CSS px, for the rectangle checks; null with no page. */
    fun pageSize(): Pair<Double, Double>? = null

    /** The full-screen scanner: `done(text, null)` or `done(null, error)`, once. */
    fun scan(prompt: String, done: (text: String?, error: String?) -> Unit) = done(null, PlatformRules.NO_SCANNER_VIEW)

    /** The camera laid over the page at `rect` (checked), each code to `onCode`; false with nothing to show it in. */
    fun previewShow(rect: PlatformRules.Rect, onCode: (String) -> Unit): Boolean = false

    /** The camera pane down and the camera let go; nothing when there is none. */
    fun previewStop() {}

    /** A paste button over the page at `rect` (checked); its tap reads the clipboard and hands the text to `onPaste`. */
    fun pasteShow(rect: PlatformRules.Rect, onPaste: (String) -> Unit): Boolean = false

    fun pasteStop() {}

    /** The clipboard's text, read now, because the page asked on a tap; "" for none. */
    fun clipboardText(): String = ""

    /** Copies `text`; `sensitive`: marked so, and cleared after 120 s if it is still Foxy's. */
    fun copy(text: String, sensitive: Boolean) {}

    /** The share sheet with `text` as plain text: null when it went up, else why not. */
    fun share(text: String): String? = PlatformRules.ALREADY_OPEN

    /** A local notification with native's words. */
    fun notify(title: String, text: String) {}

    /** A biometric (with `passcode`, or the screen lock) asked with `reason`: "yes", "no" or "unavailable". */
    fun biometric(reason: String, passcode: Boolean, done: (String) -> Unit) = done("unavailable")

    /** This app's own settings page: null when it opened, else why not. */
    fun openSettings(): String? = PlatformRules.NO_SETTINGS

    /** `url`, one of [PlatformRules.COMPANY_SITES], in the phone's browser: null when it opened, else why not. */
    fun openUrl(url: String): String? = PlatformRules.BROWSER_WOULD_NOT_OPEN

    /** Whether the screen is being recorded or mirrored now. FLAG_SECURE keeps Foxy out of any capture, so Android says no. */
    fun captured(): Boolean = false

    /** A native yes-or-no, in the one queue (PromptQueue): `done` once; the function returned cancels it (a no). */
    fun ask(prompt: Prompt, done: (Boolean) -> Unit): () -> Unit {
        done(false)
        return {}
    }

    /** A native question is on screen. */
    fun alertShowing(): Boolean = false

    /** The device owner, asked by Android after a seed alert's yes (M8); true on a phone with no screen lock. Blocks: never on the main thread. */
    fun deviceOwnerApproves(reason: String): Boolean = false

    /** Foxy's SHOW WORDS screen; `done(verified, deleteAsked, revealed)` once it is gone. False with nothing to present it from. */
    fun showWords(words: List<String>, verify: Boolean, done: (Boolean, Boolean, Boolean) -> Unit): Boolean = false

    /** Foxy's ENTER WORDS screen; `done(phrase)` (null when closed) once it is gone. False with nothing to present it from. */
    fun enterWords(wordlist: List<String>, done: (String?) -> Unit): Boolean = false

    /** The seed screen up now, if any, closes with the answer its back button gives. */
    fun closeSeedScreen() {}
}

/**
 * The page ↔ native contract (docs/BRIDGE.md), as P3 serves it: a port of
 * Foxy/Bridge/FoxyBridge.swift's dispatch and reply path.
 *
 * One table: the 18 P1 actions, the 7 P2 actions ([NetBridge]: Tor's snapshot,
 * RETRY, CONTINUE WITHOUT TOR, and the three requests that leave through
 * Route), the 21 P3 actions ([SeedBridge]'s screens, adopting, wiping and
 * protection; [PlatformBridge]'s camera, clipboard, share, notifications,
 * biometric, the Bluetooth screen's fixed "unsupported" and the rest), and
 * the `p1` stubs of conformance/contract.json for the LATER phase
 * ([ContractStubs], generated from it), which answer the actions the page asks
 * before anything else. A P3 action's stub is never put in the table: every P3
 * action is answered by its real handler. Every other action is answered
 * "Unknown action: <name>"; `{haptic}` buzzes and answers nothing; anything
 * else, `{username}` included, is ignored (S15). Only the staged page's main
 * frame at the asset origin is heard (S14).
 */
class FoxyBridge(
    private val host: PageHost,
    seedEnvironment: () -> io.islandbitcoin.foxy.keychain.SeedEnvironment,
    seedExecutor: java.util.concurrent.Executor,
    private val isDebug: Boolean,
    netEnvironment: NetEnvironment = NetEnvironment.noRoute(),
    /** A monotonic clock in ms: the notification throttle's and the words screen's pause. */
    clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    companion object {
        /** The asset loader's origin, the only one the bridge answers. */
        const val ORIGIN = "https://appassets.androidplatform.net"

        /** No page message is this long; a longer one is dropped unread. */
        const val MOST_MESSAGE_CHARS = 4 * 1024 * 1024

        /** The P1 actions: docs/BRIDGE.md, phase P1, kind action. */
        val P1_ACTIONS: Set<String> = setOf(
            "covered", "log", "fieldLog", "fieldLogClear", "isDebug", "awake",
        ) + SeedBridge.ACTIONS

        /** The P2 actions: docs/BRIDGE.md, phase P2, kind action. */
        val P2_ACTIONS: Set<String> = NetBridge.ACTIONS

        /** The P3 actions: docs/BRIDGE.md, phase P3, kind action. */
        val P3_ACTIONS: Set<String> = SeedBridge.P3_ACTIONS + PlatformBridge.ACTIONS

        /** Main frame, asset origin, nothing else (FoxyBridge.swift `answers`). */
        fun answers(isMainFrame: Boolean, origin: String?): Boolean = isMainFrame && origin == ORIGIN

        /** A JavaScript literal for a reply argument: a quoted JSON string, or `null`. */
        fun literal(value: String?): String = if (value == null) "null" else Json.quote(value)

        fun replyScript(id: String, text: String?, error: String?): String =
            "window.FoxyWallet && window.FoxyWallet._scanResult(${literal(id)}, ${literal(text)}, ${literal(error)})"
    }

    /** What a message asks for, decided before anything runs. */
    sealed class Dispatch {
        object Ignored : Dispatch()
        data class Haptic(val kind: String) : Dispatch()
        data class Unknown(val action: String, val id: String) : Dispatch()
        data class Run(val action: String, val id: String, val body: Map<String, Any?>) : Dispatch()
    }

    private val seeds = SeedBridge(seedEnvironment, seedExecutor, ::resolve, host, clock)
    private val net = NetBridge(netEnvironment, host, ::resolve)
    private val platform = PlatformBridge(host, ::resolve, seedEnvironment, seedExecutor, clock)

    /** Swaps, melts, mints and restores not yet answered: putting Foxy away waits for them. */
    val moneyInFlight: Int get() = net.moneyInFlight.size

    /** The route changed: the page hears it (`_privacy`), through the one evaluate path. */
    fun pushPrivacy() = net.pushPrivacy()

    /** A screenshot was taken (API 34+): `_screenshot`. */
    fun pushScreenshot() = platform.pushScreenshot()

    /**
     * The page went (its process ended, or it is loading again) or Foxy was put
     * away: no camera pane, paste button or seed screen of its is left over
     * whatever comes next (FoxyBridge.swift `pageGone`, I7). Main thread.
     */
    fun letGo() {
        platform.letGo()
        seeds.closeScreens()
    }

    /** The saved seed was written or deleted: no seed screen shows words from before (L4). Main thread. */
    fun seedChanged() = seeds.closeScreens()

    /** Every action the bridge answers, and the one function that answers it. */
    val handlers: Map<String, (id: String, body: Map<String, Any?>) -> Unit> = buildMap {
        put("covered") { id, _ ->
            host.post { host.covered() }
            resolve(id, "ok", null)
        }
        put("awake") { id, body ->
            val on = body["on"] as? Boolean ?: false
            host.post { host.keepAwake(on) }
            resolve(id, "ok", null)
        }
        put("log") { id, body ->
            Log.line("[foxy-js] " + (body["text"] as? String ?: ""))
            resolve(id, "ok", null)
        }
        put("fieldLog") { id, body ->
            resolve(id, FieldLog.json(body["bad"] as? Boolean ?: false), null)
        }
        put("fieldLogClear") { id, _ ->
            FieldLog.clear()
            Log.line("[foxy] the log was cleared from the LOGS screen")
            resolve(id, "ok", null)
        }
        put("isDebug") { id, _ -> resolve(id, if (isDebug) "yes" else "no", null) }
        for (action in SeedBridge.ACTIONS) {
            put(action) { id, body -> seeds.handle(action, id, body) }
        }
        for (action in NetBridge.ACTIONS) {
            put(action) { id, body -> net.handle(action, id, body) }
        }
        // P3, before the stubs: a stub never takes the place of a real handler
        for (action in SeedBridge.P3_ACTIONS) {
            put(action) { id, body -> seeds.handle(action, id, body) }
        }
        for (action in PlatformBridge.ACTIONS) {
            put(action) { id, body -> platform.handle(action, id, body) }
        }
        // the later phase's actions the page asks first, answered as the contract's p1 stubs say
        for ((action, stub) in ContractStubs.p1) {
            if (action in P3_ACTIONS) continue
            check(action !in P1_ACTIONS && action !in P2_ACTIONS) { "$action is both a served action and a stub" }
            put(action) { id, _ -> resolve(id, stub.text, stub.error) }
        }
    }

    fun dispatch(message: Any?): Dispatch {
        val body = message as? Map<*, *> ?: return Dispatch.Ignored
        // { haptic: kind }: no action, no id, handled before the guard
        (body["haptic"] as? String)?.let { return Dispatch.Haptic(it) }
        val action = body["action"] as? String ?: return Dispatch.Ignored
        val id = body["id"] as? String ?: return Dispatch.Ignored
        if (action !in handlers) return Dispatch.Unknown(action, id)
        @Suppress("UNCHECKED_CAST")
        return Dispatch.Run(action, id, body as Map<String, Any?>)
    }

    /** A message from the page, as the WebMessageListener hands it over. */
    fun onMessage(data: String?, isMainFrame: Boolean, origin: String?) {
        if (!answers(isMainFrame, origin)) {
            Log.line("[foxy] bridge message refused: ${origin ?: "no origin"} ${if (isMainFrame) "main frame" else "subframe"}")
            return
        }
        if (data == null || data.length > MOST_MESSAGE_CHARS) return
        val message = try {
            Json.parse(data)
        } catch (e: Json.Malformed) {
            return
        }
        when (val d = dispatch(message)) {
            Dispatch.Ignored -> {}
            is Dispatch.Haptic -> host.post { host.haptic(d.kind) }
            is Dispatch.Unknown -> {
                Log.line("[foxy] bridge: Unknown action: ${d.action.take(64)}")
                resolve(d.id, null, "Unknown action: ${d.action}")
            }
            is Dispatch.Run -> handlers.getValue(d.action)(d.id, d.body)
        }
    }

    /** The one reply path: `_scanResult(id, text, error)`, on the main thread, from any thread. */
    fun resolve(id: String, text: String?, error: String?) {
        // answered: no longer money in flight (FoxyBridge.swift `resolve`)
        net.moneyInFlight.remove(id)
        val js = replyScript(id, text, error)
        host.post { host.evaluate(js) }
    }
}
