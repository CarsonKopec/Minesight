package com.minesight.farm;

import com.google.gson.JsonObject;

/**
 * Server-side mirror of the client {@code AgentParams} / the Python genome - the
 * knobs the auto-tuner searches. Field names + defaults match exactly, so a
 * candidate's {@code values} map (from ask.json) drops straight in and a vector
 * tuned by the bots transfers verbatim to the real client.
 */
public final class BotParams {

    public double mineCost = 5.0;
    public double placeCost = 6.0;
    public double lavaNearCost = 8.0;
    public int maxDrop = 3;
    public double waypointRadius = 0.6;
    public int stuckWindow = 40;
    public double stuckMinMove = 0.35;
    public double sprintDist = 1.5;
    public int exploreStep = 5;
    public int mineTimeout = 200;
    public int repath = 20;
    public double reachDist = 4.5;
    public double rareWeight = 2.0;

    public double reachSq() {
        return reachDist * reachDist;
    }

    public static BotParams defaults() {
        return new BotParams();
    }

    public static BotParams fromJson(JsonObject v) {
        BotParams p = new BotParams();
        if (v == null) {
            return p;
        }
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
        return v.has(key) && v.get(key).isJsonPrimitive() ? v.get(key).getAsDouble() : fallback;
    }

    private static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}
