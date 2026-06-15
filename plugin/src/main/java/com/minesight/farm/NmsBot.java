package com.minesight.farm;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

/**
 * Bot body backed by a real NMS {@link ServerPlayer} (a fake player). Unlike the
 * scripted-Zombie body, this is an actual player: real vanilla mining via the
 * server gamemode, real player entity/model for spectating, and the foundation
 * for true impulse-driven physics. Version-locked to the Mojang-mapped server
 * (paperweight). Movement is still scripted here (teleport along the path); real
 * physics is the next step.
 *
 * <p>If spawning fails (NMS lifecycle is finicky), {@link BotTrainer} falls back
 * to {@link ZombieBot}.
 */
public final class NmsBot extends BotEpisode {

    private ServerPlayer player;

    public NmsBot(JavaPlugin plugin, ArenaManager.Arena arena, BotParams params, int budget) {
        super(plugin, arena, params, budget);
    }

    @Override
    public void spawn(String name) {
        MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
        ServerLevel level = ((CraftWorld) world).getHandle();
        GameProfile profile = new GameProfile(UUID.randomUUID(), name);
        ServerPlayer p = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        p.setPos(pos.x() + 0.5, pos.y(), pos.z() + 0.5);

        // A fake connection with an embedded (loopback) channel so the player
        // can be "placed" without a real client behind it.
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, p,
                CommonListenerCookie.createInitial(profile, false));

        p.setGameMode(GameType.SURVIVAL);
        p.getInventory().add(new net.minecraft.world.item.ItemStack(Items.NETHERITE_PICKAXE));
        this.player = p;
    }

    @Override
    protected void moveBody(BotPos to) {
        if (player != null) {
            player.setPos(to.x() + 0.5, to.y(), to.z() + 0.5);
        }
    }

    @Override
    protected void breakBlock(BotPos b) {
        if (player != null) {
            player.gameMode.destroyBlock(new BlockPos(b.x(), b.y(), b.z()));
        } else {
            world.getBlockAt(b.x(), b.y(), b.z()).setType(Material.AIR, false);
        }
    }

    @Override
    protected void placeBlock(BotPos b, Material m) {
        world.getBlockAt(b.x(), b.y(), b.z()).setType(m, false);
    }

    @Override
    protected void swingBody() {
        if (player != null) {
            player.swing(InteractionHand.MAIN_HAND);
        }
    }

    @Override
    public void cleanup() {
        if (player != null) {
            ServerPlayer p = player;
            player = null;
            ((CraftServer) Bukkit.getServer()).getServer().getPlayerList().remove(p);
        }
    }
}
