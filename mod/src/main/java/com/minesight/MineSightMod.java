package com.minesight;

import com.minesight.collector.AutoWorld;
import com.minesight.collector.CaptureHandler;
import com.minesight.collector.CollectorController;
import com.minesight.collector.CollectorSocket;
import com.minesight.render.OverlayRenderer;
import com.minesight.world.OreMemory;
import com.minesight.world.WorldMarkers;
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

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;

/**
 * MineSight client mod (Phase 1).
 *
 * Connects to the Python ML engine over WebSocket, streams player state out,
 * and renders the detections it receives as a 2D overlay. All detection work
 * happens in the Python backend; this mod never scans the world itself.
 */
@Mod(modid = MineSightMod.MODID, useMetadata = true)
public class MineSightMod {
    public static final String MODID = "minesight";

    /** Override with -Dminesight.backend=ws://host:port */
    public static final String BACKEND_URI =
            System.getProperty("minesight.backend", "ws://127.0.0.1:8765");

    /**
     * Where the Control Panel's collector server lives. Resolution order:
     * -Dminesight.collector JVM arg, then a minesight-collector.txt file in
     * the game directory (written by the Farm Agent on remote machines),
     * then localhost.
     */
    public static String collectorUri() {
        String fromProp = System.getProperty("minesight.collector", "").trim();
        if (!fromProp.isEmpty()) return fromProp;
        try {
            File file = new File(Minecraft.getMinecraft().mcDataDir, "minesight-collector.txt");
            if (file.isFile()) {
                List<String> lines = Files.readAllLines(file.toPath());
                if (!lines.isEmpty() && !lines.get(0).trim().isEmpty()) {
                    return lines.get(0).trim();
                }
            }
        } catch (Exception ignored) {
        }
        return "ws://127.0.0.1:8766";
    }

    private KeyBinding toggleOverlay;
    private KeyBinding flagReview;
    private WebSocketManager backendWs;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        DetectionStore store = new DetectionStore();
        WebSocketManager ws = new WebSocketManager(URI.create(BACKEND_URI), store);
        ws.start();
        backendWs = ws;

        MinecraftForge.EVENT_BUS.register(new PlayerStateSender(ws));
        MinecraftForge.EVENT_BUS.register(new OverlayRenderer(store));

        // Phases 3+4: world-anchored markers with persistent ore memory.
        MinecraftForge.EVENT_BUS.register(new WorldMarkers(store, new OreMemory()));

        toggleOverlay = new KeyBinding("Cycle MineSight overlay", Keyboard.KEY_F8, "MineSight");
        flagReview = new KeyBinding("Flag detection for review", Keyboard.KEY_F9, "MineSight");
        ClientRegistry.registerKeyBinding(toggleOverlay);
        ClientRegistry.registerKeyBinding(flagReview);
        MinecraftForge.EVENT_BUS.register(this);

        // Dataset collector: dormant until the Control Panel sends collect_start.
        CollectorController collector = new CollectorController();
        CollectorSocket collectorSocket = new CollectorSocket(URI.create(collectorUri()), collector);
        collector.setSocket(collectorSocket);
        collectorSocket.start();
        MinecraftForge.EVENT_BUS.register(collector);
        MinecraftForge.EVENT_BUS.register(new CaptureHandler(collector));
        // Auto-opens a world when launched with -Dminesight.autoworld=<name>
        MinecraftForge.EVENT_BUS.register(new AutoWorld());
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
            // Ask the engine to snapshot the current frame + predictions so the
            // Control Panel can correct them and feed them back into training.
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
