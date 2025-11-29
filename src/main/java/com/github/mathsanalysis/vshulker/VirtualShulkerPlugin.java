package com.github.mathsanalysis.vshulker;

import com.github.mathsanalysis.vshulker.command.ShulkerCommand;
import com.github.mathsanalysis.vshulker.config.Config;
import com.github.mathsanalysis.vshulker.listener.ShulkerBlockListener;
import com.github.mathsanalysis.vshulker.listener.ShulkerListener;
import com.github.mathsanalysis.vshulker.manager.VirtualShulkerManager;
import com.github.mathsanalysis.vshulker.tasks.SessionCleanupTask;
import com.github.mathsanalysis.vshulker.tasks.ShulkerPreviewUpdateTask;
import org.bukkit.plugin.java.JavaPlugin;
import revxrsal.commands.bukkit.BukkitCommandHandler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class VirtualShulkerPlugin extends JavaPlugin {

    private static VirtualShulkerPlugin instance;

    private VirtualShulkerManager manager;
    private BukkitCommandHandler commandHandler;
    private ExecutorService virtualThreadExecutor;
    private SessionCleanupTask cleanupTask;
    private ShulkerPreviewUpdateTask previewTask;

    @Override
    public void onEnable() {
        instance = this;

        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

        Config.load(this);

        this.manager = VirtualShulkerManager.getInstance(this);

        registerCommands();

        manager.initialize().thenRun(() -> getServer().getScheduler().runTask(this, () -> {
            registerListeners();
            startTasks();
            getLogger().info("VirtualShulker enabled!");
        })).exceptionally(throwable -> {
                    getLogger().severe("Database initialization error: " + throwable.getMessage());
                    throwable.printStackTrace(System.err);
                    getServer().getPluginManager().disablePlugin(this);
                    return null;
        });
    }

    @Override
    public void onDisable() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }

        if (previewTask != null) {
            previewTask.cancel();
        }

        if (commandHandler != null) {
            commandHandler.unregisterAllCommands();
        }

        if (manager != null) {
            manager.shutdown();
        }

        if (virtualThreadExecutor != null) {
            virtualThreadExecutor.shutdown();
            try {
                if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    virtualThreadExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                virtualThreadExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        getLogger().info("VirtualShulker disabled");
    }

    public void reload() {
        reloadConfig();
        Config.load(this);
        manager.reloadCache();
        getLogger().info("VirtualShulker reloaded");
    }

    public static VirtualShulkerPlugin getInstance() {
        return instance;
    }

    public ExecutorService getVirtualThreadExecutor() {
        return virtualThreadExecutor;
    }

    public VirtualShulkerManager getManager() {
        return manager;
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(
                new ShulkerListener(manager),
                this
        );

        getServer().getPluginManager().registerEvents(
                new ShulkerBlockListener(this, manager),
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

        previewTask = new ShulkerPreviewUpdateTask(this, manager);
        previewTask.start();
    }
}