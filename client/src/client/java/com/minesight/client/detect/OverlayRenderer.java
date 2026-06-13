package com.minesight.client.detect;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * Draws 2D bounding boxes on the HUD for the latest detections - the 1.21 port
 * of the 1.8.9 {@code OverlayRenderer}, on {@link DrawContext} instead of the
 * old {@code Gui}/{@code GlStateManager}.
 *
 * <p>Detections arrive in capture-frame pixels (the Minecraft window the engine
 * captured); they are rescaled into the GUI's scaled coordinates so resolution
 * mismatches stay aligned.
 */
public final class OverlayRenderer {

    private final DetectionStore store;

    public OverlayRenderer(DetectionStore store) {
        this.store = store;
    }

    /** Registered on Fabric's HudRenderCallback. */
    public void render(DrawContext ctx) {
        if (!OverlayMode.get().hud()) {
            return;
        }
        DetectionFrame frame = store.getFresh();
        if (frame == null || frame.objects == null || frame.objects.isEmpty()) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();

        float scaledW = ctx.getScaledWindowWidth();
        float scaledH = ctx.getScaledWindowHeight();
        float frameW = frame.frame_w > 0 ? frame.frame_w : mc.getWindow().getWidth();
        float frameH = frame.frame_h > 0 ? frame.frame_h : mc.getWindow().getHeight();
        float sx = scaledW / frameW;
        float sy = scaledH / frameH;

        for (Detection d : frame.objects) {
            int left = Math.round((d.x - d.w / 2f) * sx);
            int top = Math.round((d.y - d.h / 2f) * sy);
            int right = Math.round((d.x + d.w / 2f) * sx);
            int bottom = Math.round((d.y + d.h / 2f) * sy);
            int color = OreColors.colorFor(d.label);

            drawBoxOutline(ctx, left, top, right, bottom, color);

            String text = d.label + " " + Math.round(d.confidence * 100) + "%";
            int textY = top - 10 < 0 ? bottom + 2 : top - 10;
            ctx.drawText(mc.textRenderer, text, left, textY, color, true);
        }
    }

    private static void drawBoxOutline(DrawContext ctx, int left, int top, int right, int bottom, int color) {
        ctx.fill(left, top, right, top + 1, color);        // top
        ctx.fill(left, bottom - 1, right, bottom, color);  // bottom
        ctx.fill(left, top, left + 1, bottom, color);      // left
        ctx.fill(right - 1, top, right, bottom, color);    // right
    }
}
