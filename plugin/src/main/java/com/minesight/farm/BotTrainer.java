package com.minesight.farm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Headless training loop: drives the CMA-ES auto-tuner with server-side bots.
 *
 * <p>Reuses the exact same file protocol as the client harness - it reads
 * candidate params from {@code ask.json}, runs an episode per candidate with a
 * {@link MiningBot} in a freshly-reset {@link ArenaManager} arena, and appends
 * the fitness to {@code tell.jsonl}. So {@code python -m minesight.evolve} (the
 * real run) drives the bots with no client at all. Each arena slot runs an
 * independent episode, so N slots evaluate N candidates in parallel.
 */
public final class BotTrainer {

    private final MineSightFarmPlugin plugin;
    private final ArenaManager arenas;
    private final Path dir;
    private final int budget;

    private boolean running;
    private int slots;
    private int lastGen = -1;
    private final Set<String> claimed = new HashSet<>();
    private final List<ScheduledTask> tasks = new ArrayList<>();
    private final List<BotEpisode> bots = new ArrayList<>();

    private BossBar bossBar;
    private double bestFitness = Double.NEGATIVE_INFINITY;
    private double maxFitness = -1;

    // "zombie" | "nms" (scripted) | "physics" - selectable at runtime.
    private volatile String botMode;

    public BotTrainer(MineSightFarmPlugin plugin, ArenaManager arenas) {
        this.plugin = plugin;
        this.arenas = arenas;
        this.dir = resolveDir();
        this.budget = envInt("MINESIGHT_EPISODE_TICKS", 3600);
        this.botMode = initialBotMode();
    }

    /** Initial mode from the legacy JVM flags, so -D still works as a default. */
    private static String initialBotMode() {
        if ("zombie".equalsIgnoreCase(System.getProperty("minesight.bot", "nms"))) {
            return "zombie";
        }
        return "physics".equalsIgnoreCase(System.getProperty("minesight.nmsMove", "scripted"))
                ? "physics" : "nms";
    }

    public String botMode() {
        return botMode;
    }

    /** Select the bot body for future episodes: zombie / nms (scripted) / physics. */
    public void setBotMode(String mode) {
        if (mode == null) {
            return;
        }
        switch (mode.toLowerCase()) {
            case "zombie", "nms", "physics" -> botMode = mode.toLowerCase();
            case "scripted" -> botMode = "nms";
            default -> {
            }
        }
    }

    public boolean isRunning() {
        return running;
    }

    public Path runDir() {
        return dir;
    }

    /** Start headless training across {@code n} arena slots (0..n-1). */
    public void start(int n) {
        if (running) {
            return;
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            plugin.getLogger().warning("train: can't open " + dir);
            return;
        }
        running = true;
        slots = Math.max(1, Math.min(n, arenas.slotCount()));
        claimed.clear();
        bestFitness = Double.NEGATIVE_INFINITY;
        maxFitness = maxFitnessOf(arenas.arena(0));
        bossBar = Bukkit.createBossBar("⛏ MineSight training", BarColor.GREEN, BarStyle.SEGMENTED_10);
        for (Player p : Bukkit.getOnlinePlayers()) {
            bossBar.addPlayer(p);
        }
        updateBossBar();
        for (int id = 0; id < slots; id++) {
            launchNext(id);
        }
        plugin.getComponentLogger().info(Component.text(
                ">> Bot training started across " + slots + " arenas", NamedTextColor.AQUA));
    }

