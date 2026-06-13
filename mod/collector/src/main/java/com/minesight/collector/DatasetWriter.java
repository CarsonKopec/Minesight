package com.minesight.collector;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Writes captured frames as PNG + YOLO label files on a worker thread so the
 * render thread never blocks on disk IO or PNG encoding.
 */
public class DatasetWriter {
    public interface Callback {
        void onSaved(String fileName, int boxes, String thumbB64);

        void onError(String message);
    }

    /** For remote clients: the encoded image travels over the WebSocket. */
    public interface UploadCallback {
        void onReady(String fileName, String pngB64, String labels, int boxes, String thumbB64);

        void onError(String message);
    }

    private final ExecutorService exec = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "MineSight-DatasetWriter");
            t.setDaemon(true);
            return t;
        }
    });

    private File imagesDir;
    private File labelsDir;
    private boolean uploadMode;
    private int counter;
    /** Random per-game-instance token so parallel clients writing into the
     *  same pool can never collide on filenames. */
    private final String instanceToken =
            Integer.toHexString((int) (Math.random() * 0xFFFF) | 0x10000).substring(1);

    public boolean prepare(File outputDir, boolean upload) {
        uploadMode = upload;
        if (upload) {
            return true;  // the Control Panel host writes the files
        }
        imagesDir = new File(outputDir, "images");
        labelsDir = new File(outputDir, "labels");
        // mkdirs() returns false when the dir already exists, and parallel
        // clients race to create these - so create blindly, then verify.
        imagesDir.mkdirs();
        labelsDir.mkdirs();
        return imagesDir.isDirectory() && labelsDir.isDirectory();
    }

    private String nextName() {
        return "collected_" + instanceToken + "_" + System.currentTimeMillis() + "_" + counter++;
    }

    private static String labelText(List<float[]> boxes) {
        StringBuilder sb = new StringBuilder();
        for (float[] b : boxes) {
            sb.append(String.format(Locale.ROOT, "%d %.6f %.6f %.6f %.6f%n",
                    (int) b[0], b[1], b[2], b[3], b[4]));
        }
        return sb.toString();
    }

    /** boxes entries are {classIndex, cx, cy, w, h} normalized to [0,1]. */
    public void saveAsync(final ByteBuffer rgba, final int width, final int height,
                          final List<float[]> boxes, final Callback cb) {
        final String name = nextName();
        exec.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedImage img = toImage(rgba, width, height);
                    ImageIO.write(img, "png", new File(imagesDir, name + ".png"));
                    java.io.FileOutputStream fos =
                            new java.io.FileOutputStream(new File(labelsDir, name + ".txt"));
                    try {
                        fos.write(labelText(boxes).getBytes("UTF-8"));
                    } finally {
                        fos.close();
                    }
                    cb.onSaved(name + ".png", boxes.size(), thumbnail(img, 320));
                } catch (Exception ex) {
                    cb.onError(ex.toString());
                }
            }
        });
    }

    /** Remote variant: encode and hand back base64 instead of touching disk. */
    public void uploadAsync(final ByteBuffer rgba, final int width, final int height,
                            final List<float[]> boxes, final UploadCallback cb) {
        final String name = nextName();
        exec.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedImage img = toImage(rgba, width, height);
                    ByteArrayOutputStream png = new ByteArrayOutputStream();
                    ImageIO.write(img, "png", png);
                    cb.onReady(
                            name + ".png",
                            Base64.getEncoder().encodeToString(png.toByteArray()),
                            labelText(boxes),
                            boxes.size(),
                            thumbnail(img, 320));
                } catch (Exception ex) {
                    cb.onError(ex.toString());
                }
            }
        });
    }

    /** OpenGL rows are bottom-up; flip while converting to an RGB image. */
    private static BufferedImage toImage(ByteBuffer rgba, int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            int srcRow = height - 1 - y;
            for (int x = 0; x < width; x++) {
                int i = (srcRow * width + x) * 4;
                int r = rgba.get(i) & 0xFF;
                int g = rgba.get(i + 1) & 0xFF;
                int b = rgba.get(i + 2) & 0xFF;
                row[x] = (r << 16) | (g << 8) | b;
            }
            img.setRGB(0, y, width, 1, row, 0, width);
        }
        return img;
    }

    private static String thumbnail(BufferedImage img, int maxW) throws Exception {
        int w = Math.min(maxW, img.getWidth());
        int h = (int) (img.getHeight() * (w / (double) img.getWidth()));
        BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(small, "jpg", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }
}
