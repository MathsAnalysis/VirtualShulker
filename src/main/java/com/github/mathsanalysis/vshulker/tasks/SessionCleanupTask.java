package com.github.mathsanalysis.vshulker.tasks;

import com.github.mathsanalysis.vshulker.VirtualShulkerPlugin;
import com.github.mathsanalysis.vshulker.manager.VirtualShulkerManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

public final class SessionCleanupTask extends BukkitRunnable {

    private final VirtualShulkerPlugin plugin;
    private final VirtualShulkerManager manager;

    public SessionCleanupTask(VirtualShulkerPlugin plugin, VirtualShulkerManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public void run() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (manager.hasOpenShulker(player) && player.getOpenInventory().getTopInventory().getViewers().isEmpty()) {
                plugin.getLogger().warning("Detected orphaned session for player: " + player.getName());
                manager.closeShulker(player, true);
            }
        }
    }

    public void start() {
        this.runTaskTimerAsynchronously(plugin, 600L, 600L);
    }
}