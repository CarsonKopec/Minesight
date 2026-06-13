package com.minesight.world;

import com.minesight.Detection;
import com.minesight.DetectionFrame;
import com.minesight.DetectionStore;
import com.minesight.OverlayMode;
import com.minesight.render.OreColors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.Project;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;

/**
 * Phase 4: anchors the engine's 2D detections to real world positions.
 *
 * The spec expected noisy depth estimation from 2D boxes - but the mod has
 * the actual world: unproject each detection's box center through the live
 * GL matrices into a ray and raycast it; the block it hits is the block the
 * model was looking at. Those positions feed OreMemory (Phase 3) and are
 * rendered as through-wall world markers with per-vein labels.
 */
public class WorldMarkers {
    private static final float MIN_ANCHOR_CONFIDENCE = 0.45f;
    private static final double ANCHOR_RANGE = 32.0;
    private static final int RENDER_RANGE = 48;
    private static final long FRESH_MS = 4000;
    private static final long ALERT_COOLDOWN_MS = 3000;

    private final DetectionStore store;
    private final OreMemory memory;
    private final Minecraft mc = Minecraft.getMinecraft();

    private final FloatBuffer modelview = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private final FloatBuffer objPos = BufferUtils.createFloatBuffer(3);

    private long lastProcessed;
    private long lastAlert;
    private List<OreMemory.Cluster> clusterCache;
    private long clusterCacheAt;

    public WorldMarkers(DetectionStore store, OreMemory memory) {
        this.store = store;
        this.memory = memory;
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (mc.theWorld == null || mc.thePlayer == null) return;
        if (!OverlayMode.get().world()) return;
        // Memory is per singleplayer world; multiplayer gets a shared bucket.
        memory.load(mc.getIntegratedServer() != null
                ? mc.getIntegratedServer().getFolderName() : "multiplayer");

        DetectionFrame frame = store.getFresh();
        if (frame != null && frame.receivedAt != lastProcessed) {
            lastProcessed = frame.receivedAt;
            anchorDetections(frame);
        }
        renderMarkers();
        memory.saveIfDirty();
    }

    // --- Phase 4: 2D detection -> 3D block ------------------------------------

