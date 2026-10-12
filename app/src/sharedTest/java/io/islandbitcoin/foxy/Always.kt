package io.islandbitcoin.foxy

/**
 * A `try`/`finally` whose clean-up cannot hide the failure it cleans up after.
 *
 * In a plain `finally`, a clean-up step that throws (an assertion, say)
 * replaces the body's own failure and skips every step after it. DeviceExitTest
 * puts the phone back (unprotected mode off, Tor resumed) after a real-money
 * leg, and the person has to see why the money leg stopped, not that a
 * clean-up assertion did; and Tor has to come back either way.
 *
 * Pure Kotlin in `src/sharedTest`, tested on the JVM by AlwaysTest.
 */
object Always {
    /**
     * Runs [body], then every one of [after], in order, whatever happened. The
     * first failure is the one thrown: the body's if it failed, otherwise the
     * first clean-up step's; every later failure is suppressed on it.
     */
    fun run(body: () -> Unit, vararg after: () -> Unit) {
        var failure: Throwable? = null
        try {
            body()
        } catch (t: Throwable) {
            failure = t
        }
        for (step in after) {
            try {
                step()
            } catch (t: Throwable) {
                val first = failure
                if (first == null) failure = t else first.addSuppressed(t)
            }
        }
        failure?.let { throw it }
    }
}
