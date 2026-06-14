package com.minesight.farm;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Folia-safe successor to the 1.8.9 {@code ServerOreLocator}.
 *
 * <p>One headless Folia server force-generates and scans chunks for wanted ore
 * using the regionized scheduler, so chunks scattered across the map are
 * generated on different CPU cores - the real reason 2.0 scales to many cameras.
 *
 * <p>Results are kept in <b>per-class</b> queues so the capture session can hunt
 * whichever ore is furthest behind its goal ({@link #pollClass}), plus a separate
 * <b>confuser</b> queue scanned in a surface band for hard-negative shots
 * ({@link #pollConfuser}).
 *
 * <p>Threading: {@link #configure}/{@link #start}/{@link #stop} from any thread;
 * {@link #pump} on a global-region task (launches async chunk loads only); chunk
 * gen is async, the snapshot is taken on the owning region thread, and the scan
 * runs on the async scheduler (snapshots are thread-safe). Queues drain anywhere.
 */
public final class FoliaOreLocator {

    /**
     * A located block in world coordinates ({@code label} empty for confusers),
     * plus a precomputed camera viewpoint ({@code ex,ey,ez}) sitting in the air
     * pocket that exposes the block - so the camera lands with a clear line of
     * sight instead of inside rock.
     */
    public record OrePos(int x, int y, int z, String label, double ex, double ey, double ez) {
    }

    // Face directions for the air-exposure / viewpoint search.
    private static final int[][] DIRS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };
    private static final int MAX_RUN = 5;       // how far to walk into air
    private static final double DESIRED_DIST = 2.6;
    private static final double MIN_DIST = 1.3;

    private static final int RESULT_CAP = 512;
    private static final int CONFUSER_CAP = 128;
    private static final int MAX_IN_FLIGHT = 12;
    /** Surface band scanned for confuser blocks (flowers/grass live up here). */
    private static final int CONF_BAND_LO = 55;
    private static final int CONF_BAND_HI = 120;

    private final Plugin plugin;
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<OrePos>> oreByClass = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<OrePos> confusers = new ConcurrentLinkedQueue<>();
    private final AtomicInteger oreQueued = new AtomicInteger();
    private final AtomicInteger confuserQueued = new AtomicInteger();

    private volatile boolean running;
    private volatile World world;
    private volatile Set<String> wanted = Set.of();
    private volatile Set<Material> confuserMaterials = Set.of();
    private volatile int yLo;
    private volatile int yHi;
    private volatile int confLo = CONF_BAND_LO;
    private volatile int confHi = CONF_BAND_HI;
    private volatile int worldMin;
    private volatile int worldMax;

    private volatile List<long[]> chunkOrder = new ArrayList<>();
    private final AtomicInteger cursor = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger chunksScanned = new AtomicInteger();
    private final AtomicInteger oresFound = new AtomicInteger();

    public FoliaOreLocator(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * (Re)aim the locator at an area. Clears scan progress but keeps queued
     * results so a re-aim mid-session doesn't drop confirmed ore.
     *
     * @param confuserMats blocks to also scan (surface band) for hard negatives;
     *                     empty to skip confuser scanning entirely.
     */
    public synchronized void configure(World world, int centerX, int centerZ, int radiusBlocks,
                                       Set<String> wantedLabels, int yLoIn, int yHiIn,
                                       Set<Material> confuserMats) {
        this.world = world;
        this.wanted = Set.copyOf(wantedLabels);
        this.confuserMaterials = Set.copyOf(confuserMats);
        this.worldMin = world.getMinHeight();
        this.worldMax = world.getMaxHeight();
        this.yLo = Math.max(worldMin, yLoIn);
        this.yHi = Math.min(worldMax - 1, yHiIn);
        this.confLo = Math.max(worldMin, CONF_BAND_LO);
        this.confHi = Math.min(worldMax - 1, CONF_BAND_HI);

        final int ccx = centerX >> 4;
        final int ccz = centerZ >> 4;
        final int rc = Math.max(1, radiusBlocks >> 4);
        List<long[]> order = new ArrayList<>();
        for (int dx = -rc; dx <= rc; dx++) {
            for (int dz = -rc; dz <= rc; dz++) {
                order.add(new long[]{ccx + dx, ccz + dz});
            }
        }
        // Nearest-first: spend the budget close to the camera before the rim.
        order.sort((a, b) -> {
            long da = sq(a[0] - ccx) + sq(a[1] - ccz);
            long db = sq(b[0] - ccx) + sq(b[1] - ccz);
            return Long.compare(da, db);
        });
        this.chunkOrder = order;
        this.cursor.set(0);
    }

    private static long sq(long v) {
        return v * v;
    }

    public void start() {
        running = true;
    }

    public void stop() {
        running = false;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean exhausted() {
        return cursor.get() >= chunkOrder.size() && inFlight.get() == 0;
    }

    public int available() {
        return oreQueued.get();
    }

    public int availableClass(String label) {
        Queue<OrePos> q = oreByClass.get(label);
        return q == null ? 0 : q.size();
    }

    public int availableConfusers() {
        return confuserQueued.get();
    }

    public int chunksScanned() {
        return chunksScanned.get();
    }

    public int oresFound() {
        return oresFound.get();
    }

    /** Drain one located ore of any class (first non-empty queue), or null. */
    public OrePos poll() {
        for (Queue<OrePos> q : oreByClass.values()) {
            OrePos p = q.poll();
            if (p != null) {
                oreQueued.decrementAndGet();
                return p;
            }
        }
        return null;
    }

    /** Drain one located ore of a specific class, or null if that queue is dry. */
    public OrePos pollClass(String label) {
        Queue<OrePos> q = oreByClass.get(label);
        if (q == null) {
            return null;
        }
        OrePos p = q.poll();
        if (p != null) {
            oreQueued.decrementAndGet();
        }
        return p;
    }

    /** Drain one located confuser block (for a hard-negative shot), or null. */
    public OrePos pollConfuser() {
        OrePos p = confusers.poll();
        if (p != null) {
            confuserQueued.decrementAndGet();
        }
        return p;
    }

    public void clearResults() {
        oreByClass.clear();
        confusers.clear();
        oreQueued.set(0);
        confuserQueued.set(0);
    }

    /**
     * Launch async chunk gen+scan up to the in-flight budget. MUST run on a
     * global-region task. Returns immediately; work completes asynchronously.
     */
    public void pump() {
        if (!running || world == null) {
            return;
        }
        List<long[]> order = chunkOrder;
        while (inFlight.get() < MAX_IN_FLIGHT
                && (oreQueued.get() < RESULT_CAP || confuserQueued.get() < CONFUSER_CAP)) {
            int idx = cursor.getAndIncrement();
            if (idx >= order.size()) {
                return;  // exhausted; outstanding work will still drain in
            }
            long[] c = order.get(idx);
            submit((int) c[0], (int) c[1]);
        }
    }

    private void submit(int cx, int cz) {
        final World w = world;
        if (w == null) {
            return;
        }
        inFlight.incrementAndGet();
        w.getChunkAtAsync(cx, cz, true).whenComplete((chunk, ex) -> {
            if (ex != null || chunk == null) {
                inFlight.decrementAndGet();
                return;
            }
            plugin.getServer().getRegionScheduler().execute(plugin, w, cx, cz, () -> {
                ChunkSnapshot snap;
                try {
                    snap = chunk.getChunkSnapshot(false, false, false);
                } catch (Throwable t) {
                    inFlight.decrementAndGet();
                    return;
                }
                plugin.getServer().getAsyncScheduler().runNow(plugin,
                        task -> {
                            try {
                                scanSnapshot(snap, cx, cz);
                            } finally {
                                inFlight.decrementAndGet();
                            }
                        });
            });
        });
    }

    private void scanSnapshot(ChunkSnapshot snap, int cx, int cz) {
        final Set<String> want = wanted;
        final Set<Material> confMats = confuserMaterials;
        final int lo = yLo;
        final int hi = yHi;
        final int baseX = cx << 4;
        final int baseZ = cz << 4;
        chunksScanned.incrementAndGet();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (oreQueued.get() < RESULT_CAP) {
                    for (int y = lo; y <= hi; y++) {
                        String label = OreCatalog.labelFor(snap.getBlockType(x, y, z));
                        if (label != null && want.contains(label)) {
                            oresFound.incrementAndGet();
                            // Only queue ore with an air-exposed face we can aim at;
                            // a fully buried block would just photograph as rock.
                            double[] eye = viewpoint(snap, x, y, z, baseX, baseZ);
                            if (eye != null) {
                                oreByClass.computeIfAbsent(label, k -> new ConcurrentLinkedQueue<>())
                                        .add(new OrePos(baseX + x, y, baseZ + z, label,
                                                eye[0], eye[1], eye[2]));
                                oreQueued.incrementAndGet();
                            }
                        }
                    }
                }
                if (!confMats.isEmpty() && confuserQueued.get() < CONFUSER_CAP) {
                    for (int y = confLo; y <= confHi; y++) {
                        if (confMats.contains(snap.getBlockType(x, y, z))) {
                            double[] eye = viewpoint(snap, x, y, z, baseX, baseZ);
                            if (eye != null) {
                                confusers.add(new OrePos(baseX + x, y, baseZ + z, "",
                                        eye[0], eye[1], eye[2]));
                                confuserQueued.incrementAndGet();
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Find a camera position with line of sight to the block at local (lx,ly,lz):
     * the air-exposed face with the most open space, stepped out into that air.
     * Returns world {@code {ex,ey,ez}}, or {@code null} if no in-snapshot air face
     * (the block is buried and can't be cleanly photographed).
     */
    private double[] viewpoint(ChunkSnapshot snap, int lx, int ly, int lz, int baseX, int baseZ) {
        int bestDir = -1;
        int bestRun = 0;
        for (int di = 0; di < DIRS.length; di++) {
            int[] d = DIRS[di];
            int run = 0;
            for (int step = 1; step <= MAX_RUN; step++) {
                int nx = lx + d[0] * step;
                int ny = ly + d[1] * step;
                int nz = lz + d[2] * step;
                if (nx < 0 || nx > 15 || nz < 0 || nz > 15
                        || ny < worldMin || ny >= worldMax) {
                    break;  // out of the snapshot/world - can't confirm air
                }
                if (!snap.getBlockType(nx, ny, nz).isAir()) {
                    break;
                }
                run++;
            }
            if (run > bestRun) {
                bestRun = run;
                bestDir = di;
            }
        }
        if (bestDir < 0) {
            return null;
        }
        int[] d = DIRS[bestDir];
        double dist = Math.max(MIN_DIST, Math.min(DESIRED_DIST, bestRun - 0.2));
        double cx = baseX + lx + 0.5;
        double cy = ly + 0.5;
        double cz = baseZ + lz + 0.5;
        return new double[]{cx + d[0] * dist, cy + d[1] * dist, cz + d[2] * dist};
    }
}
