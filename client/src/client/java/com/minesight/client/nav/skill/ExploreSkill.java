package com.minesight.client.nav.skill;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Strip-mine forward so the vision model can reveal + remember new ore. Tunnels a
 * straight segment, turns clockwise when blocked, and FAILs once it has turned a
 * full loop without progress (boxed in). It never "succeeds" on its own - the
 * caller interrupts it the moment ore appears.
 */
public final class ExploreSkill implements Skill {

    private final SkillContext ctx;
    private Direction dir = Direction.NORTH;
    private int turns;

    public ExploreSkill(SkillContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String name() {
        return "explore";
    }

    @Override
    public void start() {
        dir = ctx.player() != null ? ctx.player().getHorizontalFacing() : Direction.NORTH;
        turns = 0;
    }

    @Override
    public Status tick() {
        ClientPlayerEntity p = ctx.player();
        if (p == null) {
            return Status.FAILURE;
        }
        if (ctx.walker.isActive()) {
            ctx.walker.tick();
            return Status.RUNNING;
        }
        if (ctx.walker.isBlocked()) {
            dir = rotateCw(dir);
            if (++turns >= 4) {
                return Status.FAILURE;        // boxed in
            }
        } else {
            turns = 0;
        }
        BlockPos feet = p.getBlockPos();
        List<BlockPos> tunnel = new ArrayList<>();
        tunnel.add(feet);
        for (int i = 1; i <= ctx.params.exploreStep; i++) {
            tunnel.add(feet.add(dir.getOffsetX() * i, 0, dir.getOffsetZ() * i));
        }
        ctx.walker.start(tunnel);
        return Status.RUNNING;
    }

    @Override
    public void stop() {
        ctx.walker.stop();
    }

    private static Direction rotateCw(Direction d) {
        return switch (d) {
            case NORTH -> Direction.EAST;
            case EAST -> Direction.SOUTH;
            case SOUTH -> Direction.WEST;
            case WEST -> Direction.NORTH;
            default -> Direction.NORTH;
        };
    }
}
