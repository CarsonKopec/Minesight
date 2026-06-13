package com.minesight.collector;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Random;

/**
 * Launch helper for unattended collection farms: opens a singleplayer world
 * automatically once the main menu is up (creating it as a creative world
 * with a random seed if it doesn't exist).
 *
 * The world name comes from -Dminesight.autoworld=<name>, or from a
 * minesight-autoworld.txt file in the game directory - the Control Panel's
 * launcher writes that file into each client's isolated run directory.
 */
public class AutoWorld {
    /** Ticks the main menu must be stable before launching (~2s). */
    private static final int MENU_SETTLE_TICKS = 40;

    private final Minecraft mc = Minecraft.getMinecraft();
    private final String worldName;
    private boolean done;
    private int menuTicks;

    public AutoWorld() {
        String name = System.getProperty("minesight.autoworld", "").trim();
        if (name.isEmpty()) {
            File file = new File(mc.mcDataDir, "minesight-autoworld.txt");
            if (file.isFile()) {
                try {
                    List<String> lines = Files.readAllLines(file.toPath());
                    if (!lines.isEmpty()) {
                        name = lines.get(0).trim();
                    }
                } catch (Exception ignored) {
                }
            }
        }
        this.worldName = name;
        this.done = name.isEmpty();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (done || event.phase != TickEvent.Phase.END) return;
        if (mc.theWorld != null) {
            done = true;  // already in a world (user beat us to it)
            return;
        }
        // Launching from inside a GuiOpenEvent is reentrant; waiting for the
        // menu to sit stable for a moment is both safer and lets resource
        // loading finish.
        if (!(mc.currentScreen instanceof GuiMainMenu)) {
            menuTicks = 0;
            return;
        }
        if (++menuTicks < MENU_SETTLE_TICKS) return;
        done = true;
        if (mc.getSaveLoader().canLoadWorld(worldName)) {
            mc.launchIntegratedServer(worldName, worldName, null);
        } else {
            WorldSettings settings = new WorldSettings(
                    new Random().nextLong(),
                    WorldSettings.GameType.CREATIVE,
                    true,   // generate structures
                    false,  // hardcore
                    WorldType.DEFAULT);
            mc.launchIntegratedServer(worldName, worldName, settings);
        }
    }
}
