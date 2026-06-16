package com.minesight.client.nav.skill;

import com.minesight.client.detect.OreMemory;
import com.minesight.client.nav.AgentParams;
import com.minesight.client.nav.AutoWalker;
import com.minesight.client.nav.PathFinder;
import com.minesight.client.nav.PathRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * The shared environment skills operate in: the game handle, vision memory, tuned
 * params, and the movement primitives (pathfinder + walker + path renderer). Also
 * holds the small helpers more than one skill needs (look-at, reach test, release
 * inputs), so each skill stays focused on its decision logic.
 */
public final class SkillContext {

    public final MinecraftClient mc;
    public final OreMemory memory;
    public final AgentParams params;
    public final PathFinder finder;
    public final AutoWalker walker;
    public final PathRenderer renderer;

    public SkillContext(MinecraftClient mc, OreMemory memory, AgentParams params,
                        PathFinder finder, AutoWalker walker, PathRenderer renderer) {
        this.mc = mc;
        this.memory = memory;
        this.params = params;
        this.finder = finder;
        this.walker = walker;
        this.renderer = renderer;
    }

    public ClientPlayerEntity player() {
        return mc.player;
    }

    /** Is the block within the player's mining reach? */
    public boolean inReach(ClientPlayerEntity p, BlockPos b) {
        Vec3d eye = p.getEyePos();
        double dx = b.getX() + 0.5 - eye.x;
        double dy = b.getY() + 0.5 - eye.y;
        double dz = b.getZ() + 0.5 - eye.z;
        return dx * dx + dy * dy + dz * dz <= params.reachSq();
    }

    /** Aim at a block and hold attack to break it (vanilla look + mine). */
    public void mineFacing(ClientPlayerEntity p, BlockPos b) {
        Vec3d eye = p.getEyePos();
        double dx = b.getX() + 0.5 - eye.x;
        double dy = b.getY() + 0.5 - eye.y;
        double dz = b.getZ() + 0.5 - eye.z;
        double hyp = Math.sqrt(dx * dx + dz * dz);
        p.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        p.setPitch((float) -Math.toDegrees(Math.atan2(dy, hyp)));
        mc.options.forwardKey.setPressed(false);
        mc.options.attackKey.setPressed(true);
    }

    /** Release every movement/attack key the agent drives. */
    public void release() {
        mc.options.forwardKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.attackKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
    }
}
