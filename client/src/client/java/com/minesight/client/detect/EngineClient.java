package com.minesight.client.detect;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Connection to the Python ML engine (default {@code ws://127.0.0.1:8765},
 * override {@code -Dminesight.engineUrl}). Receives {@code detections} frames
 * into the {@link DetectionStore} for the overlay, and streams {@code player}
 * state each tick plus {@code review_capture} on demand.
 *
 * <p>Uses the JDK's built-in {@link WebSocket} (no shaded library). Reconnect is
 * lazy + best-effort on a cooldown, driven from the client tick; sends are
 * serialized on a single daemon thread.
 */
public final class EngineClient {

    private static final Logger LOG = LoggerFactory.getLogger("minesight");
    private static final long RETRY_COOLDOWN_MS = 3_000;

    private final String url;
    private final HttpClient http = HttpClient.newHttpClient();
    private final DetectionStore store;
    private final Gson gson = new Gson();
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "minesight-engine");
        t.setDaemon(true);
        return t;
    });

    private volatile WebSocket ws;
    private volatile boolean connecting;
    private volatile long lastAttempt;

    public EngineClient(DetectionStore store) {
        this.store = store;
        this.url = System.getProperty("minesight.engineUrl", "ws://127.0.0.1:8765");
    }

    public boolean isConnected() {
        return ws != null;
    }

    /** Client tick: keep the connection alive and stream player state. */
    public void tick(MinecraftClient mc) {
        ensureConnected();
        if (ws != null && mc.player != null) {
            sendPlayer(mc.player, mc);
        }
    }

    private void ensureConnected() {
        if (ws != null || connecting || url.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAttempt < RETRY_COOLDOWN_MS) {
            return;
        }
        lastAttempt = now;
        connecting = true;
        http.newWebSocketBuilder()
                .buildAsync(URI.create(url), new Listener())
                .whenComplete((socket, ex) -> {
                    connecting = false;
                    if (ex != null) {
                        LOG.debug("engine: not connected to {} ({})", url, ex.getMessage());
                    } else {
                        ws = socket;
                        LOG.info("engine: connected to {}", url);
                    }
                });
    }

    private void sendPlayer(ClientPlayerEntity player, MinecraftClient mc) {
        JsonObject m = new JsonObject();
        m.addProperty("type", "player");
        m.addProperty("x", player.getX());
        m.addProperty("y", player.getY());
        m.addProperty("z", player.getZ());
        m.addProperty("yaw", player.getYaw());
        m.addProperty("pitch", player.getPitch());
        m.addProperty("fov", mc.options.getFov().getValue());
        send(m.toString());
    }

    /** Ask the engine to snapshot the current frame + predictions (F9). */
    public void reviewCapture() {
        send("{\"type\":\"review_capture\"}");
    }

    private void send(String message) {
        WebSocket socket = ws;
        if (socket == null) {
            return;
        }
        sender.execute(() -> {
            try {
                socket.sendText(message, true).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                ws = null;
                try {
                    socket.abort();
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void handle(String message) {
        try {
            JsonObject obj = JsonParser.parseString(message).getAsJsonObject();
            String type = obj.has("type") ? obj.get("type").getAsString() : "";
            if ("detections".equals(type)) {
                store.update(gson.fromJson(obj, DetectionFrame.class));
            }
        } catch (Exception ignored) {
            // Malformed message; drop it.
        }
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder rx = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            rx.append(data);
            if (last) {
                String msg = rx.toString();
                rx.setLength(0);
                handle(msg);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            ws = null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            ws = null;
            return null;
        }
    }
}
