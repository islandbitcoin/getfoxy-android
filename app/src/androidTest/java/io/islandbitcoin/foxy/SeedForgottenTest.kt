package io.islandbitcoin.foxy

import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.islandbitcoin.foxy.keychain.NUT13
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/**
 * S20 when the activity is finished: the seed kept for the page goes with the
 * page. Back on API 28-30 finishes the activity, so pause, stop and destroy all
 * land inside ProcessLifecycleOwner's 700 ms hold, and MainActivity removes the
 * PutAway observer before its ON_STOP would arrive: PutAway never runs. The
 * page going (WebHost.destroy) must drop the seed by itself. Needs no Keystore,
 * so it runs on a locked phone too.
 */
@RunWith(AndroidJUnit4::class)
class SeedForgottenTest {
    private val words = "legal winner thank year wave sausage worth useful legal winner thank yellow"

    @Test
    fun finishingTheActivityDropsTheKeptSeed() {
        val services = AtomicReference<FoxyServices?>(null)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val s = (activity.application as FoxyApplication).services
                services.set(s)
                s.vault.keep(NUT13.seed(words), s.vault.epochNow)
            }
            val vault = services.get()!!.vault
            assertNotNull("the seed is kept for the visit", vault.kept())

            // Back: the activity finishes, well inside the 700 ms before ProcessLifecycleOwner says stop
            scenario.onActivity { it.finish() }
            val end = SystemClock.elapsedRealtime() + 5_000
            while (scenario.state != Lifecycle.State.DESTROYED && SystemClock.elapsedRealtime() < end) SystemClock.sleep(20)
            assertTrue("the activity was destroyed", scenario.state == Lifecycle.State.DESTROYED)
            assertNull("the page went, and the seed with it", vault.kept())
            // and nothing brings it back once ProcessLifecycleOwner's hold is over
            SystemClock.sleep(1_500)
            assertNull(vault.kept())
        } finally {
            scenario.close()
        }
    }
}
