package com.minesight.collector;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Configuration and progress of one dataset-collection session (from collect_start). */
public class CollectSession {
    public File outputDir;
    public int target = 200;
    public int radius = 300;
    public int yMin = 5;
    public int yMax = 62;
    public float gammaMin = 0.0f;
    public float gammaMax = 1.5f;
    public int fovMin = 70;
    public int fovMax = 110;
    public double negativeRatio = 0.0;  // 0 = never save frames without boxes
    /** Minimum ticks to wait after a teleport so the chunk renderer catches up. */
    public int settleTicks = 40;
    /** Skip ores already captured in this world (persistent history). */
    public boolean avoidRevisits = true;
    public List<String> classes = new ArrayList<String>();
    /**
     * Optional per-class minimum BOX counts ("collect until gold >= 200").
     * When non-empty, the session runs until every listed class is satisfied
     * (the image target still acts as a hard cap).
     */
    public Map<String, Integer> classTargets = new HashMap<String, Integer>();
    /**
     * True for clients on a DIFFERENT machine than the Control Panel: images
     * are streamed over the WebSocket instead of written to outputDir (which
     * only exists on the host). Fixed at session start.
     */
    public boolean upload;

    public int saved;

    public static CollectSession fromJson(JsonObject o) {
        CollectSession s = new CollectSession();
        s.outputDir = new File(o.get("output_dir").getAsString());
        if (o.has("upload")) s.upload = o.get("upload").getAsBoolean();
        if (o.has("classes")) {
            JsonArray arr = o.getAsJsonArray("classes");
            for (int i = 0; i < arr.size(); i++) {
                s.classes.add(arr.get(i).getAsString());
            }
        }
        s.applyUpdate(o);
        return s;
    }

    /**
     * Applies tunable parameters from a collect_start or collect_update
     * message. Classes and output dir are deliberately NOT updatable mid-run:
     * label indices and file destinations must stay fixed within a session.
     */
    public void applyUpdate(JsonObject o) {
        if (o.has("target")) target = o.get("target").getAsInt();
        if (o.has("radius")) radius = o.get("radius").getAsInt();
        if (o.has("y_min")) yMin = o.get("y_min").getAsInt();
        if (o.has("y_max")) yMax = o.get("y_max").getAsInt();
        if (o.has("gamma_min")) gammaMin = o.get("gamma_min").getAsFloat();
        if (o.has("gamma_max")) gammaMax = o.get("gamma_max").getAsFloat();
        if (o.has("fov_min")) fovMin = o.get("fov_min").getAsInt();
        if (o.has("fov_max")) fovMax = o.get("fov_max").getAsInt();
        if (o.has("negative_ratio")) negativeRatio = o.get("negative_ratio").getAsDouble();
        if (o.has("settle_ticks")) settleTicks = o.get("settle_ticks").getAsInt();
        if (o.has("avoid_revisits")) avoidRevisits = o.get("avoid_revisits").getAsBoolean();
        if (o.has("class_targets")) {
            classTargets.clear();
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("class_targets").entrySet()) {
                int n = e.getValue().getAsInt();
                if (n > 0) classTargets.put(e.getKey(), n);
            }
        }
    }

    public int classIndex(String label) {
        return classes.indexOf(label);
    }

    public Set<String> classSet() {
        return new HashSet<String>(classes);
    }
}
