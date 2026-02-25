package dev.chunkloader;

import dev.chunkloader.commands.ChunkLoaderCommand;
import dev.chunkloader.managers.TaskManager;
import org.bukkit.plugin.java.JavaPlugin;

public class ChunkLoader extends JavaPlugin {

    private static ChunkLoader instance;
    private TaskManager taskManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        taskManager = new TaskManager(this);
        taskManager.start();

        getCommand("chunkloader").setExecutor(new ChunkLoaderCommand(this));

        getLogger().info("╔══════════════════════════════════╗");
        getLogger().info("║       ChunkLoader v1.0.0         ║");
        getLogger().info("║  Fast Pre-Gen for Purpur 1.21.x  ║");
        getLogger().info("╚══════════════════════════════════╝");
        getLogger().info(taskManager.getJobCount() + " job(s) resumed from disk.");
    }

    @Override
    public void onDisable() {
        if (taskManager != null) taskManager.shutdown();
        getLogger().info("ChunkLoader disabled. Jobs saved.");
    }

    public static ChunkLoader getInstance() { return instance; }
    public TaskManager getTaskManager()     { return taskManager; }
}
