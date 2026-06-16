package com.minesight.farm;

import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Training-arena rig for the autonomous-miner auto-tuner.
 *
 * <p>Creates a dedicated void world ({@code minesight_arena}) tiled into a grid of
 * identical, self-contained mining arenas. Each arena is a bedrock-shelled box of
 * stone laced with a <b>deterministic</b> layout - corridors, scattered
 * ground-truth ore, lava pockets, a water gap to bridge, and a drop shaft - so
 * every candidate trains on the exact same challenge (fitness is a fair A/B).
 *
 * <p>The layout is generated once from a per-arena seed into an in-memory
 * {@code Material[]}, then stamped to the world; a reset just re-stamps it, wiping
 * everything the agent dug/placed. Ground-truth ore positions are handed to the
 * client so it can seed its memory directly - no vision pipeline needed to train
 * the agent's body (pathfinding / mining / bridging / hazard avoidance).
 *
 * <p>Folia-correct: world edits run on the region scheduler for each chunk; on a
 * Paper dev server those all serialize on the main thread.
 */
public final class ArenaManager {

    public static final String WORLD = "minesight_arena";

    // Arena box including the 1-block bedrock shell.
    static final int W = 32, H = 18, D = 32;
    static final int FLOOR_Y = 60;          // world Y of the arena's bedrock floor
    static final int SPACING = 48;          // gap between arena origins (> W, so no overlap)
    static final int GRID = 32;             // GRID x GRID = up to 1024 arena slots
                                            // (lazily generated, so unused slots cost nothing)
    static final long SEED_BASE = 0x6D696E65L;  // "mine"

    private static final int ORE_COUNT = 14;
    private static final int CORRIDOR_FLOOR = 8;     // local Y of the corridor floor (mid-height)
    // 4-tall corridors: the pathfinder's step-up/parkour moves need headroom to
    // "jump", so a 2-tall tunnel is a one-way drop the agent can't climb out of.
    private static final int CORRIDOR_TOP = CORRIDOR_FLOOR + 4;

    /** A ground-truth ore block: world coords + dataset label. */
    public record GroundTruthOre(String label, int x, int y, int z) {
    }

    /** One training arena: a fixed box with a deterministic layout. */
    public static final class Arena {
        final int id;
        final int ox, oy, oz;                 // world corner (min) of the box
        final Material[] layout;              // W*H*D, generated once
        final List<GroundTruthOre> ores;
        final Location spawn;
        UUID client;                          // sticky assignment

        Arena(int id, int ox, int oy, int oz, Material[] layout,
              List<GroundTruthOre> ores, Location spawn) {
            this.id = id;
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.layout = layout;
            this.ores = ores;
            this.spawn = spawn;
        }

        public int id() {
            return id;
        }

        public Location spawn() {
            return spawn.clone();
        }

        public List<GroundTruthOre> ores() {
            return ores;
        }
    }

    private final JavaPlugin plugin;
    private final Map<Integer, Arena> arenas = new HashMap<>();
    private final Map<UUID, Integer> assignment = new HashMap<>();
    private final Map<Material, BlockData> blockCache = new EnumMap<>(Material.class);
    private World world;

