package com.minesight.collector;

import net.minecraft.block.material.Material;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

/** Line-of-sight checks against world block data. */
public final class RaycastUtil {
    private RaycastUtil() {
    }

    /**
     * True if the straight path from eye reaches the target block without
     * crossing anything that hides it.
     *
     * Custom voxel walk instead of vanilla rayTraceBlocks: vanilla's
     * stopOnLiquid only stops on SOURCE liquid blocks, so rays pass through
     * FLOWING lava/water and ores under lava flows get labeled while the
     * image shows only lava. Here ANY liquid occludes, as does anything
     * solid; pass-through stuff (air, torches, plants) doesn't.
     */
    public static boolean firstBlockHit(World world, Vec3 eye, Vec3 target, BlockPos block) {
        double x = eye.xCoord;
        double y = eye.yCoord;
        double z = eye.zCoord;
        double dx = target.xCoord - x;
        double dy = target.yCoord - y;
        double dz = target.zCoord - z;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-6) return true;
        dx /= len;
        dy /= len;
        dz /= len;

        int ix = MathHelper.floor_double(x);
        int iy = MathHelper.floor_double(y);
        int iz = MathHelper.floor_double(z);
        int stepX = dx > 0 ? 1 : -1;
        int stepY = dy > 0 ? 1 : -1;
        int stepZ = dz > 0 ? 1 : -1;
        double tMaxX = dx != 0 ? ((stepX > 0 ? ix + 1 - x : x - ix) / Math.abs(dx)) : Double.MAX_VALUE;
        double tMaxY = dy != 0 ? ((stepY > 0 ? iy + 1 - y : y - iy) / Math.abs(dy)) : Double.MAX_VALUE;
        double tMaxZ = dz != 0 ? ((stepZ > 0 ? iz + 1 - z : z - iz) / Math.abs(dz)) : Double.MAX_VALUE;
        double tDeltaX = dx != 0 ? 1 / Math.abs(dx) : Double.MAX_VALUE;
        double tDeltaY = dy != 0 ? 1 / Math.abs(dy) : Double.MAX_VALUE;
        double tDeltaZ = dz != 0 ? 1 / Math.abs(dz) : Double.MAX_VALUE;

        for (int i = 0; i < 96; i++) {
            double t;
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                ix += stepX;
                t = tMaxX;
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                iy += stepY;
                t = tMaxY;
                tMaxY += tDeltaY;
            } else {
                iz += stepZ;
                t = tMaxZ;
                tMaxZ += tDeltaZ;
            }
            if (t > len) return false;  // walked past the segment without reaching the target voxel
            if (ix == block.getX() && iy == block.getY() && iz == block.getZ()) {
                return true;
            }
            Material material = world.getBlockState(new BlockPos(ix, iy, iz)).getBlock().getMaterial();
            if (material.isLiquid() || material.blocksMovement()) {
                return false;
            }
        }
        return false;
    }

    /**
     * Counts how many of the block's center + 6 face centers are visible from
     * the eye. Used to skip fully occluded ores while still keeping partially
     * occluded ones (the spec explicitly wants those in the dataset).
     */
    public static int visibleSamples(World world, Vec3 eye, BlockPos block) {
        double cx = block.getX() + 0.5;
        double cy = block.getY() + 0.5;
        double cz = block.getZ() + 0.5;
        double[][] samples = {
                {cx, cy, cz},
                {cx + 0.49, cy, cz}, {cx - 0.49, cy, cz},
                {cx, cy + 0.49, cz}, {cx, cy - 0.49, cz},
                {cx, cy, cz + 0.49}, {cx, cy, cz - 0.49},
        };
        int visible = 0;
        for (double[] s : samples) {
            if (firstBlockHit(world, eye, new Vec3(s[0], s[1], s[2]), block)) {
                visible++;
            }
        }
        return visible;
    }
}
