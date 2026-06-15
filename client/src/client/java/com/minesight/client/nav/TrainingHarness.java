package com.minesight.client.nav;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minesight.client.detect.OreMemory;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * In-game side of the CMA-ES auto-tuner (engine: {@code minesight.evolve}). Reads
 * candidate agent parameters from a shared run dir, runs a fixed-length mining
 * episode with each, scores it, and reports the fitness back - closing the
 * optimization loop.
 *
 * <p>Protocol (run dir default {@code ~/.minesight/train}, overridable with
 * {@code -Dminesight.trainDir} or {@code $MINESIGHT_TRAIN_DIR}):
 * <ul>
 *   <li>{@code ask.json} - the optimizer's current candidates; we pick any with
 *       no result yet and run it.</li>
 *   <li>{@code tell.jsonl} - we append one line per finished episode:
 *       {@code {"id","fitness","score","ores","deaths","ticks"}}.</li>
 * </ul>
 *
 * <p><b>Fitness</b> = rarity-weighted ore broken − death penalty, over a fixed
 * tick budget. Episodes currently run back-to-back in place (no world reset);
 * server-driven regen between episodes is a follow-up, so for now train on a
 * fresh, ore-rich area and accept some between-episode variance.
 *
 * <p>Single-player / own-server dev tool. Bind {@code key.minesight.train} in
 * Controls (unbound by default).
 */
public final class TrainingHarness {

    private static final Logger LOG = LoggerFactory.getLogger("minesight");
    private static final int POLL_TICKS = 20;          // 1s between run-dir polls
    private static final double DEATH_PENALTY = 10.0;

    private final MinecraftClient mc;
    private final OreMemory memory;
    private final Path dir;
    private final int episodeTicks;

    private boolean training;
    private int pollCooldown;

    // Active episode.
    private MiningAgent agent;
    private String currentId;
    private int ticksLeft;
    private int deaths;
    private float prevHealth;

    public TrainingHarness(MinecraftClient mc, OreMemory memory) {
        this.mc = mc;
        this.memory = memory;
        this.dir = resolveDir();
        this.episodeTicks = envInt("MINESIGHT_EPISODE_TICKS", 3600);  // ~3 min
    }

    public boolean isActive() {
        return training;
    }

    public void toggle() {
        if (training) {
            stop();
            msg("training off");
            return;
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            msg("training: can't open " + dir);
            return;
        }
        training = true;
        pollCooldown = 0;
        msg("training on - run: python -m minesight.evolve (" + dir + ")");
    }

    public void stop() {
        training = false;
        endEpisode(false);  // discard any half-finished episode
    }

    public void tick() {
        if (!training || mc.player == null || mc.world == null) {
            return;
        }
        if (agent != null) {
            runEpisode(mc.player);
        } else {
            waitForWork();
        }
    }

    // -- episode lifecycle -------------------------------------------------

    private void waitForWork() {
        if (--pollCooldown > 0) {
            return;
        }
        pollCooldown = POLL_TICKS;

        JsonObject ask = readJsonObject(dir.resolve("ask.json"));
        if (ask == null || !ask.has("candidates")) {
            return;  // optimizer hasn't asked yet
        }
        Set<String> answered = answeredIds();
        for (JsonElement el : ask.getAsJsonArray("candidates")) {
            JsonObject cand = el.getAsJsonObject();
            String id = cand.get("id").getAsString();
            if (answered.contains(id)) {
                continue;
            }
            beginEpisode(id, cand.getAsJsonObject("values"));
            return;
        }
        // All current candidates answered - idle until the next generation.
    }

    private void beginEpisode(String id, JsonObject values) {
        AgentParams params = AgentParams.fromJson(values);
        agent = new MiningAgent(mc, memory, params);
        agent.start();
        currentId = id;
        ticksLeft = episodeTicks;
        deaths = 0;
        prevHealth = mc.player.getHealth();
        msg("episode " + id + " (" + episodeTicks + " ticks)");
        LOG.info("train: begin {} params={}", id, values);
    }

    private void runEpisode(ClientPlayerEntity player) {
        float hp = player.getHealth();
        if (hp <= 0.0f && prevHealth > 0.0f) {
            deaths++;
            endEpisode(true);   // death ends the episode early; fitness eats the penalty
            return;
        }
        prevHealth = hp;

        agent.tick();
        // The agent self-stops when boxed in or hurt; keep the episode going so it
        // uses the full time budget (score carries over across re-starts).
        if (!agent.isActive() && ticksLeft > 1) {
            agent.start();
        }
        if (--ticksLeft <= 0) {
            endEpisode(true);
        }
    }

    private void endEpisode(boolean report) {
        if (agent == null) {
            return;
        }
        if (report && currentId != null) {
            double fitness = agent.minedScore() - DEATH_PENALTY * deaths;
            writeTell(currentId, fitness, agent.minedScore(), agent.minedCount(), deaths,
                    episodeTicks - Math.max(ticksLeft, 0));
            msg("episode " + currentId + " done: fitness " + String.format("%.1f", fitness));
        }
        agent.stop();
        agent = null;
        currentId = null;
        pollCooldown = 0;  // grab the next candidate promptly
    }

    // -- run-dir IO --------------------------------------------------------

    /** Ids that already have a result line in tell.jsonl. */
    private Set<String> answeredIds() {
        Set<String> ids = new HashSet<>();
        Path tell = dir.resolve("tell.jsonl");
        if (!Files.exists(tell)) {
            return ids;
        }
        try {
            List<String> lines = Files.readAllLines(tell, StandardCharsets.UTF_8);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                    if (o.has("id")) {
                        ids.add(o.get("id").getAsString());
                    }
                } catch (RuntimeException ignored) {
                    // skip a malformed line
                }
            }
        } catch (IOException e) {
            LOG.warn("train: can't read tell.jsonl", e);
        }
        return ids;
    }

    private void writeTell(String id, double fitness, double score, int ores, int deaths, int ticks) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("fitness", fitness);
        o.addProperty("score", score);
        o.addProperty("ores", ores);
        o.addProperty("deaths", deaths);
        o.addProperty("ticks", ticks);
        try {
            Files.writeString(dir.resolve("tell.jsonl"), o + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warn("train: can't append tell.jsonl", e);
        }
    }

    private JsonObject readJsonObject(Path path) {
        if (!Files.exists(path)) {
            return null;
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            return JsonParser.parseString(text).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            return null;  // optimizer may be mid-write; try again next poll
        }
    }

    private static Path resolveDir() {
        String override = System.getProperty("minesight.trainDir");
        if (override == null) {
            override = System.getenv("MINESIGHT_TRAIN_DIR");
        }
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".minesight", "train");
    }

    private static int envInt(String key, int fallback) {
        String v = System.getenv(key);
        if (v == null) {
            v = System.getProperty(key.toLowerCase().replace('_', '.'));
        }
        if (v != null) {
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }

    private void msg(String s) {
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("[MineSight] " + s), true);
        }
        LOG.info("train: {}", s);
    }
}
