package com.minesight.client.detect;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;

import java.util.List;

/**
 * True 3D ore highlights: wireframe boxes drawn on the actual blocks in world
 * space (the 1.21 successor to the 1.8.9 {@code Tessellator} box rendering), via
 * {@code WorldRenderEvents} + {@link VertexRendering#drawOutline} into the
 * {@link RenderLayers#LINES} layer. Replaces the earlier HUD-projected 2D rects.
 *
 * <p>Depth-tested, so a box shows on visible ore; the radar/labels cover ore
 * that's currently behind terrain. Fresh sightings glow; old memory dims. Owns
 * the forget-mined-block check since it already walks the in-range nodes.
 */
public final class WorldHighlightRenderer {

    private static final int RENDER_RANGE = 48;
    private static final long FRESH_MS = 4000;
    private static final float LINE_WIDTH = 1.5f;

    private final MinecraftClient mc;
    private final OreMemory memory;

    public WorldHighlightRenderer(MinecraftClient mc, OreMemory memory) {
        this.mc = mc;
        this.memory = memory;
    }

    /** Registered on WorldRenderEvents.AFTER_ENTITIES (camera-relative matrices). */
    public void render(WorldRenderContext ctx) {
        if (!OverlayMode.get().world()) {
            return;
        }
        if (mc.player == null || mc.world == null || mc.gameRenderer.getCamera() == null) {
            return;
        }
        List<OreMemory.Node> nodes = memory.snapshot();
        if (nodes.isEmpty()) {
            return;
        }

        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        MatrixStack matrices = ctx.matrices();
        VertexConsumer lines = ctx.consumers().getBuffer(RenderLayers.LINES);
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
            float alpha = now - node.lastSeen < FRESH_MS ? 0.95f
                    : (OreColors.isRare(node.label) ? 0.7f : 0.4f);
            int color = withAlpha(OreColors.colorFor(node.label), alpha);
            VertexRendering.drawOutline(matrices, lines, VoxelShapes.fullCube(),
                    node.pos.getX() - cam.x, node.pos.getY() - cam.y, node.pos.getZ() - cam.z,
                    color, LINE_WIDTH);
        }
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
}
