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
}

/**
 * The page ↔ native contract (docs/BRIDGE.md), as P1 serves it: a port of
 * Foxy/Bridge/FoxyBridge.swift's dispatch and reply path.
 *
 * One table: the 18 P1 actions, and the 10 `p1` stubs of conformance/contract.json
 * ([ContractStubs], generated from it), which answer the later phases' actions
 * the page asks before anything else. Every other action is answered
 * "Unknown action: <name>"; `{haptic}` buzzes and answers nothing; anything else,
 * `{username}` included, is ignored (S15). Only the staged page's main frame at
 * the asset origin is heard (S14).
 */
class FoxyBridge(
    private val host: PageHost,
    seedEnvironment: () -> io.islandbitcoin.foxy.keychain.SeedEnvironment,
    seedExecutor: java.util.concurrent.Executor,
    private val isDebug: Boolean,
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

    private val seeds = SeedBridge(seedEnvironment, seedExecutor, ::resolve)

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
        // the later phases' actions the page asks first, answered as the contract's p1 stubs say
        for ((action, stub) in ContractStubs.p1) {
            check(action !in P1_ACTIONS) { "$action is both a P1 action and a stub" }
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
        val js = replyScript(id, text, error)
        host.post { host.evaluate(js) }
    }
}
