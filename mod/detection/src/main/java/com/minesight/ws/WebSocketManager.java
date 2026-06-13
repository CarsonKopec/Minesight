package com.minesight.ws;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minesight.DetectionFrame;
import com.minesight.DetectionStore;
import com.minesight.net.ReconnectingSocket;

import java.net.URI;

/**
 * Connection to the Python ML engine: receives detection frames into the
 * {@link DetectionStore} and forwards outbound messages (player state, review
 * captures). The reconnect plumbing lives in the shared core
 * {@link ReconnectingSocket}.
 */
public class WebSocketManager {
    private final Gson gson = new Gson();
    private final JsonParser parser = new JsonParser();
    private final DetectionStore store;
    private final ReconnectingSocket socket;

    public WebSocketManager(URI uri, DetectionStore store) {
        this.store = store;
        this.socket = new ReconnectingSocket(uri, new ReconnectingSocket.Listener() {
            @Override
            public void onOpen(ReconnectingSocket s) {
            }

            @Override
            public void onMessage(String message) {
                handleMessage(message);
            }
        });
    }

    public void start() {
        socket.start();
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
        socket.send(json);
    }
}
