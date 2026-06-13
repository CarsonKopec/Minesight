package com.minesight.world;

import com.minesight.OreScanner;
import com.minesight.OverlayMode;
import com.minesight.render.OreColors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.List;

/**
 * Phase 5: a top-down radar/minimap of the ore memory, drawn on the HUD.
 *
 * Player sits at the center facing up; remembered ore nodes within range plot
 * as color-coded dots, rare ores beyond range clamp to the rim as directional
 * indicators. Below it: an action suggestion toward the nearest valuable ore
 * and a depth advisor (what vanilla spawns at this Y). Toggle with F7.
 */
public class RadarRenderer {
    private static final int SIZE = 104;       // radar square, px
    private static final int MARGIN = 6;
    private static final double RANGE = 64.0;   // blocks shown to the rim
    private static final int BG = 0xA0101418;
    private static final int BORDER = 0xFF3A3F45;
    private static final int RING = 0x40FFFFFF;

    private static boolean enabled = true;

    private final OreMemory memory;

    public RadarRenderer(OreMemory memory) {
        this.memory = memory;
    }

    public static boolean toggle() {
        enabled = !enabled;
        return enabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
        if (!enabled || OverlayMode.get() == OverlayMode.OFF) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null || mc.gameSettings.hideGUI) return;
        if (mc.getIntegratedServer() != null) {
            memory.load(mc.getIntegratedServer().getFolderName());
        }

        ScaledResolution sr = new ScaledResolution(mc);
        int cx = sr.getScaledWidth() - MARGIN - SIZE / 2;
        int cy = MARGIN + SIZE / 2;
        int half = SIZE / 2;

        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);

        Gui.drawRect(cx - half, cy - half, cx + half, cy + half, BG);
        drawBorder(cx - half, cy - half, cx + half, cy + half, BORDER);
        // range rings at 1/3 and 2/3
        ring(cx, cy, half / 3);
        ring(cx, cy, half * 2 / 3);

        double px = mc.thePlayer.posX;
        double pz = mc.thePlayer.posZ;
        float yaw = mc.thePlayer.rotationYaw;
        double yawRad = Math.toRadians(yaw);
        double lookX = -Math.sin(yawRad);
        double lookZ = Math.cos(yawRad);
        double rightX = -lookZ;
        double rightZ = lookX;
        double scale = half / RANGE;

        OreMemory.Node nearestValuable = null;
        double nearestDist = Double.MAX_VALUE;

        for (OreMemory.Node node : memory.snapshot()) {
            double dx = (node.pos.getX() + 0.5) - px;
            double dz = (node.pos.getZ() + 0.5) - pz;
            double forward = dx * lookX + dz * lookZ;
            double right = dx * rightX + dz * rightZ;
            double dist = Math.sqrt(dx * dx + dz * dz);
            int color = OreColors.colorFor(node.label);

            boolean rare = OreColors.isRare(node.label);
            if (rare && dist < nearestDist) {
                nearestDist = dist;
                nearestValuable = node;
            }

            int sx = (int) Math.round(cx + right * scale);
            int sy = (int) Math.round(cy - forward * scale);
            if (dist <= RANGE) {
                int r = rare ? 2 : 1;
                Gui.drawRect(sx - r, sy - r, sx + r + 1, sy + r + 1, color);
            } else if (rare) {
                // Clamp rare ores beyond range to the rim as a direction arrow.
                double len = Math.max(1e-6, Math.sqrt(right * right + forward * forward));
                int ex = (int) Math.round(cx + (right / len) * (half - 3));
                int ey = (int) Math.round(cy - (forward / len) * (half - 3));
                Gui.drawRect(ex - 2, ey - 2, ex + 3, ey + 3, color);
            }
        }

        // Player marker: white dot + a notch pointing up (forward).
        Gui.drawRect(cx - 1, cy - 1, cx + 2, cy + 2, 0xFFFFFFFF);
        Gui.drawRect(cx, cy - 5, cx + 1, cy, 0xFFFFFFFF);

        // Below the radar: suggestion + depth advisor.
        int textY = cy + half + 3;
        int rightEdge = cx + half;
        if (nearestValuable != null) {
            String dir = direction(nearestValuable, px, pz, lookX, lookZ, rightX, rightZ);
            String text = dir + " " + nearestValuable.label.replace("_ore", "")
                    + " " + (int) nearestDist + "m";
            int color = OreColors.colorFor(nearestValuable.label);
            mc.fontRendererObj.drawStringWithShadow(
                    text, rightEdge - mc.fontRendererObj.getStringWidth(text), textY, color);
            textY += 10;
        }
        String depth = OreScanner.depthHint((int) mc.thePlayer.posY);
        if (!depth.isEmpty()) {
            String text = "Y" + (int) mc.thePlayer.posY + " · " + depth;
            mc.fontRendererObj.drawStringWithShadow(
                    text, rightEdge - mc.fontRendererObj.getStringWidth(text), textY, 0xFFB0B0B0);
        }

        GlStateManager.disableBlend();
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.popMatrix();
    }

    /** Arrow + word for the action suggestion: ahead / behind / left / right. */
    private static String direction(OreMemory.Node node, double px, double pz,
                                    double lookX, double lookZ, double rightX, double rightZ) {
        double dx = (node.pos.getX() + 0.5) - px;
        double dz = (node.pos.getZ() + 0.5) - pz;
        double forward = dx * lookX + dz * lookZ;
        double right = dx * rightX + dz * rightZ;
        if (Math.abs(forward) >= Math.abs(right)) {
            return forward >= 0 ? "▲" : "▼";  // ahead / behind
        }
        return right >= 0 ? "▶" : "◀";        // right / left
    }

    private static void ring(int cx, int cy, int r) {
        Gui.drawRect(cx - r, cy - r, cx + r + 1, cy - r + 1, RING);
        Gui.drawRect(cx - r, cy + r, cx + r + 1, cy + r + 1, RING);
        Gui.drawRect(cx - r, cy - r, cx - r + 1, cy + r + 1, RING);
        Gui.drawRect(cx + r, cy - r, cx + r + 1, cy + r + 1, RING);
    }

    private static void drawBorder(int x0, int y0, int x1, int y1, int color) {
        Gui.drawRect(x0, y0, x1, y0 + 1, color);
        Gui.drawRect(x0, y1 - 1, x1, y1, color);
        Gui.drawRect(x0, y0, x0 + 1, y1, color);
        Gui.drawRect(x1 - 1, y0, x1, y1, color);
    }
}
