package com.minesight.collector;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finds ore blocks in loaded chunks and maps them to dataset class labels. */
public final class OreScanner {
    private static final Map<Block, String> ORE_LABELS = new HashMap<Block, String>();

    /** Vanilla 1.8.9 spawn bands; teleport Y is sampled inside these. */
    private static final Map<String, int[]> Y_BANDS = new HashMap<String, int[]>();

    static {
        ORE_LABELS.put(Blocks.diamond_ore, "diamond_ore");
        ORE_LABELS.put(Blocks.emerald_ore, "emerald_ore");
        ORE_LABELS.put(Blocks.gold_ore, "gold_ore");
        ORE_LABELS.put(Blocks.iron_ore, "iron_ore");
        ORE_LABELS.put(Blocks.lapis_ore, "lapis_ore");
        ORE_LABELS.put(Blocks.redstone_ore, "redstone_ore");
        ORE_LABELS.put(Blocks.lit_redstone_ore, "redstone_ore");
        ORE_LABELS.put(Blocks.coal_ore, "coal_ore");
        ORE_LABELS.put(Blocks.quartz_ore, "quartz_ore");

        Y_BANDS.put("diamond_ore", new int[]{2, 16});
        Y_BANDS.put("redstone_ore", new int[]{2, 16});
        Y_BANDS.put("lapis_ore", new int[]{2, 32});
        Y_BANDS.put("gold_ore", new int[]{2, 32});
        Y_BANDS.put("emerald_ore", new int[]{4, 32});
        Y_BANDS.put("iron_ore", new int[]{2, 64});
        Y_BANDS.put("coal_ore", new int[]{5, 128});
        Y_BANDS.put("quartz_ore", new int[]{10, 118});
    }

    private OreScanner() {
    }

    public static String labelFor(Block block) {
        return ORE_LABELS.get(block);
    }

    /** Spawn Y band for a class label, or null if unknown. */
    public static int[] yBand(String label) {
        return Y_BANDS.get(label);
    }

    /** Positions of wanted ore blocks within a cubic radius of center. */
    public static List<BlockPos> scan(World world, BlockPos center, int radius, Set<String> wanted) {
        List<BlockPos> out = new ArrayList<BlockPos>();
        int yLo = Math.max(1, center.getY() - radius);
        int yHi = Math.min(255, center.getY() + radius);
        BlockPos from = new BlockPos(center.getX() - radius, yLo, center.getZ() - radius);
        BlockPos to = new BlockPos(center.getX() + radius, yHi, center.getZ() + radius);
        // Client-side isBlockLoaded always returns true; check for real data.
        if (world.getChunkFromBlockCoords(center).isEmpty()) return out;
        for (BlockPos pos : BlockPos.getAllInBox(from, to)) {
            String label = ORE_LABELS.get(world.getBlockState(pos).getBlock());
            if (label != null && wanted.contains(label)) {
                out.add(pos);
            }
        }
        return out;
    }
}
