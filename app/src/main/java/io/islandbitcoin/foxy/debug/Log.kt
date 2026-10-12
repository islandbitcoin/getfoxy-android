package io.islandbitcoin.foxy.debug

/**
 * Foxy's one log call, the port of iOS's module-wide `print` (Foxy/Debug/Print.swift).
 *
 * Every line goes to [FieldLog], redacted, in every build. A debug build also
 * puts it in logcat: `console` is set by [DebugConsole.install] from the debug
 * source set (src/debug), and the release source set's install sets nothing,
 * so a release build has no logcat call in its code at all (and R8 strips
 * android.util.Log besides, proguard-rules.pro).
 */
object Log {
    @Volatile internal var console: ((String) -> Unit)? = null

    fun line(text: String) {
        console?.invoke(text)
        FieldLog.write(text)
    }
}
