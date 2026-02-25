package dev.chunkloader.commands;

import dev.chunkloader.ChunkLoader;
import dev.chunkloader.data.GenerationJob;
import dev.chunkloader.data.GenerationJob.Shape;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;

public class ChunkLoaderCommand implements CommandExecutor, TabCompleter {

    private final ChunkLoader plugin;
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String HDR = "<dark_gray>◈ <gradient:#7B2FBE:#00C8FF>ChunkLoader</gradient> <dark_gray>│ ";

    public ChunkLoaderCommand(ChunkLoader plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("chunkloader.use")) {
            sender.sendMessage(MM.deserialize(HDR + "<red>No permission."));
            return true;
        }
        if (args.length == 0) { sendHelp(sender); return true; }

        switch (args[0].toLowerCase()) {

            // /cl start <world> <radius> [shape] [centerX] [centerZ]
            case "start" -> {
                if (args.length < 3) {
                    sender.sendMessage(MM.deserialize(HDR + "<red>Usage: /cl start <world> <radius> [square|circle] [centerX] [centerZ]"));
                    return true;
                }

                World world = Bukkit.getWorld(args[1]);
                if (world == null) {
                    sender.sendMessage(MM.deserialize(HDR + "<red>World <white>" + args[1] + "</white> not found."));
                    return true;
                }

                int radius;
                try { radius = Integer.parseInt(args[2]); }
                catch (NumberFormatException e) {
                    sender.sendMessage(MM.deserialize(HDR + "<red>Radius must be a number."));
                    return true;
                }

                int maxRadius = plugin.getConfig().getInt("max-radius", 5000);
                if (radius > maxRadius) {
                    sender.sendMessage(MM.deserialize(HDR + "<red>Max radius is <white>" + maxRadius + "</white>."));
                    return true;
                }

                Shape shape = Shape.SQUARE;
                if (args.length >= 4) {
                    try { shape = Shape.valueOf(args[3].toUpperCase()); }
                    catch (IllegalArgumentException e) {
                        sender.sendMessage(MM.deserialize(HDR + "<red>Shape must be <white>square</white> or <white>circle</white>."));
                        return true;
                    }
                }

                // Centre defaults to world spawn; can override with X Z args
                int cx = world.getSpawnLocation().getBlockX() >> 4;
                int cz = world.getSpawnLocation().getBlockZ() >> 4;
                if (args.length >= 6) {
                    try {
                        cx = Integer.parseInt(args[4]) >> 4; // convert block → chunk
                        cz = Integer.parseInt(args[5]) >> 4;
                    } catch (NumberFormatException e) {
                        sender.sendMessage(MM.deserialize(HDR + "<red>Center X/Z must be numbers."));
                        return true;
                    }
                } else if (sender instanceof Player p) {
                    // Default to player's current position if in-game
                    cx = p.getLocation().getBlockX() >> 4;
                    cz = p.getLocation().getBlockZ() >> 4;
                }

                GenerationJob job = plugin.getTaskManager().startJob(world.getName(), cx, cz, radius, shape);
                if (job == null) {
                    sender.sendMessage(MM.deserialize(HDR + "<red>Failed to start job."));
                    return true;
                }

                sender.sendMessage(MM.deserialize(HDR + "<green>Generation started!"));
                sender.sendMessage(MM.deserialize(
                    "  <dark_gray>› <gray>World   <dark_gray>│ <white>" + world.getName()));
                sender.sendMessage(MM.deserialize(
                    "  <dark_gray>› <gray>Centre  <dark_gray>│ <white>" + (cx << 4) + ", " + (cz << 4)));
                sender.sendMessage(MM.deserialize(
                    "  <dark_gray>› <gray>Radius  <dark_gray>│ <white>" + radius + " chunks <gray>(" + (radius * 16) + " blocks)"));
                sender.sendMessage(MM.deserialize(
                    "  <dark_gray>› <gray>Shape   <dark_gray>│ <white>" + shape.name().toLowerCase()));
                sender.sendMessage(MM.deserialize(
                    "  <dark_gray>› <gray>Total   <dark_gray>│ <white>" + job.getTotal() + " chunks"));
                sender.sendMessage(MM.deserialize(
                    "  <dark_gray>› <gray>ID      <dark_gray>│ <white>" + job.getId().toString().substring(0, 8)));
            }

            // /cl pause <id>
            case "pause" -> {
                if (args.length < 2) { sender.sendMessage(MM.deserialize(HDR + "<red>Usage: /cl pause <id>")); return true; }
                UUID id = plugin.getTaskManager().resolveId(args[1]).orElse(null);
                if (id == null || !plugin.getTaskManager().pauseJob(id)) {
                    sender.sendMessage(MM.deserialize(HDR + "<red>Job not found or already finished."));
                    return true;
                }
                GenerationJob job = plugin.getTaskManager().getJob(id);
                String state = job.isPaused() ? "<yellow>paused" : "<green>resumed";
                sender.sendMessage(MM.deserialize(HDR + "Job " + state + "<dark_gray>."));
            }

            // /cl cancel <id>
            case "cancel" -> {
                if (args.length < 2) { sender.sendMessage(MM.deserialize(HDR + "<red>Usage: /cl cancel <id>")); return true; }
                UUID id = plugin.getTaskManager().resolveId(args[1]).orElse(null);
                if (id == null || !plugin.getTaskManager().cancelJob(id)) {
                    sender.sendMessage(MM.deserialize(HDR + "<red>Job not found."));
                    return true;
                }
                sender.sendMessage(MM.deserialize(HDR + "<yellow>Job cancelled."));
            }

            // /cl status [id]
            case "status" -> {
                Collection<GenerationJob> active = plugin.getTaskManager().getActiveJobs();

                if (args.length >= 2) {
                    // Specific job
                    UUID id = plugin.getTaskManager().resolveId(args[1]).orElse(null);
                    GenerationJob job = id != null ? plugin.getTaskManager().getJob(id) : null;
                    if (job == null) { sender.sendMessage(MM.deserialize(HDR + "<red>Job not found.")); return true; }
                    printJobStatus(sender, job);
                } else if (active.isEmpty()) {
                    sender.sendMessage(MM.deserialize(HDR + "<gray>No active generation jobs."));
                } else {
                    sender.sendMessage(MM.deserialize(HDR + "<white>" + active.size() + " active job(s)<dark_gray>:"));
                    for (GenerationJob job : active) printJobStatus(sender, job);
                }
            }

            // /cl list — show all jobs including finished
            case "list" -> {
                Collection<GenerationJob> all = plugin.getTaskManager().getAllJobs();
                if (all.isEmpty()) { sender.sendMessage(MM.deserialize(HDR + "<gray>No jobs on record.")); return true; }
                sender.sendMessage(MM.deserialize(HDR + "<white>" + all.size() + " total job(s)<dark_gray>:"));
                for (GenerationJob j : all) {
                    String status = j.isFinished() ? "<green>✔ done" : j.isCancelled() ? "<red>✘ cancelled" : j.isPaused() ? "<yellow>⏸ paused" : "<aqua>⟳ running";
                    sender.sendMessage(MM.deserialize(String.format(
                        "  <dark_gray>› <white>%s <dark_gray>│ <white>%s <dark_gray>│ %s <dark_gray>│ <gray>%.1f%%",
                        j.getId().toString().substring(0, 8),
                        j.getWorldName(),
                        status,
                        j.getProgress()
                    )));
                }
            }

            // /cl reload
            case "reload" -> {
                if (!sender.hasPermission("chunkloader.admin")) {
                    sender.sendMessage(MM.deserialize(HDR + "<red>No permission."));
                    return true;
                }
                plugin.reloadConfig();
                sender.sendMessage(MM.deserialize(HDR + "<green>Config reloaded."));
            }

            default -> sendHelp(sender);
        }
        return true;
    }

    private void printJobStatus(CommandSender sender, GenerationJob job) {
        String statusColor = job.isPaused() ? "<yellow>" : "<aqua>";
        sender.sendMessage(MM.deserialize("<dark_gray>  ┌─ <white>" + job.getId().toString().substring(0, 8) + " <dark_gray>│ <white>" + job.getWorldName()));
        sender.sendMessage(MM.deserialize(String.format(
            "<dark_gray>  │ <gray>Progress  <dark_gray>│ " + statusColor + "%.1f%% <dark_gray>(%d / %d chunks)",
            job.getProgress(), job.getGenerated(), job.getTotal())));

        // Visual progress bar
        int barLen  = 30;
        int filled  = (int) (barLen * job.getProgress() / 100.0);
        String bar  = "<green>" + "█".repeat(filled) + "<dark_gray>" + "░".repeat(barLen - filled);
        sender.sendMessage(MM.deserialize("  <dark_gray>│            <dark_gray>[" + bar + "<dark_gray>]"));

        sender.sendMessage(MM.deserialize(String.format(
            "<dark_gray>  │ <gray>Speed     <dark_gray>│ <white>%.1f chunks/s", job.getChunksPerSecond())));
        sender.sendMessage(MM.deserialize(
            "<dark_gray>  │ <gray>ETA       <dark_gray>│ <white>" + job.formatEta()));
        sender.sendMessage(MM.deserialize(
            "<dark_gray>  │ <gray>Shape     <dark_gray>│ <white>" + job.getShape().name().toLowerCase()));
        if (job.isPaused())
            sender.sendMessage(MM.deserialize("<dark_gray>  │ <yellow>           PAUSED"));
        sender.sendMessage(MM.deserialize("<dark_gray>  └────────────────────────────────"));
    }

    private void sendHelp(CommandSender s) {
        s.sendMessage(MM.deserialize("<dark_gray>╔══ <gradient:#7B2FBE:#00C8FF>ChunkLoader</gradient> <dark_gray>══╗"));
        for (String line : new String[]{
            "│ <gray>/cl start <world> <radius> [shape] [x] [z]",
            "│    <dark_gray>shape: square (default) | circle",
            "│    <dark_gray>x/z:   block coords of centre (default: your pos)",
            "│ <gray>/cl pause <id>       <dark_gray>– Pause/resume",
            "│ <gray>/cl cancel <id>      <dark_gray>– Cancel job",
            "│ <gray>/cl status [id]      <dark_gray>– Progress bar",
            "│ <gray>/cl list             <dark_gray>– All jobs",
            "│ <gray>/cl reload           <dark_gray>– Reload config",
            "╚════════════════════════════════╝"
        }) s.sendMessage(MM.deserialize("<dark_gray>" + line));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        if (args.length == 1)
            return List.of("start", "pause", "cancel", "status", "list", "reload");
        if (args.length == 2) {
            return switch (args[0].toLowerCase()) {
                case "start" -> Bukkit.getWorlds().stream().map(World::getName).toList();
                case "pause", "cancel", "status" -> plugin.getTaskManager().getActiveJobs().stream()
                    .map(j -> j.getId().toString().substring(0, 8))
                    .toList();
                default -> List.of();
            };
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("start"))
            return List.of("square", "circle");
        return List.of();
    }
}
