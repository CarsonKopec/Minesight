package com.minesight.client.nav.skill;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;

/**
 * Break a single block within reach: aim at it and hold attack until it turns to
 * air. SUCCESS when broken, FAILURE if it drifts out of reach or the break takes
 * longer than {@code mineTimeout} ticks (e.g. unexpectedly hard / blocked).
 */
public final class MineBlockSkill implements Skill {

    private final SkillContext ctx;
    private final BlockPos target;
    private int ticks;

    public MineBlockSkill(SkillContext ctx, BlockPos target) {
        this.ctx = ctx;
        this.target = target;
    }

    @Override
    public String name() {
        return "mine " + target.toShortString();
    }

    @Override
    public void start() {
        ticks = 0;
    }

    @Override
    public Status tick() {
        ClientPlayerEntity p = ctx.player();
        if (p == null || ctx.mc.world == null) {
            return Status.FAILURE;
        }
        if (ctx.mc.world.getBlockState(target).isAir()) {
            return Status.SUCCESS;            // broke it
        }
        if (!ctx.inReach(p, target)) {
            return Status.FAILURE;            // drifted off - caller re-approaches
        }
        ctx.mineFacing(p, target);
        return ++ticks > ctx.params.mineTimeout ? Status.FAILURE : Status.RUNNING;
    }

    @Override
    public void stop() {
        ctx.release();
    }
}
