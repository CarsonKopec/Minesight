package com.minesight.farm;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
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

    // Scripted = teleport-along-path (stable + verified). Physics = real impulse
    // movement (experimental). Selected per run from the GUI / command.
    private final boolean physics;

    private ServerPlayer player;
    private double lastX, lastZ;
    private int stuckTicks;
    private boolean stuck;
    private boolean jumpMove;

    public NmsBot(JavaPlugin plugin, ArenaManager.Arena arena, BotParams params, int budget,
                  boolean physics) {
        super(plugin, arena, params, budget);
        this.physics = physics;
    }

    @Override
    public void spawn(String name) {
        MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
        ServerLevel level = ((CraftWorld) world).getHandle();
        GameProfile profile = new GameProfile(UUID.randomUUID(), name);
        // Plain ServerPlayer (client-authoritative) for scripted movement - it
        // stays connected. Only physics mode needs the server-authoritative
        // BotServerPlayer, which trades stability for real walking.
        ServerPlayer p = physics
                ? new BotServerPlayer(server, level, profile)
                : new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        p.setPos(pos.x() + 0.5, pos.y(), pos.z() + 0.5);
        p.setGlowingTag(true);   // glowing outline - watch the bot through walls

        // A fake connection with an embedded (loopback) channel so the player
        // can be "placed" without a real client behind it. We auto-answer the
        // server's keep-alive packets (a clientless player would otherwise be
        // timed out and disconnected), which is what was causing the bot to
        // vanish and the training loop to re-spawn it (join/leave churn).
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        channel.pipeline().addFirst("minesight_keepalive", new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
                    throws Exception {
                if (msg instanceof ClientboundKeepAlivePacket ka) {
                    channel.writeInbound(new ServerboundKeepAlivePacket(ka.getId()));
                }
                super.write(ctx, msg, promise);
            }
        });
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
    protected void startMove(BotPos target) {
        stuck = false;
        stuckTicks = 0;
        // A waypoint ~2 cells away (parkour leap) needs a running jump.
        jumpMove = Math.hypot(target.x() - pos.x(), target.z() - pos.z()) > 1.5;
        if (player != null) {
            lastX = player.getX();
            lastZ = player.getZ();
        }
    }

    @Override
    protected boolean tickMove(BotPos target) {
        if (player == null) {
            return true;
        }
        if (!physics) {
            moveBody(target);     // scripted teleport (verified-working fallback)
            return true;
        }
        // Real movement: face the waypoint, hold forward, jump to step up - and
        // let the server's player physics carry the body (collision, gravity).
        double px = player.getX(), py = player.getY(), pz = player.getZ();
        double dx = target.x() + 0.5 - px;
        double dz = target.z() + 0.5 - pz;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz < params.waypointRadius && Math.abs(py - target.y()) < 1.2) {
            stopInputs();
            return true;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(0.0f);
        player.zza = 1.0f;                                   // forward impulse
        player.setSprinting(pos.dist(goal) > params.sprintDist);
        boolean stepUp = target.y() > Math.floor(py) + 0.01;
        player.setJumping(stepUp || player.horizontalCollision || jumpMove);
        if (jumpMove) {
            player.setSprinting(true);   // a running jump clears the gap
        }
        // Stuck detection - the movement-feel knobs the sim/zombie can't tune.
        if (++stuckTicks >= params.stuckWindow) {
            double moved = Math.hypot(px - lastX, pz - lastZ);
            lastX = px;
            lastZ = pz;
            stuckTicks = 0;
            if (moved < params.stuckMinMove) {
                stopInputs();
                stuck = true;
            }
        }
        return false;
    }

    @Override
    protected boolean moveStuck() {
        return stuck;
    }

    private void stopInputs() {
        if (player != null) {
            player.zza = 0.0f;
            player.xxa = 0.0f;
            player.setJumping(false);
            player.setSprinting(false);
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
