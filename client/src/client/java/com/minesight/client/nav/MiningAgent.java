package com.minesight.client.nav;

import com.minesight.client.detect.OreColors;
import com.minesight.client.detect.OreMemory;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The autonomous mining brain - a small FSM that plays by itself, grounded in
 * what the vision model sees. It reuses the movement/dig/bridge executor
 * ({@link AutoWalker}) and the pathfinder; the FSM just decides what to do:
 *
 * <pre>
 *   EXPLORE  strip-mine forward so the eyes reveal + remember ore
 *      |  (ore remembered)
 *      v
 *   GOTO     path (+ dig/bridge) to the nearest unvisited ore
 *      |  (in reach)
 *      v
 *   MINE     break the ore, mark it done -> back to EXPLORE
 * </pre>
 *
 * <p>A SURVIVE interrupt stops everything the moment it takes damage (it's
 * conservative on purpose - fight/eat/flee come later). Single-player /
 * own-server only; toggle with the keybind. Mutually exclusive with the manual
 * {@link Navigator}.
 */
public final class MiningAgent {

    private static final Logger LOG = LoggerFactory.getLogger("minesight");
    private static final long PICK_RANGE_SQ = 80L * 80L;
    /** Fitness weight for breaking a rare ore vs. a common one (auto-tuner). */
    private static final double RARE_VALUE = 8.0;
    private static final double COMMON_VALUE = 1.0;

    private enum State {EXPLORE, GOTO, MINE}

    private final MinecraftClient mc;
    private final OreMemory memory;
    private final AgentParams params;
    private final PathFinder finder;
    private final AutoWalker walker;
    private final PathRenderer renderer;

    private boolean running;
    private State state = State.EXPLORE;
    private BlockPos target;
    private Direction exploreDir = Direction.NORTH;
    private int exploreTurns;
    private int repath;
    private int mineTicks;
    private float lastHealth = -1.0f;
    private final Set<Long> done = new HashSet<>();
    /** Rarity-weighted count of ore broken since construction - the fitness signal. */
    private double minedScore;
    private int minedCount;

    public MiningAgent(MinecraftClient mc, OreMemory memory) {
        this(mc, memory, AgentParams.defaults());
    }

    public MiningAgent(MinecraftClient mc, OreMemory memory, AgentParams params) {
        this.mc = mc;
        this.memory = memory;
        this.params = params;
        this.finder = new PathFinder(mc, params);
        this.walker = new AutoWalker(mc, params);
        this.renderer = new PathRenderer(mc);
    }

    public PathRenderer renderer() {
        return renderer;
    }

    public boolean isActive() {
        return running;
    }

    /** Rarity-weighted ore broken since this agent was built - the episode fitness. */
    public double minedScore() {
        return minedScore;
    }

    /** Raw count of ore blocks broken since construction. */
    public int minedCount() {
        return minedCount;
    }

    public void toggle() {
        if (running) {
            msg("auto-mine off");
            stop();
            return;
        }
        if (mc.player == null) {
            return;
        }
        start();
        msg("auto-mine on");
    }

    /**
     * Begin (or resume) autonomous mining. Resets the FSM and the visited set so
     * it explores fresh, but keeps the mined-score tally - the training harness
     * re-starts a stalled agent mid-episode without losing its fitness so far.
     */
    public void start() {
        running = true;
        state = State.EXPLORE;
        target = null;
        exploreDir = mc.player != null ? mc.player.getHorizontalFacing() : Direction.NORTH;
        exploreTurns = 0;
        done.clear();
        lastHealth = -1.0f;
    }

    public void stop() {
        running = false;
        target = null;
        walker.stop();
        renderer.setPath(null, null);
        release();
    }

    public void tick() {
        if (!running) {
            return;
        }
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) {
            stop();
            return;
        }
        // SURVIVE: bail the instant we take damage.
        float hp = player.getHealth();
        if (lastHealth >= 0.0f && hp < lastHealth - 0.5f) {
            lastHealth = hp;
            msg("took damage - auto-mine off");
            stop();
            return;
        }
        lastHealth = hp;

