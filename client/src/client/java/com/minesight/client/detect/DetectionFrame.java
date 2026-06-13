package com.minesight.client.detect;

import java.util.List;

/**
 * A full {@code detections} message from the Python engine. Field names match the
 * JSON protocol for direct Gson mapping.
 */
public class DetectionFrame {
    public String type;
    public List<Detection> objects;
    public int frame_w;
    public int frame_h;

    /** Set locally when the message arrives; used for staleness checks. */
    public transient long receivedAt;
}
