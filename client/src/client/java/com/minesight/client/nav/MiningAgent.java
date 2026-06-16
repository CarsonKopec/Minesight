package com.minesight.client.nav;

import com.minesight.client.detect.OreMemory;
import com.minesight.client.nav.skill.MineOreSkill;
import com.minesight.client.nav.skill.SkillContext;
import com.minesight.client.nav.skill.Status;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The autonomous mining brain - now a thin runner over the skill library. It owns
 * the movement primitives (pathfinder, walker, renderer) via a {@link
 * SkillContext}, runs the {@link MineOreSkill} (pick ore -> go -> mine -> explore),
 * and adds the one thing above any single skill: a SURVIVE interrupt that stops
 * the moment it takes damage (fight/eat/flee are skills for later).
 *
 * <p>Single-player / own-server only; toggle with the keybind. Mutually exclusive
 * with the manual {@link Navigator}. The training harness drives it through
 * {@link #start()} / {@link #stop()} / {@link #minedScore()}.
 */
public final class MiningAgent {

    private static final Logger LOG = LoggerFactory.getLogger("minesight");

    private final MinecraftClient mc;
    private final PathRenderer renderer;
    private final SkillContext ctx;
    private final MineOreSkill skill;

    private boolean running;
    private float lastHealth = -1.0f;

    public MiningAgent(MinecraftClient mc, OreMemory memory) {
        this(mc, memory, AgentParams.defaults());
    }

    public MiningAgent(MinecraftClient mc, OreMemory memory, AgentParams params) {
        this.mc = mc;
        this.renderer = new PathRenderer(mc);
        this.ctx = new SkillContext(mc, memory, params,
                new PathFinder(mc, params), new AutoWalker(mc, params), renderer);
        this.skill = new MineOreSkill(ctx);
    }

    public PathRenderer renderer() {
        return renderer;
    }

    public boolean isActive() {
        return running;
    }

    /** Rarity-weighted ore broken since this agent was built - the episode fitness. */
    public double minedScore() {
        return skill.minedScore();
    }

    /** Raw count of ore blocks broken since construction. */
    public int minedCount() {
        return skill.minedCount();
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
     * Begin (or resume) autonomous mining. Resets the skill's visited set so it
     * explores fresh, but keeps the mined-score tally - the training harness
     * re-starts a stalled agent mid-episode without losing its fitness so far.
     */
    public void start() {
        running = true;
        lastHealth = -1.0f;
        skill.start();
    }

    public void stop() {
        running = false;
        skill.stop();
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

        if (skill.tick() != Status.RUNNING) {
            msg("auto-mine: nothing left to mine, stopping");
            stop();
        }
    }

    private void msg(String s) {
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("[MineSight] " + s), true);
        }
        LOG.info("agent: {}", s);
    }
}
