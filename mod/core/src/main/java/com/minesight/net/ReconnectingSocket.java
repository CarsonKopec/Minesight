package com.minesight.net;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;

/**
 * Shared reconnecting WebSocket client for all MineSight mods. Lives in the
 * core module so the Java-WebSocket library is shaded exactly once; feature
 * mods talk to this wrapper and never touch the underlying library.
 *
 * Runs on a daemon thread and reconnects forever; every failure is swallowed
 * so the game runs normally whether or not the backend is up.
 */
public class ReconnectingSocket {
    public interface Listener {
        /** Called when a connection opens (e.g. to send a hello). */
        void onOpen(ReconnectingSocket socket);

        /** Called for each text message received. */
        void onMessage(String message);
    }

    private static final long RECONNECT_DELAY_MS = 3000;

    private final URI uri;
    private final Listener listener;
    private final String threadName;
    private volatile WebSocketClient client;
    private volatile boolean running = true;

    public ReconnectingSocket(URI uri, Listener listener) {
        this(uri, listener, "MineSight-WebSocket");
    }

    public ReconnectingSocket(URI uri, Listener listener, String threadName) {
        this.uri = uri;
        this.listener = listener;
        this.threadName = threadName;
    }

    public void start() {
        Thread t = new Thread(this::connectLoop, threadName);
        t.setDaemon(true);
        t.start();
    }

    private void connectLoop() {
        while (running) {
            try {
                // A WebSocketClient can't be reused after closing; new one per attempt.
                WebSocketClient c = newClient();
                client = c;
                if (c.connectBlocking()) {
                    while (running && !c.isClosed()) {
                        Thread.sleep(500);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(RECONNECT_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private WebSocketClient newClient() {
        final ReconnectingSocket self = this;
        return new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                listener.onOpen(self);
            }

            @Override
            public void onMessage(String message) {
                listener.onMessage(message);
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
            }

            @Override
            public void onError(Exception ex) {
            }
        };
    }

    /** Sends if connected, silently drops otherwise. Safe to call every tick. */
    public void send(String json) {
        WebSocketClient c = client;
        if (c != null && c.isOpen()) {
            try {
                c.send(json);
            } catch (Exception ignored) {
            }
        }
    }

    public void stop() {
        running = false;
        WebSocketClient c = client;
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }
}
