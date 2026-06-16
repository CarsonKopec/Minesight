package com.minesight.client.nav.skill;

/**
 * A goal-directed unit of agent behavior - the "verb" the agent (or a future
 * planner) can ask for: mine the nearest ore, go to a block, explore, and later
 * chop a tree, flee, fight. A skill runs over many ticks and reports when it's
 * done.
 *
 * <p>Skills compose the low-level movement/dig primitives ({@link
 * com.minesight.client.nav.PathFinder} + {@link
 * com.minesight.client.nav.AutoWalker}); they don't reimplement them. Adding a
 * new capability is "write a new Skill", not "edit the FSM".
 */
public interface Skill {

    /** Short human/log name, e.g. {@code "mine-ore"} or {@code "goto 12,64,-3"}. */
    String name();

    /** Begin (or restart) the skill. */
    default void start() {
    }

    /** Advance one tick; returns whether the skill is still running, done, or failed. */
    Status tick();

    /** Release any held inputs / stop the skill early. */
    default void stop() {
    }
}
