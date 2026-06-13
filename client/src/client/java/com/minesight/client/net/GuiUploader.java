package com.minesight.client.net;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Base64;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Streams captured frames to the Python Control Panel's collector WebSocket
 * (default {@code ws://127.0.0.1:8766}, override with {@code -Dminesight.guiUrl}),
 * reusing the existing {@code collect_image} protocol so the GUI stores them in
 * its dataset pool - the same path the 1.8.9 remote clients used.
 *
 * <p>Uses the JDK's built-in {@link WebSocket} (no shaded library). Connection is
 * lazy + best-effort with a retry cooldown; if it isn't connected the caller just
 * keeps the local copy. Sends are serialized on a single daemon thread because
 * {@link WebSocket#sendText} forbids overlapping sends.
 */
public final class GuiUploader {

    private static final Logger LOG = LoggerFactory.getLogger("minesight");
    private static final long RETRY_COOLDOWN_MS = 5_000;

    private final String url;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "minesight-uploader");
        t.setDaemon(true);
        return t;
    });

    private volatile WebSocket ws;
    private volatile boolean connecting;
    private volatile long lastAttempt;

    public GuiUploader() {
        this.url = System.getProperty("minesight.guiUrl", "ws://127.0.0.1:8766");
    }

    public boolean isConnected() {
        return ws != null;
    }

    /** Try to (re)connect if we aren't, honoring a cooldown. Non-blocking. */
    public void ensureConnected() {
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
                        LOG.info("GUI upload: not connected to {} ({})", url, ex.getMessage());
                    } else {
                        ws = socket;
                        LOG.info("GUI upload: connected to {}", url);
                        send("{\"type\":\"collector_hello\",\"client\":\"fabric-2.0\"}");
                    }
                });
    }

    /** Stream one capture as {@code collect_image} (+ a log line). No-op if offline. */
    public void uploadImage(String file, byte[] png, String labels, int boxes) {
        if (ws == null) {
            return;
        }
        String b64 = Base64.getEncoder().encodeToString(png);
        String image = "{\"type\":\"collect_image\",\"file\":" + js(file)
                + ",\"png\":\"" + b64 + "\",\"labels\":" + js(labels) + "}";
        send(image);
        send("{\"type\":\"collect_log\",\"message\":"
                + js("streamed " + file + " (" + boxes + " boxes)") + "}");
    }

    private void send(String message) {
        WebSocket socket = ws;
        if (socket == null) {
            return;
        }
        sender.execute(() -> {
            try {
                socket.sendText(message, true).get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOG.warn("GUI upload send failed; dropping connection ({})", e.getMessage());
                ws = null;
                try {
                    socket.abort();
                } catch (Exception ignored) {
                }
            }
        });
    }

    /** Minimal JSON string escaper (avoids pulling in a JSON library). */
    private static String js(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    private final class Listener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            webSocket.request(1);  // GUI may send collect_start etc.; the plugin drives us, so ignore
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
