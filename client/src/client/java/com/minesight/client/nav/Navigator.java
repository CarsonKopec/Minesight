package com.minesight.client.nav;

import com.minesight.client.detect.OreColors;
import com.minesight.client.detect.OreMemory;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Vision-guided navigation: routes the player to the nearest remembered ore that
 * the model has seen, draws the route, and auto-walks it. A single-player /
 * own-server mining assist (it controls your movement) - "Baritone but not": it
 * only walks existing passages and only targets vision-confirmed ore, no mining.
 *
 * <p>Toggle with the keybind. Re-paths periodically as you move, stops on arrival
 * or when stuck.
 */
public final class Navigator {

    private static final Logger LOG = LoggerFactory.getLogger("minesight");
    private static final int REPATH_INTERVAL = 20;   // ticks
    private static final long ARRIVE_SQ = 9;         // 3 blocks

    private final MinecraftClient mc;
    private final OreMemory memory;
    private final PathFinder finder;
    private final AutoWalker walker;
    private final PathRenderer renderer;

    private boolean active;
    private BlockPos target;
    private int repath;

    public Navigator(MinecraftClient mc, OreMemory memory) {
        this.mc = mc;
        this.memory = memory;
        this.finder = new PathFinder(mc);
        this.walker = new AutoWalker(mc);
        this.renderer = new PathRenderer(mc);
    }

    public PathRenderer renderer() {
        return renderer;
    }

    /** Keybind: start navigating to the nearest valuable ore, or stop. */
    public void toggle() {
        if (active) {
            msg("navigation off");
            stop();
            return;
        }
        if (mc.player == null) {
            return;
        }
        target = pickTarget();
        if (target == null) {
            msg("no remembered ore to navigate to");
            return;
        }
        if (recompute()) {
            active = true;
            msg("navigating to " + label(target) + " (" + dist(target) + "m)");
        } else {
            msg("no walkable path to the ore");
            stop();
        }
    }

    public void tick() {
        if (!active) {
            return;
        }
        if (mc.player == null || mc.world == null || target == null) {
            stop();
            return;
        }
        if (sqDist(mc.player.getBlockPos(), target) <= ARRIVE_SQ) {
            msg("arrived at " + label(target));
            stop();
            return;
        }
        if (--repath <= 0 && !recompute()) {
            msg("lost the path");
            stop();
            return;
        }
        walker.tick();
        if (!walker.isActive()) {
            if (walker.isBlocked()) {
                msg("blocked (unbreakable or lava) - navigation off");
                stop();
            } else if (!recompute()) {
                msg("stuck - navigation off");
                stop();
            }
        }
    }

    private boolean recompute() {
        List<BlockPos> path = finder.findPath(mc.player.getBlockPos(), target);
        if (path == null || path.isEmpty()) {
            return false;
        }
        renderer.setPath(path, target);
        walker.start(path);
        repath = REPATH_INTERVAL;
        return true;
    }

    private void stop() {
        active = false;
        walker.stop();
        renderer.setPath(null, null);
    }

    /** Nearest rare ore if any is remembered, else nearest ore of any kind. */
    private BlockPos pickTarget() {
        BlockPos me = mc.player.getBlockPos();
        BlockPos bestRare = null;
        BlockPos bestAny = null;
        long rareDist = Long.MAX_VALUE;
        long anyDist = Long.MAX_VALUE;
        for (OreMemory.Node n : memory.snapshot()) {
            long d = sqDist(n.pos, me);
            if (d < anyDist) {
                anyDist = d;
                bestAny = n.pos;
            }
            if (OreColors.isRare(n.label) && d < rareDist) {
                rareDist = d;
                bestRare = n.pos;
            }
        }
        return bestRare != null ? bestRare : bestAny;
    }

    private String label(BlockPos t) {
        for (OreMemory.Node n : memory.snapshot()) {
            if (n.pos.equals(t)) {
                return n.label.replace("_ore", "");
            }
        }
        return "ore";
    }

    private int dist(BlockPos t) {
        return (int) Math.sqrt(sqDist(t, mc.player.getBlockPos()));
    }

    private static long sqDist(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX();
        long dy = a.getY() - b.getY();
        long dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private void msg(String s) {
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("[MineSight] " + s), true);
        }
        LOG.info("nav: {}", s);
    }
}
