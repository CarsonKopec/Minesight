package com.minesight.client.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Wire format for the {@code minesight:farm} channel, shared in spirit with the
 * Folia plugin (which mirrors this with classic plugin messaging). Every message
 * is a UTF type tag followed by a type-specific body, all via
 * {@link java.io.DataInput}/{@link java.io.DataOutput}.
 */
public final class FarmProtocol {

    public static final String HELLO = "hello";
    public static final String PONG = "pong";
    public static final String CAPTURE = "capture";
    public static final String CAPTURED = "captured";

    /** An ore the plugin wants photographed, as a world-space block AABB. */
    public record OreBox(String label, int minX, int minY, int minZ,
                         int maxX, int maxY, int maxZ) {
    }

    /** plugin -> client: "stand where you are and photograph these ores". */
    public record CaptureRequest(int shotId, boolean hideHud, List<OreBox> boxes) {
    }

    private FarmProtocol() {
    }

    /** client -> plugin greeting. */
    public static byte[] hello(String clientId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream d = new DataOutputStream(out)) {
            d.writeUTF(HELLO);
            d.writeUTF(clientId);
        } catch (IOException ignored) {
        }
        return out.toByteArray();
    }

    /** client -> plugin capture acknowledgement. */
    public static byte[] captured(int shotId, boolean ok, int boxesVisible) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream d = new DataOutputStream(out)) {
            d.writeUTF(CAPTURED);
            d.writeInt(shotId);
            d.writeBoolean(ok);
            d.writeInt(boxesVisible);
        } catch (IOException ignored) {
        }
        return out.toByteArray();
    }

    /** Read a {@code capture} body (the type tag has already been consumed). */
    public static CaptureRequest readCaptureBody(DataInputStream in) throws IOException {
        int shotId = in.readInt();
        boolean hideHud = in.readBoolean();
        int n = in.readInt();
        List<OreBox> boxes = new ArrayList<>(Math.max(0, n));
        for (int i = 0; i < n; i++) {
            String label = in.readUTF();
            int minX = in.readInt();
            int minY = in.readInt();
            int minZ = in.readInt();
            int maxX = in.readInt();
            int maxY = in.readInt();
            int maxZ = in.readInt();
            boxes.add(new OreBox(label, minX, minY, minZ, maxX, maxY, maxZ));
        }
        return new CaptureRequest(shotId, hideHud, boxes);
    }

    /** Convenience: open a {@link DataInputStream} over a payload. */
    public static DataInputStream reader(byte[] data) {
        return new DataInputStream(new ByteArrayInputStream(data));
    }
}
