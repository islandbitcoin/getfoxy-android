package io.islandbitcoin.foxy

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * S8: the page has no network. The iOS -FoxyNetBlockTest set
 * (FoxyWebView.swift:529-585) run from the real page in the real WebView: fetch,
 * WebSocket, preconnect, DNS prefetch, prefetch, an image, a beacon and a
 * Worker's fetch, every one aimed at a loopback listener in the test package's
 * own process. The listener must record nothing. It first records a connection
 * of its own, so "nothing" is a count that could have been more.
 */
@RunWith(AndroidJUnit4::class)
class NetBlockTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().context
    private var service: Messenger? = null
    private val bound = LinkedBlockingQueue<Messenger>()
    private val answers = LinkedBlockingQueue<Bundle>()
    private val thread = HandlerThread("netblock-replies").apply { start() }
    private val replies = Messenger(object : Handler(thread.looper) {
        override fun handleMessage(msg: Message) {
            answers.add(Bundle(msg.data))
        }
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            bound.add(Messenger(binder))
        }

        override fun onServiceDisconnected(name: ComponentName?) {}
    }

    @Before
    fun bindListener() {
        val intent = Intent().setClassName(context.packageName, ListenerService::class.java.name)
        assertTrue("the listener service binds", context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        service = bound.poll(20, TimeUnit.SECONDS)
        assertNotNull("the listener service answered", service)
    }

    @After
    fun unbind() {
        runCatching { context.unbindService(connection) }
        thread.quitSafely()
    }

    private fun ask(): Bundle {
        val m = Message.obtain(null, ListenerService.ASK)
        m.replyTo = replies
        service!!.send(m)
        return answers.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("the listener did not answer")
    }

    @Test
    fun nothingThePageTriesReachesTheListener() {
        // the control: the listener counts a connection made from its own process
        service!!.send(Message.obtain(null, ListenerService.SELF))
        val end = SystemClock.elapsedRealtime() + 10_000
        var before = ask()
        while (before.getInt(ListenerService.KEY_COUNT) < 1 && SystemClock.elapsedRealtime() < end) {
            SystemClock.sleep(200)
            before = ask()
        }
        assertEquals("the listener counts what reaches it", 1, before.getInt(ListenerService.KEY_COUNT))
        val host = "127.0.0.1:" + before.getInt(ListenerService.KEY_PORT)

        ActivityScenario.launch(MainActivity::class.java).use { s ->
            assertTrue(Page.until(s, Page.BOOTED, 90_000))
            val report = Page.async(s, """
                const host = '$host';
                const r = [];
                r.push(typeof RTCPeerConnection === 'undefined' ? 'webrtc removed' : 'webrtc AVAILABLE');
                try { await fetch('http://' + host + '/foxy-fetch', { mode: 'no-cors', cache: 'no-store' }); r.push('fetch SENT'); }
                catch (e) { r.push('fetch blocked'); }
                try { await fetch('https://example.com/?foxy-netblock', { mode: 'no-cors', cache: 'no-store' }); r.push('fetch example.com SENT'); }
                catch (e) { r.push('fetch example.com blocked'); }
                await new Promise(function (ok) {
                  let ws;
                  try { ws = new WebSocket('ws://' + host + '/'); }
                  catch (e) { r.push('websocket blocked at construction'); ok(); return; }
                  const t = setTimeout(function () { r.push('websocket no answer in 8s'); ok(); }, 8000);
                  ws.onopen = function () { clearTimeout(t); r.push('websocket OPENED'); ws.close(); ok(); };
                  ws.onerror = function () { clearTimeout(t); r.push('websocket blocked'); ok(); };
                });
                for (const [tag, attrs] of [
                  ['link', { rel: 'preconnect', href: 'http://' + host + '/foxy-preconnect' }],
                  ['link', { rel: 'dns-prefetch', href: 'http://' + host + '/' }],
                  ['link', { rel: 'prefetch', href: 'http://' + host + '/foxy-prefetch' }],
                  ['img', { src: 'http://' + host + '/foxy-img.png' }],
                ]) {
                  try { const e = document.createElement(tag); Object.assign(e, attrs); document.head.appendChild(e); r.push(tag + ' ' + (attrs.rel || 'src') + ' added'); }
                  catch (e) { r.push(tag + ' refused'); }
                }
                try { r.push('beacon ' + (navigator.sendBeacon('http://' + host + '/foxy-beacon', 'x') ? 'queued' : 'refused')); }
                catch (e) { r.push('beacon threw'); }
                await new Promise(function (ok) {
                  try {
                    const src = "fetch('http://" + host + "/foxy-worker', { mode: 'no-cors' }).then(function () { postMessage('SENT'); }, function (e) { postMessage('blocked'); })";
                    const wk = new Worker(URL.createObjectURL(new Blob([src], { type: 'text/javascript' })));
                    const t = setTimeout(function () { r.push('worker no answer in 6s'); ok(); }, 6000);
                    wk.onmessage = function (m) { clearTimeout(t); r.push('worker fetch ' + m.data); ok(); };
                    wk.onerror = function (e) { clearTimeout(t); r.push('worker refused'); ok(); };
                  } catch (e) { r.push('worker refused at construction'); ok(); }
                });
                await new Promise(function (ok) { setTimeout(ok, 3000); });
                return r.join(' | ');
            """.trimIndent(), 60_000)
            assertNotNull("the net-block script finished", report)
            android.util.Log.i("foxy-netblock", "page saw: $report")
            assertTrue(report!!, report.contains("webrtc removed"))
            assertTrue(report, !report.contains("SENT") && !report.contains("OPENED") && !report.contains("AVAILABLE"))
        }
        // whatever the page heard, the listener is where the truth is
        SystemClock.sleep(2_000)
        val after = ask()
        assertEquals(
            "connections that reached the listener from the page: ${after.getStringArrayList(ListenerService.KEY_LINES)}",
            before.getInt(ListenerService.KEY_COUNT), after.getInt(ListenerService.KEY_COUNT),
        )
    }
}
