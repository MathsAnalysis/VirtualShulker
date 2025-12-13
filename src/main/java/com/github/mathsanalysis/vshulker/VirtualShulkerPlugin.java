package com.github.mathsanalysis.vshulker;

import com.github.mathsanalysis.vshulker.command.ShulkerCommand;
import com.github.mathsanalysis.vshulker.config.Config;
import com.github.mathsanalysis.vshulker.listener.ShulkerBlockListener;
import com.github.mathsanalysis.vshulker.listener.ShulkerListener;
import com.github.mathsanalysis.vshulker.manager.VirtualShulkerManager;
import com.github.mathsanalysis.vshulker.tasks.SessionCleanupTask;
import org.bukkit.plugin.java.JavaPlugin;
import revxrsal.commands.bukkit.BukkitCommandHandler;

public final class VirtualShulkerPlugin extends JavaPlugin {

    private static VirtualShulkerPlugin instance;

    private VirtualShulkerManager manager;
    private BukkitCommandHandler commandHandler;
    private SessionCleanupTask cleanupTask;

    @Override
    public void onEnable() {
        instance = this;

        Config.load(this);

        this.manager = VirtualShulkerManager.getInstance(this);
        manager.initialize();

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
            manager.shutdown();
        }
    }

    public void reload() {
        reloadConfig();
        Config.load(this);
    }

    public static VirtualShulkerPlugin getInstance() {
        return instance;
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
    }
}