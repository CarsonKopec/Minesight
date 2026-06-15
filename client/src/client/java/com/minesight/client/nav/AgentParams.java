package com.minesight.client.nav;

import com.google.gson.JsonObject;

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

    private static double num(JsonObject v, String key, double fallback) {
        return v != null && v.has(key) && v.get(key).isJsonPrimitive()
                ? v.get(key).getAsDouble() : fallback;
    }

    private static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}
