package com.minesight.client.capture;

import net.minecraft.client.texture.NativeImage;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

/**
 * Writes captured frames as a YOLO dataset: {@code images/<name>.png} +
 * {@code labels/<name>.txt} (normalized {@code class cx cy w h}), the 2.0 port
 * of the 1.8.9 collector's {@code DatasetWriter}.
 *
 * <p>The class index order below MUST match the engine's {@code data.yaml}. It
 * mirrors the 1.8.9 class set plus the new {@code copper_ore}; deepslate variants
 * fold into their base label upstream (see the plugin's {@code OreCatalog}). A
 * {@code classes.txt} is written alongside so the Python side can reconcile.
 */
public final class DatasetWriter {

    /** Canonical class order. Keep in sync with engine data.yaml. */
    public static final List<String> CLASSES = List.of(
            "coal_ore", "copper_ore", "iron_ore", "gold_ore", "redstone_ore",
            "emerald_ore", "lapis_ore", "diamond_ore", "quartz_ore");

    /** A normalized YOLO box: a class label plus its screen rect. */
    public record Box(String label, GroundTruthProjector.Rect rect) {
    }

    private final File imagesDir;
    private final File labelsDir;
    private volatile boolean classesWritten;

    public DatasetWriter(File baseDir) {
        this.imagesDir = new File(baseDir, "images");
        this.labelsDir = new File(baseDir, "labels");
    }

    public static int classIndex(String label) {
        return CLASSES.indexOf(label);
    }

    /**
     * Write one frame + its labels. Returns the image file on success, or
     * {@code null} if there were no boxes (we never save empty-label frames) or
     * I/O failed.
     */
    public File write(NativeImage image, List<Box> boxes, int shotId) {
        if (boxes.isEmpty()) {
            return null;  // no point saving a frame with nothing to learn from
        }
        if (!imagesDir.exists() && !imagesDir.mkdirs()) {
            return null;
        }
        if (!labelsDir.exists() && !labelsDir.mkdirs()) {
            return null;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        String name = System.currentTimeMillis() + "_" + shotId;

        StringBuilder label = new StringBuilder();
        for (Box b : boxes) {
            int cls = classIndex(b.label());
            if (cls < 0) {
                continue;  // unknown class - skip rather than write a bad index
            }
            GroundTruthProjector.Rect r = b.rect();
            double cx = ((r.x0() + r.x1()) / 2.0) / w;
            double cy = ((r.y0() + r.y1()) / 2.0) / h;
            double bw = r.width() / w;
            double bh = r.height() / h;
            label.append(cls)
                    .append(String.format(Locale.ROOT, " %.6f %.6f %.6f %.6f%n", cx, cy, bw, bh));
        }
        if (label.length() == 0) {
            return null;
        }
        try {
            File png = new File(imagesDir, name + ".png");
            image.writeTo(png);
            Files.write(new File(labelsDir, name + ".txt").toPath(),
                    label.toString().getBytes(StandardCharsets.UTF_8));
            writeClassesOnce();
            return png;
        } catch (IOException e) {
            return null;
        }
    }

    private void writeClassesOnce() {
        if (classesWritten) {
            return;
        }
        try {
            File parent = imagesDir.getParentFile();
            Files.write(new File(parent, "classes.txt").toPath(),
                    String.join("\n", CLASSES).getBytes(StandardCharsets.UTF_8));
            classesWritten = true;
        } catch (IOException ignored) {
            // best effort; the boxes are written either way
        }
    }
}
