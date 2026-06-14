package com.minesight.client.nav;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Bounded A* walkable pathfinder over the loaded voxel grid - finds a survival
 * route (walk, step up 1, drop a few, avoid lava) from the player to a block the
 * vision model has remembered. Not Baritone: it only routes through existing
 * passable space (it won't plan to mine through walls), and it only targets ore
 * MineSight actually saw.
 *
 * <p>Runs on the client thread; reads blocks from {@code mc.world}. Bounded by an
 * expansion cap + a range box so it never hitches.
 */
public final class PathFinder {

    private static final int MAX_EXPANSIONS = 15000;
    private static final int MAX_DROP = 3;
    private static final int MAX_RANGE = 96;
    /** Cost of mining one block, in walk-steps - high enough to prefer walking. */
    private static final double MINE_COST = 5.0;
    /** Cost of placing a block to bridge a gap. */
    private static final double PLACE_COST = 6.0;
    /** Penalty for standing next to lava - routes away from the edges. */
    private static final double LAVA_NEAR_COST = 8.0;

    private final MinecraftClient mc;

    public PathFinder(MinecraftClient mc) {
        this.mc = mc;
    }

    private record Node(BlockPos pos, double f) {
    }

    /** Path of feet-positions from start to a stand-able block by the ore, or null. */
    public List<BlockPos> findPath(BlockPos start, BlockPos ore) {
        if (mc.world == null) {
            return null;
        }
        if (!standable(start)) {
            BlockPos snapped = standableAt(start);
            if (snapped == null) {
                return null;
            }
            start = snapped;
        }
        BlockPos goal = standableNear(ore, start);
        if (goal == null) {
            return null;
        }
        long goalKey = goal.asLong();

        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        Map<Long, Double> gScore = new HashMap<>();
        Map<Long, BlockPos> cameFrom = new HashMap<>();
        gScore.put(start.asLong(), 0.0);
        open.add(new Node(start, heuristic(start, goal)));
        int expansions = 0;

        while (!open.isEmpty() && expansions++ < MAX_EXPANSIONS) {
            Node cur = open.poll();
            long curKey = cur.pos.asLong();
            if (curKey == goalKey) {
                return reconstruct(cameFrom, cur.pos);
            }
            double curG = gScore.getOrDefault(curKey, Double.MAX_VALUE);
            if (cur.f - heuristic(cur.pos, goal) > curG + 1.0e-6) {
                continue;  // stale queue entry
            }
            for (BlockPos nb : neighbors(cur.pos, start)) {
                double tentative = curG + cost(cur.pos, nb);
                long nbKey = nb.asLong();
                if (tentative < gScore.getOrDefault(nbKey, Double.MAX_VALUE)) {
                    gScore.put(nbKey, tentative);
                    cameFrom.put(nbKey, cur.pos);
                    open.add(new Node(nb, tentative + heuristic(nb, goal)));
                }
            }
        }
        return null;
    }

    private List<BlockPos> neighbors(BlockPos pos, BlockPos start) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        List<BlockPos> out = new ArrayList<>(10);
        for (int[] d : dirs) {
            int dx = d[0];
            int dz = d[1];
            if (Math.abs(pos.getX() + dx - start.getX()) > MAX_RANGE
                    || Math.abs(pos.getZ() + dz - start.getZ()) > MAX_RANGE) {
                continue;
            }
            boolean diagonal = dx != 0 && dz != 0;
            if (diagonal && (!clear(pos.add(dx, 0, 0)) || !clear(pos.add(0, 0, dz)))) {
                continue;  // no corner cutting
            }
            BlockPos flat = pos.add(dx, 0, dz);
            boolean added = false;
            if (standable(flat)) {                                  // walk
                out.add(flat);
                added = true;
            } else if (standable(pos.add(dx, 1, dz)) && clear(pos.up().up())) {  // step up
                out.add(pos.add(dx, 1, dz));
                added = true;
            } else if (clear(flat)) {                               // drop down
                for (int dy = 1; dy <= MAX_DROP; dy++) {
                    BlockPos down = pos.add(dx, -dy, dz);
                    if (standable(down)) {
                        out.add(down);
                        added = true;
                        break;
                    }
                    if (!passable(down) || water(down)) {
                        break;  // hit ground, or water - don't dive in, bridge over it
                    }
                }
            }
            // Dig straight through at the same level (cardinal only) when there's
            // floor to stand on and the obstruction is breakable.
            if (!added && !diagonal && solidGround(flat.down()) && digThrough(flat)) {
                out.add(flat);
                added = true;
            }
            // Bridge across a gap (cardinal): step onto a floorless cell by placing
            // a block under it - the way over water/lava/voids. Needs blocks + a
            // support, and the head must stay above water (don't bridge underwater).
            if (!added && !diagonal && clear(flat) && !hazard(flat) && !water(flat.up())
                    && !solidGround(flat.down()) && solid(pos.down()) && hasBuildingBlocks()) {
                out.add(flat);
            }
        }
        // Dig one block down to descend (if there's solid floor to land on).
        if (mineable(pos.down()) && solidGround(pos.add(0, -2, 0))) {
            out.add(pos.down());
        }
        return out;
    }

    /** Can the player tunnel into this feet position (obstruction is breakable)? */
    private boolean digThrough(BlockPos flat) {
        boolean anySolid = false;
        for (BlockPos b : new BlockPos[]{flat, flat.up()}) {
            if (solid(b)) {
                if (!mineable(b)) {
                    return false;  // bedrock / unbreakable in the way
                }
                anySolid = true;
            }
        }
        return anySolid;
    }

    private double cost(BlockPos from, BlockPos to) {
        boolean diagonal = from.getX() != to.getX() && from.getZ() != to.getZ();
        double c = diagonal ? 1.414 : 1.0;
        int dy = to.getY() - from.getY();
        if (dy > 0) {
            c += 1.0;          // climbing is slower
        } else if (dy < 0) {
            c += 0.4 * -dy;    // dropping has a mild cost
        }
        // Any currently-solid block we must break to occupy `to`.
        if (solid(to)) {
            c += MINE_COST;
        }
        if (solid(to.up())) {
            c += MINE_COST;
        }
        // Same-level move onto a floorless cell = a bridge (place a block).
        if (dy == 0 && !solid(to.down())) {
            c += PLACE_COST;
        }
        if (lavaAdjacent(to)) {
            c += LAVA_NEAR_COST;
        }
        return c;
    }

    private double heuristic(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private List<BlockPos> reconstruct(Map<Long, BlockPos> cameFrom, BlockPos end) {
        List<BlockPos> path = new ArrayList<>();
        BlockPos cur = end;
        path.add(cur);
        while (cameFrom.containsKey(cur.asLong())) {
            cur = cameFrom.get(cur.asLong());
            path.add(cur);
        }
        Collections.reverse(path);
        return path;
    }

    /** A stand-able block adjacent to the ore, nearest to start. */
    private BlockPos standableNear(BlockPos ore, BlockPos start) {
        BlockPos best = null;
        long bestD = Long.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0 && dy <= 0) {
                        continue;
                    }
                    BlockPos c = ore.add(dx, dy, dz);
                    if (standable(c)) {
                        long d = sqDist(c, start);
                        if (d < bestD) {
                            bestD = d;
                            best = c;
                        }
                    }
                }
            }
        }
        return best;
    }

    private BlockPos standableAt(BlockPos pos) {
        for (int dy = 0; dy >= -MAX_DROP; dy--) {
            BlockPos c = pos.add(0, dy, 0);
            if (standable(c)) {
                return c;
            }
        }
        return null;
    }

    /** Feet position: room for the player, solid ground, not a hazard, and the
     *  head isn't submerged (so deep water is bridged over, not swum through;
     *  wading shallow water with the head in air is still fine). */
    private boolean standable(BlockPos feet) {
        return clear(feet) && solidGround(feet.down()) && !hazard(feet) && !water(feet.up());
    }

    private boolean water(BlockPos pos) {
        return mc.world.getBlockState(pos).isOf(Blocks.WATER);
    }

    /** Standing here hurts: fire/lava/cactus/berries/powder snow at body level,
     *  or magma underfoot. */
    private boolean hazard(BlockPos feet) {
        return hazardBlock(feet) || hazardBlock(feet.up())
                || mc.world.getBlockState(feet.down()).isOf(Blocks.MAGMA_BLOCK);
    }

    private boolean hazardBlock(BlockPos p) {
        BlockState s = mc.world.getBlockState(p);
        return s.isOf(Blocks.LAVA) || s.isOf(Blocks.FIRE) || s.isOf(Blocks.SOUL_FIRE)
                || s.isOf(Blocks.CACTUS) || s.isOf(Blocks.SWEET_BERRY_BUSH)
                || s.isOf(Blocks.POWDER_SNOW) || s.isOf(Blocks.WITHER_ROSE);
    }

    private boolean lavaAdjacent(BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (mc.world.getBlockState(pos.offset(d)).isOf(Blocks.LAVA)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasBuildingBlocks() {
        if (mc.player == null) {
            return false;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (!s.isEmpty() && s.getItem() instanceof BlockItem) {
                return true;
            }
        }
        return false;
    }

    /** Room for a player's body (feet + head passable). */
    private boolean clear(BlockPos feet) {
        return passable(feet) && passable(feet.up());
    }

    private boolean passable(BlockPos pos) {
        BlockState s = mc.world.getBlockState(pos);
        return !s.isOf(Blocks.LAVA) && s.getCollisionShape(mc.world, pos).isEmpty();
    }

    private boolean solidGround(BlockPos pos) {
        BlockState s = mc.world.getBlockState(pos);
        return !s.isOf(Blocks.LAVA) && !s.getCollisionShape(mc.world, pos).isEmpty();
    }

    /** A full-collision block (stone, dirt...) - air, lava, water, plants are not. */
    private boolean solid(BlockPos pos) {
        return !mc.world.getBlockState(pos).getCollisionShape(mc.world, pos).isEmpty();
    }

    /** A solid block we're allowed to break: not unbreakable, not touching lava
     *  (so a tunnel never breaks straight into a lava flow). */
    private boolean mineable(BlockPos pos) {
        BlockState s = mc.world.getBlockState(pos);
        if (s.getCollisionShape(mc.world, pos).isEmpty()) {
            return false;  // nothing solid to mine
        }
        return s.getHardness(mc.world, pos) >= 0.0f && !lavaAdjacent(pos);
    }

    private static long sqDist(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX();
        long dy = a.getY() - b.getY();
        long dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
