package com.minesight;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;

/**
 * MineSight core: shared library mod. Carries the code every MineSight feature
 * mod needs — detection data types ({@link Detection}, {@link DetectionFrame},
 * {@link DetectionStore}), {@link OverlayMode}, {@link OreScanner},
 * {@link com.minesight.render.OreColors}, and the shaded WebSocket client
 * ({@link com.minesight.net.ReconnectingSocket}). The feature mods declare a
 * required dependency on it, so this jar must be present for any of them to load.
 */
@Mod(modid = MineSightCore.MODID, useMetadata = true)
public class MineSightCore {
    public static final String MODID = "minesightcore";

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        // Library only - no behavior of its own.
    }
}
