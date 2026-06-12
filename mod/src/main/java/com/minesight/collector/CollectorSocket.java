package com.minesight.collector;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;

/**
 * Connection to the Control Panel GUI (which hosts the server on port 8766).
 * Mirrors WebSocketManager's forever-reconnect behavior; all failures are
 * silent because the GUI is usually not running during normal play.
 */
public class CollectorSocket {
    private static final long RECONNECT_DELAY_MS = 3000;

    private final JsonParser parser = new JsonParser();
    private final URI uri;
    private final CollectorController controller;
    private volatile WebSocketClient client;
    private volatile boolean running = true;

    public CollectorSocket(URI uri, CollectorController controller) {
        this.uri = uri;
        this.controller = controller;
    }

    public void start() {
        Thread t = new Thread(this::connectLoop, "MineSight-Collector-WS");
        t.setDaemon(true);
        t.start();
    }

    private void connectLoop() {
        while (running) {
            try {
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
                JsonObject hello = new JsonObject();
                hello.addProperty("type", "collector_hello");
                send(hello.toString());
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
            final JsonObject obj = parser.parse(message).getAsJsonObject();
            // Commands must run on the client thread, not the socket thread.
            Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    controller.onCommand(obj);
                }
            });
        } catch (Exception ignored) {
        }
    }

    public void send(JsonObject obj) {
        WebSocketClient c = client;
        if (c != null && c.isOpen()) {
            try {
                c.send(obj.toString());
            } catch (Exception ignored) {
            }
        }
    }
}
