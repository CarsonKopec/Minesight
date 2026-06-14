package com.minesight.client.nav;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

import java.util.List;

/**
 * Draws the navigation route in world space: a small marker on each path node
 * (a breadcrumb trail) plus a full box on the goal ore. Uses the same
 * {@code WorldRenderEvents} + {@link RenderLayers#LINES} path as the ore
 * highlights.
 */
public final class PathRenderer {

    private static final int PATH_COLOR = 0xCC4AEDD9;   // accent, semi-transparent
    private static final int GOAL_COLOR = 0xFFFFD24A;   // amber
    private static final VoxelShape DOT = VoxelShapes.cuboid(0.35, 0.02, 0.35, 0.65, 0.14, 0.65);

    private final MinecraftClient mc;
    private volatile List<BlockPos> path;
    private volatile BlockPos goal;

    public PathRenderer(MinecraftClient mc) {
        this.mc = mc;
    }

    public void setPath(List<BlockPos> path, BlockPos goal) {
        this.path = path;
        this.goal = goal;
    }

    public void render(WorldRenderContext ctx) {
        List<BlockPos> p = path;
        if (p == null || p.isEmpty() || mc.gameRenderer.getCamera() == null) {
            return;
        }
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        MatrixStack matrices = ctx.matrices();
        VertexConsumer lines = ctx.consumers().getBuffer(RenderLayers.LINES);
        for (BlockPos wp : p) {
            VertexRendering.drawOutline(matrices, lines, DOT,
                    wp.getX() - cam.x, wp.getY() - cam.y, wp.getZ() - cam.z, PATH_COLOR, 1.5f);
        }
        BlockPos g = goal;
        if (g != null) {
            VertexRendering.drawOutline(matrices, lines, VoxelShapes.fullCube(),
                    g.getX() - cam.x, g.getY() - cam.y, g.getZ() - cam.z, GOAL_COLOR, 2.0f);
        }
    }
}
