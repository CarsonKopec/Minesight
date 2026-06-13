package com.minesight.farm;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * Orchestrates a run of captures for one camera player: poll located ore from
 * the {@link FoliaOreLocator}, teleport the spectator to a viewpoint, let the
 * client settle + photograph, await its {@code captured} ack, repeat until the
 * target image count is met or the area runs dry.
 *
 * <p>{@link #tick} runs on the global-region heartbeat; teleports and packet
 * sends are dispatched onto the player's region thread (Folia). The client owns
 * the render-settle wait, so the plugin only needs a short post-teleport delay
 * before asking for the shot.
 */
public final class CaptureSession {

    private enum State {IDLE, TP_WAIT, CAP_WAIT, DONE}

    /** Ticks to let the async teleport land before asking for a capture. */
    private static final int TP_SETTLE_TICKS = 10;
    /** Abandon a shot if the client never acks within this many ticks. */
    private static final int CAP_TIMEOUT_TICKS = 200;
    /** Safety cap so an all-occluded area can't loop forever. */
    private static final int ATTEMPT_FACTOR = 8;

    private final MineSightFarmPlugin plugin;
    private final FoliaOreLocator locator;
    private final UUID playerId;
    private final int target;
    private final boolean hideHud;

    private State state = State.IDLE;
    private int saved;
    private int failed;
    private int shotCounter;
    private int stateTicks;
    private int currentShot = -1;
    private FoliaOreLocator.OrePos currentOre;

    private volatile boolean acked;
    private volatile boolean ackOk;

    public CaptureSession(MineSightFarmPlugin plugin, FoliaOreLocator locator,
                          UUID playerId, int target, boolean hideHud) {
        this.plugin = plugin;
        this.locator = locator;
        this.playerId = playerId;
        this.target = target;
        this.hideHud = hideHud;
    }

    public boolean isDone() {
        return state == State.DONE;
    }

    public String status() {
        return String.format("capture %s: %d/%d saved, %d failed",
                isDone() ? "done" : "running", saved, target, failed);
    }

    /** Client ack callback (player region thread). */
    public void onCaptured(int shotId, boolean ok) {
        if (shotId == currentShot) {
            ackOk = ok;
            acked = true;
        }
    }

    public void stop() {
        state = State.DONE;
    }

    /** Global-region heartbeat: advance the capture state machine. */
    public void tick() {
        if (state == State.DONE) {
            return;
        }
        if (saved >= target || (saved + failed) >= target * ATTEMPT_FACTOR) {
            finish();
            return;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            finish();  // camera left
            return;
        }
        switch (state) {
            case IDLE -> beginShot(player);
            case TP_WAIT -> {
                if (++stateTicks >= TP_SETTLE_TICKS) {
                    requestCapture(player);
                }
            }
            case CAP_WAIT -> {
                if (acked) {
                    if (ackOk) {
                        saved++;
                    } else {
                        failed++;
                    }
                    state = State.IDLE;
                } else if (++stateTicks >= CAP_TIMEOUT_TICKS) {
                    failed++;
                    state = State.IDLE;
                }
            }
            default -> {
            }
        }
    }

    private void beginShot(Player player) {
        FoliaOreLocator.OrePos ore = locator.poll();
        if (ore == null) {
            // Out of queued ore: keep waiting if the scan is still producing,
            // otherwise we've exhausted the area.
            if (!locator.isRunning() && locator.exhausted()) {
                finish();
            }
            return;
        }
        currentOre = ore;
        currentShot = ++shotCounter;
        teleport(player, ore);
        state = State.TP_WAIT;
        stateTicks = 0;
    }

    private void requestCapture(Player player) {
        acked = false;
        ackOk = false;
        plugin.sendCapture(player, currentShot, hideHud, List.of(currentOre));
        state = State.CAP_WAIT;
        stateTicks = 0;
    }

    private void teleport(Player player, FoliaOreLocator.OrePos ore) {
        Location target = new Location(player.getWorld(),
                ore.x() + 0.5, ore.y() + 0.5, ore.z() + 0.5);
        // Fixed diagonal viewpoint; the client rejects + reports the shot if the
        // ore turns out occluded, and the session simply moves to the next one.
        Location eye = target.clone().add(3.0, 2.0, 3.0);
        eye.setDirection(target.toVector().subtract(eye.toVector()));
        player.getScheduler().run(plugin, t -> {
            player.setGameMode(GameMode.SPECTATOR);
            player.teleportAsync(eye);
        }, null);
    }

    private void finish() {
        state = State.DONE;
        plugin.getLogger().info("Capture session finished: " + saved + " saved, "
                + failed + " failed (target " + target + ").");
    }
}
