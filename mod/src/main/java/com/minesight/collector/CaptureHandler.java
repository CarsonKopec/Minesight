package com.minesight.collector;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.BlockPos;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.Project;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Render-side of collection. Each frame during a session:
 *  1. If a capture is requested: verify the camera is actually on target,
 *     project visible ores to ground-truth boxes, read back the framebuffer.
 *  2. Draw in-game highlight boxes around the scanned ores - AFTER the pixel
 *     read, so the player sees them but the saved images never contain them.
 */
public class CaptureHandler {
    private static final int MIN_BOX_PX = 6;
    private static final int MIN_VISIBLE_SAMPLES = 2;
    /** The aimed ore must project inside this central fraction of the screen. */
    private static final float AIM_MARGIN = 0.08f;

    private final CollectorController controller;
    private final FloatBuffer modelview = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private final FloatBuffer winPos = BufferUtils.createFloatBuffer(3);

    public CaptureHandler(CollectorController controller) {
        this.controller = controller;
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        CollectSession session = controller.session();
        Minecraft mc = Minecraft.getMinecraft();
        if (session == null || mc.thePlayer == null || mc.theWorld == null) return;

        if (controller.captureRequested) {
            capture(mc, session);
        }
        // Game-only feedback; never part of the captured pixels above.
        drawHighlights(mc);
    }

