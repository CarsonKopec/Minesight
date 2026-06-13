package com.minesight.client.detect;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.util.List;

/**
 * Top-down radar/minimap of the ore memory on the HUD - the 1.21 port of the
 * 1.8.9 {@code RadarRenderer}. The player sits at the center facing up; remembered
 * ore within range plots as color-coded dots, rare ore beyond range clamps to the
 * rim as a direction indicator. Below it: a suggestion toward the nearest valuable
 * ore and a depth advisor (what spawns at this Y). Toggled with F7.
 */
public final class RadarRenderer {

    private static final int SIZE = 104;
    private static final int MARGIN = 6;
    private static final double RANGE = 64.0;
    private static final int BG = 0xA0101418;
    private static final int BORDER = 0xFF3A3F45;
    private static final int RING = 0x40FFFFFF;

    private static boolean enabled = true;

    private final MinecraftClient mc;
    private final OreMemory memory;

    public RadarRenderer(MinecraftClient mc, OreMemory memory) {
        this.mc = mc;
        this.memory = memory;
    }

    public static boolean toggle() {
        enabled = !enabled;
        return enabled;
    }

    public void render(DrawContext ctx) {
        if (!enabled || OverlayMode.get() == OverlayMode.OFF) {
            return;
        }
        if (mc.player == null || mc.world == null) {
            return;
        }

        int cx = ctx.getScaledWindowWidth() - MARGIN - SIZE / 2;
        int cy = MARGIN + SIZE / 2;
        int half = SIZE / 2;

        ctx.fill(cx - half, cy - half, cx + half, cy + half, BG);
        border(ctx, cx - half, cy - half, cx + half, cy + half, BORDER);
        ring(ctx, cx, cy, half / 3);
        ring(ctx, cx, cy, half * 2 / 3);

        double px = mc.player.getX();
        double pz = mc.player.getZ();
        double yawRad = Math.toRadians(mc.player.getYaw());
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
                ctx.fill(sx - r, sy - r, sx + r + 1, sy + r + 1, color);
            } else if (rare) {
                double len = Math.max(1e-6, Math.sqrt(right * right + forward * forward));
                int ex = (int) Math.round(cx + (right / len) * (half - 3));
                int ey = (int) Math.round(cy - (forward / len) * (half - 3));
                ctx.fill(ex - 2, ey - 2, ex + 3, ey + 3, color);
            }
        }

        // Player marker: dot + a notch pointing up (forward).
        ctx.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xFFFFFFFF);
        ctx.fill(cx, cy - 5, cx + 1, cy, 0xFFFFFFFF);

        int textY = cy + half + 3;
        int rightEdge = cx + half;
        if (nearestValuable != null) {
            String dir = direction(nearestValuable, px, pz, lookX, lookZ, rightX, rightZ);
            String text = dir + " " + nearestValuable.label.replace("_ore", "")
                    + " " + (int) nearestDist + "m";
            int color = OreColors.colorFor(nearestValuable.label);
            ctx.drawText(mc.textRenderer, text,
                    rightEdge - mc.textRenderer.getWidth(text), textY, color, true);
            textY += 10;
        }
        String depth = Depths.hint(mc.player.getBlockY());
        if (!depth.isEmpty()) {
            String text = "Y" + mc.player.getBlockY() + " - " + depth;
            ctx.drawText(mc.textRenderer, text,
                    rightEdge - mc.textRenderer.getWidth(text), textY, 0xFFB0B0B0, true);
        }
    }

    private static String direction(OreMemory.Node node, double px, double pz,
                                    double lookX, double lookZ, double rightX, double rightZ) {
        double dx = (node.pos.getX() + 0.5) - px;
        double dz = (node.pos.getZ() + 0.5) - pz;
        double forward = dx * lookX + dz * lookZ;
        double right = dx * rightX + dz * rightZ;
        if (Math.abs(forward) >= Math.abs(right)) {
            return forward >= 0 ? "^" : "v";
        }
        return right >= 0 ? ">" : "<";
    }

    private static void ring(DrawContext ctx, int cx, int cy, int r) {
        ctx.fill(cx - r, cy - r, cx + r + 1, cy - r + 1, RING);
        ctx.fill(cx - r, cy + r, cx + r + 1, cy + r + 1, RING);
        ctx.fill(cx - r, cy - r, cx - r + 1, cy + r + 1, RING);
        ctx.fill(cx + r, cy - r, cx + r + 1, cy + r + 1, RING);
    }

    private static void border(DrawContext ctx, int x0, int y0, int x1, int y1, int color) {
        ctx.fill(x0, y0, x1, y0 + 1, color);
        ctx.fill(x0, y1 - 1, x1, y1, color);
        ctx.fill(x0, y0, x0 + 1, y1, color);
        ctx.fill(x1 - 1, y0, x1, y1, color);
    }
}
