package com.minesight.client.nav.skill;

/** Outcome of a {@link Skill} tick. */
public enum Status {
    /** Still working - tick again next time. */
    RUNNING,
    /** Goal achieved. */
    SUCCESS,
    /** Couldn't achieve the goal (no path, timed out, boxed in, …). */
    FAILURE
}