        switch (state) {
            case EXPLORE -> explore(player);
            case GOTO -> goingTo(player);
            case MINE -> mining(player);
            default -> {
            }
        }
    }

    private void explore(ClientPlayerEntity player) {
        BlockPos ore = pickOre(player);
        if (ore != null && startGoto(player, ore)) {
            return;
        }
        // No ore in memory: strip-mine forward so the eyes can find some.
        if (walker.isActive()) {
            walker.tick();
            return;
        }
        if (walker.isBlocked()) {
            exploreDir = rotateCw(exploreDir);
            if (++exploreTurns >= 4) {
                msg("auto-mine: boxed in, stopping");
                stop();
                return;
            }
        } else {
            exploreTurns = 0;
        }
        BlockPos feet = player.getBlockPos();
        List<BlockPos> tunnel = new ArrayList<>();
        tunnel.add(feet);
        for (int i = 1; i <= params.exploreStep; i++) {
            tunnel.add(feet.add(exploreDir.getOffsetX() * i, 0, exploreDir.getOffsetZ() * i));
        }
        walker.start(tunnel);
    }

    private void goingTo(ClientPlayerEntity player) {
        if (target == null) {
            state = State.EXPLORE;
            return;
        }
        if (inReach(player, target)) {
            walker.stop();
            state = State.MINE;
            mineTicks = 0;
            return;
        }
        if (--repath <= 0) {
            repath = params.repath;
            List<BlockPos> path = finder.findPath(player.getBlockPos(), target);
            if (path == null || path.isEmpty()) {
                abandon(target);
                return;
            }
            walker.start(path);
            renderer.setPath(path, target);
        }
        walker.tick();
        if (!walker.isActive() && walker.isBlocked() && !inReach(player, target)) {
            abandon(target);
        }
    }

    private void mining(ClientPlayerEntity player) {
        if (mc.world.getBlockState(target).isAir()) {   // broke it
            minedScore += OreColors.isRare(labelOf(target)) ? RARE_VALUE : COMMON_VALUE;
            minedCount++;
            done.add(target.asLong());
            memory.forget(target);
            target = null;
            mineTicks = 0;
            release();
            state = State.EXPLORE;
            return;
        }
        if (!inReach(player, target)) {                  // drifted off
            startGoto(player, target);
            return;
        }
        mineFacing(player, target);
        if (++mineTicks > params.mineTimeout) {
            abandon(target);
        }
    }

    /** Remembered label for a position (for fitness weighting), or empty. */
    private String labelOf(BlockPos b) {
        for (OreMemory.Node n : memory.snapshot()) {
            if (n.pos.equals(b)) {
                return n.label;
            }
        }
        return "";
    }

    private boolean startGoto(ClientPlayerEntity player, BlockPos ore) {
        List<BlockPos> path = finder.findPath(player.getBlockPos(), ore);
        if (path == null || path.isEmpty()) {
            done.add(ore.asLong());
            return false;
        }
        target = ore;
        walker.start(path);
        renderer.setPath(path, ore);
        repath = params.repath;
        state = State.GOTO;
        return true;
    }

    private void abandon(BlockPos ore) {
        done.add(ore.asLong());
        target = null;
        mineTicks = 0;
        walker.stop();
        release();
        state = State.EXPLORE;
    }

    /**
     * Nearest unvisited ore in range, by <i>effective</i> distance: rare ore gets
     * its squared distance divided by rareWeight^2, so a high rareWeight makes the
     * agent detour for diamond/emerald while a low one just takes whatever's closest.
     */
    private BlockPos pickOre(ClientPlayerEntity player) {
        BlockPos me = player.getBlockPos();
        BlockPos best = null;
        double bestEff = Double.MAX_VALUE;
        double rareDiscount = params.rareWeight * params.rareWeight;
        for (OreMemory.Node n : memory.snapshot()) {
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

    private void mineFacing(ClientPlayerEntity player, BlockPos b) {
        Vec3d eye = player.getEyePos();
        double dx = b.getX() + 0.5 - eye.x;
        double dy = b.getY() + 0.5 - eye.y;
        double dz = b.getZ() + 0.5 - eye.z;
        double hyp = Math.sqrt(dx * dx + dz * dz);
        player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        player.setPitch((float) -Math.toDegrees(Math.atan2(dy, hyp)));
        mc.options.forwardKey.setPressed(false);
        mc.options.attackKey.setPressed(true);
    }

    private boolean inReach(ClientPlayerEntity player, BlockPos b) {
        Vec3d eye = player.getEyePos();
        double dx = b.getX() + 0.5 - eye.x;
        double dy = b.getY() + 0.5 - eye.y;
        double dz = b.getZ() + 0.5 - eye.z;
        return dx * dx + dy * dy + dz * dz <= params.reachSq();
    }

    private void release() {
        mc.options.forwardKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.attackKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
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
        LOG.info("agent: {}", s);
    }
}
