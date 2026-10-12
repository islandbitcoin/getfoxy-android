package io.islandbitcoin.foxy;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A loopback listener that counts every connection that reaches it, in the test
 * package's own process: the app under test has no INTERNET permission and may
 * not open a socket. NetBlockTest points every network road the page has at it;
 * the count must not move. Java, not Kotlin: this process is the test APK's
 * alone, which carries no Kotlin standard library of its own.
 */
public class ListenerService extends Service {
    public static final int ASK = 1;
    /** Connect to the listener from this process, to prove it counts. */
    public static final int SELF = 2;
    public static final String KEY_PORT = "port";
    public static final String KEY_COUNT = "count";
    public static final String KEY_LINES = "lines";

    private ServerSocket server;
    private final AtomicInteger count = new AtomicInteger(0);
    private final CopyOnWriteArrayList<String> lines = new CopyOnWriteArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            throw new IllegalStateException("the listener could not open its port", e);
        }
        Thread accept = new Thread(() -> {
            while (!server.isClosed()) {
                final Socket s;
                try {
                    s = server.accept();
                } catch (IOException e) {
                    break;
                }
                count.incrementAndGet();
                Thread read = new Thread(() -> {
                    try {
                        s.setSoTimeout(2000);
                        String first = new BufferedReader(new InputStreamReader(s.getInputStream())).readLine();
                        lines.add(first == null ? "(nothing)" : first);
                    } catch (IOException e) {
                        lines.add("(no line)");
                    } finally {
                        try { s.close(); } catch (IOException ignored) { }
                    }
                });
                read.setDaemon(true);
                read.start();
            }
        }, "listener");
        accept.setDaemon(true);
        accept.start();
    }

    private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == SELF) {
                Thread self = new Thread(() -> {
                    try (Socket s = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                        OutputStream out = s.getOutputStream();
                        out.write("SELF\n".getBytes());
                        out.flush();
                    } catch (IOException ignored) {
                    }
                });
                self.setDaemon(true);
                self.start();
                return;
            }
            if (msg.what != ASK) return;
            Message reply = Message.obtain(null, ASK);
            Bundle data = new Bundle();
            data.putInt(KEY_PORT, server.getLocalPort());
            data.putInt(KEY_COUNT, count.get());
            data.putStringArrayList(KEY_LINES, new ArrayList<>(lines));
            reply.setData(data);
            try {
                msg.replyTo.send(reply);
            } catch (RemoteException ignored) {
            }
        }
    });

    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    @Override
    public void onDestroy() {
        try { server.close(); } catch (IOException ignored) { }
        super.onDestroy();
    }
}
