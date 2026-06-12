package com.minesight;

import com.google.gson.JsonObject;
import com.minesight.ws.WebSocketManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Streams player state (position, rotation, FOV) to the Python engine once
 * per client tick (20 Hz). Phase 1 doesn't consume it server-side yet, but
 * screen-to-world mapping (Phase 4) needs the history.
 */
public class PlayerStateSender {
    private final WebSocketManager ws;

    public PlayerStateSender(WebSocketManager ws) {
        this.ws = ws;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) return;

        JsonObject msg = new JsonObject();
        msg.addProperty("type", "player");
        msg.addProperty("x", player.posX);
        msg.addProperty("y", player.posY);
        msg.addProperty("z", player.posZ);
        msg.addProperty("yaw", player.rotationYaw);
        msg.addProperty("pitch", player.rotationPitch);
        msg.addProperty("fov", mc.gameSettings.fovSetting);
        ws.send(msg.toString());
    }
}
