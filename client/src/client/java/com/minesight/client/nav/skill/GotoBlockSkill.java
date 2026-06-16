package com.minesight.client.nav.skill;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * Walk (digging/bridging/jumping as needed) to within mining reach of a target
 * block. SUCCESS when in reach, FAILURE if no path exists or the walker gets
 * blocked by something unbreakable. Re-paths periodically as the world changes.
 */
public final class GotoBlockSkill implements Skill {

    private final SkillContext ctx;
    private final BlockPos target;
    private int repath;

    public GotoBlockSkill(SkillContext ctx, BlockPos target) {
        this.ctx = ctx;
        this.target = target;
    }

    @Override
    public String name() {
        return "goto " + target.toShortString();
    }

    @Override
    public void start() {
        repath = 1;   // path on the first tick
    }

    @Override
    public Status tick() {
        ClientPlayerEntity p = ctx.player();
        if (p == null) {
            return Status.FAILURE;
        }
        if (ctx.inReach(p, target)) {
            ctx.walker.stop();
            return Status.SUCCESS;
        }
        if (--repath <= 0) {
            repath = ctx.params.repath;
            List<BlockPos> path = ctx.finder.findPath(p.getBlockPos(), target);
            if (path == null || path.isEmpty()) {
                return Status.FAILURE;
            }
            ctx.walker.start(path);
            ctx.renderer.setPath(path, target);
        }
        ctx.walker.tick();
        if (!ctx.walker.isActive() && ctx.walker.isBlocked() && !ctx.inReach(p, target)) {
            return Status.FAILURE;
        }
        return Status.RUNNING;
    }

    @Override
    public void stop() {
        ctx.walker.stop();
    }
}
