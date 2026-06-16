package com.minesight.farm;

import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Bounded, dig-aware A* over the real server world - the server-side port of the
 * client {@code PathFinder} and the Python sim, sharing the same cost model
 * (mine/place/lava-near costs, walk/step-up/drop/dig/bridge moves). Operating on
 * actual Bukkit blocks means the bot pathfinds through exactly what the real
 * client would see, against the real arena geometry.
 *
 * <p>All block access is read-only here; the bot mutates the world as it digs and
 * re-paths against the changes. Must be called on the region thread owning the
 * arena.
 */
public final class BotPathFinder {

    private static final int MAX_EXPANSIONS = 20000;
    /** Cost of a parkour leap - cheap enough to prefer jumping a gap over bridging it. */
    private static final double JUMP_COST = 3.0;

    private final World world;
    private final BotPos min;
    private final BotPos max;
    private final BotParams p;

    public BotPathFinder(World world, BotPos min, BotPos max, BotParams p) {
        this.world = world;
        this.min = min;
        this.max = max;
        this.p = p;
    }

    private record Node(BotPos pos, double f) {
    }

    public List<BotPos> find(BotPos start, BotPos goal) {
        if (!standable(start)) {
            BotPos snapped = snapDown(start);
            if (snapped == null) {
                return null;
            }
            start = snapped;
        }
        if (!standable(goal)) {
            return null;
        }
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        Map<BotPos, Double> g = new HashMap<>();
        Map<BotPos, BotPos> came = new HashMap<>();
        g.put(start, 0.0);
        open.add(new Node(start, heuristic(start, goal)));
        int expansions = 0;
        while (!open.isEmpty() && expansions++ < MAX_EXPANSIONS) {
            Node cur = open.poll();
            if (cur.pos.equals(goal)) {
                return reconstruct(came, cur.pos);
            }
            double cg = g.getOrDefault(cur.pos, Double.MAX_VALUE);
            if (cur.f - heuristic(cur.pos, goal) > cg + 1e-6) {
                continue;  // stale
            }
            for (BotPos nb : neighbors(cur.pos)) {
                double tentative = cg + cost(cur.pos, nb);
                if (tentative < g.getOrDefault(nb, Double.MAX_VALUE)) {
                    g.put(nb, tentative);
                    came.put(nb, cur.pos);
                    open.add(new Node(nb, tentative + heuristic(nb, goal)));
                }
            }
        }
        return null;
    }

