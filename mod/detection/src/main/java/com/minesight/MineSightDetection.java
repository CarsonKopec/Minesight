package com.minesight;

import com.minesight.render.OverlayRenderer;
import com.minesight.ws.WebSocketManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

import java.net.URI;

/**
 * MineSight detection overlay (Phase 1-2). Connects to the Python ML engine,
 * streams player state out, renders the 2D detection overlay, and exposes the
 * overlay-mode (F8) and review-capture (F9) keybinds. Detection work happens in
 * the engine; this never scans the world itself.
 */
@Mod(modid = MineSightDetection.MODID, useMetadata = true,
        dependencies = "required-after:minesightcore")
public class MineSightDetection {
    public static final String MODID = "minesightdetection";

    /** Override with -Dminesight.backend=ws://host:port */
    public static final String BACKEND_URI =
            System.getProperty("minesight.backend", "ws://127.0.0.1:8765");

    private KeyBinding toggleOverlay;
    private KeyBinding flagReview;
    private WebSocketManager backendWs;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        DetectionStore store = DetectionStore.getInstance();
        backendWs = new WebSocketManager(URI.create(BACKEND_URI), store);
        backendWs.start();

        MinecraftForge.EVENT_BUS.register(new PlayerStateSender(backendWs));
        MinecraftForge.EVENT_BUS.register(new OverlayRenderer(store));

        toggleOverlay = new KeyBinding("Cycle MineSight overlay", Keyboard.KEY_F8, "MineSight");
        flagReview = new KeyBinding("Flag detection for review", Keyboard.KEY_F9, "MineSight");
        ClientRegistry.registerKeyBinding(toggleOverlay);
        ClientRegistry.registerKeyBinding(flagReview);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || toggleOverlay == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        while (toggleOverlay.isPressed()) {
            OverlayMode mode = OverlayMode.cycle();
            if (mc.thePlayer != null) {
                mc.thePlayer.addChatMessage(new ChatComponentText(
                        EnumChatFormatting.AQUA + "[MineSight] " + EnumChatFormatting.RESET
                                + "overlay: " + mode.label));
            }
        }
        while (flagReview.isPressed()) {
            if (backendWs != null) {
                backendWs.send("{\"type\":\"review_capture\"}");
            }
            if (mc.thePlayer != null) {
                mc.thePlayer.addChatMessage(new ChatComponentText(
                        EnumChatFormatting.AQUA + "[MineSight] " + EnumChatFormatting.RESET
                                + "flagged this frame for review"));
            }
        }
    }
}
