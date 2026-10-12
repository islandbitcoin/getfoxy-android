package io.islandbitcoin.foxy.keychain

/**
 * The API 28-29 screen-lock confirmation's start and answer, apart from Android
 * so the JVM tests can drive it (ScreenLockConfirmationTest): [DeviceAuth]
 * hands it KeyguardManager's intent and MainActivity's launcher. `I` is the
 * intent (android.content.Intent in the app).
 */
internal class ScreenLockConfirmation<I : Any> {
    /** Starts the confirmation and answers whether it was given (DeviceAuth.confirmCredential). */
    @Volatile var start: ((I, (Boolean) -> Unit) -> Boolean)? = null

    /** True from the moment the confirmation is started until its answer is back (DeviceAuth.confirming). */
    @Volatile var confirming: Boolean = false
        private set

    /** Told each answer, as [confirming] clears and before `done` (DeviceAuth.confirmAnswered). */
    @Volatile var answered: ((Boolean) -> Unit)? = null

    /** Asks with `intent` (null: no screen lock to ask for); `done` once. */
    fun ask(intent: I?, done: (DeviceAuth.Result) -> Unit) {
        val start = start
        when {
            intent == null -> done(DeviceAuth.Result.UNAVAILABLE)
            start == null -> done(DeviceAuth.Result.NO)
            else -> {
                // set before the activity starts: its stop of MainActivity is this question's, not a put-away
                confirming = true
                val started = start(intent) { yes ->
                    confirming = false
                    // PutAway hears the answer before the seed executor does: a no puts away what the stop skipped
                    answered?.invoke(yes)
                    done(if (yes) DeviceAuth.Result.YES else DeviceAuth.Result.NO)
                }
                if (!started) {
                    confirming = false
                    done(DeviceAuth.Result.NO)
                }
            }
        }
    }
}
