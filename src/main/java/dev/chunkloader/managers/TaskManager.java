package dev.chunkloader.managers;

import dev.chunkloader.ChunkLoader;
import dev.chunkloader.data.GenerationJob;
import dev.chunkloader.data.GenerationJob.Shape;
import dev.chunkloader.tasks.GenerationTask;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class TaskManager {

    private final ChunkLoader plugin;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    // Active jobs
    private final Map<UUID, GenerationJob>  jobs     = new ConcurrentHashMap<>();
    // Job ID → running BukkitTask
    private final Map<UUID, BukkitTask>     tasks    = new ConcurrentHashMap<>();

    private final File dataFile;
    private BukkitTask progressBroadcastTask;

    public TaskManager(ChunkLoader plugin) {
        this.plugin   = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "jobs.yml");
    }

    public void start() {
        loadJobs();
        startProgressBroadcast();
    }

    public void shutdown() {
        // Cancel all running tasks gracefully
        for (BukkitTask t : tasks.values()) t.cancel();
        tasks.clear();
        if (progressBroadcastTask != null) progressBroadcastTask.cancel();
        saveJobs();
    }

    // ── Job lifecycle ─────────────────────────────────────────────────────────

    /**
     * Start a new generation job.
     * @param worldName world to generate in
     * @param centerX   centre chunk X
     * @param centerZ   centre chunk Z
     * @param radius    radius in chunks
     * @param shape     SQUARE or CIRCLE
     * @return the created job, or null if the world doesn't exist
     */
    public GenerationJob startJob(String worldName, int centerX, int centerZ, int radius, Shape shape) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;

        GenerationJob job = new GenerationJob(worldName, centerX, centerZ, radius, shape);
        jobs.put(job.getId(), job);

        int intervalTicks = plugin.getConfig().getInt("task-interval-ticks", 1);
        GenerationTask task = new GenerationTask(plugin, job, world);
        BukkitTask bt = task.runTaskTimer(plugin, 0L, intervalTicks);
        tasks.put(job.getId(), bt);

        saveJobs();
        plugin.getLogger().info("[ChunkLoader] Started job " + job);
        return job;
    }

    public boolean pauseJob(UUID id) {
        GenerationJob job = jobs.get(id);
        if (job == null || job.isFinished() || job.isCancelled()) return false;
        job.setPaused(!job.isPaused());
        saveJobs();
        return true;
    }

    public boolean cancelJob(UUID id) {
        GenerationJob job = jobs.get(id);
        if (job == null) return false;
        job.setCancelled(true);
        BukkitTask bt = tasks.remove(id);
        if (bt != null) bt.cancel();
        jobs.remove(id);
        saveJobs();
        plugin.getLogger().info("[ChunkLoader] Cancelled job " + id);
        return true;
    }

    /** Called by GenerationTask when a job completes. */
    public void onJobFinished(GenerationJob job) {
        tasks.remove(job.getId());
        plugin.getLogger().info(String.format(
            "[ChunkLoader] ✔ Job finished! World: %s | %d chunks generated | Took: %ds",
            job.getWorldName(), job.getGenerated(), job.getElapsedSeconds()
        ));

        // Broadcast completion to all online staff
        String msg = String.format(
            "<gradient:#00C8FF:#7B2FBE>ChunkLoader</gradient> <dark_gray>│ " +
            "<green>✔ Generation complete! <white>%s <gray>— <white>%d<gray> chunks in <white>%ds",
            job.getWorldName(), job.getGenerated(), job.getElapsedSeconds()
        );
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("chunkloader.notify")) {
                p.sendMessage(MM.deserialize(msg));
            }
        }

        saveJobs(); // keep finished job on disk for reference
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public Collection<GenerationJob> getActiveJobs() {
        return jobs.values().stream()
            .filter(j -> !j.isFinished() && !j.isCancelled())
            .toList();
    }

    public Collection<GenerationJob> getAllJobs() { return jobs.values(); }
    public GenerationJob getJob(UUID id)          { return jobs.get(id); }
    public int getJobCount()                      { return (int) getActiveJobs().stream().count(); }

    public Optional<UUID> resolveId(String input) {
        for (GenerationJob j : jobs.values()) {
            String full = j.getId().toString();
            if (full.equals(input) || full.startsWith(input)) return Optional.of(j.getId());
        }
        try { return Optional.of(UUID.fromString(input)); }
        catch (IllegalArgumentException e) { return Optional.empty(); }
    }

    // ── Progress broadcast ────────────────────────────────────────────────────

    private void startProgressBroadcast() {
        int intervalSeconds = plugin.getConfig().getInt("progress-broadcast-seconds", 30);
        if (intervalSeconds <= 0) return;

        progressBroadcastTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Collection<GenerationJob> active = getActiveJobs();
            if (active.isEmpty()) return;

            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.hasPermission("chunkloader.notify")) continue;
                for (GenerationJob job : active) {
                    p.sendMessage(MM.deserialize(String.format(
                        "<dark_gray>◈ <gradient:#7B2FBE:#00C8FF>ChunkLoader</gradient> <dark_gray>│ " +
                        "<white>%s <dark_gray>│ " +
                        "<aqua>%.1f%% <dark_gray>(%d/%d) <dark_gray>│ " +
                        "<gray>%.1f c/s <dark_gray>│ " +
                        "<gray>ETA: <white>%s" +
                        (job.isPaused() ? " <yellow>[PAUSED]" : ""),
                        job.getWorldName(),
                        job.getProgress(),
                        job.getGenerated(),
                        job.getTotal(),
                        job.getChunksPerSecond(),
                        job.formatEta()
                    )));
                }
            }
        }, intervalSeconds * 20L, intervalSeconds * 20L);
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private void loadJobs() {
        if (!dataFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);
        if (!cfg.isConfigurationSection("jobs")) return;

        for (String key : cfg.getConfigurationSection("jobs").getKeys(false)) {
            String p = "jobs." + key + ".";
            try {
                UUID id        = UUID.fromString(key);
                String world   = cfg.getString(p + "world");
                int cx         = cfg.getInt(p + "center-x");
                int cz         = cfg.getInt(p + "center-z");
                int radius     = cfg.getInt(p + "radius");
                Shape shape    = Shape.valueOf(cfg.getString(p + "shape", "SQUARE"));
                long gen       = cfg.getLong(p + "generated");
                long total     = cfg.getLong(p + "total");
                long started   = cfg.getLong(p + "started");
                boolean finished  = cfg.getBoolean(p + "finished");
                boolean cancelled = cfg.getBoolean(p + "cancelled");

                GenerationJob job = new GenerationJob(id, world, cx, cz, radius, shape, gen, total, started);
                job.setFinished(finished);
                job.setCancelled(cancelled);
                jobs.put(id, job);

                // Resume unfinished jobs automatically
                if (!finished && !cancelled) {
                    World w = Bukkit.getWorld(world);
                    if (w != null) {
                        int intervalTicks = plugin.getConfig().getInt("task-interval-ticks", 1);
                        GenerationTask task = new GenerationTask(plugin, job, w);
                        BukkitTask bt = task.runTaskTimer(plugin, 20L, intervalTicks); // 1s delay on resume
                        tasks.put(id, bt);
                        plugin.getLogger().info("[ChunkLoader] Resumed job " + job);
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to load job " + key + ": " + e.getMessage());
            }
        }
    }

    private void saveJobs() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (GenerationJob job : jobs.values()) {
            String p = "jobs." + job.getId() + ".";
            cfg.set(p + "world",     job.getWorldName());
            cfg.set(p + "center-x",  job.getCenterX());
            cfg.set(p + "center-z",  job.getCenterZ());
            cfg.set(p + "radius",    job.getRadius());
            cfg.set(p + "shape",     job.getShape().name());
            cfg.set(p + "generated", job.getGenerated());
            cfg.set(p + "total",     job.getTotal());
            cfg.set(p + "started",   job.getStartedAt());
            cfg.set(p + "finished",  job.isFinished());
            cfg.set(p + "cancelled", job.isCancelled());
        }
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            cfg.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save jobs: " + e.getMessage());
        }
    }
}
