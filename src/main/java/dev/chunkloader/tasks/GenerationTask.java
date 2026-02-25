package dev.chunkloader.tasks;

import dev.chunkloader.ChunkLoader;
import dev.chunkloader.data.GenerationJob;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The generation engine.
 *
 * How it works (Chunky-style):
 *  - Each tick we fire N async chunk generation requests in parallel.
 *  - Paper/Purpur's getChunkAtAsync() generates the chunk on a worker thread
 *    and writes it to disk — the main thread is never blocked.
 *  - We use a semaphore-style counter so we never flood the queue with
 *    more in-flight requests than the configured concurrency limit.
 *  - Spiral iteration order (centre → outward) so the centre of the
 *    generation area is done first, just like Chunky.
 */
public class GenerationTask extends BukkitRunnable {

    private final ChunkLoader plugin;
    private final GenerationJob job;
    private final World world;
    private final Iterator<long[]> chunkIterator;

    // How many async chunk requests are currently in-flight
    private final AtomicInteger inFlight = new AtomicInteger(0);

    // Max simultaneous in-flight requests (configurable)
    private final int maxConcurrent;

    // How often (ticks) we log progress to console
    private static final int PROGRESS_INTERVAL_TICKS = 40; // every 2 seconds
    private int ticksSinceProgress = 0;

    public GenerationTask(ChunkLoader plugin, GenerationJob job, World world) {
        this.plugin        = plugin;
        this.job           = job;
        this.world         = world;
        this.maxConcurrent = plugin.getConfig().getInt("max-concurrent-chunks", 8);
        this.chunkIterator = new SpiralIterator(job.getCenterX(), job.getCenterZ(), job.getRadius());
    }

    @Override
    public void run() {
        if (job.isPaused()) return;
        if (job.isCancelled()) {
            cancel();
            return;
        }

        // Fire as many new requests as we have slots for
        while (inFlight.get() < maxConcurrent && chunkIterator.hasNext()) {
            long[] coord = chunkIterator.next();
            int cx = (int) coord[0];
            int cz = (int) coord[1];

            // Skip already-generated chunks for speed (no regeneration needed)
            if (world.isChunkGenerated(cx, cz)) {
                job.incrementGenerated();
                continue;
            }

            inFlight.incrementAndGet();

            // Paper async chunk generation — does NOT block the main thread
            world.getChunkAtAsync(cx, cz, true).thenAccept(chunk -> {
                // Chunk is generated and saved to disk
                // Unload it immediately to keep memory usage low
                // (we're pre-generating, not keeping it loaded)
                world.unloadChunkRequest(cx, cz);
                job.incrementGenerated();
                inFlight.decrementAndGet();
            }).exceptionally(ex -> {
                plugin.getLogger().warning("Failed to generate chunk " + cx + "," + cz + ": " + ex.getMessage());
                inFlight.decrementAndGet();
                return null;
            });
        }

        // Check completion: iterator exhausted AND no in-flight requests remain
        if (!chunkIterator.hasNext() && inFlight.get() == 0) {
            job.setFinished(true);
            cancel();
            plugin.getTaskManager().onJobFinished(job);
            return;
        }

        // Progress logging
        ticksSinceProgress++;
        if (ticksSinceProgress >= PROGRESS_INTERVAL_TICKS) {
            ticksSinceProgress = 0;
            logProgress();
        }
    }

    private void logProgress() {
        long done  = job.getGenerated();
        long total = job.getTotal();
        double pct = total > 0 ? (done * 100.0 / total) : 0;
        long elapsed = (System.currentTimeMillis() - job.getStartedAt()) / 1000;
        double cps   = elapsed > 0 ? (double) done / elapsed : 0;
        long eta     = cps > 0 ? (long) ((total - done) / cps) : -1;

        plugin.getLogger().info(String.format(
            "[ChunkLoader] %s | %d/%d (%.1f%%) | %.1f c/s | ETA: %s",
            world.getName(), done, total, pct, cps, formatEta(eta)
        ));
    }

    private String formatEta(long seconds) {
        if (seconds < 0) return "calculating...";
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m " + (seconds % 60) + "s";
        return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
    }

    /**
     * Generates chunk coordinates in a square spiral from centre outward.
     * This matches Chunky's default shape and ensures the inner area is
     * always done first.
     */
    private static class SpiralIterator implements Iterator<long[]> {

        private final int centerX;
        private final int centerZ;
        private final int radius;
        private int x = 0, z = 0;
        private int dx = 0, dz = -1;
        private final int total;
        private int count = 0;
        private long skipped = 0; // chunks already counted as skipped

        SpiralIterator(int centerX, int centerZ, int radius) {
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.radius  = radius;
            int side = radius * 2 + 1;
            this.total = side * side;
        }

        @Override
        public boolean hasNext() {
            return count < total;
        }

        @Override
        public long[] next() {
            while (count < total) {
                int outX = centerX + x;
                int outZ = centerZ + z;
                count++;

                // Advance spiral
                if (x == z || (x < 0 && x == -z) || (x > 0 && x == 1 - z)) {
                    int tmp = dx;
                    dx = -dz;
                    dz = tmp;
                }
                x += dx;
                z += dz;

                // Clamp to radius
                if (Math.abs(outX - centerX) <= radius && Math.abs(outZ - centerZ) <= radius) {
                    return new long[]{outX, outZ};
                }
            }
            return null;
        }
    }
}
