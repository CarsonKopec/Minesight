package com.minesight.client.nav;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The tunable surface of the autonomous miner - the knobs the CMA-ES auto-tuner
 * (engine: {@code minesight.evolve}) searches over. Field names and defaults
 * mirror the Python genome spec exactly, so a candidate's {@code values} map
 * drops straight in; defaults reproduce the original hand-picked constants, so
 * an untuned agent behaves identically to before.
 *
 * <p>Plain mutable holder: {@link PathFinder}, {@link AutoWalker} and
 * {@link MiningAgent} read these instead of {@code static final} constants.
 */
public final class AgentParams {

    // -- PathFinder: route shaping --
    public double mineCost = 5.0;
    public double placeCost = 6.0;
    public double lavaNearCost = 8.0;
    public int maxDrop = 3;

    // -- AutoWalker: movement feel --
    public double waypointRadius = 0.6;
    public int stuckWindow = 40;
    public double stuckMinMove = 0.35;
    public double sprintDist = 1.5;

    // -- MiningAgent: FSM behavior --
    public int exploreStep = 5;
    public int mineTimeout = 200;
    public int repath = 20;
    public double reachDist = 4.5;
    public double rareWeight = 2.0;

    /** In-reach test works in squared distance; derive it from reachDist. */
    public double reachSq() {
        return reachDist * reachDist;
    }

    public static AgentParams defaults() {
        return new AgentParams();
    }

    /**
     * Overlay a candidate's values (a {name: number} map from ask.json) onto a
     * fresh defaults object. Missing keys keep their default; each value is
     * clamped to the genome's bounds so a bad candidate can't wedge the agent.
     */
    public static AgentParams fromJson(JsonObject v) {
        AgentParams p = new AgentParams();
        p.mineCost = clamp(num(v, "mine_cost", p.mineCost), 1.0, 12.0);
        p.placeCost = clamp(num(v, "place_cost", p.placeCost), 1.0, 15.0);
        p.lavaNearCost = clamp(num(v, "lava_near_cost", p.lavaNearCost), 0.0, 20.0);
        p.maxDrop = (int) Math.round(clamp(num(v, "max_drop", p.maxDrop), 1.0, 5.0));
        p.waypointRadius = clamp(num(v, "waypoint_radius", p.waypointRadius), 0.3, 1.2);
        p.stuckWindow = (int) Math.round(clamp(num(v, "stuck_window", p.stuckWindow), 20.0, 80.0));
        p.stuckMinMove = clamp(num(v, "stuck_min_move", p.stuckMinMove), 0.1, 1.0);
        p.sprintDist = clamp(num(v, "sprint_dist", p.sprintDist), 0.8, 3.0);
        p.exploreStep = (int) Math.round(clamp(num(v, "explore_step", p.exploreStep), 2.0, 12.0));
        p.mineTimeout = (int) Math.round(clamp(num(v, "mine_timeout", p.mineTimeout), 60.0, 400.0));
        p.repath = (int) Math.round(clamp(num(v, "repath", p.repath), 5.0, 60.0));
        p.reachDist = clamp(num(v, "reach_dist", p.reachDist), 3.0, 6.0);
        p.rareWeight = clamp(num(v, "rare_weight", p.rareWeight), 1.0, 8.0);
        return p;
    }

    /**
     * Load the tuned genome the auto-tuner exported (``best.json`` in the run
     * dir, override {@code -Dminesight.agentParams} / {@code $MINESIGHT_AGENT_PARAMS}).
     * Falls back to defaults if there's no export yet. Accepts either a flat
     * {@code {name: value}} map or a {@code {"values": {...}}} wrapper.
     */
    public static AgentParams load() {
        Path path = paramsPath();
        if (path == null || !Files.exists(path)) {
            return defaults();
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            JsonObject o = JsonParser.parseString(text).getAsJsonObject();
            JsonObject values = o.has("values") && o.get("values").isJsonObject()
                    ? o.getAsJsonObject("values") : o;
            return fromJson(values);
        } catch (Exception e) {
            return defaults();
        }
    }

    /** True if a tuned export exists to load (vs. falling back to defaults). */
    public static boolean hasExport() {
        Path path = paramsPath();
        return path != null && Files.exists(path);
    }

    /** Copy every field from another params object (in-place hot-reload). */
    public void copyFrom(AgentParams o) {
        mineCost = o.mineCost;
        placeCost = o.placeCost;
        lavaNearCost = o.lavaNearCost;
        maxDrop = o.maxDrop;
        waypointRadius = o.waypointRadius;
        stuckWindow = o.stuckWindow;
        stuckMinMove = o.stuckMinMove;
        sprintDist = o.sprintDist;
        exploreStep = o.exploreStep;
        mineTimeout = o.mineTimeout;
        repath = o.repath;
        reachDist = o.reachDist;
        rareWeight = o.rareWeight;
    }

    private static Path paramsPath() {
        String override = System.getProperty("minesight.agentParams");
        if (override == null) {
            override = System.getenv("MINESIGHT_AGENT_PARAMS");
        }
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        String dir = System.getProperty("minesight.trainDir");
        if (dir == null) {
            dir = System.getenv("MINESIGHT_TRAIN_DIR");
        }
        Path base = dir != null && !dir.isBlank()
                ? Paths.get(dir)
                : Paths.get(System.getProperty("user.home"), ".minesight", "train");
        return base.resolve("best.json");
    }

    private static double num(JsonObject v, String key, double fallback) {
        return v != null && v.has(key) && v.get(key).isJsonPrimitive()
                ? v.get(key).getAsDouble() : fallback;
    }

    private static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}
