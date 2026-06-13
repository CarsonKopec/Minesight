package com.minesight.farm;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Folia-safe successor to the 1.8.9 {@code ServerOreLocator}.
 *
 * <p>The 1.8.9 version single-threaded chunk gen inside each client. Here one
 * headless Folia server force-generates and scans chunks for wanted ore using
 * the regionized scheduler, so chunks scattered across the map are generated on
 * different CPU cores - the real reason 2.0 scales to many cameras on one box.
 *
 * <p>Threading contract (all enforced internally):
 * <ul>
 *   <li>{@link #configure} / {@link #start} / {@link #stop} - call from any
 *       plugin thread (cheap, just sets fields).</li>
 *   <li>{@link #pump} - call on a repeating GLOBAL-region task. It only launches
 *       async chunk loads; it never touches blocks itself.</li>
 *   <li>Chunk gen is async ({@link World#getChunkAtAsync}); the per-chunk
 *       {@link ChunkSnapshot} is taken on the owning region thread, then the
 *       heavy block scan runs on the async scheduler (snapshots are thread-safe
 *       to read). Results land in a {@link ConcurrentLinkedQueue} the caller
 *       drains from any thread.</li>
 * </ul>
 */
public final class FoliaOreLocator {

    /** A located ore block in world coordinates. */
    public record OrePos(int x, int y, int z, String label) {
    }

    private static final int RESULT_CAP = 512;
    /** How many chunk gen+scan pipelines may be outstanding at once. */
    private static final int MAX_IN_FLIGHT = 12;

    private final Plugin plugin;
    private final ConcurrentLinkedQueue<OrePos> results = new ConcurrentLinkedQueue<>();

    private volatile boolean running;
    private volatile World world;
    private volatile Set<String> wanted = Set.of();
    private volatile int yLo;
    private volatile int yHi;

    private volatile List<long[]> chunkOrder = new ArrayList<>();
    private final AtomicInteger cursor = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger chunksScanned = new AtomicInteger();
    private final AtomicInteger oresFound = new AtomicInteger();

    public FoliaOreLocator(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * (Re)aim the locator at an area. Clears scan progress but keeps already
     * queued results so a re-aim mid-session doesn't drop confirmed ore.
     */
    public synchronized void configure(World world, int centerX, int centerZ, int radiusBlocks,
                                       Set<String> wantedLabels, int yLoIn, int yHiIn) {
        this.world = world;
        this.wanted = Set.copyOf(wantedLabels);
        this.yLo = Math.max(world.getMinHeight(), yLoIn);
        this.yHi = Math.min(world.getMaxHeight() - 1, yHiIn);

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
        return results.size();
    }

    public int chunksScanned() {
        return chunksScanned.get();
    }

    public int oresFound() {
        return oresFound.get();
    }

    /** Drain one located ore, or {@code null} if none queued yet. */
    public OrePos poll() {
        return results.poll();
    }

    /** Look at the next located ore without removing it. */
    public OrePos peek() {
        return results.peek();
    }

    public void clearResults() {
        results.clear();
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
                && results.size() < RESULT_CAP) {
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
            // Hop to the region that owns this chunk to snapshot it safely...
            plugin.getServer().getRegionScheduler().execute(plugin, w, cx, cz, () -> {
                ChunkSnapshot snap;
                try {
                    snap = chunk.getChunkSnapshot(false, false, false);
                } catch (Throwable t) {
                    inFlight.decrementAndGet();
                    return;
                }
                // ...then scan the thread-safe snapshot off the region thread.
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
        final int lo = yLo;
        final int hi = yHi;
        final int baseX = cx << 4;
        final int baseZ = cz << 4;
        chunksScanned.incrementAndGet();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = lo; y <= hi; y++) {
                    Material block = snap.getBlockType(x, y, z);
                    String label = OreCatalog.labelFor(block);
                    if (label != null && want.contains(label)) {
                        // Queue every wanted ore; the client capture pipeline
                        // verifies real visibility (raycast + pixel checks), so
                        // no fragile server-side air test here.
                        results.add(new OrePos(baseX + x, y, baseZ + z, label));
                        oresFound.incrementAndGet();
                        if (results.size() >= RESULT_CAP) {
                            return;
                        }
                    }
                }
            }
        }
    }
}
