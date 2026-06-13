package com.minesight.world;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.util.BlockPos;

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
 * Phase 3: persistent, per-world memory of every ore the MODEL has seen.
 * Positions come from raycasting detections into the world (Phase 4), so a
 * node is an exact block position with the detector's label and confidence.
 * Survives looking away, quitting, and restarting; forgets mined blocks.
 */
public class OreMemory {
    public static class Node {
        public final BlockPos pos;
        public String label;
        public float confidence;
        public long firstSeen;
        public long lastSeen;

        Node(BlockPos pos) {
            this.pos = pos;
        }
    }

    /** A connected group of same-label nodes - an ore vein (Phase 3 clustering). */
    public static class Cluster {
        public double x, y, z;
        public String label;
        public int count;
        public long lastSeen;
    }

    private static final int MAX_NODES = 4000;
    private static final long SAVE_EVERY_MS = 30000;
    private static final Gson GSON = new Gson();
    private static final Type SAVED_LIST = new TypeToken<List<Saved>>() {
    }.getType();

    private static class Saved {
        long p;
        String l;
        float c;
        long f;
        long s;
    }

    private final Map<Long, Node> nodes = new HashMap<Long, Node>();
    private String worldFolder;
    private boolean dirty;
    private long lastSave;

    private static File fileFor(String worldFolder) {
        File dir = new File(Minecraft.getMinecraft().mcDataDir, "minesight");
        dir.mkdirs();
        return new File(dir, "memory_" + worldFolder + ".json");
    }

    /** Switch to (and lazily load) the memory of a world; no-op if unchanged. */
    public void load(String worldFolder) {
        if (worldFolder.equals(this.worldFolder)) return;
        saveIfDirty();
        nodes.clear();
        this.worldFolder = worldFolder;
        File file = fileFor(worldFolder);
        if (!file.isFile()) return;
        try {
            FileReader reader = new FileReader(file);
            try {
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
            } finally {
                reader.close();
            }
        } catch (Exception ignored) {
        }
    }

    /** Records a sighting; returns true if this block is NEW to the memory. */
    public boolean record(BlockPos pos, String label, float confidence) {
        long key = pos.toLong();
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
        if (nodes.remove(pos.toLong()) != null) {
            dirty = true;
        }
    }

    public List<Node> snapshot() {
        return new ArrayList<Node>(nodes.values());
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
        Map<Long, Node> inRange = new HashMap<Long, Node>();
        for (Node n : nodes.values()) {
            if (n.pos.distanceSq(near) <= radius * radius) {
                inRange.put(n.pos.toLong(), n);
            }
        }
        List<Cluster> out = new ArrayList<Cluster>();
        Set<Long> seen = new HashSet<Long>();
        for (Map.Entry<Long, Node> e : inRange.entrySet()) {
            if (seen.contains(e.getKey())) continue;
            Node start = e.getValue();
            List<Node> members = new ArrayList<Node>();
            List<Node> queue = new ArrayList<Node>();
            queue.add(start);
            seen.add(e.getKey());
            while (!queue.isEmpty()) {
                Node cur = queue.remove(queue.size() - 1);
                members.add(cur);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            long key = cur.pos.add(dx, dy, dz).toLong();
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

    public void saveIfDirty() {
        if (!dirty || worldFolder == null) return;
        if (System.currentTimeMillis() - lastSave < SAVE_EVERY_MS) return;
        forceSave();
    }

    public void forceSave() {
        if (!dirty || worldFolder == null) return;
        try {
            List<Saved> out = new ArrayList<Saved>(nodes.size());
            for (Node n : nodes.values()) {
                Saved s = new Saved();
                s.p = n.pos.toLong();
                s.l = n.label;
                s.c = n.confidence;
                s.f = n.firstSeen;
                s.s = n.lastSeen;
                out.add(s);
            }
            FileWriter writer = new FileWriter(fileFor(worldFolder));
            try {
                GSON.toJson(out, SAVED_LIST, writer);
            } finally {
                writer.close();
            }
            dirty = false;
            lastSave = System.currentTimeMillis();
        } catch (Exception ignored) {
        }
    }
}
