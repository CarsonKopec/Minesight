package com.minesight.client.detect;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.render.Camera;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Anchors the engine's 2D detections to real world blocks - the 1.21 port of the
 * 1.8.9 {@code WorldMarkers} anchoring (Phase 4 folded into Phase 3).
 *
 * <p>{@code gluProject}/the GL matrix stack are gone, so this rebuilds the camera
 * transform deterministically (camera pose + a perspective matrix from the FOV),
 * inverts it, unprojects each detection's box center to a world ray, and raycasts:
 * the block it hits is what the model was looking at. Hits feed {@link OreMemory}.
 *
 * <p>Runs on the client tick; shares the projection convention with
 * {@link com.minesight.client.capture.GroundTruthProjector}, so it carries the
 * same FOV/near calibration caveat (risk #2 / backlog #7).
 */
public final class DetectionAnchor {

    private static final float MIN_CONFIDENCE = 0.45f;
    private static final double ANCHOR_RANGE = 48.0;
    private static final float NEAR_PLANE = 0.05f;
    private static final float FAR_PLANE = 1000.0f;

    private final MinecraftClient mc;
    private final DetectionStore store;
    private final OreMemory memory;
    private long lastProcessed;

    public DetectionAnchor(MinecraftClient mc, DetectionStore store, OreMemory memory) {
        this.mc = mc;
        this.store = store;
        this.memory = memory;
    }

    public void tick() {
        if (mc.world == null || mc.player == null || mc.gameRenderer.getCamera() == null) {
            return;
        }
        DetectionFrame frame = store.getFresh();
        if (frame == null || frame.objects == null || frame.objects.isEmpty()) {
            return;
        }
        if (frame.receivedAt == lastProcessed) {
            return;  // anchor each engine frame at most once
        }
        lastProcessed = frame.receivedAt;
        memory.load(worldKey());

        Camera camera = mc.gameRenderer.getCamera();
        Vec3d camPos = camera.getCameraPos();
        int fbW = mc.getWindow().getFramebufferWidth();
        int fbH = mc.getWindow().getFramebufferHeight();
        double fovDeg = mc.options.getFov().getValue();
        // inv = (P * Rx(pitch) * Ry(yaw+180))^-1  (same transform GroundTruthProjector builds).
        Matrix4f inv = new Matrix4f()
                .perspective((float) Math.toRadians(fovDeg), (float) fbW / fbH, NEAR_PLANE, FAR_PLANE)
                .rotateX((float) Math.toRadians(camera.getPitch()))
                .rotateY((float) Math.toRadians(camera.getYaw() + 180.0))
                .invert();

        double scaleX = frame.frame_w > 0 ? (double) fbW / frame.frame_w : 1.0;
        double scaleY = frame.frame_h > 0 ? (double) fbH / frame.frame_h : 1.0;

        for (Detection d : frame.objects) {
            if (d.confidence < MIN_CONFIDENCE) {
                continue;
            }
            double winX = d.x * scaleX;
            double winY = d.y * scaleY;
            float ndcX = (float) (2.0 * winX / fbW - 1.0);
            float ndcY = (float) (1.0 - 2.0 * winY / fbH);  // screen +Y down -> NDC +Y up
            Vec3d near = unproject(inv, camPos, ndcX, ndcY, -1.0f);
            Vec3d far = unproject(inv, camPos, ndcX, ndcY, 1.0f);
            if (near == null || far == null) {
                continue;
            }
            Vec3d dir = far.subtract(near);
            if (dir.lengthSquared() < 1.0e-6) {
                continue;
            }
            Vec3d end = near.add(dir.normalize().multiply(ANCHOR_RANGE));
            BlockHitResult hit = mc.world.raycast(new RaycastContext(
                    near, end, RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE, mc.player));
            if (hit.getType() != HitResult.Type.BLOCK) {
                continue;
            }
            // Verify the ray actually landed on a real ore. Otherwise a held ore
            // item (ray passes to the wall behind) or a moved/placed block would
            // get highlighted. Trust the world's block type as the label.
            BlockPos bp = hit.getBlockPos();
            String actual = OreBlocks.labelFor(mc.world.getBlockState(bp).getBlock());
            if (actual != null) {
                memory.record(bp, actual, d.confidence);
            }
        }
        memory.saveIfDirty();
    }

    private static Vec3d unproject(Matrix4f inv, Vec3d camPos, float ndcX, float ndcY, float ndcZ) {
        Vector4f p = new Vector4f(ndcX, ndcY, ndcZ, 1.0f);
        inv.transform(p);
        if (Math.abs(p.w) < 1.0e-6f) {
            return null;
        }
        return new Vec3d(camPos.x + p.x / p.w, camPos.y + p.y / p.w, camPos.z + p.z / p.w);
    }

    /** A stable per-world key for the memory file. */
    private String worldKey() {
        if (mc.isInSingleplayer()) {
            return "singleplayer";
        }
        ServerInfo s = mc.getCurrentServerEntry();
        return s != null ? "mp_" + s.address.replaceAll("[^A-Za-z0-9._-]", "_") : "world";
    }
}
