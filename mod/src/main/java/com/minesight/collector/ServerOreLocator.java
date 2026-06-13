package com.minesight.collector;

import net.minecraft.util.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Server-side ore finder for the integrated (singleplayer) server.
 *
 * Runs on the SERVER thread: force-generates chunks around a center and scans
 * them for the wanted ores that are actually cave-exposed (>=1 air neighbor),
 * so the collector can teleport the camera straight to confirmed, photographable
 * ore instead of teleporting blindly and hoping. Found positions go into a
 * thread-safe queue the client-thread controller drains.
 */
public class ServerOreLocator {
    private static final int RESULT_CAP = 256;

    private final ConcurrentLinkedQueue<BlockPos> results = new ConcurrentLinkedQueue<BlockPos>();
    private volatile boolean scanning;

    private List<int[]> chunkOrder = new ArrayList<int[]>();
    private int cursor;
    private Set<String> wanted;
    private int yLo;
    private int yHi;

    /** (Re)aim the locator at an area; clears progress, keeps queued results. */
    public synchronized void configure(BlockPos center, int radiusBlocks, Set<String> wantedLabels,
                                       int yLoIn, int yHiIn) {
        this.wanted = wantedLabels;
        this.yLo = Math.max(1, yLoIn);
        this.yHi = Math.min(254, yHiIn);
        final int ccx = center.getX() >> 4;
        final int ccz = center.getZ() >> 4;
        int rc = Math.max(1, radiusBlocks >> 4);
        List<int[]> order = new ArrayList<int[]>();
        for (int dx = -rc; dx <= rc; dx++) {
            for (int dz = -rc; dz <= rc; dz++) {
                order.add(new int[]{ccx + dx, ccz + dz});
            }
        }
        Collections.sort(order, new Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                long da = (long) (a[0] - ccx) * (a[0] - ccx) + (long) (a[1] - ccz) * (a[1] - ccz);
                long db = (long) (b[0] - ccx) * (b[0] - ccx) + (long) (b[1] - ccz) * (b[1] - ccz);
                return Long.compare(da, db);
            }
        });
        this.chunkOrder = order;
        this.cursor = 0;
    }

    public boolean isScanning() {
        return scanning;
    }

    public boolean exhausted() {
        return cursor >= chunkOrder.size();
    }

    public int available() {
        return results.size();
    }

    public BlockPos poll() {
        return results.poll();
    }

    public void markScanning() {
        scanning = true;
    }

    /**
     * Generate + scan up to maxChunks chunks from the cursor. MUST run on the
     * server thread (schedule via integratedServer.addScheduledTask). Cheap-ish
     * but bounded: keep maxChunks small to avoid server tick spikes.
     */
    public void scanBatch(WorldServer world, int maxChunks) {
        try {
            for (int n = 0; n < maxChunks && cursor < chunkOrder.size(); n++) {
                if (results.size() >= RESULT_CAP) break;
                int[] c = chunkOrder.get(cursor++);
                scanChunk(world, c[0], c[1]);
            }
        } catch (Exception ignored) {
            // Worst case we just find fewer ores; never crash the server tick.
        } finally {
            scanning = false;
        }
    }

    private void scanChunk(WorldServer world, int cx, int cz) {
        try {
            world.theChunkProviderServer.loadChunk(cx, cz);  // generate if absent
        } catch (Exception e) {
            return;
        }
        int baseX = cx << 4;
        int baseZ = cz << 4;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = baseX; x < baseX + 16; x++) {
            for (int z = baseZ; z < baseZ + 16; z++) {
                for (int y = yLo; y <= yHi; y++) {
                    pos.set(x, y, z);
                    String label = OreScanner.labelFor(world.getBlockState(pos).getBlock());
                    if (label != null && wanted.contains(label) && hasAirNeighbor(world, x, y, z)) {
                        results.add(new BlockPos(x, y, z));
                        if (results.size() >= RESULT_CAP) return;
                    }
                }
            }
        }
    }

    private static boolean hasAirNeighbor(WorldServer world, int x, int y, int z) {
        return world.isAirBlock(new BlockPos(x + 1, y, z))
                || world.isAirBlock(new BlockPos(x - 1, y, z))
                || world.isAirBlock(new BlockPos(x, y + 1, z))
                || world.isAirBlock(new BlockPos(x, y - 1, z))
                || world.isAirBlock(new BlockPos(x, y, z + 1))
                || world.isAirBlock(new BlockPos(x, y, z - 1));
    }
}
