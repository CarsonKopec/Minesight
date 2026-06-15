package com.minesight.client.nav;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minesight.client.detect.OreMemory;
import com.minesight.client.net.FarmPayload;
import com.minesight.client.net.FarmProtocol;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
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
 * In-game side of the CMA-ES auto-tuner (engine: {@code minesight.evolve}), driven
 * by the server's {@link com.minesight.farm.ArenaManager training arenas}.
 *
 * <p>Each episode: pull a candidate parameter set from {@code ask.json}, ask the
 * plugin to reset a fresh arena and drop us in ({@code arena_request} ->
 * {@code arena_ready}), seed a throwaway memory with the arena's ground-truth ore,
 * run the autonomous agent with those params until <b>every ore is cleared</b> (a
 * speed bonus rewards finishing fast) or the tick budget runs out, then report
 * fitness to {@code tell.jsonl} and request the next arena.
 *
 * <p>Ground-truth seeding means no vision pipeline is needed here - this trains the
 * agent's <i>body</i> (pathfinding, mining, bridging, hazard avoidance), which is
 * exactly what the genome tunes. With N farm clients each gets its own arena, so
 * episodes run in parallel.
 *
 * <p>Run dir default {@code ~/.minesight/train} (override {@code -Dminesight.trainDir}
 * / {@code $MINESIGHT_TRAIN_DIR}). Bind {@code key.minesight.train} in Controls.
 */
public final class TrainingHarness {

    private static final Logger LOG = LoggerFactory.getLogger("minesight");
    private static final String CLIENT_ID = "fabric-train";
    private static final int POLL_TICKS = 20;            // 1s between run-dir polls
    private static final int ARENA_TIMEOUT = 200;        // give up waiting after 10s
    private static final double DEATH_PENALTY = 10.0;
    private static final double SPEED_WEIGHT = 20.0;     // bonus for clearing early

    private enum Phase {WAITING, AWAIT_ARENA, RUNNING}

    private final MinecraftClient mc;
    private final Path dir;
    private final int episodeTicks;
    // Throwaway memory we seed per episode; never load()ed so it never persists,
    // keeping the player's real ore memory untouched.
    private final OreMemory trainMemory = new OreMemory();

    private boolean training;
    private Phase phase = Phase.WAITING;
    private int pollCooldown;
    private int awaitTicks;

    // Pending candidate (chosen, arena requested, not yet running).
    private String pendingId;
    private AgentParams pendingParams;

    // Active episode.
    private MiningAgent agent;
    private int arenaId;
    private int seededCount;
    private int ticksLeft;
    private int deaths;
    private float prevHealth;

    public TrainingHarness(MinecraftClient mc) {
        this.mc = mc;
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
        phase = Phase.WAITING;
        pollCooldown = 0;
        msg("training on - run: python -m minesight.evolve (" + dir + ")");
    }

    public void stop() {
        training = false;
        phase = Phase.WAITING;
        if (agent != null) {
            agent.stop();
            agent = null;
        }
        pendingId = null;
    }

    public void tick() {
        if (!training || mc.player == null || mc.world == null) {
            return;
        }
        switch (phase) {
            case WAITING -> pollForCandidate();
            case AWAIT_ARENA -> {
                if (++awaitTicks > ARENA_TIMEOUT) {
                    msg("arena request timed out - is the plugin running?");
                    phase = Phase.WAITING;
                    pollCooldown = POLL_TICKS;
                }
            }
            case RUNNING -> runEpisode(mc.player);
        }
    }

    /** plugin -> client: arena reset + we're in it. Seed ground truth and run. */
    public void onArenaReady(FarmProtocol.ArenaReady r) {
        if (!training || phase != Phase.AWAIT_ARENA || pendingParams == null) {
            return;
        }
        seedMemory(r);
        arenaId = r.arenaId();
        agent = new MiningAgent(mc, trainMemory, pendingParams);
        agent.start();
        ticksLeft = episodeTicks;
        deaths = 0;
        prevHealth = mc.player != null ? mc.player.getHealth() : 20.0f;
        phase = Phase.RUNNING;
        msg("episode " + pendingId + " in arena " + arenaId + " (" + seededCount + " ore)");
        LOG.info("train: begin {} arena={} ore={}", pendingId, arenaId, seededCount);
    }

