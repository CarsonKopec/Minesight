package com.minesight.farm;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One headless training episode - the shared agent brain (pathfinding + the
 * goto/mine FSM + scoring), independent of how the bot is embodied. Subclasses
 * provide the "body": a Bukkit {@link ZombieBot} or a real NMS {@link NmsBot}
 * ServerPlayer. Behavior is scored identically to the in-game harness and the
 * Python sim (rarity-weighted ore - deaths + finish-fast).
 *
 * <p>Ticked once per server tick on the arena's region thread.
 */
public abstract class BotEpisode {

    protected static final double RARE_VALUE = 8.0;
    protected static final double COMMON_VALUE = 1.0;
    protected static final double DEATH_PENALTY = 10.0;
    protected static final double SPEED_WEIGHT = 20.0;
    protected static final double MAX_HP = 20.0;
    private static final double LAVA_STEP_DMG = 3.0;
    private static final double LAVA_IN_DMG = 12.0;
    private static final double FALL_PER = 3.0;

    public record Result(double fitness, double score, int ores, int deaths, int ticks,
                         boolean cleared) {
    }

    private enum State {IDLE, PATH, MOVE, MINE, DONE}

    protected final JavaPlugin plugin;
    protected final World world;
    protected final BotParams params;
    protected final BotPathFinder finder;
    private final int budget;
    private final BotPos spawnPos;
    private final Map<BotPos, String> remaining = new HashMap<>();
    private final int total;

    protected BotPos pos;
    private double hp = MAX_HP;
    private int t;
    private double score;
    private int mined;
    private int deaths;

    private State state = State.IDLE;
    private List<BotPos> path;
    private int wp;
    private BotPos target;
    protected BotPos goal;

    private List<BotPos> mineQueue;
    private boolean mineThenScore;
    private boolean miningOre;
    private BotPos mineCell;
    private int mineCountdown;

    private BotPos moveTo;

    protected BotEpisode(JavaPlugin plugin, ArenaManager.Arena arena, BotParams params, int budget) {
        this.plugin = plugin;
        this.params = params;
        this.budget = budget;
        Location s = arena.spawn();
        this.world = s.getWorld();
        BotPos min = new BotPos(arena.ox, arena.oy, arena.oz);
        BotPos max = new BotPos(arena.ox + ArenaManager.W - 1, arena.oy + ArenaManager.H - 1,
                arena.oz + ArenaManager.D - 1);
        this.finder = new BotPathFinder(world, min, max, params);
        this.spawnPos = new BotPos(s.getBlockX(), s.getBlockY(), s.getBlockZ());
        this.pos = spawnPos;
        for (ArenaManager.GroundTruthOre o : arena.ores()) {
            remaining.put(new BotPos(o.x(), o.y(), o.z()), o.label());
        }
        this.total = remaining.size();
    }

    // -- body hooks (implemented by Zombie / NMS subclasses) ---------------

    /** Create the visible bot at {@link #pos}. Region thread. */
    public abstract void spawn(String name);

    /** Instantly reposition the body to a cell (respawn / fall-settle). */
    protected abstract void moveBody(BotPos to);

    /** Begin walking toward a waypoint (called once when entering MOVE). */
    protected abstract void startMove(BotPos target);

    /** Advance the walk one tick; return true once the waypoint is reached. */
    protected abstract boolean tickMove(BotPos target);

    /** True if the body has stopped making progress (re-path / abandon). */
    protected abstract boolean moveStuck();

    /** Break the block at this cell (clears it from the world). */
    protected abstract void breakBlock(BotPos b);

    /** Place a block (bridging). */
    protected abstract void placeBlock(BotPos b, Material m);

    /** Swing for the mining animation (optional). */
    protected abstract void swingBody();

    /** Despawn the body. */
    public abstract void cleanup();

    protected Location center(BotPos b) {
        return new Location(world, b.x() + 0.5, b.y(), b.z() + 0.5);
    }

    // -- lifecycle ---------------------------------------------------------

    public boolean isDone() {
        return state == State.DONE;
    }

    public Result result() {
        boolean cleared = mined == total;
        double speedBonus = cleared ? SPEED_WEIGHT * (budget - Math.min(t, budget)) / budget : 0.0;
        double fitness = score - DEATH_PENALTY * deaths + speedBonus;
        return new Result(fitness, score, mined, deaths, Math.min(t, budget), cleared);
    }

    public void tick() {
        if (state == State.DONE) {
            return;
        }
        if (t++ >= budget) {
            state = State.DONE;
            return;
        }
        switch (state) {
            case IDLE -> idle();
            case PATH -> pathStep();
            case MOVE -> moveStep();
            case MINE -> mineStep();
            default -> {
            }
        }
    }

    private void idle() {
        if (remaining.isEmpty()) {
            state = State.DONE;
            return;
        }
        target = pickOre();
        goal = finder.standableNear(target, pos, params.reachDist);
        if (goal == null) {
            remaining.remove(target);
            return;
        }
        path = finder.find(pos, goal);
        if (path == null || path.isEmpty()) {
            remaining.remove(target);
            return;
        }
        wp = 1;
        state = State.PATH;
    }

