package com.minesight;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finds ore blocks in loaded chunks and maps them to dataset class labels. */
public final class OreScanner {
    private static final Map<Block, String> ORE_LABELS = new HashMap<Block, String>();

    /**
     * Blocks that can fool the model (colorful/cluttered surfaces), grouped
     * into toggleable categories. Photographed as empty-label HARD NEGATIVES
     * so the model learns NOT to fire on them. The GUI picks which categories
     * to collect, so it isn't always hunting flowers.
     */
    private static final Map<String, Set<Block>> CONFUSER_CATEGORIES =
            new LinkedHashMap<String, Set<Block>>();

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

        CONFUSER_CATEGORIES.put("flowers", new HashSet<Block>(Arrays.asList(
                Blocks.red_flower,      // poppy, tulips, orchid, allium...
                Blocks.yellow_flower,   // dandelion
                Blocks.double_plant))); // rose bush, sunflower, peony, lilac
        CONFUSER_CATEGORIES.put("foliage", new HashSet<Block>(Arrays.asList(
                Blocks.tallgrass, Blocks.leaves, Blocks.leaves2, Blocks.vine, Blocks.waterlily)));
        CONFUSER_CATEGORIES.put("mushrooms", new HashSet<Block>(Arrays.asList(
                Blocks.red_mushroom, Blocks.brown_mushroom,
                Blocks.red_mushroom_block, Blocks.brown_mushroom_block)));
        CONFUSER_CATEGORIES.put("redstone", new HashSet<Block>(Arrays.asList(
                Blocks.redstone_torch, Blocks.unlit_redstone_torch,
                Blocks.redstone_block, Blocks.redstone_wire)));
        CONFUSER_CATEGORIES.put("crops", new HashSet<Block>(Arrays.asList(
                Blocks.pumpkin, Blocks.lit_pumpkin, Blocks.melon_block, Blocks.cactus)));
    }

    private OreScanner() {
    }

    public static String labelFor(Block block) {
        return ORE_LABELS.get(block);
    }

    /** Confuser block positions within radius, limited to the enabled categories. */
    public static List<BlockPos> scanConfusers(World world, BlockPos center, int radius,
                                               Set<String> categories) {
        List<BlockPos> out = new ArrayList<BlockPos>();
        if (world.getChunkFromBlockCoords(center).isEmpty()) return out;
        Set<Block> wanted = new HashSet<Block>();
        for (String cat : categories) {
            Set<Block> blocks = CONFUSER_CATEGORIES.get(cat);
            if (blocks != null) wanted.addAll(blocks);
        }
        if (wanted.isEmpty()) return out;
        int yLo = Math.max(1, center.getY() - radius);
        int yHi = Math.min(255, center.getY() + radius);
        BlockPos from = new BlockPos(center.getX() - radius, yLo, center.getZ() - radius);
        BlockPos to = new BlockPos(center.getX() + radius, yHi, center.getZ() + radius);
        for (BlockPos pos : BlockPos.getAllInBox(from, to)) {
            if (wanted.contains(world.getBlockState(pos).getBlock())) {
                out.add(pos);
            }
        }
        return out;
    }

    /** Spawn Y band for a class label, or null if unknown. */
    public static int[] yBand(String label) {
        return Y_BANDS.get(label);
    }

    /**
     * Ores whose vanilla spawn band includes this Y - a grounded "what am I
     * likely to find here" hint for the radar (Phase 5 prediction).
     */
    public static String depthHint(int y) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, int[]> e : Y_BANDS.entrySet()) {
            int[] band = e.getValue();
            if (y >= band[0] && y <= band[1]) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(e.getKey().replace("_ore", ""));
            }
        }
        return sb.toString();
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
