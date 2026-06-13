package com.minesight;

/** What MineSight draws; cycled in-game with the overlay keybind (F8). */
public enum OverlayMode {
    BOTH("2D boxes + 3D markers"),
    WORLD_ONLY("3D markers only"),
    HUD_ONLY("2D boxes only"),
    OFF("off");

    public final String label;

    OverlayMode(String label) {
        this.label = label;
    }

    private static OverlayMode current = BOTH;

    public static OverlayMode get() {
        return current;
    }

    public static OverlayMode cycle() {
        current = values()[(current.ordinal() + 1) % values().length];
        return current;
    }

    public boolean hud() {
        return this == BOTH || this == HUD_ONLY;
    }

    public boolean world() {
        return this == BOTH || this == WORLD_ONLY;
    }
}