    private void anchorDetections(DetectionFrame frame) {
        modelview.clear();
        projection.clear();
        viewport.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, modelview);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projection);
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
        double vx = mc.getRenderManager().viewerPosX;
        double vy = mc.getRenderManager().viewerPosY;
        double vz = mc.getRenderManager().viewerPosZ;

        double scaleX = frame.frame_w > 0 ? (double) mc.displayWidth / frame.frame_w : 1.0;
        double scaleY = frame.frame_h > 0 ? (double) mc.displayHeight / frame.frame_h : 1.0;

        for (Detection d : frame.objects) {
            if (d.confidence < MIN_ANCHOR_CONFIDENCE) continue;
            float winX = (float) (d.x * scaleX);
            float winY = (float) (mc.displayHeight - d.y * scaleY);  // GL is bottom-up

            Vec3 near = unproject(winX, winY, 0f, vx, vy, vz);
            Vec3 far = unproject(winX, winY, 1f, vx, vy, vz);
            if (near == null || far == null) continue;
            Vec3 dir = new Vec3(far.xCoord - near.xCoord, far.yCoord - near.yCoord,
                    far.zCoord - near.zCoord).normalize();
            Vec3 end = near.addVector(dir.xCoord * ANCHOR_RANGE, dir.yCoord * ANCHOR_RANGE,
                    dir.zCoord * ANCHOR_RANGE);

            MovingObjectPosition hit = mc.theWorld.rayTraceBlocks(near, end, true, true, false);
            if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) continue;
            boolean isNew = memory.record(hit.getBlockPos(), d.label, d.confidence);
            if (isNew && OreColors.isRare(d.label)
                    && System.currentTimeMillis() - lastAlert > ALERT_COOLDOWN_MS) {
                lastAlert = System.currentTimeMillis();
                mc.thePlayer.playSound("random.orb", 0.8f, 1.5f);
            }
        }
    }

    private Vec3 unproject(float winX, float winY, float winZ, double vx, double vy, double vz) {
        objPos.clear();
        if (!Project.gluUnProject(winX, winY, winZ, modelview, projection, viewport, objPos)) {
            return null;
        }
        return new Vec3(objPos.get(0) + vx, objPos.get(1) + vy, objPos.get(2) + vz);
    }

    // --- Phase 3+4 rendering ----------------------------------------------------

    private void renderMarkers() {
        List<OreMemory.Node> nodes = memory.snapshot();
        if (nodes.isEmpty()) return;
        BlockPos playerPos = mc.thePlayer.getPosition();
        double vx = mc.getRenderManager().viewerPosX;
        double vy = mc.getRenderManager().viewerPosY;
        double vz = mc.getRenderManager().viewerPosZ;
        long now = System.currentTimeMillis();

        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.disableDepth();   // memory works through walls
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GL11.glLineWidth(2.0f);

        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        for (OreMemory.Node node : nodes) {
            if (node.pos.distanceSq(playerPos) > RENDER_RANGE * RENDER_RANGE) continue;
            // Forget mined blocks - but only judge REAL chunk data, never blanks.
            if (!mc.theWorld.getChunkFromBlockCoords(node.pos).isEmpty()
                    && mc.theWorld.isAirBlock(node.pos)) {
                memory.forget(node.pos);
                continue;
            }
            int color = OreColors.colorFor(node.label);
            float r = ((color >> 16) & 0xFF) / 255f;
            float g = ((color >> 8) & 0xFF) / 255f;
            float b = (color & 0xFF) / 255f;
            // Fresh sightings glow; old memory dims (rares stay brighter).
            float alpha = now - node.lastSeen < FRESH_MS ? 0.95f
                    : (OreColors.isRare(node.label) ? 0.65f : 0.35f);
            drawBoxOutline(tess, wr,
                    node.pos.getX() - vx, node.pos.getY() - vy, node.pos.getZ() - vz,
                    r, g, b, alpha);
        }

        GL11.glLineWidth(1.0f);
        GlStateManager.enableTexture2D();

        // One label per vein, refreshed every half second.
        if (now - clusterCacheAt > 500) {
            clusterCacheAt = now;
            clusterCache = memory.clusters(playerPos, RENDER_RANGE);
        }
        if (clusterCache != null) {
            for (OreMemory.Cluster c : clusterCache) {
                double dist = mc.thePlayer.getDistance(c.x, c.y, c.z);
                if (dist > RENDER_RANGE) continue;
                String text = c.label.replace("_ore", "")
                        + (c.count > 1 ? " ×" + c.count : "")
                        + " · " + (int) dist + "m";
                drawLabel(text, c.x - vx, c.y + 0.9 - vy, c.z - vz, OreColors.colorFor(c.label));
            }
        }

        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.popMatrix();
    }

    private static void drawBoxOutline(Tessellator tess, WorldRenderer wr,
                                       double x, double y, double z,
                                       float r, float g, float b, float a) {
        double e = 0.004;
        double x0 = x - e, y0 = y - e, z0 = z - e;
        double x1 = x + 1 + e, y1 = y + 1 + e, z1 = z + 1 + e;
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        line(wr, x0, y0, z0, x1, y0, z0, r, g, b, a);
        line(wr, x1, y0, z0, x1, y0, z1, r, g, b, a);
        line(wr, x1, y0, z1, x0, y0, z1, r, g, b, a);
        line(wr, x0, y0, z1, x0, y0, z0, r, g, b, a);
        line(wr, x0, y1, z0, x1, y1, z0, r, g, b, a);
        line(wr, x1, y1, z0, x1, y1, z1, r, g, b, a);
        line(wr, x1, y1, z1, x0, y1, z1, r, g, b, a);
        line(wr, x0, y1, z1, x0, y1, z0, r, g, b, a);
        line(wr, x0, y0, z0, x0, y1, z0, r, g, b, a);
        line(wr, x1, y0, z0, x1, y1, z0, r, g, b, a);
        line(wr, x1, y0, z1, x1, y1, z1, r, g, b, a);
        line(wr, x0, y0, z1, x0, y1, z1, r, g, b, a);
        tess.draw();
    }

    private static void line(WorldRenderer wr,
                             double x0, double y0, double z0,
                             double x1, double y1, double z1,
                             float r, float g, float b, float a) {
        wr.pos(x0, y0, z0).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, z1).color(r, g, b, a).endVertex();
    }

    /** Billboarded floating text, nameplate-style. */
    private void drawLabel(String text, double x, double y, double z, int color) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);
        GlStateManager.rotate(-mc.getRenderManager().playerViewY, 0f, 1f, 0f);
        GlStateManager.rotate(mc.getRenderManager().playerViewX, 1f, 0f, 0f);
        GlStateManager.scale(-0.026f, -0.026f, 0.026f);
        FontRenderer fr = mc.fontRendererObj;
        fr.drawStringWithShadow(text, -fr.getStringWidth(text) / 2f, 0f, color);
        GlStateManager.popMatrix();
    }
}
