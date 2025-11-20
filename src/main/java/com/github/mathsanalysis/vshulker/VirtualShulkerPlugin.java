package com.github.mathsanalysis.vshulker;

import com.github.mathsanalysis.vshulker.command.ShulkerCommand;
import com.github.mathsanalysis.vshulker.config.Config;
import com.github.mathsanalysis.vshulker.listener.ShulkerListener;
import com.github.mathsanalysis.vshulker.manager.VirtualShulkerManager;
import com.github.mathsanalysis.vshulker.tasks.SessionCleanupTask;
import com.github.mathsanalysis.vshulker.tasks.ShulkerPreviewUpdateTask;
import org.bukkit.plugin.java.JavaPlugin;
import revxrsal.commands.bukkit.BukkitCommandHandler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class VirtualShulkerPlugin extends JavaPlugin {

    private static VirtualShulkerPlugin instance;

    private VirtualShulkerManager manager;
    private BukkitCommandHandler commandHandler;
    private ExecutorService virtualThreadExecutor;
    private SessionCleanupTask cleanupTask;

    @Override
    public void onEnable() {
        instance = this;

        this.manager = VirtualShulkerManager.getInstance(this);
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

        Config.load(this);
        registerListeners();
        registerCommands();
        startTasks();
    }

    @Override
    public void onDisable() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }

        if (commandHandler != null) {
            commandHandler.unregisterAllCommands();
        }

        if (manager != null) {
            manager.saveData();
        }

        if (virtualThreadExecutor != null) {
            virtualThreadExecutor.shutdown();
        }
    }

    public void reload() {
        reloadConfig();
        Config.load(this);
        manager.clearData();
    }

    public static VirtualShulkerPlugin getInstance() {
        return instance;
    }

    public ExecutorService getVirtualThreadExecutor() {
        return virtualThreadExecutor;
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(
                new ShulkerListener(manager),
                this
        );
    }

    private void registerCommands() {
        commandHandler = BukkitCommandHandler.create(this);
        commandHandler.register(new ShulkerCommand(this));
    }

    private void startTasks() {
        cleanupTask = new SessionCleanupTask(this, manager);
        cleanupTask.start();

        ShulkerPreviewUpdateTask previewTask = new ShulkerPreviewUpdateTask(this, manager);
        previewTask.start();
    }
}