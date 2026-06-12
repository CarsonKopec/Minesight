package com.minesight;

import com.minesight.collector.AutoWorld;
import com.minesight.collector.CaptureHandler;
import com.minesight.collector.CollectorController;
import com.minesight.collector.CollectorSocket;
import com.minesight.render.OverlayRenderer;
import com.minesight.ws.WebSocketManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;

import java.net.URI;

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

    /** The Control Panel GUI hosts this server; override with -Dminesight.collector */
    public static final String COLLECTOR_URI =
            System.getProperty("minesight.collector", "ws://127.0.0.1:8766");

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        DetectionStore store = new DetectionStore();
        WebSocketManager ws = new WebSocketManager(URI.create(BACKEND_URI), store);
        ws.start();

        MinecraftForge.EVENT_BUS.register(new PlayerStateSender(ws));
        MinecraftForge.EVENT_BUS.register(new OverlayRenderer(store));

        // Dataset collector: dormant until the Control Panel sends collect_start.
        CollectorController collector = new CollectorController();
        CollectorSocket collectorSocket = new CollectorSocket(URI.create(COLLECTOR_URI), collector);
        collector.setSocket(collectorSocket);
        collectorSocket.start();
        MinecraftForge.EVENT_BUS.register(collector);
        MinecraftForge.EVENT_BUS.register(new CaptureHandler(collector));
        // Auto-opens a world when launched with -Dminesight.autoworld=<name>
        MinecraftForge.EVENT_BUS.register(new AutoWorld());
    }
}
