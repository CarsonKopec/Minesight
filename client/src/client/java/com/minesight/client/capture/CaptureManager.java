package com.minesight.client.capture;

import com.minesight.client.net.FarmPayload;
import com.minesight.client.net.FarmProtocol;
import com.minesight.client.net.GuiUploader;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives client-side capture: when the plugin sends a {@code capture} request,
 * settle for a few ticks (let the teleported view render), grab the framebuffer,
 * project + occlusion-test each ore AABB, write the labeled frame, and ack.
 *
 * <p>Ground-truth projection rebuilds the camera transform deterministically
 * (camera pose from {@code gameRenderer.getCamera()} + a perspective matrix from
 * the FOV option) rather than depending on the churning Fabric render-context
 * API. The exact FOV/near constants are the calibration knobs flagged as risk #2
 * / backlog #7 - tune them against in-game captures.
 *
 * <p>Threading: everything here runs on the client (main) thread, where camera
 * reads, framebuffer readback, and packet sends are all safe.
 */
public final class CaptureManager {

    private static final Logger LOG = LoggerFactory.getLogger("minesight");

    /** Ticks to wait after a request before capturing (let the view settle). */
    private static final int SETTLE_TICKS = 12;
    /** Give up on a request after this many ticks (chunks never rendered). */
    private static final int TIMEOUT_TICKS = 120;
    /** MC's world near plane; used when rebuilding the projection matrix. */
    private static final float NEAR_PLANE = 0.05f;
    private static final float FAR_PLANE = 1000.0f;

    private final MinecraftClient mc;
    private final DatasetWriter writer;
    private final GuiUploader uploader = new GuiUploader();

    // Pending request state (client-thread only).
    private FarmProtocol.CaptureRequest pending;
    private int waited;
    private boolean prevHudHidden;

    public CaptureManager(MinecraftClient mc) {
        this.mc = mc;
        this.writer = new DatasetWriter(new File(mc.runDirectory, "minesight/captures"));
    }

    /** Client-thread: accept a new capture request (replaces any in flight). */
    public void onCapture(FarmProtocol.CaptureRequest req) {
        this.pending = req;
        this.waited = 0;
        uploader.ensureConnected();  // best-effort; falls back to local-only
        if (req.hideHud()) {
            this.prevHudHidden = mc.options.hudHidden;
            mc.options.hudHidden = true;
        }
        LOG.info("Capture {} requested: {} ore box(es)", req.shotId(), req.boxes().size());
    }

    /** Client-thread tick: settle, then capture once the view is ready. */
    public void tick() {
        if (pending == null) {
            return;
        }
        waited++;
        boolean ready = mc.world != null && mc.gameRenderer.getCamera() != null;
        if (ready && waited >= SETTLE_TICKS) {
            doCapture();
            return;
        }
        if (waited >= TIMEOUT_TICKS) {
            LOG.warn("Capture {} timed out before the view was ready", pending.shotId());
            finish(false, 0);
        }
    }

    private void doCapture() {
        final FarmProtocol.CaptureRequest req = pending;
        final Framebuffer fb = mc.getFramebuffer();
        final int width = fb.textureWidth;
        final int height = fb.textureHeight;

        final Camera camera = mc.gameRenderer.getCamera();
        final Vec3d eye = camera.getCameraPos();
        final float yaw = camera.getYaw();
        final float pitch = camera.getPitch();
        final Matrix4f projection = projectionMatrix(width, height);

        final List<DatasetWriter.Box> visible = new ArrayList<>();
        for (FarmProtocol.OreBox box : req.boxes()) {
            GroundTruthProjector.Rect rect = GroundTruthProjector.project(
                    projection, eye, yaw, pitch, width, height,
                    box.minX(), box.minY(), box.minZ(),
                    box.maxX(), box.maxY(), box.maxZ());
            if (rect == null) {
                continue;
            }
            double cx = (box.minX() + box.maxX()) / 2.0;
            double cy = (box.minY() + box.maxY()) / 2.0;
            double cz = (box.minZ() + box.maxZ()) / 2.0;
            if (!hasLineOfSight(eye, cx, cy, cz)) {
                continue;  // ore is occluded by terrain - don't label it
            }
            visible.add(new DatasetWriter.Box(box.label(), rect));
        }

        if (visible.isEmpty()) {
            LOG.info("Capture {}: no visible ore, nothing saved", req.shotId());
            finish(false, 0);
            return;
        }

        // takeScreenshot reads the framebuffer and hands us the NativeImage; do
        // all work inside the callback (the recorder owns the image's lifetime).
        ScreenshotRecorder.takeScreenshot(fb, image -> {
            File saved = writer.write(image, visible, req.shotId());
            if (saved != null) {
                LOG.info("Capture {} saved {} box(es) -> {}",
                        req.shotId(), visible.size(), saved.getName());
                streamToGui(saved, visible, width, height);
            } else {
                LOG.warn("Capture {}: write failed", req.shotId());
            }
            finish(saved != null, visible.size());
        });
    }

    /** Stream a saved frame to the Control Panel if connected (local copy stays). */
    private void streamToGui(File png, List<DatasetWriter.Box> boxes, int width, int height) {
        if (!uploader.isConnected()) {
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(png.toPath());
            String labels = DatasetWriter.buildLabels(boxes, width, height);
            uploader.uploadImage(png.getName(), bytes, labels, boxes.size());
        } catch (IOException e) {
            LOG.warn("GUI upload: could not read {} ({})", png.getName(), e.getMessage());
        }
    }

    /** Perspective matrix matching MC's world camera (FOV from options, world aspect). */
    private Matrix4f projectionMatrix(int width, int height) {
        double fovDeg = mc.options.getFov().getValue();
        float aspect = (float) width / (float) height;
        return new Matrix4f().perspective(
                (float) Math.toRadians(fovDeg), aspect, NEAR_PLANE, FAR_PLANE);
    }

    /** True if a ray from the camera reaches the ore cell without being blocked. */
    private boolean hasLineOfSight(Vec3d eye, double x, double y, double z) {
        if (mc.world == null || mc.player == null) {
            return true;
        }
        Vec3d target = new Vec3d(x, y, z);
        BlockHitResult hit = mc.world.raycast(new RaycastContext(
                eye, target, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, mc.player));
        if (hit.getType() == HitResult.Type.MISS) {
            return true;  // nothing in the way
        }
        BlockPos bp = hit.getBlockPos();
        return Math.abs(bp.getX() + 0.5 - x) < 1.0
                && Math.abs(bp.getY() + 0.5 - y) < 1.0
                && Math.abs(bp.getZ() + 0.5 - z) < 1.0;
    }

    private void finish(boolean ok, int boxes) {
        FarmProtocol.CaptureRequest req = pending;
        pending = null;
        if (req != null && req.hideHud()) {
            mc.options.hudHidden = prevHudHidden;
        }
        if (req != null && ClientPlayNetworking.canSend(FarmPayload.ID)) {
            ClientPlayNetworking.send(new FarmPayload(
                    FarmProtocol.captured(req.shotId(), ok, boxes)));
        }
    }
}
