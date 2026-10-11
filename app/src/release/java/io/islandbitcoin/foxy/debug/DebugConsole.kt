package io.islandbitcoin.foxy.debug

/** Release builds say nothing: [Log] lines reach FieldLog only. */
object DebugConsole {
    const val ENABLED = false

    fun install() {
        // nothing: no logcat in a release build
    }
}
