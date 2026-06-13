package com.minesight.client.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Raw-bytes custom payload on the {@code minesight:farm} channel. Carrying an
 * opaque byte[] lets the Fabric client and the Paper/Folia plugin (which uses
 * classic plugin messaging) share one wire format - both read/write the bytes
 * with DataInput/DataOutput.
 */
public record FarmPayload(byte[] data) implements CustomPayload {

    public static final CustomPayload.Id<FarmPayload> ID =
            new CustomPayload.Id<>(Identifier.of("minesight", "farm"));

    public static final PacketCodec<PacketByteBuf, FarmPayload> CODEC = PacketCodec.of(
            (value, buf) -> buf.writeBytes(value.data),
            buf -> {
                byte[] b = new byte[buf.readableBytes()];
                buf.readBytes(b);
                return new FarmPayload(b);
            });

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
