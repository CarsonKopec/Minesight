package com.minesight.client.detect;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persistent, per-world memory of every ore the model has seen - the 1.21 port of
 * the 1.8.9 {@code OreMemory}. Positions come from raycasting detections into the
 * world ({@link DetectionAnchor}), so a node is an exact block with the detector's
 * label. Survives looking away and restarting; forgets mined blocks.
 *
 * <p>All access is on the client (main) thread (anchor tick + HUD render), so no
 * locking is needed.
 */
public final class OreMemory {

    public static final class Node {
        public final BlockPos pos;
        public String label;
        public float confidence;
        public long firstSeen;
        public long lastSeen;

        Node(BlockPos pos) {
            this.pos = pos;
        }
    }

    /** A connected group of same-label nodes - an ore vein. */
    public static final class Cluster {
        public double x, y, z;
        public String label;
        public int count;
        public long lastSeen;
    }

    private static final int MAX_NODES = 4000;
    private static final long SAVE_EVERY_MS = 30_000;
    private static final Gson GSON = new Gson();
    private static final Type SAVED_LIST = new TypeToken<List<Saved>>() {
    }.getType();

    private static final class Saved {
        long p;
        String l;
        float c;
        long f;
        long s;
    }

    private final Map<Long, Node> nodes = new HashMap<>();
    private String worldKey;
    private boolean dirty;
    private long lastSave;

    private static File fileFor(String key) {
        File dir = new File(MinecraftClient.getInstance().runDirectory, "minesight");
        dir.mkdirs();
        return new File(dir, "memory_" + key + ".json");
    }

    /** Switch to (and lazily load) a world's memory; no-op if unchanged. */
    public void load(String key) {
        if (key == null || key.equals(worldKey)) {
            return;
        }
        forceSave();
        nodes.clear();
        worldKey = key;
        File file = fileFor(key);
        if (!file.isFile()) {
            return;
        }
        try (FileReader reader = new FileReader(file)) {
            List<Saved> saved = GSON.fromJson(reader, SAVED_LIST);
            if (saved != null) {
                for (Saved s : saved) {
                    Node n = new Node(BlockPos.fromLong(s.p));
                    n.label = s.l;
                    n.confidence = s.c;
                    n.firstSeen = s.f;
                    n.lastSeen = s.s;
                    nodes.put(s.p, n);
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** Records a sighting; returns true if this block is NEW to the memory. */
    public boolean record(BlockPos pos, String label, float confidence) {
        long key = pos.asLong();
        long now = System.currentTimeMillis();
        Node n = nodes.get(key);
        boolean isNew = n == null;
        if (isNew) {
            if (nodes.size() >= MAX_NODES) {
                evictOldest();
            }
            n = new Node(pos);
            n.firstSeen = now;
            nodes.put(key, n);
        }
        n.label = label;
        n.confidence = Math.max(n.confidence, confidence);
        n.lastSeen = now;
        dirty = true;
        return isNew;
    }

    public void forget(BlockPos pos) {
        if (nodes.remove(pos.asLong()) != null) {
            dirty = true;
        }
    }

    public List<Node> snapshot() {
        return new ArrayList<>(nodes.values());
    }

    public int size() {
        return nodes.size();
    }

    private void evictOldest() {
        long oldestKey = 0;
        long oldestSeen = Long.MAX_VALUE;
        for (Map.Entry<Long, Node> e : nodes.entrySet()) {
            if (e.getValue().lastSeen < oldestSeen) {
                oldestSeen = e.getValue().lastSeen;
                oldestKey = e.getKey();
            }
        }
        nodes.remove(oldestKey);
    }

    /** Greedy flood-fill of touching same-label nodes into veins. */
    public List<Cluster> clusters(BlockPos near, int radius) {
        long r2 = (long) radius * radius;
        Map<Long, Node> inRange = new HashMap<>();
        for (Node n : nodes.values()) {
            if (sqDist(n.pos, near) <= r2) {
                inRange.put(n.pos.asLong(), n);
            }
        }
        List<Cluster> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Map.Entry<Long, Node> e : inRange.entrySet()) {
            if (seen.contains(e.getKey())) {
                continue;
            }
            Node start = e.getValue();
            List<Node> members = new ArrayList<>();
            List<Node> queue = new ArrayList<>();
            queue.add(start);
            seen.add(e.getKey());
            while (!queue.isEmpty()) {
                Node cur = queue.remove(queue.size() - 1);
                members.add(cur);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            long key = cur.pos.add(dx, dy, dz).asLong();
                            Node neighbor = inRange.get(key);
                            if (neighbor != null && !seen.contains(key)
                                    && neighbor.label.equals(start.label)) {
                                seen.add(key);
                                queue.add(neighbor);
                            }
                        }
                    }
                }
            }
            Cluster c = new Cluster();
            c.label = start.label;
            c.count = members.size();
            for (Node m : members) {
                c.x += m.pos.getX() + 0.5;
                c.y += m.pos.getY() + 0.5;
                c.z += m.pos.getZ() + 0.5;
                c.lastSeen = Math.max(c.lastSeen, m.lastSeen);
            }
            c.x /= members.size();
            c.y /= members.size();
            c.z /= members.size();
            out.add(c);
        }
        return out;
    }

    private static long sqDist(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX();
        long dy = a.getY() - b.getY();
        long dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    public void saveIfDirty() {
        if (dirty && worldKey != null && System.currentTimeMillis() - lastSave >= SAVE_EVERY_MS) {
            forceSave();
        }
    }

    public void forceSave() {
        if (!dirty || worldKey == null) {
            return;
        }
        try (FileWriter writer = new FileWriter(fileFor(worldKey))) {
            List<Saved> out = new ArrayList<>(nodes.size());
            for (Node n : nodes.values()) {
                Saved s = new Saved();
                s.p = n.pos.asLong();
                s.l = n.label;
                s.c = n.confidence;
                s.f = n.firstSeen;
                s.s = n.lastSeen;
                out.add(s);
            }
            GSON.toJson(out, SAVED_LIST, writer);
            dirty = false;
            lastSave = System.currentTimeMillis();
        } catch (Exception ignored) {
        }
    }
}
