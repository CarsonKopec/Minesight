package com.minesight.farm;

import org.bukkit.Material;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Modern-Minecraft (1.21) ore -> dataset-label mapping, the 2.0 successor to the
 * 1.8.9 {@code OreScanner}. Bukkit {@link Material} based so it works server-side
 * in the Folia plugin with no Mojang-mapping dependency.
 *
 * <p>Label scheme: deepslate variants map to the SAME base label as their stone
 * counterpart (e.g. {@code DEEPSLATE_DIAMOND_ORE -> "diamond_ore"}). The deepslate
 * texture is just a darker background of the same ore, so it is extra visual
 * diversity for one class rather than a new class - and this keeps the label set
 * compatible with the existing Python/dataset classes. The one genuinely new
 * class modern MC adds is {@code copper_ore}. Split deepslate into its own classes
 * here later if the model ever needs to distinguish them.
 */
public final class OreCatalog {

    /** Block material -> dataset class label. */
    private static final Map<Material, String> ORE_LABELS = new EnumMap<>(Material.class);

    /** Confuser blocks grouped into toggleable hard-negative categories. */
    private static final Map<String, Set<Material>> CONFUSER_CATEGORIES = new LinkedHashMap<>();

    /** Vanilla 1.21 spawn bands (min..max Y); teleport Y is sampled inside these. */
    private static final Map<String, int[]> Y_BANDS = new LinkedHashMap<>();

    static {
        put("coal_ore", Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE);
        put("iron_ore", Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE);
        put("copper_ore", Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE);
        put("gold_ore", Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE, Material.NETHER_GOLD_ORE);
        put("redstone_ore", Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE);
        put("emerald_ore", Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE);
        put("lapis_ore", Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE);
        put("diamond_ore", Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE);
        put("quartz_ore", Material.NETHER_QUARTZ_ORE);

        // 1.21 distributions are triangular; these are practical min..max bands.
        Y_BANDS.put("coal_ore", new int[]{0, 192});
        Y_BANDS.put("iron_ore", new int[]{-24, 72});
        Y_BANDS.put("copper_ore", new int[]{-16, 112});
        Y_BANDS.put("gold_ore", new int[]{-64, 32});
        Y_BANDS.put("redstone_ore", new int[]{-64, 15});
        Y_BANDS.put("emerald_ore", new int[]{-16, 256});
        Y_BANDS.put("lapis_ore", new int[]{-64, 64});
        Y_BANDS.put("diamond_ore", new int[]{-64, 16});
        Y_BANDS.put("quartz_ore", new int[]{8, 116});

        CONFUSER_CATEGORIES.put("flowers", set(
                Material.POPPY, Material.DANDELION, Material.BLUE_ORCHID, Material.ALLIUM,
                Material.AZURE_BLUET, Material.RED_TULIP, Material.ORANGE_TULIP,
                Material.WHITE_TULIP, Material.PINK_TULIP, Material.OXEYE_DAISY,
                Material.CORNFLOWER, Material.LILY_OF_THE_VALLEY, Material.TORCHFLOWER,
                Material.ROSE_BUSH, Material.SUNFLOWER, Material.PEONY, Material.LILAC));
        CONFUSER_CATEGORIES.put("foliage", set(
                Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN,
                Material.OAK_LEAVES, Material.BIRCH_LEAVES, Material.SPRUCE_LEAVES,
                Material.JUNGLE_LEAVES, Material.VINE, Material.LILY_PAD, Material.GLOW_LICHEN));
        CONFUSER_CATEGORIES.put("mushrooms", set(
                Material.RED_MUSHROOM, Material.BROWN_MUSHROOM,
                Material.RED_MUSHROOM_BLOCK, Material.BROWN_MUSHROOM_BLOCK));
        CONFUSER_CATEGORIES.put("redstone", set(
                Material.REDSTONE_TORCH, Material.REDSTONE_WIRE,
                Material.REDSTONE_BLOCK, Material.REDSTONE_LAMP));
        CONFUSER_CATEGORIES.put("crops", set(
                Material.PUMPKIN, Material.JACK_O_LANTERN, Material.MELON, Material.CACTUS));
        // Modern cave clutter that looks ore-ish in the dark.
        CONFUSER_CATEGORIES.put("caves", set(
                Material.AMETHYST_CLUSTER, Material.LARGE_AMETHYST_BUD,
                Material.MEDIUM_AMETHYST_BUD, Material.SMALL_AMETHYST_BUD,
                Material.SCULK, Material.SCULK_VEIN, Material.SCULK_SENSOR,
                Material.SCULK_CATALYST, Material.SCULK_SHRIEKER));
    }

    private OreCatalog() {
    }

    private static void put(String label, Material... blocks) {
        for (Material m : blocks) {
            ORE_LABELS.put(m, label);
        }
    }

    private static Set<Material> set(Material... blocks) {
        return new HashSet<>(Arrays.asList(blocks));
    }

    /** Dataset label for an ore block, or {@code null} if it is not a tracked ore. */
    public static String labelFor(Material block) {
        return ORE_LABELS.get(block);
    }

    /** Every label the catalog knows (for validating GUI/command input). */
    public static Set<String> labels() {
        return new HashSet<>(ORE_LABELS.values());
    }

    /** Spawn Y band {min, max} for a label, or {@code null} if unknown. */
    public static int[] yBand(String label) {
        return Y_BANDS.get(label);
    }

    /** Confuser materials for the enabled hard-negative categories. */
    public static Set<Material> confusers(Set<String> categories) {
        Set<Material> wanted = new HashSet<>();
        for (String cat : categories) {
            Set<Material> blocks = CONFUSER_CATEGORIES.get(cat);
            if (blocks != null) {
                wanted.addAll(blocks);
            }
        }
        return wanted;
    }
}