    public void stop() {
        running = false;
        for (ScheduledTask t : tasks) {
            t.cancel();
        }
        tasks.clear();
        for (BotEpisode b : bots) {
            b.cleanup();
        }
        bots.clear();
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }
        plugin.getComponentLogger().info(Component.text("[x] Bot training stopped", NamedTextColor.GOLD));
    }

    /** Run a single watchable episode in one arena (for {@code /msf bot}). */
    public void runDemo(int arenaId, BotParams params, Consumer<BotEpisode.Result> onResult) {
        runEpisode(arenaId, params, "MineBot demo", onResult);
    }

    /**
     * Bot body for the current {@link #botMode}: connection-free Zombie, a real
     * NMS ServerPlayer with scripted teleport movement, or one with real physics.
     */
    private BotEpisode makeBot(ArenaManager.Arena arena, BotParams params) {
        return switch (botMode) {
            case "zombie" -> new ZombieBot(plugin, arena, params, budget);
            case "physics" -> new NmsBot(plugin, arena, params, budget, true);
            default -> new NmsBot(plugin, arena, params, budget, false);   // nms scripted
        };
    }

    /** Whether a tuned best.json export exists to run with (vs. defaults). */
    public boolean hasBest() {
        return Files.exists(dir.resolve("best.json"));
    }

    /** The auto-tuner's exported params (best.json), or defaults if none yet. */
    public BotParams bestParams() {
        JsonObject o = readJson(dir.resolve("best.json"));
        if (o == null) {
            return BotParams.defaults();
        }
        JsonObject values = o.has("values") && o.get("values").isJsonObject()
                ? o.getAsJsonObject("values") : o;
        return BotParams.fromJson(values);
    }

    // -- per-slot training loop -------------------------------------------

    private void launchNext(int slot) {
        if (!running) {
            return;
        }
        Candidate c = pickCandidate();
        if (c == null) {
            // No work yet (optimizer computing the next generation); re-check soon.
            plugin.getServer().getGlobalRegionScheduler()
                    .runDelayed(plugin, task -> launchNext(slot), 20L);
            return;
        }
        runEpisode(slot, c.params, "MineBot#" + slot, result -> {
            writeTell(c.id, result);
            if (result.fitness() > bestFitness) {
                bestFitness = result.fitness();
            }
            updateBossBar();
            if (running) {
                launchNext(slot);
            }
        });
    }

    /** Live boss bar: best fitness so far as a % of the per-episode maximum. */
    private void updateBossBar() {
        if (bossBar == null) {
            return;
        }
        boolean haveBest = bestFitness > Double.NEGATIVE_INFINITY;
        double best = haveBest ? bestFitness : 0.0;
        double frac = maxFitness > 0 && haveBest
                ? Math.max(0.0, Math.min(1.0, best / maxFitness)) : 0.0;
        int pct = (int) Math.round(frac * 100);
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
            if (bossBar == null) {
                return;
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                bossBar.addPlayer(p);   // catch spectators who joined after start
            }
            bossBar.setProgress(frac);
            bossBar.setTitle(String.format(
                    "⛏ MineSight — best fitness %.1f (%d%%) · gen %d", best, pct, lastGen));
        });
    }

    /** The best fitness an arena allows: every ore + the finish-fast bonus. */
    private double maxFitnessOf(ArenaManager.Arena arena) {
        double m = 20.0;   // BotEpisode.SPEED_WEIGHT, awarded for an instant clear
        for (ArenaManager.GroundTruthOre o : arena.ores()) {
            String l = o.label();
            m += (l.contains("diamond") || l.contains("emerald")) ? 8.0 : 1.0;
        }
        return m;
    }

    private void runEpisode(int arenaId, BotParams params, String name,
                            Consumer<BotEpisode.Result> onResult) {
        // A brand-new random layout every episode (different each bot restart).
        ArenaManager.Arena arena = arenas.fresh(arenaId);
        World w = arena.spawn().getWorld();
        int cx = (arena.ox + ArenaManager.W / 2) >> 4;
        int cz = (arena.oz + ArenaManager.D / 2) >> 4;
        arenas.stamp(arena, () -> {
            BotEpisode bot = makeBot(arena, params);
            try {
                bot.spawn(name);
            } catch (Throwable ex) {
                // NMS lifecycle is finicky - fall back to the API-safe zombie body.
                plugin.getLogger().warning("Bot spawn failed (" + ex + "); falling back to zombie.");
                bot.cleanup();
                bot = new ZombieBot(plugin, arena, params, budget);
                bot.spawn(name);
            }
            final BotEpisode runBot = bot;
            bots.add(runBot);
            plugin.getComponentLogger().info(Component.text(
                    "  [+] " + name + " logged in (arena " + arenaId + ")", NamedTextColor.GREEN));
            ScheduledTask task = plugin.getServer().getRegionScheduler().runAtFixedRate(
                    plugin, w, cx, cz, t -> {
                        runBot.tick();
                        if (runBot.isDone()) {
                            t.cancel();
                            tasks.remove(t);
                            BotEpisode.Result r = runBot.result();
                            runBot.cleanup();
                            bots.remove(runBot);
                            plugin.getComponentLogger().info(Component.text(String.format(
                                    "  [-] %s logged out (arena %d) - fitness %.1f, %d/%d ore, %d deaths%s",
                                    name, arenaId, r.fitness(), r.ores(), arena.ores().size(),
                                    r.deaths(), r.cleared() ? ", CLEARED" : ""),
                                    r.cleared() ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.YELLOW));
                            onResult.accept(r);
                        }
                    }, 1L, 1L);
            tasks.add(task);
        });
    }

    // -- candidate selection ----------------------------------------------

    private record Candidate(String id, BotParams params) {
    }

    private Candidate pickCandidate() {
        JsonObject ask = readJson(dir.resolve("ask.json"));
        if (ask == null || !ask.has("candidates")) {
            return null;
        }
        int gen = ask.has("gen") ? ask.get("gen").getAsInt() : 0;
        if (gen != lastGen) {
            claimed.clear();
            lastGen = gen;
        }
        Set<String> taken = answeredIds();
        taken.addAll(claimed);
        for (JsonElement el : ask.getAsJsonArray("candidates")) {
            JsonObject cand = el.getAsJsonObject();
            String id = cand.get("id").getAsString();
            if (taken.contains(id)) {
                continue;
            }
            claimed.add(id);
            return new Candidate(id, BotParams.fromJson(cand.getAsJsonObject("values")));
        }
        return null;
    }

    private Set<String> answeredIds() {
        Set<String> ids = new HashSet<>();
        Path tell = dir.resolve("tell.jsonl");
        if (!Files.exists(tell)) {
            return ids;
        }
        try {
            for (String line : Files.readAllLines(tell, StandardCharsets.UTF_8)) {
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
                    // skip malformed
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("train: can't read tell.jsonl");
        }
        return ids;
    }

    private void writeTell(String id, BotEpisode.Result r) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("fitness", r.fitness());
        o.addProperty("score", r.score());
        o.addProperty("ores", r.ores());
        o.addProperty("deaths", r.deaths());
        o.addProperty("ticks", r.ticks());
        o.addProperty("cleared", r.cleared());
        o.addProperty("source", "bot");
        try {
            Files.writeString(dir.resolve("tell.jsonl"), o + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().warning("train: can't append tell.jsonl");
        }
    }

    private JsonObject readJson(Path path) {
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            return null;  // optimizer may be mid-write
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
        if (v != null) {
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }
}
