package com.minesight.client;

import com.minesight.client.net.FarmPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * MineSight Fabric client (1.21.11) - proof of concept for the 2.0 split.
 *
 * Proves the linchpin: a custom-packet round-trip with the MineSightFarm
 * Paper/Folia plugin over the {@code minesight:farm} channel. The full client
 * will add the detection overlay, world markers/radar, and the capture+label
 * pipeline on top of this transport.
 */
public class MineSightClient implements ClientModInitializer {

    private static final Logger LOG = LoggerFactory.getLogger("minesight");

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.playS2C().register(FarmPayload.ID, FarmPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(FarmPayload.ID, FarmPayload.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(FarmPayload.ID,
                (payload, context) -> context.client().execute(() -> handle(payload.data())));

        // PoC: greet the plugin on join; it should reply with a pong.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (ClientPlayNetworking.canSend(FarmPayload.ID)) {
                ClientPlayNetworking.send(new FarmPayload(hello()));
                LOG.info("Sent hello to MineSightFarm");
            } else {
                LOG.info("Server does not register the minesight:farm channel (no plugin?)");
            }
        });
    }

    private static byte[] hello() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream d = new DataOutputStream(out)) {
            d.writeUTF("hello");
            d.writeUTF("fabric-client");
        } catch (IOException ignored) {
        }
        return out.toByteArray();
    }

    private static void handle(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            String type = in.readUTF();
            if ("pong".equals(type)) {
                String who = in.readUTF();
                LOG.info("Got pong from {}", who);
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.player != null) {
                    mc.player.sendMessage(Text.literal("[MineSight] linked to " + who), false);
                }
            }
        } catch (IOException e) {
            LOG.warn("bad packet", e);
        }
    }
}
