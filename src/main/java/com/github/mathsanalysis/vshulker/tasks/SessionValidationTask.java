package com.github.mathsanalysis.vshulker.tasks;

import com.github.mathsanalysis.vshulker.VirtualShulkerPlugin;
import com.github.mathsanalysis.vshulker.manager.VirtualShulkerManager;
import org.bukkit.scheduler.BukkitRunnable;

public final class SessionValidationTask extends BukkitRunnable {

    private final VirtualShulkerPlugin plugin;
    private final VirtualShulkerManager manager;

    public SessionValidationTask(VirtualShulkerPlugin plugin, VirtualShulkerManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public void run() {
        manager.validateAllSessions();
    }

    public void start() {
        this.runTaskTimer(plugin, 1L, 1L);
    }
}