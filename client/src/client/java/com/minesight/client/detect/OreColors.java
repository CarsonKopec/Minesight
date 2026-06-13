package com.minesight.client.detect;

import java.util.LinkedHashMap;
import java.util.Map;

/** Shared label -> ARGB color mapping for HUD boxes and world markers. */
public final class OreColors {
    private static final Map<String, Integer> LABEL_COLORS = new LinkedHashMap<>();
    private static final int DEFAULT_COLOR = 0xFFFFFFFF;

    static {
        LABEL_COLORS.put("diamond", 0xFF4AEDD9);
        LABEL_COLORS.put("emerald", 0xFF2ECC40);
        LABEL_COLORS.put("gold", 0xFFFFD700);
        LABEL_COLORS.put("iron", 0xFFD8C8B8);
        LABEL_COLORS.put("coal", 0xFF8A8A8A);
        LABEL_COLORS.put("redstone", 0xFFFF4136);
        LABEL_COLORS.put("lapis", 0xFF3D5AFE);
        LABEL_COLORS.put("copper", 0xFFE07B4F);
        LABEL_COLORS.put("quartz", 0xFFEFE6DC);
    }

    private OreColors() {
    }

    public static int colorFor(String label) {
        if (label == null) {
            return DEFAULT_COLOR;
        }
        String lower = label.toLowerCase();
        for (Map.Entry<String, Integer> e : LABEL_COLORS.entrySet()) {
            if (lower.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return DEFAULT_COLOR;
    }

    /** Rare ores get priority treatment (alerts, brighter markers). */
    public static boolean isRare(String label) {
        if (label == null) {
            return false;
        }
        String lower = label.toLowerCase();
        return lower.contains("diamond") || lower.contains("emerald");
    }
}