    private void pathStep() {
        if (wp >= path.size()) {
            mineQueue = new ArrayList<>();
            for (BotPos c : lineCells(pos, target)) {
                mineQueue.add(c);
                mineQueue.add(c.up());
            }
            mineQueue.add(target);
            mineThenScore = true;
            startMining();
            return;
        }
        BotPos w = path.get(wp);
        BotPos obs = obstruction(w);
        if (obs != null) {
            if (!finder.mineable(obs)) {
                remaining.remove(target);
                state = State.IDLE;
                return;
            }
            mineQueue = new ArrayList<>(List.of(obs));
            mineThenScore = false;
            startMining();
            return;
        }
        if (w.y() == pos.y() && !finder.solid(w.down())) {
            placeBlock(w.down(), Material.COBBLESTONE);   // bridge, then walk across
        }
        moveTo = w;
        startMove(w);
        state = State.MOVE;
    }

    private void moveStep() {
        if (tickMove(moveTo)) {
            applyHazards(pos, moveTo);
            if (hp <= 0) {
                die();
                return;
            }
            pos = moveTo;
            wp++;
            state = State.PATH;
        } else if (moveStuck()) {
            // No progress (collision / can't reach) - give up this ore and re-pick.
            remaining.remove(target);
            state = State.IDLE;
        }
    }

    private void startMining() {
        while (mineQueue != null && !mineQueue.isEmpty()) {
            BotPos c = mineQueue.remove(0);
            boolean isOre = c.equals(target);
            if (!isOre && !finder.mineable(c)) {
                continue;
            }
            if (isOre && finder.mat(c).isAir()) {
                scoreOre();
                settleFall();
                state = State.IDLE;
                return;
            }
            mineCell = c;
            miningOre = isOre;
            mineCountdown = breakTicks(c);
            state = State.MINE;
            return;
        }
        state = mineThenScore ? State.IDLE : State.PATH;
    }

    private void mineStep() {
        swingBody();
        if (--mineCountdown > 0) {
            return;
        }
        if (miningOre) {
            scoreOre();
            settleFall();
            miningOre = false;
            state = State.IDLE;
            return;
        }
        breakBlock(mineCell);
        startMining();
    }

    private void scoreOre() {
        String label = remaining.remove(target);
        breakBlock(target);
        if (label != null) {
            score += isRare(label) ? RARE_VALUE : COMMON_VALUE;
            mined++;
        }
    }

    private void applyHazards(BotPos from, BotPos to) {
        if (finder.mat(to) == Material.LAVA || finder.mat(to.up()) == Material.LAVA) {
            hp -= LAVA_IN_DMG;
        } else if (finder.lavaAdjacent(to)) {
            hp -= LAVA_STEP_DMG;
        }
        int drop = from.y() - to.y();
        if (drop > 3) {
            hp -= (drop - 3) * FALL_PER;
        }
    }

    private void settleFall() {
        int fall = 0;
        while (fall < 8 && !finder.standable(pos)) {
            pos = pos.down();
            fall++;
        }
        moveBody(pos);
        if (fall > 3) {
            hp -= (fall - 3) * FALL_PER;
            if (hp <= 0) {
                die();
            }
        }
    }

    private void die() {
        deaths++;
        pos = spawnPos;
        hp = MAX_HP;
        moveBody(pos);
        state = State.IDLE;
    }

    private BotPos pickOre() {
        BotPos best = null;
        double bestEff = Double.MAX_VALUE;
        double disc = params.rareWeight * params.rareWeight;
        for (Map.Entry<BotPos, String> e : remaining.entrySet()) {
            long d2 = e.getKey().sqDist(pos);
            double eff = isRare(e.getValue()) ? d2 / disc : d2;
            if (eff < bestEff) {
                bestEff = eff;
                best = e.getKey();
            }
        }
        return best;
    }

    private BotPos obstruction(BotPos w) {
        if (finder.solid(w.up())) {
            return w.up();
        }
        if (finder.solid(w)) {
            return w;
        }
        return null;
    }

    private List<BotPos> lineCells(BotPos from, BotPos ore) {
        int steps = Math.max(Math.max(Math.abs(ore.x() - from.x()), Math.abs(ore.y() - from.y())),
                Math.abs(ore.z() - from.z()));
        List<BotPos> cells = new ArrayList<>();
        for (int i = 1; i < steps; i++) {
            double f = (double) i / steps;
            cells.add(new BotPos(
                    (int) Math.round(from.x() + (ore.x() - from.x()) * f),
                    (int) Math.round(from.y() + (ore.y() - from.y()) * f),
                    (int) Math.round(from.z() + (ore.z() - from.z()) * f)));
        }
        return cells;
    }

    private int breakTicks(BotPos b) {
        return finder.mat(b).name().contains("DEEPSLATE") ? 9 : 6;
    }

    private static boolean isRare(String label) {
        return label.contains("diamond") || label.contains("emerald");
    }
}
