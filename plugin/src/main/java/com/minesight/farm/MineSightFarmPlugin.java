package com.minesight.farm;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
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
import java.util.List;
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
public class MineSightFarmPlugin extends JavaPlugin implements PluginMessageListener, CommandExecutor {

    public static final String CHANNEL = "minesight:farm";

    private FoliaOreLocator locator;
    private volatile CaptureSession session;

    @Override
    public void onEnable() {
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);

        locator = new FoliaOreLocator(this);

        if (getCommand("minesightfarm") != null) {
            getCommand("minesightfarm").setExecutor(this);
        }

        // Folia global-region heartbeat: pumps the ore scanner (launches async
        // chunk gen+scan up to its in-flight budget) and advances any capture
        // session every tick.
        getServer().getGlobalRegionScheduler().runAtFixedRate(this, task -> {
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
        }, 1L, 1L);

        getLogger().info("MineSightFarm enabled (Folia). Channel " + CHANNEL
                + ", command /minesightfarm.");
    }

    @Override
    public void onDisable() {
        if (locator != null) {
            locator.stop();
        }
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
    }

    // ---- /minesightfarm command -------------------------------------------

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /minesightfarm <scan <ore> [radius] | capture [count] | status | tp | stop>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "scan":
                return cmdScan(sender, args);
            case "capture":
                return cmdCapture(sender, args);
            case "status":
                return cmdStatus(sender);
            case "tp":
                return cmdTeleport(sender);
            case "stop":
                locator.stop();
                CaptureSession s = session;
                if (s != null) {
                    s.stop();
                    session = null;
                }
                sender.sendMessage("MineSight: scan + capture stopped.");
                return true;
            default:
                sender.sendMessage("Unknown subcommand: " + args[0]);
                return true;
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
        locator.clearResults();
        locator.configure(at.getWorld(), at.getBlockX(), at.getBlockZ(), radius,
                Set.of(ore), band[0], band[1]);
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
        session = new CaptureSession(this, locator, player.getUniqueId(), count, true);
        sender.sendMessage("MineSight: capturing " + count + " frame(s) from queued ore. /minesightfarm status to watch.");
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
        Location target = new Location(player.getWorld(), ore.x() + 0.5, ore.y() + 0.5, ore.z() + 0.5);
        Location eye = target.clone().add(3.0, 2.0, 3.0);
        eye.setDirection(target.toVector().subtract(eye.toVector()));
        // Teleport + gamemode must run on the player's region thread (Folia).
        player.getScheduler().run(this, task -> {
            player.setGameMode(GameMode.SPECTATOR);
            player.teleportAsync(eye).thenAccept(ok -> player.sendMessage(
                    "MineSight: " + (ok ? "looking at " : "teleport failed for ")
                            + ore.label() + " @ " + ore.x() + "," + ore.y() + "," + ore.z()));
        }, null);
        return true;
    }

    // ---- plugin-channel transport -----------------------------------------

    /**
     * plugin -> client {@code capture}: "photograph these ore AABBs from where
     * you stand". One block per box: min (x,y,z) .. max (x+1,y+1,z+1).
     */
    void sendCapture(Player player, int shotId, boolean hideHud, List<FoliaOreLocator.OrePos> boxes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream d = new DataOutputStream(out)) {
            d.writeUTF("capture");
            d.writeInt(shotId);
            d.writeBoolean(hideHud);
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
                default -> getLogger().fine("Unknown packet from " + player.getName() + ": " + type);
            }
        } catch (IOException e) {
            getLogger().warning("Bad packet from " + player.getName() + ": " + e);
        }
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
