package com.minesight.farm;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * MineSight farm plugin (Folia) - proof of concept.
 *
 * For now it just proves the linchpin of the 2.0 architecture: a custom
 * plugin-message round-trip with a Fabric client over the {@code minesight:farm}
 * channel. The full plugin will add regionized ore scanning, spectator
 * teleporting, and capture orchestration on top of this transport.
 */
public class MineSightFarmPlugin extends JavaPlugin implements PluginMessageListener {

    public static final String CHANNEL = "minesight:farm";

    @Override
    public void onEnable() {
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
        getLogger().info("MineSightFarm enabled (Folia). Listening on plugin channel " + CHANNEL);

        // Folia: a global-region heartbeat proves the regionized scheduler API
        // resolves. The real ore scanner will schedule per-region tasks here.
        getServer().getGlobalRegionScheduler().runAtFixedRate(this,
                task -> getLogger().fine("MineSightFarm heartbeat"), 200L, 200L);
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
    }

    /** PoC: read the client's hello, reply with a pong over the same channel. */
    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player,
                                        @NotNull byte[] message) {
        if (!CHANNEL.equals(channel)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            String type = in.readUTF();
            getLogger().info("Packet from " + player.getName() + ": " + type);
            if ("hello".equals(type)) {
                sendPong(player, in.readUTF());
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
