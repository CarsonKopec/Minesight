package com.minesight.farm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MineSight farm plugin (Folia) - server side of the 2.0 split.
 *
 * <p>Responsibilities (built incrementally):
 * <ul>
 *   <li>Plugin-message transport with the Fabric client over the
 *       {@code minesight:farm} channel (the PoC hello/pong, extended later to
 *       capture commands).</li>
 *   <li>Regionized ore scanning via {@link FoliaOreLocator}, driven off a
 *       global-region tick.</li>
 *   <li>Spectator teleporting to located ore ({@code teleportAsync}).</li>
 * </ul>
 *
 * <p>The {@code /minesightfarm} command exercises the scan + teleport path
 * in-game without needing the Python control panel.
 */
public class MineSightFarmPlugin extends JavaPlugin implements PluginMessageListener, BasicCommand {

    public static final String CHANNEL = "minesight:farm";

    private FoliaOreLocator locator;
    private volatile CaptureSession session;
    private VisitedStore visited;
    private GuiLink guiLink;
    private ArenaManager arenas;
    private BotTrainer botTrainer;
    private int heartbeat;

    @Override
    public void onEnable() {
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);

        locator = new FoliaOreLocator(this);
        visited = new VisitedStore(this);
        guiLink = new GuiLink(this);
        arenas = new ArenaManager(this);
        arenas.init();
        botTrainer = new BotTrainer(this, arenas);

        // Paper plugins don't support YAML command declarations; register the
        // command programmatically (callable from onEnable).
        registerCommand("minesightfarm",
                "Control the MineSight dataset farm (regionized ore scan + teleport).",
                List.of("msf"), this);

        // Folia global-region heartbeat: pumps the ore scanner (launches async
        // chunk gen+scan up to its in-flight budget) and advances any capture
        // session every tick.
        getServer().getGlobalRegionScheduler().runAtFixedRate(this, task -> {
            guiLink.ensureConnected();
            if (locator.isRunning()) {
                locator.pump();
            }
            CaptureSession s = session;
            if (s != null) {
                s.tick();
                if (s.isDone()) {
                    session = null;
                }
            }
            // Periodic live status (every ~5s) while scanning or capturing.
            if (++heartbeat % 100 == 0 && (locator.isRunning() || s != null)) {
                getLogger().info(String.format(
                        "status: %d ore + %d confusers queued, %d found in %d chunks%s",
                        locator.available(), locator.availableConfusers(), locator.oresFound(),
                        locator.chunksScanned(), s != null ? " | " + s.status() : ""));
            }
        }, 1L, 1L);

