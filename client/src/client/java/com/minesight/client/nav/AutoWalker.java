package com.minesight.client.nav;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Walks the player along a path, mining through obstructions when the route
 * requires it - the "auto-walk + dig" executor. Movement is driven through the
 * vanilla key bindings (stable across the 1.21.x input refactor); mining is the
 * vanilla "look at the block and hold attack" so the interaction manager handles
 * swing + server sync.
 *
 * <p>Each tick it either (a) mines the block blocking the next waypoint, or
 * (b) walks toward the waypoint. Stops with {@link #isBlocked()} on unbreakable
 * blocks or nearby lava so the {@link Navigator} can re-path or bail.
 */
public final class AutoWalker {

    private static final double WAYPOINT_RADIUS = 0.6;
    private static final int STUCK_WINDOW = 40;       // mining is slow; be patient
    private static final double STUCK_MIN_MOVE = 0.35;

    private final MinecraftClient mc;
    private List<BlockPos> path;
    private int index;
    private boolean active;
    private boolean blocked;
    private double lastX;
    private double lastZ;
    private int stuckTicks;

    public AutoWalker(MinecraftClient mc) {
        this.mc = mc;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isBlocked() {
        return blocked;
    }

    public void start(List<BlockPos> path) {
        this.path = path;
        this.index = 0;
        this.active = true;
        this.blocked = false;
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
        if (player == null || mc.world == null || path == null || index >= path.size()) {
            stop();
            return;
        }
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();

        BlockPos wp = path.get(index);
        if (horiz(px, pz, wp) < WAYPOINT_RADIUS && Math.abs(wp.getY() - py) < 1.3) {
            if (++index >= path.size()) {
                stop();
                return;
            }
            wp = path.get(index);
        }

        // Clear the waypoint column before we can occupy it.
        BlockPos block = obstruction(wp);
        if (block != null) {
            if (!mineable(block) || lavaNear(block) || lavaNear(player.getBlockPos())) {
                blocked = true;
                stop();
                return;
            }
            mineFacing(player, block);
            return;
        }

        // Walk toward the waypoint.
        release();
        double dx = wp.getX() + 0.5 - px;
        double dz = wp.getZ() + 0.5 - pz;
        player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        player.setPitch(0.0f);
        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(horiz(px, pz, wp) > 1.5);
        boolean stepUp = wp.getY() > Math.floor(py) + 0.01;
        mc.options.jumpKey.setPressed(stepUp || player.horizontalCollision);

        // Bumped a wall the path didn't anticipate - mine what's directly ahead.
        if (player.horizontalCollision) {
            BlockPos ahead = aheadSolid(player, dx, dz);
            if (ahead != null) {
                if (!mineable(ahead) || lavaNear(ahead)) {
                    blocked = true;
                    stop();
                    return;
                }
                mineFacing(player, ahead);
                return;
            }
        }

        if (++stuckTicks >= STUCK_WINDOW) {
            double moved = Math.hypot(px - lastX, pz - lastZ);
            lastX = px;
            lastZ = pz;
            stuckTicks = 0;
            if (moved < STUCK_MIN_MOVE) {
                stop();
            }
        }
    }

    /** Aim at the block and hold attack; stand still while breaking it. */
    private void mineFacing(ClientPlayerEntity player, BlockPos b) {
        Vec3d eye = player.getEyePos();
        double dx = b.getX() + 0.5 - eye.x;
        double dy = b.getY() + 0.5 - eye.y;
        double dz = b.getZ() + 0.5 - eye.z;
        double hyp = Math.sqrt(dx * dx + dz * dz);
        player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        player.setPitch((float) -Math.toDegrees(Math.atan2(dy, hyp)));
        mc.options.forwardKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.attackKey.setPressed(true);
        stuckTicks = 0;  // breaking is slow; don't trip the stuck check
    }

    private BlockPos obstruction(BlockPos wp) {
        if (solid(wp.up())) {
            return wp.up();   // clear head room first
        }
        if (solid(wp)) {
            return wp;
        }
        return null;
    }

    private BlockPos aheadSolid(ClientPlayerEntity player, double dx, double dz) {
        int sx = dx > 0.3 ? 1 : (dx < -0.3 ? -1 : 0);
        int sz = dz > 0.3 ? 1 : (dz < -0.3 ? -1 : 0);
        BlockPos feet = player.getBlockPos();
        if (solid(feet.up().add(sx, 0, sz))) {
            return feet.up().add(sx, 0, sz);
        }
        if (solid(feet.add(sx, 0, sz))) {
            return feet.add(sx, 0, sz);
        }
        return null;
    }

    private double horiz(double px, double pz, BlockPos wp) {
        double dx = wp.getX() + 0.5 - px;
        double dz = wp.getZ() + 0.5 - pz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private boolean solid(BlockPos pos) {
        return !mc.world.getBlockState(pos).getCollisionShape(mc.world, pos).isEmpty();
    }

    private boolean mineable(BlockPos pos) {
        BlockState s = mc.world.getBlockState(pos);
        return !s.getCollisionShape(mc.world, pos).isEmpty() && s.getHardness(mc.world, pos) >= 0.0f;
    }

    private boolean lavaNear(BlockPos pos) {
        if (mc.world.getBlockState(pos).isOf(Blocks.LAVA)) {
            return true;
        }
        for (Direction d : Direction.values()) {
            if (mc.world.getBlockState(pos.offset(d)).isOf(Blocks.LAVA)) {
                return true;
            }
        }
        return false;
    }

    private void release() {
        mc.options.forwardKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.attackKey.setPressed(false);
    }
}