    // -- episode lifecycle -------------------------------------------------

    private void pollForCandidate() {
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
            startCandidate(id, cand.getAsJsonObject("values"));
            return;
        }
        // All current candidates answered - idle until the next generation.
    }

    private void startCandidate(String id, JsonObject values) {
        pendingId = id;
        pendingParams = AgentParams.fromJson(values);
        if (!sendToPlugin(FarmProtocol.arenaRequest(CLIENT_ID))) {
            msg("training needs the arena server (no plugin channel)");
            pendingId = null;
            return;  // stay WAITING; retry next poll
        }
        phase = Phase.AWAIT_ARENA;
        awaitTicks = 0;
        LOG.info("train: requested arena for {}", id);
    }

    private void runEpisode(ClientPlayerEntity player) {
        float hp = player.getHealth();
        if (hp <= 0.0f && prevHealth > 0.0f) {
            deaths++;
            endEpisode(false);     // death ends the episode; fitness eats the penalty
            return;
        }
        prevHealth = hp;

        agent.tick();
        // Keep the episode running its full budget even if the agent self-stops.
        if (!agent.isActive() && ticksLeft > 1) {
            agent.start();
        }
        if (seededCount > 0 && agent.minedCount() >= seededCount) {
            endEpisode(true);      // goal: all ground-truth ore cleared
            return;
        }
        if (--ticksLeft <= 0) {
            endEpisode(false);
        }
    }

    private void endEpisode(boolean goalMet) {
        if (agent == null) {
            phase = Phase.WAITING;
            return;
        }
        if (pendingId != null) {
            int used = episodeTicks - Math.max(ticksLeft, 0);
            double speedBonus = goalMet ? SPEED_WEIGHT * (Math.max(ticksLeft, 0) / (double) episodeTicks) : 0.0;
            double fitness = agent.minedScore() - DEATH_PENALTY * deaths + speedBonus;
            writeTell(pendingId, fitness, agent.minedScore(), agent.minedCount(), deaths, used, goalMet);
            sendToPlugin(FarmProtocol.episodeEnd(arenaId));
            msg("episode " + pendingId + (goalMet ? " CLEARED" : " done") + ": fitness "
                    + String.format("%.1f", fitness));
        }
        agent.stop();
        agent = null;
        pendingId = null;
        phase = Phase.WAITING;
        pollCooldown = 0;          // grab the next candidate promptly
    }

    // -- memory seeding ----------------------------------------------------

    private void seedMemory(FarmProtocol.ArenaReady r) {
        for (OreMemory.Node n : trainMemory.snapshot()) {
            trainMemory.forget(n.pos);
        }
        for (FarmProtocol.GroundTruthOre o : r.ores()) {
            trainMemory.record(new BlockPos(o.x(), o.y(), o.z()), o.label(), 1.0f);
        }
        seededCount = r.ores().size();
    }

    // -- transport ---------------------------------------------------------

    private boolean sendToPlugin(byte[] payload) {
        if (!ClientPlayNetworking.canSend(FarmPayload.ID)) {
            return false;
        }
        ClientPlayNetworking.send(new FarmPayload(payload));
        return true;
    }

    // -- run-dir IO --------------------------------------------------------

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

    private void writeTell(String id, double fitness, double score, int ores, int deaths,
                           int ticks, boolean goal) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("fitness", fitness);
        o.addProperty("score", score);
        o.addProperty("ores", ores);
        o.addProperty("deaths", deaths);
        o.addProperty("ticks", ticks);
        o.addProperty("goal", goal);
        o.addProperty("arena", arenaId);
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