    public ArenaManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Create (or adopt) the arena world. Call from onEnable (main thread). */
    public void init() {
        world = plugin.getServer().getWorld(WORLD);
        if (world == null) {
            WorldCreator wc = new WorldCreator(WORLD);
            wc.type(WorldType.FLAT);
            wc.generatorSettings("{\"layers\":[],\"biome\":\"minecraft:the_void\"}");
            wc.generateStructures(false);
            world = wc.createWorld();
        }
        if (world == null) {
            plugin.getLogger().warning("Arena world could not be created; training disabled.");
            return;
        }
        // Static, predictable arenas: no daylight/weather/mobs/fire spread/random ticks.
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_FIRE_TICK, false);
        world.setGameRule(GameRule.RANDOM_TICK_SPEED, 0);
        world.setGameRule(GameRule.FALL_DAMAGE, true);   // falling IS a hazard to learn
        world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);  // no bot achievement spam
        world.setTime(6000);
        plugin.getLogger().info("Arena world '" + WORLD + "' ready (" + GRID * GRID + " slots).");
    }

    public boolean ready() {
        return world != null;
    }

    /** The arena assigned to a client (sticky), allocating one on first request. */
    public synchronized Arena assign(UUID client) {
        Integer id = assignment.get(client);
        if (id == null) {
            id = assignment.size() % (GRID * GRID);
            assignment.put(client, id);
        }
        return arena(id);
    }

    /** Get (lazily generating) the arena with this id. */
    public synchronized Arena arena(int id) {
        return arenas.computeIfAbsent(id, k -> generate(k, SEED_BASE + k));
    }

    /** Regenerate a slot with a fresh random layout (a new arena every episode). */
    public synchronized Arena fresh(int id) {
        Arena a = generate(id, java.util.concurrent.ThreadLocalRandom.current().nextLong());
        arenas.put(id, a);
        return a;
    }

    public int assignedId(UUID client) {
        Integer id = assignment.get(client);
        return id != null ? id : -1;
    }

    // -- generation (pure: no world access) --------------------------------

    private Arena generate(int id, long seed) {
        int col = id % GRID;
        int row = id / GRID;
        int ox = col * SPACING;
        int oz = row * SPACING;
        int oy = FLOOR_Y;
        Material[] layout = new Material[W * H * D];
        List<GroundTruthOre> ores = new ArrayList<>();
        Random r = new Random(seed);

        // 1. Solid stone fill inside a bedrock shell.
        for (int lx = 0; lx < W; lx++) {
            for (int ly = 0; ly < H; ly++) {
                for (int lz = 0; lz < D; lz++) {
                    boolean shell = lx == 0 || lx == W - 1 || lz == 0 || lz == D - 1
                            || ly == 0 || ly == H - 1;
                    layout[idx(lx, ly, lz)] = shell ? Material.BEDROCK : Material.STONE;
                }
            }
        }

        // 2. Pick a theme so different bots learn different basics - some get a
        //    pure parkour course, the rest the all-rounder mining cave.
        int midZ = D / 2;
        String theme;
        int[] s;
        if (r.nextInt(100) < 40) {
            theme = "parkour";
            s = buildParkour(layout, ores, ox, oy, oz, r, midZ);
        } else {
            theme = "mixed";
            s = buildMixed(layout, ores, ox, oy, oz, r, midZ);
        }

        Location spawn = new Location(world, ox + s[0] + 0.5, oy + s[1], oz + s[2] + 0.5, 90f, 0f);
        plugin.getLogger().info("Generated arena " + id + " (" + theme + ") with "
                + ores.size() + " ore.");
        return new Arena(id, ox, oy, oz, layout, ores, spawn);
    }

    /** The all-rounder cave: corridors to walk, ore to dig, lava + a water gap,
     *  a stairwell, and a parkour run. Returns the spawn (local feet coords). */
    private int[] buildMixed(Material[] layout, List<GroundTruthOre> ores,
                             int ox, int oy, int oz, Random r, int midZ) {
        carveCorridorX(layout, 1, W - 2, midZ);
        for (int bx : new int[]{W / 4, W / 2, 3 * W / 4}) {
            carveCorridorZ(layout, bx, 1, D - 2);
        }
        for (int i = 0; i < 3; i++) {                       // lava pockets
            set(layout, 4 + r.nextInt(W - 8), CORRIDOR_FLOOR, midZ, Material.LAVA);
        }
        int gapX = W / 2 + 3;                                // water gap to bridge
        for (int dz = -1; dz <= 0; dz++) {
            set(layout, gapX, CORRIDOR_FLOOR, midZ + dz, Material.AIR);
            set(layout, gapX, CORRIDOR_FLOOR - 1, midZ + dz, Material.WATER);
            set(layout, gapX, CORRIDOR_FLOOR - 2, midZ + dz, Material.WATER);
        }
        int shaftX = W / 4;                                  // climbable stairwell
        int lastZ = 4, lastFy = CORRIDOR_FLOOR - 1;
        for (int i = 0; i < 5; i++) {
            int z = 4 + i, fy = CORRIDOR_FLOOR - 1 - i;
            if (fy < 1) {
                break;
            }
            set(layout, shaftX, fy, z, Material.STONE);
            for (int yy = fy + 1; yy <= CORRIDOR_TOP; yy++) {
                set(layout, shaftX, yy, z, Material.AIR);
            }
            lastZ = z;
            lastFy = fy;
        }
        placeOre(layout, ores, ox, oy, oz, shaftX, lastFy, lastZ + 1, "diamond_ore");
        carveParkour(layout, ores, ox, oy, oz, 3 * W / 4, midZ);
        scatterOres(layout, ores, ox, oy, oz, r);
        ensureStand(layout, 2, midZ);
        return new int[]{2, CORRIDOR_FLOOR + 1, midZ};
    }

    /** A parkour course: a tall tunnel of 1-block platforms over a deep pit, with
     *  stone side walls holding the ore - so the agent only reaches each ore by
     *  leaping the gaps. Returns the spawn (the first platform). */
    private int[] buildParkour(Material[] layout, List<GroundTruthOre> ores,
                               int ox, int oy, int oz, Random r, int midZ) {
        int y = CORRIDOR_FLOOR;
        // Hollow a 1-wide tall channel down the middle with a deep pit below.
        for (int lx = 1; lx < W - 1; lx++) {
            for (int ly = 2; ly <= CORRIDOR_TOP; ly++) {
                set(layout, lx, ly, midZ, Material.AIR);
            }
        }
        // Stepping platforms every 2 cells (a 1-block gap to leap between each).
        int sx = 2;
        for (int lx = sx; lx < W - 2; lx += 2) {
            set(layout, lx, y, midZ, Material.STONE);
        }
        // Ore embedded in the stone side walls beside the platforms - only
        // reachable once you've parkoured to that platform.
        scatterOres(layout, ores, ox, oy, oz, r);
        return new int[]{sx, y + 1, midZ};
    }

    private void carveCorridorX(Material[] layout, int x0, int x1, int z) {
        for (int x = x0; x <= x1; x++) {
            for (int dz = -1; dz <= 0; dz++) {
                for (int yy = CORRIDOR_FLOOR + 1; yy <= CORRIDOR_TOP; yy++) {
                    set(layout, x, yy, z + dz, Material.AIR);
                }
            }
        }
    }

    private void carveCorridorZ(Material[] layout, int x, int z0, int z1) {
        for (int z = z0; z <= z1; z++) {
            for (int yy = CORRIDOR_FLOOR + 1; yy <= CORRIDOR_TOP; yy++) {
                set(layout, x, yy, z, Material.AIR);
            }
        }
    }

    /** Turn part of a branch into a parkour run: 1-wide platforms separated by
     *  floorless gaps over a pit, with an emerald past the final jump. */
    private void carveParkour(Material[] layout, List<GroundTruthOre> ores,
                              int ox, int oy, int oz, int parX, int startZ) {
        int lastZ = startZ;
        int z = startZ + 2;
        for (int i = 0; i < 5 && z < D - 3; i++) {
            // Platform: solid floor + headroom (standable).
            set(layout, parX, CORRIDOR_FLOOR, z, Material.STONE);
            for (int yy = CORRIDOR_FLOOR + 1; yy <= CORRIDOR_TOP; yy++) {
                set(layout, parX, yy, z, Material.AIR);
            }
            lastZ = z;
            // Gap to the next platform: a floorless pit you must leap.
            if (i < 4) {
                int gz = z + 1;
                for (int dy = -3; dy <= CORRIDOR_TOP - CORRIDOR_FLOOR; dy++) {
                    set(layout, parX, CORRIDOR_FLOOR + dy, gz, Material.AIR);
                }
            }
            z += 2;
        }
        // Emerald in the wall beside the final platform - the parkour reward.
        if (parX + 1 < W - 1) {
            placeOre(layout, ores, ox, oy, oz, parX + 1, CORRIDOR_FLOOR + 1, lastZ, "emerald_ore");
        }
    }

    /** Ensure a standable air pocket with solid floor at this column. */
    private void ensureStand(Material[] layout, int lx, int lz) {
        set(layout, lx, CORRIDOR_FLOOR, lz, Material.STONE);
        for (int yy = CORRIDOR_FLOOR + 1; yy <= CORRIDOR_TOP; yy++) {
            set(layout, lx, yy, lz, Material.AIR);
        }
    }

    /** Embed ore in stone walls directly beside standable cells, so the agent
     *  can stand in the open passage and reach every ore with a single dig -
     *  no ore ends up walled off where it can't be pathed to. */
    private void scatterOres(Material[] layout, List<GroundTruthOre> ores,
                             int ox, int oy, int oz, Random r) {
        List<int[]> stands = new ArrayList<>();
        for (int lx = 2; lx < W - 2; lx++) {
            for (int ly = 1; ly < H - 2; ly++) {
                for (int lz = 2; lz < D - 2; lz++) {
                    if (standableLocal(layout, lx, ly, lz)) {
                        stands.add(new int[]{lx, ly, lz});
                    }
                }
            }
        }
        Collections.shuffle(stands, r);
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int placed = 0;
        for (int[] s : stands) {
            if (placed >= ORE_COUNT) {
                break;
            }
            // A stone wall block at feet or head level next to where it stands.
            for (int yo = 0; yo <= 1 && placed < ORE_COUNT; yo++) {
                for (int[] d : dirs) {
                    int wx = s[0] + d[0], wy = s[1] + yo, wz = s[2] + d[1];
                    if (get(layout, wx, wy, wz) == Material.STONE) {
                        placeOre(layout, ores, ox, oy, oz, wx, wy, wz, rollOre(r));
                        placed++;
                        break;
                    }
                }
            }
        }
    }

    /** A cell the agent can occupy in the layout: air for feet + head, solid floor. */
    private boolean standableLocal(Material[] layout, int lx, int ly, int lz) {
        return get(layout, lx, ly, lz) == Material.AIR
                && get(layout, lx, ly + 1, lz) == Material.AIR
                && isWall(get(layout, lx, ly - 1, lz));
    }

    private static boolean isWall(Material m) {
        return m == Material.STONE || m == Material.BEDROCK || m == Material.COBBLESTONE
                || (m != null && m.name().endsWith("_ORE"));
    }

    /** Weighted ore label: mostly common, occasionally rare. */
    private static String rollOre(Random r) {
        int k = r.nextInt(100);
        if (k < 40) return "coal_ore";
        if (k < 65) return "iron_ore";
        if (k < 80) return "copper_ore";
        if (k < 88) return "redstone_ore";
        if (k < 94) return "gold_ore";
        if (k < 98) return "lapis_ore";
        return r.nextBoolean() ? "diamond_ore" : "emerald_ore";
    }

    private void placeOre(Material[] layout, List<GroundTruthOre> ores, int ox, int oy, int oz,
                          int lx, int ly, int lz, String label) {
        Material mat = oreMaterial(label);
        set(layout, lx, ly, lz, mat);
        ores.add(new GroundTruthOre(label, ox + lx, oy + ly, oz + lz));
    }

    private static Material oreMaterial(String label) {
        return switch (label) {
            case "coal_ore" -> Material.COAL_ORE;
            case "iron_ore" -> Material.IRON_ORE;
            case "copper_ore" -> Material.COPPER_ORE;
            case "gold_ore" -> Material.GOLD_ORE;
            case "redstone_ore" -> Material.REDSTONE_ORE;
            case "lapis_ore" -> Material.LAPIS_ORE;
            case "emerald_ore" -> Material.EMERALD_ORE;
            case "diamond_ore" -> Material.DIAMOND_ORE;
            default -> Material.IRON_ORE;
        };
    }


    // -- world stamping (reset) --------------------------------------------

    /** (Re)stamp an arena's layout into the world, then run {@code done}. Wipes
     *  everything the agent changed. Runs per-chunk on the region scheduler. */
    public void stamp(Arena a, Runnable done) {
        if (world == null) {
            if (done != null) {
                done.run();
            }
            return;
        }
        int cx0 = a.ox >> 4, cx1 = (a.ox + W - 1) >> 4;
        int cz0 = a.oz >> 4, cz1 = (a.oz + D - 1) >> 4;
        AtomicInteger pending = new AtomicInteger((cx1 - cx0 + 1) * (cz1 - cz0 + 1));
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                final int fcx = cx, fcz = cz;
                plugin.getServer().getRegionScheduler().execute(plugin, world, fcx, fcz, () -> {
                    stampChunk(a, fcx, fcz);
                    if (pending.decrementAndGet() == 0 && done != null) {
                        done.run();
                    }
                });
            }
        }
    }

    private void stampChunk(Arena a, int cx, int cz) {
        // Complete reset: clear out everything the last episode left behind in
        // this chunk (dropped items from mining, stray mobs, a leftover bot)
        // before rebuilding the blocks, so each episode starts identical.
        for (org.bukkit.entity.Entity e : world.getChunkAt(cx, cz).getEntities()) {
            if (e instanceof org.bukkit.entity.Player) {
                continue;   // never remove a spectator
            }
            int ey = e.getLocation().getBlockY();
            int ex = e.getLocation().getBlockX();
            int ez = e.getLocation().getBlockZ();
            if (ex >= a.ox && ex < a.ox + W && ey >= a.oy && ey < a.oy + H
                    && ez >= a.oz && ez < a.oz + D) {
                e.remove();
            }
        }

        int minWx = cx << 4, maxWx = minWx + 15;
        int minWz = cz << 4, maxWz = minWz + 15;
        for (int lx = 0; lx < W; lx++) {
            int wx = a.ox + lx;
            if (wx < minWx || wx > maxWx) {
                continue;
            }
            for (int lz = 0; lz < D; lz++) {
                int wz = a.oz + lz;
                if (wz < minWz || wz > maxWz) {
                    continue;
                }
                for (int ly = 0; ly < H; ly++) {
                    Material mat = a.layout[idx(lx, ly, lz)];
                    world.getBlockAt(wx, a.oy + ly, wz).setBlockData(blockData(mat), false);
                }
            }
        }
    }

    private BlockData blockData(Material mat) {
        return blockCache.computeIfAbsent(mat, Material::createBlockData);
    }

    // -- agent kit + teleport ----------------------------------------------

    /** Drop a kitted agent into the arena spawn (on its region thread), then run
     *  {@code onArrived} once the teleport completes. */
    public void enter(Player p, Arena a, Runnable onArrived) {
        p.getScheduler().run(plugin, t -> {
            p.setGameMode(GameMode.SURVIVAL);
            p.getInventory().clear();
            p.getInventory().addItem(new ItemStack(Material.NETHERITE_PICKAXE));
            p.getInventory().addItem(new ItemStack(Material.COBBLESTONE, 64));
            p.setHealth(Math.min(20.0, p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue()));
            p.setFoodLevel(20);
            p.setSaturation(20f);
            p.setFireTicks(0);
            p.teleportAsync(a.spawn).thenRun(() -> {
                if (onArrived != null) {
                    onArrived.run();
                }
            });
        }, null);
    }

    /** Spectator helper: drop a watcher above an arena in spectator mode. */
    public void spectate(Player p, int id) {
        Arena a = arena(id);
        Location view = new Location(world, a.ox + W / 2.0, a.oy + H + 6.0, a.oz + D / 2.0, 90f, 60f);
        p.getScheduler().run(plugin, t -> {
            p.setGameMode(GameMode.SPECTATOR);
            p.teleportAsync(view);
        }, null);
    }

    public int slotCount() {
        return GRID * GRID;
    }

    // -- local-coord helpers -----------------------------------------------

    private static int idx(int lx, int ly, int lz) {
        return (ly * D + lz) * W + lx;
    }

    private static void set(Material[] layout, int lx, int ly, int lz, Material mat) {
        if (lx >= 0 && lx < W && ly >= 0 && ly < H && lz >= 0 && lz < D) {
            layout[idx(lx, ly, lz)] = mat;
        }
    }

    private static Material get(Material[] layout, int lx, int ly, int lz) {
        if (lx < 0 || lx >= W || ly < 0 || ly >= H || lz < 0 || lz >= D) {
            return Material.BEDROCK;
        }
        return layout[idx(lx, ly, lz)];
    }
}
