package io.islandbitcoin.foxy

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import io.islandbitcoin.foxy.debug.Log
import io.islandbitcoin.foxy.flashcard.CardTapIntake
import io.islandbitcoin.foxy.flashcard.CardTapRoute

/**
 * The door a Flashcard's address comes in by (ENG-668, review of PR #15): Android's tag
 * dispatch (NDEF_DISCOVERED) and the link (VIEW) for exactly `https://getfoxy.io/card`
 * start this activity, never MainActivity. It draws nothing (Theme.NoDisplay), keeps no
 * place in Recents or history, lives in a task of its own (an empty taskAffinity, so
 * starting it never brings Foxy's task forward by itself), and finishes in onCreate.
 *
 * It holds the address to CardAddress.isCard and asks [CardTapRoute] where the tap goes:
 * started into a new page, handed to the page that is up and on top of its task, or
 * dropped while the scanner, a seed screen or anything else is over that page. Nothing
 * of the address goes further than here; MainActivity is handed [CardTapRoute.EXTRA].
 */
class CardTapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            route(intent)
        } finally {
            // Theme.NoDisplay: finished before onResume, or Android ends the process
            finish()
        }
    }

    private fun route(i: Intent?) {
        if (i == null || replayed(i)) return
        if (!CardTapIntake.isCardIntent(i.action, i.dataString)) return
        val page = MainActivity.live
        val way = CardTapRoute.of(page != null, page?.let { topOfTask(it.taskId) }, MainActivity::class.java.name)
        Log.line("[foxy] card tap: ${way.name.lowercase()}")
        val open = Intent(this, MainActivity::class.java).putExtra(CardTapRoute.EXTRA, true)
        when (way) {
            CardTapRoute.Way.DROPPED -> return
            // the page on top of its task: brought back, and handed the tap (singleTop, onNewIntent)
            CardTapRoute.Way.DELIVERED -> open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            // no page: a new one; anything left over it from a process that ended goes
            CardTapRoute.Way.LAUNCHED -> open.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        try {
            startActivity(open)
        } catch (e: RuntimeException) {
            Log.line("[foxy] card tap: the page would not open (${e.javaClass.simpleName})")
        }
    }

    /** Not a dispatch: handed back from history (it keeps none, but never act on a replay). */
    private fun replayed(i: Intent): Boolean = CardTapIntake.replayed(restored = false, flags = i.flags)

    /** The class name of the activity on top of Foxy's task `taskId`, or null when it cannot be read. */
    private fun topOfTask(taskId: Int): String? {
        val am = getSystemService(ActivityManager::class.java) ?: return null
        return try {
            am.appTasks.map { it.taskInfo }.firstOrNull { it != null && idOf(it) == taskId }?.topActivity?.className
        } catch (e: RuntimeException) {
            // a task that went between the listing and the read
            null
        }
    }

    private fun idOf(info: ActivityManager.RecentTaskInfo): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.taskId else @Suppress("DEPRECATION") info.id
}
