package io.islandbitcoin.foxy.debug

/** Debug builds: every [Log] line in logcat too, under the tag "foxy". */
object DebugConsole {
    const val ENABLED = true

    fun install() {
        Log.console = { android.util.Log.d("foxy", it) }
    }
}
