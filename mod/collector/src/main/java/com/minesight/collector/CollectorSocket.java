package com.minesight.collector;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minesight.net.ReconnectingSocket;
import net.minecraft.client.Minecraft;

import java.net.URI;

/**
 * Connection to the Control Panel GUI (which hosts the collector server on
 * port 8766). Announces itself with collector_hello on connect and routes
 * incoming commands onto the client thread. Reconnect plumbing is the shared
 * core {@link ReconnectingSocket}.
 */
public class CollectorSocket {
    private final JsonParser parser = new JsonParser();
    private final CollectorController controller;
    private final ReconnectingSocket socket;

    public CollectorSocket(URI uri, CollectorController controller) {
        this.controller = controller;
        this.socket = new ReconnectingSocket(uri, new ReconnectingSocket.Listener() {
            @Override
            public void onOpen(ReconnectingSocket s) {
                JsonObject hello = new JsonObject();
                hello.addProperty("type", "collector_hello");
                s.send(hello.toString());
            }

            @Override
            public void onMessage(String message) {
                handleMessage(message);
            }
        }, "MineSight-Collector-WS");
    }

    public void start() {
        socket.start();
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
        socket.send(obj.toString());
    }
}
