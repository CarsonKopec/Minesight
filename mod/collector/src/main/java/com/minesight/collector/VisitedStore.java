package com.minesight.collector;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.util.BlockPos;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Persistent, per-world memory of ore blocks that already appear in saved
 * captures. Lets sessions (including future ones in the same world) skip
 * re-photographing the same veins, which would create near-duplicate images.
 */
public class VisitedStore {
    private static final Gson GSON = new Gson();
    private static final Type LIST_OF_LONGS = new TypeToken<List<Long>>() {
    }.getType();

    private final Set<Long> captured = new HashSet<Long>();
    private String worldFolder;
    private boolean dirty;

    private static File fileFor(String worldFolder) {
        File dir = new File(Minecraft.getMinecraft().mcDataDir, "minesight");
        dir.mkdirs();
        return new File(dir, "visited_" + worldFolder + ".json");
    }

    /** Loads history for a world; no-op if that world is already loaded. */
    public void load(String worldFolder) {
        if (worldFolder.equals(this.worldFolder)) return;
        saveIfDirty();
        captured.clear();
        this.worldFolder = worldFolder;
        File file = fileFor(worldFolder);
        if (!file.isFile()) return;
        try {
            FileReader reader = new FileReader(file);
            try {
                List<Long> longs = GSON.fromJson(reader, LIST_OF_LONGS);
                if (longs != null) captured.addAll(longs);
            } finally {
                reader.close();
            }
        } catch (Exception ignored) {
            // Corrupt history just means some duplicates - not worth crashing over.
        }
    }

    public boolean contains(BlockPos pos) {
        return captured.contains(pos.toLong());
    }

    public void add(BlockPos pos) {
        if (captured.add(pos.toLong())) dirty = true;
    }

    public int size() {
        return captured.size();
    }

    public void saveIfDirty() {
        if (!dirty || worldFolder == null) return;
        try {
            FileWriter writer = new FileWriter(fileFor(worldFolder));
            try {
                GSON.toJson(captured, writer);
            } finally {
                writer.close();
            }
            dirty = false;
        } catch (Exception ignored) {
        }
    }

    /** Forget everything for the current world and delete the file. */
    public int clear() {
        int n = captured.size();
        captured.clear();
        dirty = false;
        if (worldFolder != null) {
            fileFor(worldFolder).delete();
        }
        return n;
    }
}
