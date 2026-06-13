package com.minesight.farm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The plugin's link to the Python Control Panel's collector WebSocket (default
 * {@code ws://127.0.0.1:8766}, override {@code -Dminesight.guiUrl}).
 *
 * <p>Announces {@code role:server} in its hello so the GUI routes only the
 * server-side settings here (scan area, ore, target), and forwards
 * {@code collect_start}/{@code collect_update}/{@code collect_stop} to the plugin.
 * The Fabric client connects to the same WS as {@code role:client} for the
 * render-side settings.
 *
 * <p>JDK {@link WebSocket} (no shaded library); lazy + best-effort reconnect on a
 * cooldown driven from the plugin heartbeat; sends serialized on one daemon thread.
 */
final class GuiLink {

    private static final long RETRY_COOLDOWN_MS = 5_000;

    private final MineSightFarmPlugin plugin;
    private final String url;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "minesight-guilink");
        t.setDaemon(true);
        return t;
    });

    private volatile WebSocket ws;
    private volatile boolean connecting;
    private volatile long lastAttempt;

    GuiLink(MineSightFarmPlugin plugin) {
        this.plugin = plugin;
        this.url = System.getProperty("minesight.guiUrl", "ws://127.0.0.1:8766");
    }

    /** Try to (re)connect if we aren't, honoring a cooldown. Non-blocking. */
    void ensureConnected() {
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
                        plugin.getLogger().fine("GUI link: not connected to " + url
                                + " (" + ex.getMessage() + ")");
                    } else {
                        ws = socket;
                        plugin.getLogger().info("GUI link: connected to " + url);
                        send("{\"type\":\"collector_hello\",\"role\":\"server\",\"client\":\"folia-plugin\"}");
                    }
                });
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
            switch (type) {
                case "collect_start" -> plugin.onCollectorSettings(obj, true);
                case "collect_update" -> plugin.onCollectorSettings(obj, false);
                case "collect_stop" -> plugin.onCollectorStop();
                default -> {
                }
            }
        } catch (Exception ignored) {
            // not JSON we care about
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
