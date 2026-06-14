package com.minesight.client.detect;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;

import java.util.HashMap;
import java.util.Map;

/**
 * Client-side ore block -> dataset label map (1.21), the read-side counterpart to
 * the plugin's {@code OreCatalog}. Used to confirm a raycast actually landed on a
 * real ore before recording it into {@link OreMemory} - so a held ore item (ray
 * passes through to the wall) or a moved block never highlights the wrong thing.
 * Deepslate variants fold into their base label.
 */
public final class OreBlocks {

    private static final Map<Block, String> LABELS = new HashMap<>();

    static {
        put("coal_ore", Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE);
        put("iron_ore", Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE);
        put("copper_ore", Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE);
        put("gold_ore", Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE);
        put("redstone_ore", Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE);
        put("emerald_ore", Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE);
        put("lapis_ore", Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE);
        put("diamond_ore", Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE);
        put("quartz_ore", Blocks.NETHER_QUARTZ_ORE);
    }

    private OreBlocks() {
    }

    private static void put(String label, Block... blocks) {
        for (Block b : blocks) {
            LABELS.put(b, label);
        }
    }

    /** Dataset label for an ore block, or {@code null} if it is not a tracked ore. */
    public static String labelFor(Block block) {
        return LABELS.get(block);
    }
}
