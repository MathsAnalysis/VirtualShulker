package com.github.mathsanalysis.vshulker.manager;

import com.github.mathsanalysis.vshulker.VirtualShulkerPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class MigrationManager {

    private final VirtualShulkerPlugin plugin;
    private final VirtualShulkerManager manager;
    private final File migrationMarker;

    public MigrationManager(VirtualShulkerPlugin plugin, VirtualShulkerManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.migrationMarker = new File(plugin.getDataFolder(), ".migrated_to_nbt");
    }

    public int migrateFromDatabase() {
        if (migrationMarker.exists()) {
            plugin.getLogger().info("Database to NBT migration already completed (marker file exists)");
            return 0;
        }

        File databaseFile = new File(plugin.getDataFolder(), "shulkers.db");
        if (!databaseFile.exists()) {
            plugin.getLogger().info("No database file found, skipping migration");
            createMigrationMarker();
            return 0;
        }

        plugin.getLogger().info("========================================");
        plugin.getLogger().info("Starting migration from DATABASE to NBT");
        plugin.getLogger().info("This is a ONE-TIME operation");
        plugin.getLogger().info("========================================");

        DatabaseManager database = new DatabaseManager(plugin);

        try {
            database.initialize().join();

            Map<String, ItemStack[]> allShulkers = database.loadAllShulkers().join();

            if (allShulkers.isEmpty()) {
                plugin.getLogger().info("No shulkers found in database, migration complete");
                createMigrationMarker();
                backupDatabase(databaseFile);
                return 0;
            }

            plugin.getLogger().info("Found " + allShulkers.size() + " shulkers in database");

            AtomicInteger migrated = new AtomicInteger(0);
            AtomicInteger notFound = new AtomicInteger(0);

            for (Player player : Bukkit.getOnlinePlayers()) {
                int playerMigrated = migratePlayerShulkers(player, allShulkers);
                migrated.addAndGet(playerMigrated);
            }

            notFound.set(allShulkers.size() - migrated.get());

            plugin.getLogger().info("========================================");
            plugin.getLogger().info("MIGRATION COMPLETE!");
            plugin.getLogger().info("Successfully migrated: " + migrated.get() + " shulkers");
            
            if (notFound.get() > 0) {
                plugin.getLogger().warning("Could not find items for " + notFound.get() + " shulkers");
                plugin.getLogger().warning("These may belong to offline players");
                plugin.getLogger().warning("They will be migrated when those players join");
                plugin.getLogger().warning("Database backup kept at: shulkers.db.backup");
            } else {
                backupDatabase(databaseFile);
            }
            
            plugin.getLogger().info("========================================");

            createMigrationMarker();

            return migrated.get();

        } catch (Exception e) {
            plugin.getLogger().severe("MIGRATION FAILED: " + e.getMessage());
            e.printStackTrace(System.err);
            return 0;
        } finally {
            database.close();
        }
    }

    private int migratePlayerShulkers(Player player, Map<String, ItemStack[]> allShulkers) {
        int count = 0;

        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (migrateShulkerItem(item, allShulkers)) {
                player.getInventory().setItem(i, item);
                count++;
            }
        }

        for (int i = 0; i < player.getEnderChest().getSize(); i++) {
            ItemStack item = player.getEnderChest().getItem(i);
            if (migrateShulkerItem(item, allShulkers)) {
                player.getEnderChest().setItem(i, item);
                count++;
            }
        }

        if (count > 0) {
            plugin.getLogger().info("Migrated " + count + " shulkers for " + player.getName());
        }

        return count;
    }

    private boolean migrateShulkerItem(ItemStack item, Map<String, ItemStack[]> allShulkers) {
        if (!manager.isShulkerBox(item)) {
            return false;
        }

        String shulkerId = manager.getShulkerIdFromItem(item);
        if (shulkerId == null) {
            return false;
        }

        ItemStack[] databaseContents = allShulkers.get(shulkerId);
        if (databaseContents == null) {
            return false;
        }

        try {
            org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
            if (!(meta instanceof org.bukkit.inventory.meta.BlockStateMeta blockMeta)) {
                return false;
            }

            org.bukkit.block.ShulkerBox box = (org.bukkit.block.ShulkerBox) blockMeta.getBlockState();
            box.getInventory().setContents(databaseContents);

            blockMeta.setBlockState(box);
            item.setItemMeta(blockMeta);

            plugin.getLogger().fine("Migrated shulker " + shulkerId + " to NBT");

            allShulkers.remove(shulkerId);
            
            return true;

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to migrate shulker " + shulkerId + ": " + e.getMessage());
            return false;
        }
    }

    public void migrateLazyPlayer(Player player) {
        if (!migrationMarker.exists()) {
            return;
        }

        File databaseFile = new File(plugin.getDataFolder(), "shulkers.db");
        if (!databaseFile.exists()) {
            return;
        }

        DatabaseManager database = new DatabaseManager(plugin);

        try {
            database.initialize().join();
            Map<String, ItemStack[]> allShulkers = database.loadAllShulkers().join();

            if (allShulkers.isEmpty()) {
                return;
            }

            int migrated = migratePlayerShulkers(player, allShulkers);

            if (migrated > 0) {
                plugin.getLogger().info("Lazy-migrated " + migrated + " shulkers for " + player.getName());
            }

        } catch (Exception e) {
            plugin.getLogger().warning("Lazy migration failed for " + player.getName() + ": " + e.getMessage());
        } finally {
            database.close();
        }
    }

    private void createMigrationMarker() {
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());

            if (!migrationMarker.exists()) {
                Files.createFile(migrationMarker.toPath());
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to create migration marker: " + e.getMessage());
        }
    }


    private void backupDatabase(File databaseFile) {
        try {
            File backup = new File(plugin.getDataFolder(), "shulkers.db.backup");
            
            if (backup.exists()) {
                deleteRecursively(backup.toPath());
            }

            if (databaseFile.renameTo(backup)) {
                plugin.getLogger().info("Database backed up to: " + backup.getName());
                plugin.getLogger().info("You can safely delete this backup after verifying the migration");
            }

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to backup database: " + e.getMessage());
        }
    }

    public static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;

        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public @NotNull FileVisitResult postVisitDirectory(@NotNull Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }


}