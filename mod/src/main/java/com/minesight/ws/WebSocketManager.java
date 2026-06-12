package com.minesight.ws;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minesight.DetectionFrame;
import com.minesight.DetectionStore;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;

/**
 * Maintains the connection to the Python ML engine on a background daemon
 * thread, reconnecting forever. The game must run normally with the backend
 * down, so every failure here is swallowed silently.
 */
public class WebSocketManager {
    private static final long RECONNECT_DELAY_MS = 3000;

    private final Gson gson = new Gson();
    private final JsonParser parser = new JsonParser();
    private final URI uri;
    private final DetectionStore store;
    private volatile WebSocketClient client;
    private volatile boolean running = true;

    public WebSocketManager(URI uri, DetectionStore store) {
        this.uri = uri;
        this.store = store;
    }

    public void start() {
        Thread t = new Thread(this::connectLoop, "MineSight-WebSocket");
        t.setDaemon(true);
        t.start();
    }

    private void connectLoop() {
        while (running) {
            try {
                // A WebSocketClient can't be reused after closing; make a new one per attempt.
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
        return new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshake) {
            }

            @Override
            public void onMessage(String message) {
                handleMessage(message);
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
            }

            @Override
            public void onError(Exception ex) {
            }
        };
    }

    private void handleMessage(String message) {
        try {
            JsonObject obj = parser.parse(message).getAsJsonObject();
            String type = obj.has("type") ? obj.get("type").getAsString() : "";
            if ("detections".equals(type)) {
                store.update(gson.fromJson(obj, DetectionFrame.class));
            }
        } catch (Exception ignored) {
            // Malformed message; drop it.
        }
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
}
