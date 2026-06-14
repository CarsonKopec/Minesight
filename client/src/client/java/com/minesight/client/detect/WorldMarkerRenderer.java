package com.minesight.client.detect;

import com.minesight.client.capture.GroundTruthProjector;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.List;

/**
 * HUD labels for the world markers - one floating name per ore vein, projected
 * to screen above the cluster. The 3D wireframe boxes themselves are drawn in
 * world space by {@link WorldHighlightRenderer}; this just adds the readable
 * labels (in-world text on the rewritten 1.21 pipeline is awkward, so labels
 * stay on the HUD). Gated by {@link OverlayMode#world()}.
 */
public final class WorldMarkerRenderer {

    private static final int RENDER_RANGE = 48;
    private static final float NEAR_PLANE = 0.05f;
    private static final float FAR_PLANE = 1000.0f;

    private final MinecraftClient mc;
    private final OreMemory memory;

    private long clusterCacheAt;
    private List<OreMemory.Cluster> clusterCache;

    public WorldMarkerRenderer(MinecraftClient mc, OreMemory memory) {
        this.mc = mc;
        this.memory = memory;
    }

    public void render(DrawContext ctx) {
        if (!OverlayMode.get().world()) {
            return;
        }
        if (mc.player == null || mc.world == null || mc.gameRenderer.getCamera() == null) {
            return;
        }
        if (memory.size() == 0) {
            return;
        }

        Camera camera = mc.gameRenderer.getCamera();
        Vec3d camPos = camera.getCameraPos();
        float yaw = camera.getYaw();
        float pitch = camera.getPitch();
        int w = ctx.getScaledWindowWidth();
        int h = ctx.getScaledWindowHeight();
        double fovDeg = mc.options.getFov().getValue();
        Matrix4f proj = new Matrix4f().perspective(
                (float) Math.toRadians(fovDeg), (float) w / h, NEAR_PLANE, FAR_PLANE);

        BlockPos playerPos = mc.player.getBlockPos();
        long now = System.currentTimeMillis();
        if (now - clusterCacheAt > 500 || clusterCache == null) {
            clusterCacheAt = now;
            clusterCache = memory.clusters(playerPos, RENDER_RANGE);
        }
        for (OreMemory.Cluster c : clusterCache) {
            double dist = Math.sqrt(sq(c.x - (playerPos.getX() + 0.5))
                    + sq(c.y - (playerPos.getY() + 0.5)) + sq(c.z - (playerPos.getZ() + 0.5)));
            if (dist > RENDER_RANGE) {
                continue;
            }
            double[] p = GroundTruthProjector.projectPoint(proj, camPos, yaw, pitch, w, h,
                    c.x, c.y + 0.9, c.z);
            if (p == null) {
                continue;
            }
            String text = c.label.replace("_ore", "")
                    + (c.count > 1 ? " x" + c.count : "") + " - " + (int) dist + "m";
            int tw = mc.textRenderer.getWidth(text);
            ctx.drawText(mc.textRenderer, text, (int) (p[0] - tw / 2.0), (int) p[1],
                    OreColors.colorFor(c.label), true);
        }
    }

    private static double sq(double v) {
        return v * v;
    }
}
