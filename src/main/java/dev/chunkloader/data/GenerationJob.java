package dev.chunkloader.data;

import org.bukkit.World;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public class GenerationJob {

    public enum Shape { SQUARE, CIRCLE }

    private final UUID id;
    private final String worldName;
    private final int centerX;   // chunk coordinates
    private final int centerZ;
    private final int radius;    // in chunks
    private final Shape shape;

    private boolean paused    = false;
    private boolean cancelled = false;
    private boolean finished  = false;

    private AtomicLong generated = new AtomicLong(0);
    private final long total;
    private final long startedAt;
    private long pausedAt = 0;
    private long totalPausedMs = 0;

    public GenerationJob(String worldName, int centerX, int centerZ, int radius, Shape shape) {
        this.id        = UUID.randomUUID();
        this.worldName = worldName;
        this.centerX   = centerX;
        this.centerZ   = centerZ;
        this.radius    = radius;
        this.shape     = shape;
        this.startedAt = System.currentTimeMillis();

        // Calculate total chunks for this shape
        if (shape == Shape.CIRCLE) {
            long count = 0;
            for (int dx = -radius; dx <= radius; dx++)
                for (int dz = -radius; dz <= radius; dz++)
                    if (dx * dx + dz * dz <= (long) radius * radius) count++;
            this.total = count;
        } else {
            long side = radius * 2L + 1;
            this.total = side * side;
        }
    }

    // For loading from disk
    public GenerationJob(UUID id, String worldName, int centerX, int centerZ,
                         int radius, Shape shape, long generated, long total, long startedAt) {
        this.id        = id;
        this.worldName = worldName;
        this.centerX   = centerX;
        this.centerZ   = centerZ;
        this.radius    = radius;
        this.shape     = shape;
        this.generated = new AtomicLong(generated);
        this.total     = total;
        this.startedAt = startedAt;
    }

    public void incrementGenerated() { generated.incrementAndGet(); }

    public double getProgress() { return total > 0 ? (generated.get() * 100.0 / total) : 0; }

    public long getElapsedSeconds() {
        long elapsed = System.currentTimeMillis() - startedAt - totalPausedMs;
        return elapsed / 1000;
    }

    public double getChunksPerSecond() {
        long elapsed = getElapsedSeconds();
        return elapsed > 0 ? (double) generated.get() / elapsed : 0;
    }

    public long getEtaSeconds() {
        double cps = getChunksPerSecond();
        if (cps <= 0) return -1;
        return (long) ((total - generated.get()) / cps);
    }

    // ── Getters ──────────────────────────────────────────────────────────────
    public UUID getId()          { return id; }
    public String getWorldName() { return worldName; }
    public int getCenterX()      { return centerX; }
    public int getCenterZ()      { return centerZ; }
    public int getRadius()       { return radius; }
    public Shape getShape()      { return shape; }
    public long getGenerated()   { return generated.get(); }
    public long getTotal()       { return total; }
    public long getStartedAt()   { return startedAt; }
    public boolean isPaused()    { return paused; }
    public boolean isCancelled() { return cancelled; }
    public boolean isFinished()  { return finished; }

    public void setPaused(boolean p) {
        if (p && !paused) pausedAt = System.currentTimeMillis();
        if (!p && paused) totalPausedMs += System.currentTimeMillis() - pausedAt;
        this.paused = p;
    }
    public void setCancelled(boolean c) { this.cancelled = c; }
    public void setFinished(boolean f)  { this.finished  = f; }

    public String formatEta() {
        long s = getEtaSeconds();
        if (s < 0)    return "calculating...";
        if (s < 60)   return s + "s";
        if (s < 3600) return (s / 60) + "m " + (s % 60) + "s";
        return (s / 3600) + "h " + ((s % 3600) / 60) + "m";
    }

    @Override
    public String toString() {
        return String.format("[%s] %s r=%d %.1f%% (%d/%d)",
            id.toString().substring(0, 8), worldName, radius, getProgress(), generated.get(), total);
    }
}
