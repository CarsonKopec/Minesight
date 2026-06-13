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
 * Through-wall world markers for remembered ore - the 1.21 port of the 1.8.9
 * {@code WorldMarkers} rendering. Rather than fight the reworked world-render
 * pipeline, it projects each {@link OreMemory} node's block AABB to screen with
 * the same camera matrix the capture/anchor paths use and draws it on the HUD,
 * with one label per vein. Gated by {@link OverlayMode#world()}.
 */
public final class WorldMarkerRenderer {

    private static final int RENDER_RANGE = 48;
    private static final long FRESH_MS = 4000;
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
        if (mc.world == null || mc.player == null || mc.gameRenderer.getCamera() == null) {
            return;
        }
        List<OreMemory.Node> nodes = memory.snapshot();
        if (nodes.isEmpty()) {
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
        long range2 = (long) RENDER_RANGE * RENDER_RANGE;

        for (OreMemory.Node node : nodes) {
            if (sqDist(node.pos, playerPos) > range2) {
                continue;
            }
            // Forget mined blocks - but only judge loaded chunks, never blanks.
            if (mc.world.isChunkLoaded(node.pos) && mc.world.getBlockState(node.pos).isAir()) {
                memory.forget(node.pos);
                continue;
            }
            GroundTruthProjector.Rect r = GroundTruthProjector.project(
                    proj, camPos, yaw, pitch, w, h,
                    node.pos.getX(), node.pos.getY(), node.pos.getZ(),
                    node.pos.getX() + 1, node.pos.getY() + 1, node.pos.getZ() + 1);
            if (r == null) {
                continue;
            }
            // Fresh sightings glow; old memory dims (rares stay brighter).
            float alpha = now - node.lastSeen < FRESH_MS ? 0.95f
                    : (OreColors.isRare(node.label) ? 0.7f : 0.4f);
            int color = withAlpha(OreColors.colorFor(node.label), alpha);
            box(ctx, (int) r.x0(), (int) r.y0(), (int) r.x1(), (int) r.y1(), color);
        }

        renderLabels(ctx, proj, camPos, yaw, pitch, w, h, playerPos, now);
    }

    private void renderLabels(DrawContext ctx, Matrix4f proj, Vec3d camPos, float yaw, float pitch,
                              int w, int h, BlockPos playerPos, long now) {
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

    private static void box(DrawContext ctx, int left, int top, int right, int bottom, int color) {
        ctx.fill(left, top, right, top + 1, color);
        ctx.fill(left, bottom - 1, right, bottom, color);
        ctx.fill(left, top, left + 1, bottom, color);
        ctx.fill(right - 1, top, right, bottom, color);
    }

    private static int withAlpha(int argb, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    private static long sqDist(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX();
        long dy = a.getY() - b.getY();
        long dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static double sq(double v) {
        return v * v;
    }
}