        getLogger().info("MineSightFarm enabled (Folia). Channel " + CHANNEL
                + ", command /minesightfarm.");
    }

    @Override
    public void onDisable() {
        if (botTrainer != null) {
            botTrainer.stop();
        }
        if (locator != null) {
            locator.stop();
        }
        if (visited != null) {
            visited.save();
        }
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
    }

    // ---- /minesightfarm command (Paper BasicCommand) -----------------------

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        CommandSender sender = source.getSender();
        if (args.length == 0) {
            sender.sendMessage("Usage: /minesightfarm <scan <ore> [radius] | capture [count] | "
                    + "arena <tp|reset|list> [n] | bot [n] | train <start [n]|stop> | status | tp | stop>");
            return;
        }
        switch (args[0].toLowerCase()) {
            case "scan" -> cmdScan(sender, args);
            case "capture" -> cmdCapture(sender, args);
            case "arena" -> cmdArena(sender, args);
            case "bot" -> cmdBot(sender, args);
            case "train" -> cmdTrain(sender, args);
            case "status" -> cmdStatus(sender);
            case "tp" -> cmdTeleport(sender);
            case "stop" -> {
                locator.stop();
                CaptureSession s = session;
                if (s != null) {
                    s.stop();
                    session = null;
                }
                sender.sendMessage("MineSight: scan + capture stopped.");
            }
            default -> sender.sendMessage("Unknown subcommand: " + args[0]);
        }
    }

    private boolean cmdScan(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("MineSight: /minesightfarm scan must be run by a player (needs a world + position).");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("Usage: /minesightfarm scan <ore> [radius]");
            sender.sendMessage("Ores: " + String.join(", ", OreCatalog.labels()));
            return true;
        }
        String ore = args[1].toLowerCase();
        if (!ore.endsWith("_ore")) {
            ore = ore + "_ore";
        }
        if (OreCatalog.yBand(ore) == null) {
            sender.sendMessage("MineSight: unknown ore '" + ore + "'. Known: "
                    + String.join(", ", OreCatalog.labels()));
            return true;
        }
        int radius = 128;
        if (args.length >= 3) {
            try {
                radius = Math.max(16, Math.min(512, Integer.parseInt(args[2])));
            } catch (NumberFormatException ignored) {
                sender.sendMessage("MineSight: radius must be a number; using " + radius + ".");
            }
        }
        int[] band = OreCatalog.yBand(ore);
        Location at = player.getLocation();
        visited.load(at.getWorld().getName());
        locator.clearResults();
        locator.configure(at.getWorld(), at.getBlockX(), at.getBlockZ(), radius,
                Set.of(ore), band[0], band[1], Set.of());
        locator.start();
        sender.sendMessage("MineSight: scanning for " + ore + " within " + radius
                + " blocks, Y " + band[0] + ".." + band[1] + ". Use /minesightfarm status.");
        return true;
    }

    private boolean cmdCapture(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("MineSight: /minesightfarm capture must be run by a player (the camera).");
            return true;
        }
        if (locator.available() == 0 && !locator.isRunning()) {
            sender.sendMessage("MineSight: no ore queued. Run /minesightfarm scan <ore> first.");
            return true;
        }
        if (session != null && !session.isDone()) {
            sender.sendMessage("MineSight: a capture session is already running. /minesightfarm stop to cancel.");
            return true;
        }
        int count = 1;
        if (args.length >= 2) {
            try {
                count = Math.max(1, Math.min(10000, Integer.parseInt(args[1])));
            } catch (NumberFormatException ignored) {
                sender.sendMessage("MineSight: count must be a number; using " + count + ".");
            }
        }
        session = new CaptureSession(this, locator, visited, true, count, true, 0.0, Map.of());
        sender.sendMessage("MineSight: capturing " + count + " frame(s) across all cameras. /minesightfarm status to watch.");
        return true;
    }

    private boolean cmdStatus(CommandSender sender) {
        sender.sendMessage(String.format(
                "MineSight: %s | %d ore queued, %d found across %d chunks scanned%s",
                locator.isRunning() ? "scanning" : "idle",
                locator.available(), locator.oresFound(), locator.chunksScanned(),
                locator.exhausted() ? " (area exhausted)" : ""));
        CaptureSession s = session;
        if (s != null) {
            sender.sendMessage("MineSight: " + s.status());
        }
        return true;
    }

    private boolean cmdTeleport(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("MineSight: /minesightfarm tp must be run by a player.");
            return true;
        }
        FoliaOreLocator.OrePos ore = locator.poll();
        if (ore == null) {
            sender.sendMessage("MineSight: no ore queued yet. Run /minesightfarm scan first.");
            return true;
        }
        Location center = new Location(player.getWorld(), ore.x() + 0.5, ore.y() + 0.5, ore.z() + 0.5);
        Location eye = new Location(player.getWorld(), ore.ex(), ore.ey(), ore.ez());
        eye.setDirection(center.toVector().subtract(eye.toVector()));
        // Teleport + gamemode must run on the player's region thread (Folia).
        player.getScheduler().run(this, task -> {
            player.setGameMode(GameMode.SPECTATOR);
            player.teleportAsync(eye).thenAccept(ok -> player.sendMessage(
                    "MineSight: " + (ok ? "looking at " : "teleport failed for ")
                            + ore.label() + " @ " + ore.x() + "," + ore.y() + "," + ore.z()));
        }, null);
        return true;
    }

    /** {@code /msf arena <tp|reset|list> [n]} - spectate, manually reset, or list. */
    private void cmdArena(CommandSender sender, String[] args) {
        if (arenas == null || !arenas.ready()) {
            sender.sendMessage("MineSight: arena world unavailable.");
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase() : "list";
        switch (sub) {
            case "tp" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("MineSight: arena tp must be run by a player.");
                    return;
                }
                int id = args.length >= 3 ? parseIntOr(args[2], 0) : 0;
                arenas.spectate(p, id);
                sender.sendMessage("MineSight: spectating arena " + id + ".");
            }
            case "reset" -> {
                int id = args.length >= 3 ? parseIntOr(args[2], 0) : 0;
                ArenaManager.Arena a = arenas.arena(id);
                arenas.stamp(a, () -> getLogger().info("Arena " + id + " reset."));
                sender.sendMessage("MineSight: resetting arena " + id + ".");
            }
            default -> sender.sendMessage("MineSight: " + arenas.slotCount()
                    + " arena slots in world '" + ArenaManager.WORLD + "'. "
                    + "Use /msf arena tp <n> to watch, reset <n> to rebuild.");
        }
    }

    private static int parseIntOr(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** {@code /msf bot [n]} - run one watchable bot episode in arena n. */
    private void cmdBot(CommandSender sender, String[] args) {
        if (arenas == null || !arenas.ready() || botTrainer == null) {
            sender.sendMessage("MineSight: arena world unavailable.");
            return;
        }
        int id = args.length >= 2 ? parseIntOr(args[1], 0) : 0;
        BotParams params = botTrainer.bestParams();
        sender.sendMessage("MineSight: running a bot in arena " + id
                + " (tuned params: " + botTrainer.hasBest() + "). /msf arena tp " + id + " to watch.");
        botTrainer.runDemo(id, params, r -> sender.sendMessage(String.format(
                "MineSight: bot done in arena %d - fitness %.1f (%d ore, %d deaths, %d ticks%s)",
                id, r.fitness(), r.ores(), r.deaths(), r.ticks(), r.cleared() ? ", CLEARED" : "")));
    }

    /** {@code /msf train <start [n]|stop>} - headless bot training loop. */
    private void cmdTrain(CommandSender sender, String[] args) {
        if (botTrainer == null) {
            sender.sendMessage("MineSight: trainer unavailable.");
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase() : "status";
        switch (sub) {
            case "start" -> {
                int n = args.length >= 3 ? parseIntOr(args[2], 4) : 4;
                botTrainer.start(n);
                sender.sendMessage("MineSight: bot training started on " + n
                        + " arenas. Now run: python -m minesight.evolve");
            }
            case "stop" -> {
                botTrainer.stop();
                sender.sendMessage("MineSight: bot training stopped.");
            }
            default -> sender.sendMessage("MineSight: training "
                    + (botTrainer.isRunning() ? "running" : "idle") + ". Run dir " + botTrainer.runDir());
        }
    }

    /** plugin -> client {@code arena_ready}: the arena is reset and you're in it;
     *  here is the ground-truth ore to seed memory with. */
    private void sendArenaReady(Player player, ArenaManager.Arena a) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream d = new DataOutputStream(out)) {
            d.writeUTF("arena_ready");
            d.writeInt(a.id());
            Location s = a.spawn();
            d.writeDouble(s.getX());
            d.writeDouble(s.getY());
            d.writeDouble(s.getZ());
            d.writeFloat(s.getYaw());
            List<ArenaManager.GroundTruthOre> ores = a.ores();
            d.writeInt(ores.size());
            for (ArenaManager.GroundTruthOre o : ores) {
                d.writeUTF(o.label());
                d.writeInt(o.x());
                d.writeInt(o.y());
                d.writeInt(o.z());
            }
        } catch (IOException e) {
            return;
        }
        player.getScheduler().run(this,
                t -> player.sendPluginMessage(this, CHANNEL, out.toByteArray()), null);
    }

    // ---- GUI collector link (server-side settings) -------------------------

    /** Called by {@link GuiLink} (WS thread) for collect_start / collect_update. */
    void onCollectorSettings(JsonObject s, boolean start) {
        getServer().getGlobalRegionScheduler().execute(this, () -> applyServerSettings(s, start));
    }

    /** Called by {@link GuiLink} (WS thread) for collect_stop. */
    void onCollectorStop() {
        getServer().getGlobalRegionScheduler().execute(this, () -> {
            locator.stop();
            CaptureSession s = session;
            if (s != null) {
                s.stop();
                session = null;
            }
            visited.save();
            getLogger().info("Collector: stopped by GUI.");
        });
    }

    /** Called by {@link GuiLink} (WS thread) for collect_clear_history. */
    void onCollectorClearHistory() {
        getServer().getGlobalRegionScheduler().execute(this, () -> {
            visited.clear();
            getLogger().info("Collector: visited history cleared.");
        });
    }

    private void applyServerSettings(JsonObject s, boolean start) {
        Player camera = getServer().getOnlinePlayers().stream().findFirst().orElse(null);
        if (camera == null) {
            getLogger().info("Collector settings received but no camera client is online yet.");
            return;
        }
        int radius = Math.max(16, Math.min(512, optInt(s, "radius", 128)));
        int yLo = optInt(s, "y_min", -64);
        int yHi = optInt(s, "y_max", 72);
        int target = optInt(s, "target", 0);
        boolean avoidRevisits = !s.has("avoid_revisits") || s.get("avoid_revisits").getAsBoolean();
        double hardNeg = Math.max(0.0, Math.min(0.9, optDouble(s, "hard_negative_ratio", 0.0)));
        Set<String> wanted = parseClasses(s);
        Set<Material> confMats = hardNeg > 0 ? parseConfuserMaterials(s) : Set.of();
        Map<String, Integer> classGoals = parseClassTargets(s);
        // Reading the camera's location must run on its region thread (Folia).
        camera.getScheduler().run(this, t -> {
            Location loc = camera.getLocation();
            visited.load(loc.getWorld().getName());
            locator.clearResults();
            locator.configure(loc.getWorld(), loc.getBlockX(), loc.getBlockZ(),
                    radius, wanted, yLo, yHi, confMats);
            locator.start();
            if (start && target > 0 && (session == null || session.isDone())) {
                session = new CaptureSession(this, locator, visited, avoidRevisits, target, true,
                        hardNeg, classGoals);
            }
            getLogger().info("Collector " + (start ? "start" : "live update") + ": radius "
                    + radius + ", ore " + wanted + ", Y " + yLo + ".." + yHi
                    + (start && target > 0 ? ", target " + target : "")
                    + ", hardNeg=" + hardNeg + (classGoals.isEmpty() ? "" : ", goals " + classGoals));
        }, null);
    }

    private static int optInt(JsonObject s, String key, int def) {
        return s.has(key) && s.get(key).isJsonPrimitive() ? s.get(key).getAsInt() : def;
    }

    private static double optDouble(JsonObject s, String key, double def) {
        return s.has(key) && s.get(key).isJsonPrimitive() ? s.get(key).getAsDouble() : def;
    }

    /** Ore labels from the settings' {@code classes} array, or all known ores. */
    private static Set<String> parseClasses(JsonObject s) {
        Set<String> out = new HashSet<>();
        if (s.has("classes") && s.get("classes").isJsonArray()) {
            for (JsonElement e : s.getAsJsonArray("classes")) {
                out.add(e.getAsString());
            }
        }
        return out.isEmpty() ? OreCatalog.labels() : out;
    }

    /** Confuser materials for the enabled categories (for hard-negative shots). */
    private static Set<Material> parseConfuserMaterials(JsonObject s) {
        Set<String> cats = new HashSet<>();
        if (s.has("confuser_categories") && s.get("confuser_categories").isJsonArray()) {
            for (JsonElement e : s.getAsJsonArray("confuser_categories")) {
                cats.add(e.getAsString());
            }
        }
        return OreCatalog.confusers(cats);
    }

    /** Per-class box goals from {@code class_targets}. */
    private static Map<String, Integer> parseClassTargets(JsonObject s) {
        Map<String, Integer> goals = new HashMap<>();
        if (s.has("class_targets") && s.get("class_targets").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : s.getAsJsonObject("class_targets").entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    goals.put(e.getKey(), e.getValue().getAsInt());
                }
            }
        }
        return goals;
    }

    // ---- plugin-channel transport -----------------------------------------

    /**
     * plugin -> client {@code capture}: "photograph these ore AABBs from where
     * you stand". One block per box: min (x,y,z) .. max (x+1,y+1,z+1).
     */
    void sendCapture(Player player, int shotId, boolean hideHud,
                     List<FoliaOreLocator.OrePos> boxes, boolean saveEmpty) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream d = new DataOutputStream(out)) {
            d.writeUTF("capture");
            d.writeInt(shotId);
            d.writeBoolean(hideHud);
            d.writeBoolean(saveEmpty);
            d.writeInt(boxes.size());
            for (FoliaOreLocator.OrePos b : boxes) {
                d.writeUTF(b.label());
                d.writeInt(b.x());
                d.writeInt(b.y());
                d.writeInt(b.z());
                d.writeInt(b.x() + 1);
                d.writeInt(b.y() + 1);
                d.writeInt(b.z() + 1);
            }
        } catch (IOException e) {
            return;
        }
        player.getScheduler().run(this,
                t -> player.sendPluginMessage(this, CHANNEL, out.toByteArray()), null);
    }

    /** Read client packets: hello -> pong, captured -> advance the session. */
    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player,
                                        @NotNull byte[] message) {
        if (!CHANNEL.equals(channel)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            String type = in.readUTF();
            switch (type) {
                case "hello" -> sendPong(player, in.readUTF());
                case "captured" -> {
                    int shotId = in.readInt();
                    boolean ok = in.readBoolean();
                    int boxes = in.readInt();
                    CaptureSession s = session;
                    if (s != null) {
                        s.onCaptured(shotId, ok);
                    }
                    getLogger().fine("captured shot " + shotId + " ok=" + ok + " boxes=" + boxes);
                }
                case "arena_request" -> onArenaRequest(player);
                case "episode_end" -> {
                    int id = in.readInt();
                    getLogger().fine("episode ended in arena " + id + " (" + player.getName() + ")");
                }
                default -> getLogger().fine("Unknown packet from " + player.getName() + ": " + type);
            }
        } catch (IOException e) {
            getLogger().warning("Bad packet from " + player.getName() + ": " + e);
        }
    }

    /** A client asked for a training episode: reset its arena, drop it in kitted,
     *  then hand back the ground-truth ore. */
    private void onArenaRequest(Player player) {
        if (arenas == null || !arenas.ready()) {
            getLogger().warning("arena_request from " + player.getName() + " but arena world is unavailable.");
            return;
        }
        ArenaManager.Arena a = arenas.assign(player.getUniqueId());
        arenas.stamp(a, () -> arenas.enter(player, a, () -> sendArenaReady(player, a)));
    }

    private void sendPong(Player player, String clientId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(out)) {
            data.writeUTF("pong");
            data.writeUTF("MineSightFarm v" + getPluginMeta().getVersion());
        } catch (IOException e) {
            return;
        }
        // Replying must run on the player's region thread (Folia).
        player.getScheduler().run(this,
                t -> player.sendPluginMessage(this, CHANNEL, out.toByteArray()), null);
        getLogger().info("Replied pong to client " + clientId);
    }
}
