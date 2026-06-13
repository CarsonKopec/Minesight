package com.minesight.farm;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Per-world memory of every ore block already photographed, so the farm never
 * captures the same vein twice (the 2.0 successor to the 1.8.9 visited file).
 * Survives restarts; persisted to {@code <dataFolder>/visited_<world>.txt} as
 * one {@code x,y,z} per line.
 *
 * <p>Thread-safe: the capture session reads/writes from the global-region tick
 * while {@link #load}/{@link #clear} may run from a player-region or GUI task.
 */
final class VisitedStore {

    private final Plugin plugin;
    private final Set<String> visited = Collections.synchronizedSet(new HashSet<>());
    private volatile String worldName;
    private volatile boolean dirty;

    VisitedStore(Plugin plugin) {
        this.plugin = plugin;
    }

    static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    /** Switch to (and lazily load) a world's history; no-op if unchanged. */
    synchronized void load(String world) {
        if (world == null || world.equals(worldName)) {
            return;
        }
        save();
        visited.clear();
        worldName = world;
        File f = fileFor(world);
        if (!f.isFile()) {
            return;
        }
        try {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (!t.isEmpty()) {
                    visited.add(t);
                }
            }
        } catch (Exception ignored) {
        }
    }

    boolean contains(String key) {
        return visited.contains(key);
    }

    void add(String key) {
        if (visited.add(key)) {
            dirty = true;
        }
    }

    int size() {
        return visited.size();
    }

    synchronized void clear() {
        visited.clear();
        dirty = true;
        save();
    }

    /** Persist if there are unsaved changes. Cheap to call periodically. */
    synchronized void save() {
        if (!dirty || worldName == null) {
            return;
        }
        try {
            File f = fileFor(worldName);
            File parent = f.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            StringBuilder sb = new StringBuilder();
            synchronized (visited) {
                for (String k : visited) {
                    sb.append(k).append('\n');
                }
            }
            Files.writeString(f.toPath(), sb.toString(), StandardCharsets.UTF_8);
            dirty = false;
        } catch (Exception ignored) {
        }
    }

    private File fileFor(String world) {
        return new File(plugin.getDataFolder(),
                "visited_" + world.replaceAll("[^A-Za-z0-9._-]", "_") + ".txt");
    }
}
