package com.minesight.client.nav;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * Walks the player along a path by holding the movement keys and steering the
 * view - the "auto-walk" half (no mining). Driving the vanilla key bindings
 * (rather than the refactored input record) keeps it stable across 1.21.x: the
 * movement system reads {@code forwardKey.isPressed()} as if you were holding W.
 *
 * <p>Client-thread only. Stops itself on arrival or when stuck (the
 * {@link Navigator} then re-paths or gives up).
 */
public final class AutoWalker {

    private static final double WAYPOINT_RADIUS = 0.6;
    private static final int STUCK_WINDOW = 20;
    private static final double STUCK_MIN_MOVE = 0.4;

    private final MinecraftClient mc;
    private List<BlockPos> path;
    private int index;
    private boolean active;
    private double lastX;
    private double lastZ;
    private int stuckTicks;

    public AutoWalker(MinecraftClient mc) {
        this.mc = mc;
    }

    public boolean isActive() {
        return active;
    }

    public void start(List<BlockPos> path) {
        this.path = path;
        this.index = 0;
        this.active = true;
        this.stuckTicks = 0;
        if (mc.player != null) {
            lastX = mc.player.getX();
            lastZ = mc.player.getZ();
        }
    }

    public void stop() {
        active = false;
        release();
    }

    public void tick() {
        if (!active) {
            return;
        }
        ClientPlayerEntity player = mc.player;
        if (player == null || path == null || index >= path.size()) {
            stop();
            return;
        }
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();

        BlockPos wp = path.get(index);
        double dx = wp.getX() + 0.5 - px;
        double dz = wp.getZ() + 0.5 - pz;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz < WAYPOINT_RADIUS && Math.abs(wp.getY() - py) < 1.3) {
            if (++index >= path.size()) {
                stop();
                return;
            }
            wp = path.get(index);
            dx = wp.getX() + 0.5 - px;
            dz = wp.getZ() + 0.5 - pz;
        }

        // Steer toward the waypoint and hold forward.
        player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        player.setPitch(0.0f);
        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(horiz > 1.5);
        boolean stepUp = wp.getY() > Math.floor(py) + 0.01;
        mc.options.jumpKey.setPressed(stepUp || player.horizontalCollision);

        if (++stuckTicks >= STUCK_WINDOW) {
            double moved = Math.hypot(px - lastX, pz - lastZ);
            lastX = px;
            lastZ = pz;
            stuckTicks = 0;
            if (moved < STUCK_MIN_MOVE) {
                stop();  // Navigator re-paths or gives up
            }
        }
    }

    private void release() {
        mc.options.forwardKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
    }
}
