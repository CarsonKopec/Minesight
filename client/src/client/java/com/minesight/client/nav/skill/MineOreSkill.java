package com.minesight.client.nav.skill;

import com.minesight.client.detect.OreColors;
import com.minesight.client.detect.OreMemory;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.Set;

/**
 * Mine the ore the vision model has seen, autonomously: pick the nearest unvisited
 * ore (rare prioritized), {@link GotoBlockSkill go to it}, {@link MineBlockSkill
 * break it}, repeat; when none is in range, {@link ExploreSkill explore} to find
 * more. The composite "mining brain" - what F4 runs and what a planner would call
 * as the {@code mine-ore} skill.
 *
 * <p>Returns SUCCESS only when it runs out of reachable ore and can't explore
 * further (boxed in); otherwise it keeps running. Tracks a rarity-weighted
 * mined-score (the auto-tuner's fitness signal), preserved across {@link #start()}
 * restarts.
 */
public final class MineOreSkill implements Skill {

    private static final long PICK_RANGE_SQ = 80L * 80L;
    private static final double RARE_VALUE = 8.0;
    private static final double COMMON_VALUE = 1.0;

    private enum Phase {IDLE, GOTO, MINE, EXPLORE}

    private final SkillContext ctx;
    private final Set<Long> done = new HashSet<>();

    private Phase phase = Phase.IDLE;
    private Skill current;
    private BlockPos target;
    private double minedScore;
    private int minedCount;

    public MineOreSkill(SkillContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String name() {
        return "mine-ore";
    }

    public double minedScore() {
        return minedScore;
    }

    public int minedCount() {
        return minedCount;
    }

    /** Reset the visited set + sub-skill, but keep the running mined tally. */
    @Override
    public void start() {
        done.clear();
        phase = Phase.IDLE;
        current = null;
        target = null;
    }

    @Override
    public Status tick() {
        ClientPlayerEntity p = ctx.player();
        if (p == null) {
            return Status.FAILURE;
        }
        switch (phase) {
            case IDLE -> beginNext(p);
            case GOTO -> {
                Status s = current.tick();
                if (s == Status.SUCCESS) {
                    begin(new MineBlockSkill(ctx, target), Phase.MINE);
                } else if (s == Status.FAILURE) {
                    skip(target);
                }
            }
            case MINE -> {
                Status s = current.tick();
                if (s == Status.SUCCESS) {
                    onMined(target);
                    phase = Phase.IDLE;
                } else if (s == Status.FAILURE) {
                    skip(target);
                }
            }
            case EXPLORE -> {
                BlockPos ore = pickOre(p);
                if (ore != null) {            // ore appeared - go get it
                    current.stop();
                    beginGoto(ore);
                } else if (current.tick() == Status.FAILURE) {
                    return Status.SUCCESS;     // nothing reachable + can't explore
                }
            }
            default -> {
            }
        }
        return Status.RUNNING;
    }

    @Override
    public void stop() {
        if (current != null) {
            current.stop();
        }
        ctx.walker.stop();
        ctx.renderer.setPath(null, null);
        ctx.release();
    }

    // -- phase transitions -------------------------------------------------

    private void beginNext(ClientPlayerEntity p) {
        BlockPos ore = pickOre(p);
        if (ore != null) {
            beginGoto(ore);
        } else {
            begin(new ExploreSkill(ctx), Phase.EXPLORE);
        }
    }

    private void beginGoto(BlockPos ore) {
        target = ore;
        begin(new GotoBlockSkill(ctx, ore), Phase.GOTO);
    }

    private void begin(Skill skill, Phase next) {
        current = skill;
        current.start();
        phase = next;
    }

    private void skip(BlockPos ore) {
        if (ore != null) {
            done.add(ore.asLong());
        }
        ctx.release();
        phase = Phase.IDLE;
    }

    private void onMined(BlockPos b) {
        minedScore += OreColors.isRare(labelOf(b)) ? RARE_VALUE : COMMON_VALUE;
        minedCount++;
        done.add(b.asLong());
        ctx.memory.forget(b);
        ctx.release();
    }

    // -- ore selection -----------------------------------------------------

    private BlockPos pickOre(ClientPlayerEntity p) {
        BlockPos me = p.getBlockPos();
        BlockPos best = null;
        double bestEff = Double.MAX_VALUE;
        double rareDiscount = ctx.params.rareWeight * ctx.params.rareWeight;
        for (OreMemory.Node n : ctx.memory.snapshot()) {
            if (done.contains(n.pos.asLong())) {
                continue;
            }
            long d = sqDist(n.pos, me);
            if (d > PICK_RANGE_SQ) {
                continue;
            }
            double eff = OreColors.isRare(n.label) ? d / rareDiscount : d;
            if (eff < bestEff) {
                bestEff = eff;
                best = n.pos;
            }
        }
        return best;
    }

    private String labelOf(BlockPos b) {
        for (OreMemory.Node n : ctx.memory.snapshot()) {
            if (n.pos.equals(b)) {
                return n.label;
            }
        }
        return "";
    }

    private static long sqDist(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX();
        long dy = a.getY() - b.getY();
        long dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
