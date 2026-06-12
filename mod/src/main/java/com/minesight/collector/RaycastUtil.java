package com.minesight.collector;

import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

/** Line-of-sight checks against world block data. */
public final class RaycastUtil {
    private RaycastUtil() {
    }

    /**
     * True if a ray from eye to target first hits the given block.
     *
     * stopOnLiquid is TRUE: lava and water occlude. Without it, rays pass
     * through lava and an ore buried under a lava lake gets labeled while the
     * image only shows lava - teaching the model that lava is ore.
     */
    public static boolean firstBlockHit(World world, Vec3 eye, Vec3 target, BlockPos block) {
        MovingObjectPosition hit = world.rayTraceBlocks(
                new Vec3(eye.xCoord, eye.yCoord, eye.zCoord),
                new Vec3(target.xCoord, target.yCoord, target.zCoord),
                true, true, false);
        return hit != null
                && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && hit.getBlockPos().equals(block);
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
