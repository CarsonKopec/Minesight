package com.minesight.client.capture;

import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Projects a world-space ore AABB to a screen rectangle - the 2.0 successor to
 * the 1.8.9 {@code gluProject}-based ground-truth labeller.
 *
 * <p>Rather than fight the camera quaternion convention, this rebuilds the view
 * transform the way vanilla {@code GameRenderer} does: rotate by pitch about X,
 * then by (yaw + 180) about Y, applied to the point taken relative to the camera
 * position. Multiplying by the captured projection matrix yields clip space.
 * Screen = clip / w, mapped to the framebuffer with Y flipped.
 *
 * <p>Both matrices/poses are captured live from {@code WorldRenderContext} (see
 * {@link CaptureManager}); this is the coordinate-alignment path flagged as
 * risk #2 / backlog #7 - calibrate the boxes against in-game captures.
 */
public final class GroundTruthProjector {

    /** A screen-space axis-aligned rectangle in pixels. */
    public record Rect(double x0, double y0, double x1, double y1) {
        public double width() {
            return x1 - x0;
        }

        public double height() {
            return y1 - y0;
        }
    }

    private GroundTruthProjector() {
    }

    /**
     * Project a world AABB to a clipped screen rect, or {@code null} if it is not
     * fully in front of the camera or falls off / collapses on screen.
     */
    public static Rect project(Matrix4f projection, Vec3d camPos, float yaw, float pitch,
                               int width, int height,
                               double minX, double minY, double minZ,
                               double maxX, double maxY, double maxZ) {
        // view-projection = P * Rx(pitch) * Ry(yaw+180); point is camera-relative.
        Matrix4f vp = new Matrix4f(projection)
                .rotateX((float) Math.toRadians(pitch))
                .rotateY((float) Math.toRadians(yaw + 180.0));

        double[][] corners = {
                {minX, minY, minZ}, {maxX, minY, minZ}, {minX, maxY, minZ}, {maxX, maxY, minZ},
                {minX, minY, maxZ}, {maxX, minY, maxZ}, {minX, maxY, maxZ}, {maxX, maxY, maxZ}
        };

        double sx0 = Double.MAX_VALUE, sy0 = Double.MAX_VALUE;
        double sx1 = -Double.MAX_VALUE, sy1 = -Double.MAX_VALUE;
        int inFront = 0;
        Vector4f v = new Vector4f();
        for (double[] c : corners) {
            v.set((float) (c[0] - camPos.x), (float) (c[1] - camPos.y),
                    (float) (c[2] - camPos.z), 1.0f);
            vp.transform(v);
            if (v.w <= 1.0e-4f) {
                continue;  // at or behind the near plane
            }
            inFront++;
            float ndcX = v.x / v.w;
            float ndcY = v.y / v.w;
            double px = (ndcX * 0.5 + 0.5) * width;
            double py = (0.5 - ndcY * 0.5) * height;  // GL NDC +Y is up; screen +Y is down
            sx0 = Math.min(sx0, px);
            sy0 = Math.min(sy0, py);
            sx1 = Math.max(sx1, px);
            sy1 = Math.max(sy1, py);
        }
        // Ore blocks are small; require all 8 corners in front for a clean box.
        if (inFront < 8) {
            return null;
        }
        sx0 = Math.max(0, sx0);
        sy0 = Math.max(0, sy0);
        sx1 = Math.min(width, sx1);
        sy1 = Math.min(height, sy1);
        if (sx1 - sx0 < 1.0 || sy1 - sy0 < 1.0) {
            return null;  // offscreen or sub-pixel
        }
        return new Rect(sx0, sy0, sx1, sy1);
    }
}
