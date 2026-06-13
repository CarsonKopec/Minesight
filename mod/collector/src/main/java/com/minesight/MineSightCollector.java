package com.minesight;

import com.minesight.collector.AutoWorld;
import com.minesight.collector.CaptureHandler;
import com.minesight.collector.CollectorController;
import com.minesight.collector.CollectorSocket;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;

/**
 * MineSight dataset collector: automated, ground-truth screenshot collection
 * driven by the Control Panel. Independent of the detection overlay — a farm
 * machine loads just core + this.
 */
@Mod(modid = MineSightCollector.MODID, useMetadata = true)
public class MineSightCollector {
    public static final String MODID = "minesightcollector";

    /**
     * Where the Control Panel's collector server lives: -Dminesight.collector,
     * then a minesight-collector.txt file in the game directory (written by the
     * Farm Agent on remote machines), then localhost.
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

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        CollectorController collector = new CollectorController();
        CollectorSocket collectorSocket = new CollectorSocket(URI.create(collectorUri()), collector);
        collector.setSocket(collectorSocket);
        collectorSocket.start();
        MinecraftForge.EVENT_BUS.register(collector);
        MinecraftForge.EVENT_BUS.register(new CaptureHandler(collector));
        // Auto-opens a world when launched with -Dminesight.autoworld=<name>
        MinecraftForge.EVENT_BUS.register(new AutoWorld());
    }
}
