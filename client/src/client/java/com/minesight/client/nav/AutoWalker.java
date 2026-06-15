package com.minesight.client.nav;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
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

    private final MinecraftClient mc;
    private final AgentParams params;
    private List<BlockPos> path;
    private int index;
    private boolean active;
    private boolean blocked;
    private double lastX;
    private double lastZ;
    private int stuckTicks;

    public AutoWalker(MinecraftClient mc) {
        this(mc, AgentParams.defaults());
    }

    public AutoWalker(MinecraftClient mc, AgentParams params) {
        this.mc = mc;
        this.params = params;
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
        if (horiz(px, pz, wp) < params.waypointRadius && Math.abs(wp.getY() - py) < 1.3) {
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

        // Bridge: the next cell has no floor - place a block under it and cross.
        if (wp.getY() == player.getBlockPos().getY() && !solid(wp.down())) {
            mc.options.sneakKey.setPressed(true);
            mc.options.attackKey.setPressed(false);
            if (!placeBridge(player, wp)) {
                blocked = true;
                stop();
            }
            return;
        }

        // Walk toward the waypoint.
        release();
        // Keep sneaking while a bridge is imminent so we don't overshoot the edge.
        boolean nearLedge = !solid(wp.down())
                || (index + 1 < path.size() && !solid(path.get(index + 1).down()));
        mc.options.sneakKey.setPressed(nearLedge);
        double dx = wp.getX() + 0.5 - px;
        double dz = wp.getZ() + 0.5 - pz;
        player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        player.setPitch(0.0f);
        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(horiz(px, pz, wp) > params.sprintDist);
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

        if (++stuckTicks >= params.stuckWindow) {
            double moved = Math.hypot(px - lastX, pz - lastZ);
            lastX = px;
            lastZ = pz;
            stuckTicks = 0;
            if (moved < params.stuckMinMove) {
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

    /** Sneak-bridge: place a building block under the floorless waypoint. */
    private boolean placeBridge(ClientPlayerEntity player, BlockPos wp) {
        BlockPos prev = index > 0 ? path.get(index - 1) : player.getBlockPos();
        int ddx = wp.getX() - prev.getX();
        int ddz = wp.getZ() - prev.getZ();
        Direction dir = ddx > 0 ? Direction.EAST : ddx < 0 ? Direction.WEST
                : ddz > 0 ? Direction.SOUTH : ddz < 0 ? Direction.NORTH : null;
        if (dir == null) {
            return false;
        }
        BlockPos support = wp.down().offset(dir.getOpposite());  // floor behind the gap
        if (!solid(support)) {
            return false;
        }
        int slot = buildingSlot(player);
        if (slot < 0) {
            return false;  // nothing to bridge with
        }
        if (player.getInventory().getSelectedSlot() != slot) {
            player.getInventory().setSelectedSlot(slot);
        }
        double hx = support.getX() + 0.5 + dir.getOffsetX() * 0.5;
        double hy = support.getY() + 0.5 + dir.getOffsetY() * 0.5;
        double hz = support.getZ() + 0.5 + dir.getOffsetZ() * 0.5;
        Vec3d eye = player.getEyePos();
        double ex = hx - eye.x;
        double ey = hy - eye.y;
        double ez = hz - eye.z;
        player.setYaw((float) Math.toDegrees(Math.atan2(-ex, ez)));
        player.setPitch((float) -Math.toDegrees(Math.atan2(ey, Math.sqrt(ex * ex + ez * ez))));
        mc.options.forwardKey.setPressed(false);
        BlockHitResult hit = new BlockHitResult(new Vec3d(hx, hy, hz), dir, support, false);
        mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
        player.swingHand(Hand.MAIN_HAND);
        return true;
    }

    private int buildingSlot(ClientPlayerEntity player) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (!s.isEmpty() && s.getItem() instanceof BlockItem) {
                return i;
            }
        }
        return -1;
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
        mc.options.sneakKey.setPressed(false);
    }
}