    private List<BotPos> neighbors(BotPos pos) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        List<BotPos> out = new ArrayList<>(10);
        for (int[] d : dirs) {
            int dx = d[0], dz = d[1];
            boolean diagonal = dx != 0 && dz != 0;
            BotPos flat = pos.add(dx, 0, dz);
            if (!inBounds(flat)) {
                continue;
            }
            if (diagonal && (!clear(pos.add(dx, 0, 0)) || !clear(pos.add(0, 0, dz)))) {
                continue;
            }
            boolean added = false;
            if (standable(flat)) {
                out.add(flat);
                added = true;
            } else if (standable(pos.add(dx, 1, dz)) && clear(pos.up().up())) {
                out.add(pos.add(dx, 1, dz));
                added = true;
            } else if (clear(flat)) {
                for (int dy = 1; dy <= p.maxDrop; dy++) {
                    BotPos down = pos.add(dx, -dy, dz);
                    if (standable(down)) {
                        out.add(down);
                        added = true;
                        break;
                    }
                    if (!passable(down) || water(down)) {
                        break;
                    }
                }
            }
            if (!added && !diagonal && solidGround(flat.down()) && digThrough(flat)) {
                out.add(flat);
                added = true;
            }
            if (!added && !diagonal && clear(flat) && !hazard(flat) && !water(flat.up())
                    && !solidGround(flat.down()) && solid(pos.down())) {
                out.add(flat);  // bridge (bot always has building blocks)
            }
            // Parkour leap: jump a 1-block gap (cardinal) to a platform 2 cells
            // away, when the cell between is a floorless hole and the arc is clear.
            if (!diagonal && clear(flat) && !solidGround(flat.down()) && clear(pos.up().up())) {
                BotPos land = pos.add(2 * dx, 0, 2 * dz);
                if (inBounds(land) && standable(land)) {
                    out.add(land);
                }
            }
        }
        if (mineable(pos.down()) && solidGround(pos.add(0, -2, 0))) {
            out.add(pos.down());
        }
        return out;
    }

    private boolean digThrough(BotPos flat) {
        boolean anySolid = false;
        for (BotPos b : new BotPos[]{flat, flat.up()}) {
            if (solid(b)) {
                if (!mineable(b)) {
                    return false;
                }
                anySolid = true;
            }
        }
        return anySolid;
    }

    private double cost(BotPos from, BotPos to) {
        boolean diagonal = from.x() != to.x() && from.z() != to.z();
        double c = diagonal ? 1.414 : 1.0;
        int dy = to.y() - from.y();
        if (dy > 0) {
            c += 1.0;
        } else if (dy < 0) {
            c += 0.4 * -dy;
        }
        if (solid(to)) {
            c += p.mineCost;
        }
        if (solid(to.up())) {
            c += p.mineCost;
        }
        if (dy == 0 && !solid(to.down())) {
            c += p.placeCost;
        }
        if (lavaAdjacent(to)) {
            c += p.lavaNearCost;
        }
        // A 2-cell cardinal move is a parkour leap (cheaper than bridging the gap).
        if (!diagonal && Math.abs(to.x() - from.x()) + Math.abs(to.z() - from.z()) >= 2) {
            c += JUMP_COST;
        }
        return c;
    }

    private double heuristic(BotPos a, BotPos b) {
        return a.dist(b);
    }

    private List<BotPos> reconstruct(Map<BotPos, BotPos> came, BotPos end) {
        List<BotPos> path = new ArrayList<>();
        BotPos cur = end;
        path.add(cur);
        while (came.containsKey(cur)) {
            cur = came.get(cur);
            path.add(cur);
        }
        Collections.reverse(path);
        return path;
    }

    /** Nearest standable cell within {@code reach} of the ore (stand here, dig in). */
    public BotPos standableNear(BotPos ore, BotPos from, double reach) {
        int r = Math.max(1, (int) Math.ceil(reach));
        BotPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -1; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BotPos c = ore.add(dx, dy, dz);
                    if (c.equals(ore) || c.dist(ore) > reach || !inBounds(c)) {
                        continue;
                    }
                    if (standable(c)) {
                        double dd = c.dist(from);
                        if (dd < bestD) {
                            bestD = dd;
                            best = c;
                        }
                    }
                }
            }
        }
        return best;
    }

    private BotPos snapDown(BotPos pos) {
        for (int dy = 0; dy >= -p.maxDrop; dy--) {
            BotPos c = pos.add(0, dy, 0);
            if (standable(c)) {
                return c;
            }
        }
        return null;
    }

    // -- block predicates over the real world ------------------------------

    public boolean inBounds(BotPos p2) {
        return p2.x() >= min.x() && p2.x() <= max.x() && p2.y() >= min.y() && p2.y() <= max.y()
                && p2.z() >= min.z() && p2.z() <= max.z();
    }

    public Material mat(BotPos b) {
        return world.getBlockAt(b.x(), b.y(), b.z()).getType();
    }

    public boolean solid(BotPos b) {
        return mat(b).isSolid();
    }

    public boolean mineable(BotPos b) {
        Material m = mat(b);
        return m.isSolid() && !isUnbreakable(m) && !lavaAdjacent(b);
    }

    public boolean passable(BotPos b) {
        Material m = mat(b);
        return m.isAir() || m == Material.WATER;
    }

    public boolean water(BotPos b) {
        return mat(b) == Material.WATER;
    }

    public boolean clear(BotPos feet) {
        return passable(feet) && passable(feet.up());
    }

    public boolean solidGround(BotPos b) {
        Material m = mat(b);
        return m.isSolid();
    }

    public boolean hazard(BotPos feet) {
        return mat(feet) == Material.LAVA || mat(feet.up()) == Material.LAVA
                || mat(feet.down()) == Material.MAGMA_BLOCK
                || mat(feet) == Material.FIRE;
    }

    public boolean standable(BotPos feet) {
        return clear(feet) && solidGround(feet.down()) && !hazard(feet) && !water(feet.up());
    }

    public boolean lavaAdjacent(BotPos b) {
        return mat(b.add(1, 0, 0)) == Material.LAVA || mat(b.add(-1, 0, 0)) == Material.LAVA
                || mat(b.add(0, 1, 0)) == Material.LAVA || mat(b.add(0, -1, 0)) == Material.LAVA
                || mat(b.add(0, 0, 1)) == Material.LAVA || mat(b.add(0, 0, -1)) == Material.LAVA;
    }

    private static boolean isUnbreakable(Material m) {
        return m == Material.BEDROCK || m == Material.BARRIER || m == Material.COMMAND_BLOCK
                || m == Material.STRUCTURE_BLOCK || m == Material.END_PORTAL_FRAME;
    }
}
