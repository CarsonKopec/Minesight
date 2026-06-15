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
    public static final String ARENA_REQUEST = "arena_request";
    public static final String ARENA_READY = "arena_ready";
    public static final String EPISODE_END = "episode_end";

    /** An ore the plugin wants photographed, as a world-space block AABB. */
    public record OreBox(String label, int minX, int minY, int minZ,
                         int maxX, int maxY, int maxZ) {
    }

    /** A ground-truth ore block in a training arena (exact position + label). */
    public record GroundTruthOre(String label, int x, int y, int z) {
    }

    /**
     * plugin -> client: your training arena is reset and you're standing in it.
     * Seed memory with {@code ores} and run an episode from {@code spawn}.
     */
    public record ArenaReady(int arenaId, double sx, double sy, double sz, float yaw,
                             List<GroundTruthOre> ores) {
    }

    /**
     * plugin -> client: "stand where you are and photograph these ores".
     * {@code saveEmpty} = a hard-negative shot: save the frame even with no ore
     * boxes (empty label), so the model learns those look-alikes are not ore.
     */
    public record CaptureRequest(int shotId, boolean hideHud, boolean saveEmpty, List<OreBox> boxes) {
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

    /** client -> plugin: "assign + reset my training arena and drop me in". */
    public static byte[] arenaRequest(String clientId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream d = new DataOutputStream(out)) {
            d.writeUTF(ARENA_REQUEST);
            d.writeUTF(clientId);
        } catch (IOException ignored) {
        }
        return out.toByteArray();
    }

    /** client -> plugin: "episode finished in this arena". */
    public static byte[] episodeEnd(int arenaId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream d = new DataOutputStream(out)) {
            d.writeUTF(EPISODE_END);
            d.writeInt(arenaId);
        } catch (IOException ignored) {
        }
        return out.toByteArray();
    }

    /** Read an {@code arena_ready} body (the type tag has already been consumed). */
    public static ArenaReady readArenaReadyBody(DataInputStream in) throws IOException {
        int arenaId = in.readInt();
        double sx = in.readDouble();
        double sy = in.readDouble();
        double sz = in.readDouble();
        float yaw = in.readFloat();
        int n = in.readInt();
        List<GroundTruthOre> ores = new ArrayList<>(Math.max(0, n));
        for (int i = 0; i < n; i++) {
            String label = in.readUTF();
            int x = in.readInt();
            int y = in.readInt();
            int z = in.readInt();
            ores.add(new GroundTruthOre(label, x, y, z));
        }
        return new ArenaReady(arenaId, sx, sy, sz, yaw, ores);
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
        boolean saveEmpty = in.readBoolean();
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
        return new CaptureRequest(shotId, hideHud, saveEmpty, boxes);
    }

    /** Convenience: open a {@link DataInputStream} over a payload. */
    public static DataInputStream reader(byte[] data) {
        return new DataInputStream(new ByteArrayInputStream(data));
    }
}
