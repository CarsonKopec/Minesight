package com.minesight.client.detect;

import java.util.LinkedHashMap;
import java.util.Map;

/** Y-level -> likely ores hint for the radar's depth advisor (1.21 bands). */
public final class Depths {

    private static final Map<String, int[]> Y_BANDS = new LinkedHashMap<>();

    static {
        Y_BANDS.put("coal", new int[]{0, 192});
        Y_BANDS.put("iron", new int[]{-24, 72});
        Y_BANDS.put("copper", new int[]{-16, 112});
        Y_BANDS.put("gold", new int[]{-64, 32});
        Y_BANDS.put("redstone", new int[]{-64, 15});
        Y_BANDS.put("emerald", new int[]{-16, 256});
        Y_BANDS.put("lapis", new int[]{-64, 64});
        Y_BANDS.put("diamond", new int[]{-64, 16});
    }

    private Depths() {
    }

    /** Comma-separated ores whose spawn band includes this Y. */
    public static String hint(int y) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, int[]> e : Y_BANDS.entrySet()) {
            int[] band = e.getValue();
            if (y >= band[0] && y <= band[1]) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(e.getKey());
            }
        }
        return sb.toString();
    }
}