    private void capture(Minecraft mc, CollectSession session) {
        // Matrices straight from GL: at RenderWorldLast the modelview is the
        // pure camera transform with the camera at the origin.
        modelview.clear();
        projection.clear();
        viewport.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, modelview);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projection);
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);

        double camX = mc.getRenderManager().viewerPosX;
        double camY = mc.getRenderManager().viewerPosY;
        double camZ = mc.getRenderManager().viewerPosZ;
        int width = mc.displayWidth;
        int height = mc.displayHeight;

        // Take the picture only once the camera is verifiably ON the target
        // ore (teleport + rotation packets need a few ticks to apply).
        Vec3 aim = controller.aimTargetVec();
        if (aim != null) {
            float[] p = projectPoint(aim.xCoord - camX, aim.yCoord - camY, aim.zCoord - camZ, height);
            if (p == null
                    || p[0] < width * AIM_MARGIN || p[0] > width * (1 - AIM_MARGIN)
                    || p[1] < height * AIM_MARGIN || p[1] > height * (1 - AIM_MARGIN)) {
                controller.onCaptureRejected("camera not on target yet");
                return;
            }
        }

        Vec3 eye = new Vec3(
                mc.thePlayer.posX,
                mc.thePlayer.posY + mc.thePlayer.getEyeHeight(),
                mc.thePlayer.posZ);

        List<float[]> boxes = new ArrayList<float[]>();
        List<BlockPos> boxedOres = new ArrayList<BlockPos>();
        List<String> boxedLabels = new ArrayList<String>();
        for (BlockPos ore : controller.scannedOres()) {
            if (ore.distanceSq(mc.thePlayer.getPosition()) > 16 * 16) continue;
            String label = OreScanner.labelFor(mc.theWorld.getBlockState(ore).getBlock());
            if (label == null) continue;
            int cls = session.classIndex(label);
            if (cls < 0) continue;
            if (RaycastUtil.visibleSamples(mc.theWorld, eye, ore) < MIN_VISIBLE_SAMPLES) continue;

            float[] box = projectBlock(ore, camX, camY, camZ, width, height);
            if (box == null) continue;
            boxes.add(new float[]{
                    cls,
                    (box[0] + box[2]) / 2f / width,   // cx
                    (box[1] + box[3]) / 2f / height,  // cy
                    (box[2] - box[0]) / width,        // w
                    (box[3] - box[1]) / height,       // h
            });
            boxedOres.add(ore);
            boxedLabels.add(label);
        }

        ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);

        // Don't save frames the renderer hasn't actually drawn yet (or that
        // are too dark/featureless to be useful training data).
        String reject = FrameQuality.evaluate(pixels, width, height);
        if (reject != null) {
            controller.onCaptureRejected(reject);
            return;
        }

        controller.onCaptured(pixels, width, height, boxes, boxedOres, boxedLabels);
    }

    /** Screen position {x, y, depth} of a camera-relative point, or null. */
    private float[] projectPoint(double rx, double ry, double rz, int screenHeight) {
        winPos.clear();
        if (!Project.gluProject((float) rx, (float) ry, (float) rz,
                modelview, projection, viewport, winPos)) {
            return null;
        }
        float depth = winPos.get(2);
        if (depth < 0f || depth > 1f) return null;
        return new float[]{winPos.get(0), screenHeight - winPos.get(1), depth};
    }

    /** Screen-space {minX, minY, maxX, maxY} of the block's AABB, or null. */
    private float[] projectBlock(BlockPos pos, double camX, double camY, double camZ,
                                 int width, int height) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int corner = 0; corner < 8; corner++) {
            double x = pos.getX() + ((corner & 1) == 0 ? 0 : 1) - camX;
            double y = pos.getY() + ((corner & 2) == 0 ? 0 : 1) - camY;
            double z = pos.getZ() + ((corner & 4) == 0 ? 0 : 1) - camZ;
            float[] p = projectPoint(x, y, z, height);
            if (p == null) {
                return null;  // a corner is behind the camera - skip this block
            }
            minX = Math.min(minX, p[0]);
            minY = Math.min(minY, p[1]);
            maxX = Math.max(maxX, p[0]);
            maxY = Math.max(maxY, p[1]);
        }
        minX = Math.max(0, minX);
        minY = Math.max(0, minY);
        maxX = Math.min(width, maxX);
        maxY = Math.min(height, maxY);
        if (maxX - minX < MIN_BOX_PX || maxY - minY < MIN_BOX_PX) {
            return null;
        }
        return new float[]{minX, minY, maxX, maxY};
    }

    // --- in-game highlights ---------------------------------------------------

    private void drawHighlights(Minecraft mc) {
        List<BlockPos> ores = controller.scannedOres();
        if (ores.isEmpty()) return;
        double vx = mc.getRenderManager().viewerPosX;
        double vy = mc.getRenderManager().viewerPosY;
        double vz = mc.getRenderManager().viewerPosZ;
        BlockPos target = controller.targetOre();

        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.disableDepth();   // show through walls - it's a debug view
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GL11.glLineWidth(2.0f);

        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        for (BlockPos ore : ores) {
            boolean isTarget = ore.equals(target);
            float r = isTarget ? 0.15f : 0.3f;
            float g = isTarget ? 1.0f : 0.9f;
            float b = isTarget ? 0.3f : 1.0f;
            drawBoxOutline(tess, wr,
                    ore.getX() - vx, ore.getY() - vy, ore.getZ() - vz, r, g, b);
        }

        GL11.glLineWidth(1.0f);
        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }

    private static void drawBoxOutline(Tessellator tess, WorldRenderer wr,
                                       double x, double y, double z,
                                       float r, float g, float b) {
        double e = 0.004;  // slight inflate so lines sit off the block faces
        double x0 = x - e, y0 = y - e, z0 = z - e;
        double x1 = x + 1 + e, y1 = y + 1 + e, z1 = z + 1 + e;
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        // bottom square
        line(wr, x0, y0, z0, x1, y0, z0, r, g, b);
        line(wr, x1, y0, z0, x1, y0, z1, r, g, b);
        line(wr, x1, y0, z1, x0, y0, z1, r, g, b);
        line(wr, x0, y0, z1, x0, y0, z0, r, g, b);
        // top square
        line(wr, x0, y1, z0, x1, y1, z0, r, g, b);
        line(wr, x1, y1, z0, x1, y1, z1, r, g, b);
        line(wr, x1, y1, z1, x0, y1, z1, r, g, b);
        line(wr, x0, y1, z1, x0, y1, z0, r, g, b);
        // verticals
        line(wr, x0, y0, z0, x0, y1, z0, r, g, b);
        line(wr, x1, y0, z0, x1, y1, z0, r, g, b);
        line(wr, x1, y0, z1, x1, y1, z1, r, g, b);
        line(wr, x0, y0, z1, x0, y1, z1, r, g, b);
        tess.draw();
    }

    private static void line(WorldRenderer wr,
                             double x0, double y0, double z0,
                             double x1, double y1, double z1,
                             float r, float g, float b) {
        wr.pos(x0, y0, z0).color(r, g, b, 0.9f).endVertex();
        wr.pos(x1, y1, z1).color(r, g, b, 0.9f).endVertex();
    }
}
