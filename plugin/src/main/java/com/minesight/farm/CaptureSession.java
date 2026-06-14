package com.minesight.farm;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Orchestrates a capture run across every connected camera player in parallel:
 * each camera independently polls located ore from the shared
 * {@link FoliaOreLocator}, teleports, lets the client settle + photograph, awaits
 * the {@code captured} ack, and repeats - all draining one queue toward a shared
 * target. This is the 2.0 throughput win: N cameras on one server.
 *
 * <p>Two collection-quality behaviors layer on top:
 * <ul>
 *   <li><b>Per-class goals</b>: with {@code classGoals} set, each shot prefers
 *       the class furthest behind its goal, and the run continues until every
 *       goal is met (or the image cap is hit).</li>
 *   <li><b>Hard negatives</b>: a {@code hardNegRatio} fraction of shots target a
 *       surface confuser block and ask the client to save an empty-label frame,
 *       teaching the model those look-alikes are NOT ore.</li>
 * </ul>
 *
 * <p>{@link #tick} runs on the global-region heartbeat; teleports + packet sends
 * dispatch onto each player's region thread (Folia).
 */
public final class CaptureSession {

    private enum State {IDLE, TP_WAIT, CAP_WAIT}

    private static final int TP_SETTLE_TICKS = 10;
    private static final int CAP_TIMEOUT_TICKS = 200;
    private static final int ATTEMPT_FACTOR = 8;
    private static final int MAX_POLL_SKIP = 64;

    /** Per-camera state machine. */
    private static final class Cam {
        final UUID id;
        State state = State.IDLE;
        int stateTicks;
        volatile int currentShot = -1;
        FoliaOreLocator.OrePos currentTarget;
        boolean hardNeg;
        volatile boolean acked;
        volatile boolean ackOk;

        Cam(UUID id) {
            this.id = id;
        }
    }

    private final MineSightFarmPlugin plugin;
    private final FoliaOreLocator locator;
    private final VisitedStore visited;
    private final boolean avoidRevisits;
    private final int target;
    private final boolean hideHud;
    private final double hardNegRatio;
    private final Map<String, Integer> classGoals;

    private final Map<UUID, Cam> cams = new HashMap<>();
    private final Map<String, Integer> classSaved = new HashMap<>();
    private volatile boolean done;
    private int saved;
    private int failed;
    private int shotCounter;

    public CaptureSession(MineSightFarmPlugin plugin, FoliaOreLocator locator, VisitedStore visited,
                          boolean avoidRevisits, int target, boolean hideHud,
                          double hardNegRatio, Map<String, Integer> classGoals) {
        this.plugin = plugin;
        this.locator = locator;
        this.visited = visited;
        this.avoidRevisits = avoidRevisits;
        this.target = target;
        this.hideHud = hideHud;
        this.hardNegRatio = hardNegRatio;
        this.classGoals = classGoals == null ? Map.of() : Map.copyOf(classGoals);
    }

    public boolean isDone() {
        return done;
    }

    public String status() {
        return String.format("capture %s: %d/%d saved, %d failed, %d camera(s)%s",
                done ? "done" : "running", saved, target, failed, cams.size(),
                classGoals.isEmpty() ? "" : " | goals " + classSaved + "/" + classGoals);
    }

    public void stop() {
        done = true;
    }

    /** Client ack callback (player region thread). */
    public void onCaptured(int shotId, boolean ok) {
        for (Cam c : cams.values()) {
            if (c.currentShot == shotId) {
                c.ackOk = ok;
                c.acked = true;
                return;
            }
        }
    }

    /** Global-region heartbeat: advance every camera's state machine. */
    public void tick() {
        if (done) {
            return;
        }
        if (saved >= target || goalsMet() || (saved + failed) >= target * ATTEMPT_FACTOR) {
            finish();
            return;
        }
        refreshCameras();
        if (cams.isEmpty()) {
            return;
        }
        if (locator.available() == 0 && locator.availableConfusers() == 0
                && !locator.isRunning() && locator.exhausted()
                && cams.values().stream().allMatch(c -> c.state == State.IDLE)) {
            finish();
            return;
        }
        for (Cam cam : cams.values()) {
            advance(cam);
        }
    }

    private boolean goalsMet() {
        if (classGoals.isEmpty()) {
            return false;
        }
        return classGoals.entrySet().stream()
                .allMatch(e -> classSaved.getOrDefault(e.getKey(), 0) >= e.getValue());
    }

    private void refreshCameras() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (cams.putIfAbsent(p.getUniqueId(), new Cam(p.getUniqueId())) == null) {
                plugin.getLogger().info("Capture camera added: " + p.getName()
                        + " (" + cams.size() + " active)");
            }
        }
        cams.keySet().removeIf(id -> {
            if (plugin.getServer().getPlayer(id) == null) {
                plugin.getLogger().info("Capture camera removed (" + (cams.size() - 1) + " active)");
                return true;
            }
            return false;
        });
    }

    private void advance(Cam cam) {
        Player player = plugin.getServer().getPlayer(cam.id);
        if (player == null) {
            return;
        }
        switch (cam.state) {
            case IDLE -> beginShot(cam, player);
            case TP_WAIT -> {
                if (++cam.stateTicks >= TP_SETTLE_TICKS) {
                    cam.acked = false;
                    cam.ackOk = false;
                    if (cam.hardNeg) {
                        plugin.sendCapture(player, cam.currentShot, hideHud, List.of(), true);
                    } else {
                        plugin.sendCapture(player, cam.currentShot, hideHud,
                                List.of(cam.currentTarget), false);
                    }
                    cam.state = State.CAP_WAIT;
                    cam.stateTicks = 0;
                }
            }
            case CAP_WAIT -> {
                if (cam.acked) {
                    onShotDone(cam, cam.ackOk);
                    cam.state = State.IDLE;
                } else if (++cam.stateTicks >= CAP_TIMEOUT_TICKS) {
                    failed++;
                    cam.state = State.IDLE;
                }
            }
            default -> {
            }
        }
    }

    private void onShotDone(Cam cam, boolean ok) {
        if (!ok) {
            failed++;
            return;
        }
        saved++;
        if (avoidRevisits) {
            visited.add(VisitedStore.key(
                    cam.currentTarget.x(), cam.currentTarget.y(), cam.currentTarget.z()));
        }
        if (!cam.hardNeg) {
            classSaved.merge(cam.currentTarget.label(), 1, Integer::sum);
        }
    }

    private void beginShot(Cam cam, Player player) {
        // Roll for a hard-negative (confuser) shot.
        if (hardNegRatio > 0 && ThreadLocalRandom.current().nextDouble() < hardNegRatio) {
            FoliaOreLocator.OrePos conf = pollUnvisited(locator::pollConfuser);
            if (conf != null) {
                startShot(cam, player, conf, true);
                return;
            }
        }
        FoliaOreLocator.OrePos ore = pollOre();
        if (ore != null) {
            startShot(cam, player, ore, false);
        }
    }

    private void startShot(Cam cam, Player player, FoliaOreLocator.OrePos target, boolean hardNeg) {
        cam.currentTarget = target;
        cam.hardNeg = hardNeg;
        cam.currentShot = ++shotCounter;
        teleport(player, target);
        cam.state = State.TP_WAIT;
        cam.stateTicks = 0;
        plugin.getLogger().fine("shot " + cam.currentShot + " -> " + player.getName() + " @ "
                + target.x() + "," + target.y() + "," + target.z()
                + (hardNeg ? " (hard negative)" : " " + target.label()));
    }

    /** Pick ore, preferring the class furthest behind its goal. */
    private FoliaOreLocator.OrePos pollOre() {
        String lagging = laggingClass();
        if (lagging != null) {
            FoliaOreLocator.OrePos p = pollUnvisited(() -> locator.pollClass(lagging));
            if (p != null) {
                return p;
            }
        }
        return pollUnvisited(locator::poll);
    }

    /** The goal class with the largest unmet deficit, or null if none lagging. */
    private String laggingClass() {
        String best = null;
        int bestDeficit = 0;
        for (Map.Entry<String, Integer> e : classGoals.entrySet()) {
            int deficit = e.getValue() - classSaved.getOrDefault(e.getKey(), 0);
            if (deficit > bestDeficit && locator.availableClass(e.getKey()) > 0) {
                bestDeficit = deficit;
                best = e.getKey();
            }
        }
        return best;
    }

    private interface Poller {
        FoliaOreLocator.OrePos poll();
    }

    private FoliaOreLocator.OrePos pollUnvisited(Poller poller) {
        for (int i = 0; i < MAX_POLL_SKIP; i++) {
            FoliaOreLocator.OrePos p = poller.poll();
            if (p == null) {
                return null;
            }
            if (!avoidRevisits || !visited.contains(VisitedStore.key(p.x(), p.y(), p.z()))) {
                return p;
            }
        }
        return null;
    }

    private void teleport(Player player, FoliaOreLocator.OrePos t) {
        player.getScheduler().run(plugin, task -> {
            // Camera sits in the precomputed air pocket, looking at the block.
            Location eye = new Location(player.getWorld(), t.ex(), t.ey(), t.ez());
            Location center = new Location(player.getWorld(),
                    t.x() + 0.5, t.y() + 0.5, t.z() + 0.5);
            eye.setDirection(center.toVector().subtract(eye.toVector()));
            player.setGameMode(GameMode.SPECTATOR);
            player.teleportAsync(eye);
        }, null);
    }

    private void finish() {
        done = true;
        visited.save();
        plugin.getLogger().info("Capture session finished: " + saved + " saved, "
                + failed + " failed (target " + target + ")"
                + (classGoals.isEmpty() ? "" : ", goals " + classSaved + "/" + classGoals) + ".");
    }
}
