package com.minesight.farm;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates a capture run across every connected camera player in parallel:
 * each camera independently polls located ore from the shared
 * {@link FoliaOreLocator}, teleports, lets the client settle + photograph, awaits
 * the {@code captured} ack, and repeats - all draining one ore queue toward a
 * shared target. This is the 2.0 throughput win: N cameras on one server.
 *
 * <p>{@link #tick} runs on the global-region heartbeat; teleports + packet sends
 * are dispatched onto each player's region thread (Folia). With
 * {@code avoidRevisits} it skips and records ore in the {@link VisitedStore} so a
 * vein is never shot twice across sessions.
 */
public final class CaptureSession {

    private enum State {IDLE, TP_WAIT, CAP_WAIT}

    private static final int TP_SETTLE_TICKS = 10;
    private static final int CAP_TIMEOUT_TICKS = 200;
    private static final int ATTEMPT_FACTOR = 8;
    private static final int MAX_POLL_SKIP = 64;  // bound the visited-skip loop

    /** Per-camera state machine. */
    private static final class Cam {
        final UUID id;
        State state = State.IDLE;
        int stateTicks;
        volatile int currentShot = -1;
        FoliaOreLocator.OrePos currentOre;
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

    private final Map<UUID, Cam> cams = new HashMap<>();
    private volatile boolean done;
    private int saved;
    private int failed;
    private int shotCounter;

    public CaptureSession(MineSightFarmPlugin plugin, FoliaOreLocator locator, VisitedStore visited,
                          boolean avoidRevisits, int target, boolean hideHud) {
        this.plugin = plugin;
        this.locator = locator;
        this.visited = visited;
        this.avoidRevisits = avoidRevisits;
        this.target = target;
        this.hideHud = hideHud;
    }

    public boolean isDone() {
        return done;
    }

    public String status() {
        return String.format("capture %s: %d/%d saved, %d failed, %d camera(s)",
                done ? "done" : "running", saved, target, failed, cams.size());
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
        if (saved >= target || (saved + failed) >= target * ATTEMPT_FACTOR) {
            finish();
            return;
        }
        refreshCameras();
        if (cams.isEmpty()) {
            return;  // wait for a camera to connect
        }
        // Out of ore everywhere and every camera idle -> the area is exhausted.
        if (locator.available() == 0 && !locator.isRunning() && locator.exhausted()
                && cams.values().stream().allMatch(c -> c.state == State.IDLE)) {
            finish();
            return;
        }
        for (Cam cam : cams.values()) {
            advance(cam);
        }
    }

    private void refreshCameras() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            cams.computeIfAbsent(p.getUniqueId(), Cam::new);
        }
        cams.keySet().removeIf(id -> plugin.getServer().getPlayer(id) == null);
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
                    plugin.sendCapture(player, cam.currentShot, hideHud, List.of(cam.currentOre));
                    cam.state = State.CAP_WAIT;
                    cam.stateTicks = 0;
                }
            }
            case CAP_WAIT -> {
                if (cam.acked) {
                    if (cam.ackOk) {
                        saved++;
                        if (avoidRevisits) {
                            visited.add(VisitedStore.key(
                                    cam.currentOre.x(), cam.currentOre.y(), cam.currentOre.z()));
                        }
                    } else {
                        failed++;
                    }
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

    private void beginShot(Cam cam, Player player) {
        FoliaOreLocator.OrePos ore = pollUnvisited();
        if (ore == null) {
            return;  // nothing available right now; tick() handles exhaustion
        }
        cam.currentOre = ore;
        cam.currentShot = ++shotCounter;
        teleport(player, ore);
        cam.state = State.TP_WAIT;
        cam.stateTicks = 0;
    }

    private FoliaOreLocator.OrePos pollUnvisited() {
        for (int i = 0; i < MAX_POLL_SKIP; i++) {
            FoliaOreLocator.OrePos p = locator.poll();
            if (p == null) {
                return null;
            }
            if (!avoidRevisits || !visited.contains(VisitedStore.key(p.x(), p.y(), p.z()))) {
                return p;
            }
        }
        return null;
    }

    private void teleport(Player player, FoliaOreLocator.OrePos ore) {
        player.getScheduler().run(plugin, t -> {
            Location target = new Location(player.getWorld(),
                    ore.x() + 0.5, ore.y() + 0.5, ore.z() + 0.5);
            Location eye = target.clone().add(3.0, 2.0, 3.0);
            eye.setDirection(target.toVector().subtract(eye.toVector()));
            player.setGameMode(GameMode.SPECTATOR);
            player.teleportAsync(eye);
        }, null);
    }

    private void finish() {
        done = true;
        visited.save();
        plugin.getLogger().info("Capture session finished: " + saved + " saved, "
                + failed + " failed (target " + target + ").");
    }
}
