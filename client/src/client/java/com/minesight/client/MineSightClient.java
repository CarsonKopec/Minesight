package com.minesight.client;

import com.minesight.client.capture.CaptureManager;
import com.minesight.client.detect.DetectionAnchor;
import com.minesight.client.detect.DetectionStore;
import com.minesight.client.detect.EngineClient;
import com.minesight.client.detect.OreMemory;
import com.minesight.client.detect.OverlayMode;
import com.minesight.client.detect.OverlayRenderer;
import com.minesight.client.detect.RadarRenderer;
import com.minesight.client.detect.WorldHighlightRenderer;
import com.minesight.client.detect.WorldMarkerRenderer;
import com.minesight.client.nav.Navigator;
import com.minesight.client.net.FarmPayload;
import com.minesight.client.net.FarmProtocol;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.IOException;

/**
 * MineSight Fabric client (1.21.11) - the camera + vision-overlay side of 2.0.
 *
 * <ul>
 *   <li><b>Farm transport</b>: custom packets with the MineSightFarm Folia
 *       plugin over {@code minesight:farm}; handles {@code capture} requests via
 *       {@link CaptureManager} (render, grab framebuffer, project boxes, ack).</li>
 *   <li><b>Detection overlay</b>: connects to the Python ML engine
 *       ({@link EngineClient}), streams player state, and draws live detection
 *       boxes on the HUD ({@link OverlayRenderer}). F8 cycles overlay mode, F9
 *       flags the current frame for review.</li>
 * </ul>
 */
public class MineSightClient implements ClientModInitializer {

    private static final Logger LOG = LoggerFactory.getLogger("minesight");

    private CaptureManager capture;
    private EngineClient engine;
    private DetectionAnchor anchor;
    private Navigator navigator;
    private KeyBinding overlayKey;
    private KeyBinding reviewKey;
    private KeyBinding radarKey;
    private KeyBinding navKey;

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.playS2C().register(FarmPayload.ID, FarmPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(FarmPayload.ID, FarmPayload.CODEC);

        MinecraftClient mc = MinecraftClient.getInstance();
        capture = new CaptureManager(mc);

        // Detection overlay + world memory: engine connection, anchoring, and
        // the HUD layers (2D detection boxes, through-wall markers, radar).
        DetectionStore store = new DetectionStore();
        OreMemory memory = new OreMemory();
        engine = new EngineClient(store);
        anchor = new DetectionAnchor(mc, store, memory);
        OverlayRenderer overlay = new OverlayRenderer(store);
        WorldHighlightRenderer highlights = new WorldHighlightRenderer(mc, memory);
        WorldMarkerRenderer markers = new WorldMarkerRenderer(mc, memory);
        RadarRenderer radar = new RadarRenderer(mc, memory);
        navigator = new Navigator(mc, memory);
        // 3D ore boxes + nav route in world space; 2D boxes + labels + radar on the HUD.
        WorldRenderEvents.AFTER_ENTITIES.register(highlights::render);
        WorldRenderEvents.AFTER_ENTITIES.register(navigator.renderer()::render);
        HudRenderCallback.EVENT.register((ctx, tick) -> {
            overlay.render(ctx);
            markers.render(ctx);
            radar.render(ctx);
        });

        overlayKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.minesight.overlay", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F8,
                KeyBinding.Category.MISC));
        reviewKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.minesight.review", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F9,
                KeyBinding.Category.MISC));
        radarKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.minesight.radar", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F7,
                KeyBinding.Category.MISC));
        navKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.minesight.navigate", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F6,
                KeyBinding.Category.MISC));

        // One client-tick handler: capture state machine, engine I/O, anchoring,
        // keybinds.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            capture.tick();
            engine.tick(client);
            anchor.tick();
            navigator.tick();
            while (navKey.wasPressed()) {
                navigator.toggle();
            }
            while (overlayKey.wasPressed()) {
                OverlayMode mode = OverlayMode.cycle();
                if (client.player != null) {
                    client.player.sendMessage(Text.literal("[MineSight] overlay: " + mode.label), true);
                }
            }
            while (reviewKey.wasPressed()) {
                engine.reviewCapture();
                if (client.player != null) {
                    client.player.sendMessage(Text.literal("[MineSight] frame flagged for review"), true);
                }
            }
            while (radarKey.wasPressed()) {
                boolean on = RadarRenderer.toggle();
                if (client.player != null) {
                    client.player.sendMessage(Text.literal("[MineSight] radar: " + (on ? "on" : "off")), true);
                }
            }
        });

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
