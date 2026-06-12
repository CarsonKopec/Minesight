package com.minesight.render;

import com.minesight.Detection;
import com.minesight.DetectionFrame;
import com.minesight.DetectionStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Draws 2D bounding boxes on the HUD for the latest detections.
 *
 * Detections arrive in capture-frame pixels (the Minecraft window client
 * area, which equals mc.displayWidth/Height when the engine captures the
 * window); they are rescaled into GUI coordinates via ScaledResolution.
 */
public class OverlayRenderer {
    private static final Map<String, Integer> LABEL_COLORS = new LinkedHashMap<String, Integer>();
    private static final int DEFAULT_COLOR = 0xFFFFFFFF;

    static {
        LABEL_COLORS.put("diamond", 0xFF4AEDD9);
        LABEL_COLORS.put("emerald", 0xFF2ECC40);
        LABEL_COLORS.put("gold", 0xFFFFD700);
        LABEL_COLORS.put("iron", 0xFFD8C8B8);
        LABEL_COLORS.put("coal", 0xFF666666);
        LABEL_COLORS.put("redstone", 0xFFFF4136);
        LABEL_COLORS.put("lapis", 0xFF3D5AFE);
        LABEL_COLORS.put("copper", 0xFFE07B4F);
    }

    private final DetectionStore store;

    public OverlayRenderer(DetectionStore store) {
        this.store = store;
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        // Post(ALL) fires once at the end of HUD rendering in 1.8.9. If boxes
        // ever fail to appear, ElementType.TEXT is a safe alternative hook.
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;

        DetectionFrame frame = store.getFresh();
        if (frame == null || frame.objects.isEmpty()) return;

        Minecraft mc = Minecraft.getMinecraft();
        ScaledResolution sr = new ScaledResolution(mc);

        // Capture-frame pixels -> GUI coordinates.
        float frameW = frame.frame_w > 0 ? frame.frame_w : mc.displayWidth;
        float frameH = frame.frame_h > 0 ? frame.frame_h : mc.displayHeight;
        float sx = sr.getScaledWidth() / frameW;
        float sy = sr.getScaledHeight() / frameH;

        for (Detection d : frame.objects) {
            int left = Math.round((d.x - d.w / 2f) * sx);
            int top = Math.round((d.y - d.h / 2f) * sy);
            int right = Math.round((d.x + d.w / 2f) * sx);
            int bottom = Math.round((d.y + d.h / 2f) * sy);
            int color = colorFor(d.label);

            drawBoxOutline(left, top, right, bottom, color);

            String text = d.label + " " + Math.round(d.confidence * 100) + "%";
            int textY = top - 10 < 0 ? bottom + 2 : top - 10;
            mc.fontRendererObj.drawStringWithShadow(text, left, textY, color);
        }

        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    private static int colorFor(String label) {
        if (label == null) return DEFAULT_COLOR;
        String lower = label.toLowerCase();
        for (Map.Entry<String, Integer> e : LABEL_COLORS.entrySet()) {
            if (lower.contains(e.getKey())) return e.getValue();
        }
        return DEFAULT_COLOR;
    }

    private static void drawBoxOutline(int left, int top, int right, int bottom, int color) {
        Gui.drawRect(left, top, right, top + 1, color);       // top
        Gui.drawRect(left, bottom - 1, right, bottom, color); // bottom
        Gui.drawRect(left, top, left + 1, bottom, color);     // left
        Gui.drawRect(right - 1, top, right, bottom, color);   // right
    }
}
