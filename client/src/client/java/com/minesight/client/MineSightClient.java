package com.minesight.client;

import com.minesight.client.capture.CaptureManager;
import com.minesight.client.net.FarmPayload;
import com.minesight.client.net.FarmProtocol;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.IOException;

/**
 * MineSight Fabric client (1.21.11) - the camera side of the 2.0 split.
 *
 * <p>Transport: custom packets with the MineSightFarm Paper/Folia plugin over
 * the {@code minesight:farm} channel. On top of the verified hello/pong
 * round-trip this now handles {@code capture} requests: the plugin teleports the
 * spectator and sends the in-view ore AABBs; {@link CaptureManager} renders,
 * grabs the framebuffer, projects ground-truth boxes, writes the labeled frame,
 * and replies {@code captured}.
 */
public class MineSightClient implements ClientModInitializer {

    private static final Logger LOG = LoggerFactory.getLogger("minesight");

    private CaptureManager capture;

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.playS2C().register(FarmPayload.ID, FarmPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(FarmPayload.ID, FarmPayload.CODEC);

        MinecraftClient mc = MinecraftClient.getInstance();
        capture = new CaptureManager(mc);

        // Drive the capture state machine on the client tick.
        ClientTickEvents.END_CLIENT_TICK.register(client -> capture.tick());

        ClientPlayNetworking.registerGlobalReceiver(FarmPayload.ID,
                (payload, context) -> context.client().execute(() -> handle(payload.data())));

        // Greet the plugin on join; it should reply with a pong.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (ClientPlayNetworking.canSend(FarmPayload.ID)) {
                ClientPlayNetworking.send(new FarmPayload(FarmProtocol.hello("fabric-client")));
                LOG.info("Sent hello to MineSightFarm");
            } else {
                LOG.info("Server does not register the minesight:farm channel (no plugin?)");
            }
        });
    }

    private void handle(byte[] data) {
        try (DataInputStream in = FarmProtocol.reader(data)) {
            String type = in.readUTF();
            switch (type) {
                case FarmProtocol.PONG -> {
                    String who = in.readUTF();
                    LOG.info("Got pong from {}", who);
                    MinecraftClient mc = MinecraftClient.getInstance();
                    if (mc.player != null) {
                        mc.player.sendMessage(Text.literal("[MineSight] linked to " + who), false);
                    }
                }
                case FarmProtocol.CAPTURE -> capture.onCapture(FarmProtocol.readCaptureBody(in));
                default -> LOG.debug("Unknown packet type: {}", type);
            }
        } catch (IOException e) {
            LOG.warn("bad packet", e);
        }
    }
}
