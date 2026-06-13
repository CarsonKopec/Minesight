package com.minesight;

import com.minesight.world.OreMemory;
import com.minesight.world.RadarRenderer;
import com.minesight.world.WorldMarkers;
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

/**
 * MineSight world awareness (Phases 3-5): persistent per-world ore memory,
 * world-anchored 3D markers, and the HUD radar/minimap. Reads the shared
 * {@link DetectionStore} that the detection mod populates, so it requires both
 * core and detection.
 */
@Mod(modid = MineSightWorld.MODID, useMetadata = true,
        dependencies = "required-after:minesightcore;required-after:minesightdetection")
public class MineSightWorld {
    public static final String MODID = "minesightworld";

    private KeyBinding toggleRadar;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        DetectionStore store = DetectionStore.getInstance();
        OreMemory memory = new OreMemory();
        MinecraftForge.EVENT_BUS.register(new WorldMarkers(store, memory));
        MinecraftForge.EVENT_BUS.register(new RadarRenderer(memory));

        toggleRadar = new KeyBinding("Toggle MineSight radar", Keyboard.KEY_F7, "MineSight");
        ClientRegistry.registerKeyBinding(toggleRadar);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || toggleRadar == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        while (toggleRadar.isPressed()) {
            boolean on = RadarRenderer.toggle();
            if (mc.thePlayer != null) {
                mc.thePlayer.addChatMessage(new ChatComponentText(
                        EnumChatFormatting.AQUA + "[MineSight] " + EnumChatFormatting.RESET
                                + "radar: " + (on ? "on" : "off")));
            }
        }
    }
}
