package com.minesight;

/**
 * Hands the latest detection frame from the WebSocket thread to the render
 * thread. The mod holds the last known state between updates (inference may
 * run slower than the game renders), but drops it once it goes stale.
 *
 * A process-wide singleton so separate feature mods share one instance: the
 * detection mod populates it; the world mod reads it for marker anchoring.
 */
public class DetectionStore {
    /** Frames older than this are not rendered (backend stopped or stalled). */
    private static final long STALE_MS = 2000;

    private static final DetectionStore INSTANCE = new DetectionStore();

    public static DetectionStore getInstance() {
        return INSTANCE;
    }

    private volatile DetectionFrame latest;

    public void update(DetectionFrame frame) {
        if (frame == null || frame.objects == null) return;
        frame.receivedAt = System.currentTimeMillis();
        latest = frame;
    }

    /** The latest frame, or null if nothing fresh enough to draw. */
    public DetectionFrame getFresh() {
        DetectionFrame f = latest;
        if (f == null) return null;
        if (System.currentTimeMillis() - f.receivedAt > STALE_MS) return null;
        return f;
    }
}
