package com.minesight.collector;

import java.nio.ByteBuffer;

/**
 * Cheap framebuffer sanity check before saving a capture. Catches frames where
 * the chunk renderer hasn't caught up with a teleport yet (void/black), and
 * frames too dark or featureless to be useful training data.
 */
public final class FrameQuality {
    private static final int STRIDE = 997;          // prime, ~2k samples at 1080p
    private static final int BLACK_LUMA = 6;        // below this a pixel counts as black
    private static final double MAX_BLACK_FRAC = 0.92;
    private static final double MIN_LUMA_STDDEV = 2.5;

    private FrameQuality() {
    }

    /** Null if the frame looks fine, otherwise a short reason. */
    public static String evaluate(ByteBuffer rgba, int width, int height) {
        int pixels = width * height;
        int samples = 0;
        int black = 0;
        double sum = 0;
        double sumSq = 0;
        for (int p = 0; p < pixels; p += STRIDE) {
            int i = p * 4;
            int r = rgba.get(i) & 0xFF;
            int g = rgba.get(i + 1) & 0xFF;
            int b = rgba.get(i + 2) & 0xFF;
            double luma = 0.299 * r + 0.587 * g + 0.114 * b;
            if (luma < BLACK_LUMA) black++;
            sum += luma;
            sumSq += luma * luma;
            samples++;
        }
        if (samples == 0) return "empty frame";
        double blackFrac = black / (double) samples;
        if (blackFrac > MAX_BLACK_FRAC) {
            return "frame is " + Math.round(blackFrac * 100) + "% black (unrendered or pitch dark)";
        }
        double mean = sum / samples;
        double std = Math.sqrt(Math.max(0, sumSq / samples - mean * mean));
        if (std < MIN_LUMA_STDDEV) {
            return "frame is featureless (uniform color)";
        }
        return null;
    }
}
