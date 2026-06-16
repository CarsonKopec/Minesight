package com.minesight.farm;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * A fake {@link ServerPlayer} that the <i>server</i> moves itself.
 *
 * <p>A normal ServerPlayer's movement is client-authoritative: the server
 * interpolates its position from the client's move packets and ignores the
 * server-side movement input. A clientless bot therefore never moves no matter
 * what impulse we set.
 *
 * <p>The (final) {@code isLocalInstanceAuthoritative()} returns
 * {@code !isClientAuthoritative()} on the server, and {@code Player} hardcodes
 * {@code isClientAuthoritative() == true}. Overriding it to {@code false} flips
 * this bot to server-authoritative, so its tick runs real movement physics
 * ({@code aiStep -> travel}) from the input {@link NmsBot} sets - and the bot
 * actually walks, jumps, falls, and collides like a real player.
 */
public final class BotServerPlayer extends ServerPlayer {

    public BotServerPlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile, ClientInformation.createDefault());
    }

    @Override
    public boolean isClientAuthoritative() {
        return false;
    }
}
