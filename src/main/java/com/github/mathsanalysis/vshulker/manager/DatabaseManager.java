package com.github.mathsanalysis.vshulker.manager;

import com.github.mathsanalysis.vshulker.VirtualShulkerPlugin;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.*;
import java.sql.*;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;

public final class DatabaseManager {

    private final VirtualShulkerPlugin plugin;
    private final ExecutorService executor;
    private final File databaseFile;
    private Connection connection;

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS shulkers (
                shulker_id TEXT PRIMARY KEY,
                contents TEXT NOT NULL,
                updated_at INTEGER DEFAULT (strftime('%s', 'now'))
            )
            """;

    private static final String CREATE_BACKUP_TABLE = """
            CREATE TABLE IF NOT EXISTS shulker_backups (
                backup_id TEXT PRIMARY KEY,
                original_id TEXT NOT NULL,
                contents TEXT NOT NULL,
                created_at INTEGER DEFAULT (strftime('%s', 'now'))
            )
            """;

    private static final String INSERT_OR_REPLACE = """
            INSERT OR REPLACE INTO shulkers (shulker_id, contents, updated_at)
            VALUES (?, ?, strftime('%s', 'now'))
            """;

    private static final String INSERT_BACKUP = """
            INSERT INTO shulker_backups (backup_id, original_id, contents, created_at)
            VALUES (?, ?, ?, strftime('%s', 'now'))
            """;

    private static final String SELECT_BY_ID = "SELECT contents FROM shulkers WHERE shulker_id = ?";
    private static final String SELECT_ALL = "SELECT shulker_id, contents FROM shulkers";
    private static final String DELETE_BY_ID = "DELETE FROM shulkers WHERE shulker_id = ?";
    private static final String CLEANUP_OLD_BACKUPS = "DELETE FROM shulker_backups WHERE created_at < ?";

    private static final long BACKUP_RETENTION_SECONDS = 7 * 24 * 60 * 60;

    public DatabaseManager(VirtualShulkerPlugin plugin) {
        this.plugin = plugin;
        this.executor = plugin.getVirtualThreadExecutor();
        this.databaseFile = new File(plugin.getDataFolder(), "shulkers.db");
    }

    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            try {
                if (!plugin.getDataFolder().exists()) {
                    plugin.getDataFolder().mkdirs();
                }

                connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());

                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("PRAGMA journal_mode=WAL");
                    stmt.execute("PRAGMA synchronous=NORMAL");
                    stmt.execute("PRAGMA foreign_keys=ON");

                    stmt.execute(CREATE_TABLE);
                    stmt.execute(CREATE_BACKUP_TABLE);
                }

                plugin.getLogger().info("SQLite database initialized: " + databaseFile.getName());

                cleanupOldBackups();

            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Database initialization error", e);
                throw new RuntimeException("Failed to initialize database", e);
            }
        }, executor);
    }

    public CompletableFuture<Void> saveShulker(String shulkerId, ItemStack[] contents) {
        return CompletableFuture.runAsync(() -> saveShulkerInternal(shulkerId, contents), executor);
    }

    public void saveShulkerSync(String shulkerId, ItemStack[] contents) {
        saveShulkerInternal(shulkerId, contents);
    }

    private void saveShulkerInternal(String shulkerId, ItemStack[] contents) {
        try {
            ItemStack[] existing = loadShulkerInternal(shulkerId);
            if (existing != null && existing.length > 0) {
                try {
                    String backupId = shulkerId + "_backup_" + System.currentTimeMillis();
                    saveBackup(backupId, shulkerId, existing);
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to create backup for shulker " + shulkerId + ": " + e.getMessage());
                }
            }

            String serialized = serializeContents(contents);

            try (PreparedStatement pstmt = connection.prepareStatement(INSERT_OR_REPLACE)) {
                pstmt.setString(1, shulkerId);
                pstmt.setString(2, serialized);
                pstmt.executeUpdate();
            }

        } catch (SQLException | IOException e) {
            plugin.getLogger().log(Level.SEVERE, "CRITICAL: Error saving shulker " + shulkerId, e);
            throw new RuntimeException("Failed to save shulker", e);
        }
    }

    private void saveBackup(String backupId, String originalId, ItemStack[] contents) throws SQLException, IOException {
        String serialized = serializeContents(contents);

        try (PreparedStatement pstmt = connection.prepareStatement(INSERT_BACKUP)) {
            pstmt.setString(1, backupId);
            pstmt.setString(2, originalId);
            pstmt.setString(3, serialized);
            pstmt.executeUpdate();
        }

        plugin.getLogger().fine("Created backup " + backupId + " for shulker " + originalId);
    }

    public CompletableFuture<Void> saveAllShulkers(Map<String, ItemStack[]> shulkerData) {
        return CompletableFuture.runAsync(() -> {
            try {
                connection.setAutoCommit(false);

                try (PreparedStatement pstmt = connection.prepareStatement(INSERT_OR_REPLACE)) {
                    int count = 0;
                    for (Map.Entry<String, ItemStack[]> entry : shulkerData.entrySet()) {
                        String serialized = serializeContents(entry.getValue());
                        pstmt.setString(1, entry.getKey());
                        pstmt.setString(2, serialized);
                        pstmt.addBatch();
                        count++;

                        if (count % 100 == 0) {
                            pstmt.executeBatch();
                        }
                    }
                    pstmt.executeBatch();
                }

                connection.commit();
                connection.setAutoCommit(true);

                plugin.getLogger().info("Batch saved " + shulkerData.size() + " shulkers to database");

            } catch (SQLException | IOException e) {
                try {
                    connection.rollback();
                    connection.setAutoCommit(true);
                } catch (SQLException ex) {
                    plugin.getLogger().log(Level.SEVERE, "Rollback error", ex);
                }
                plugin.getLogger().log(Level.SEVERE, "Batch save error", e);
                throw new RuntimeException("Failed to batch save shulkers", e);
            }
        }, executor);
    }

    public CompletableFuture<ItemStack[]> loadShulker(String shulkerId) {
        return CompletableFuture.supplyAsync(() -> loadShulkerInternal(shulkerId), executor);
    }

    public ItemStack[] loadShulkerSync(String shulkerId) {
        return loadShulkerInternal(shulkerId);
    }

    private ItemStack[] loadShulkerInternal(String shulkerId) {
        try (PreparedStatement pstmt = connection.prepareStatement(SELECT_BY_ID)) {
            pstmt.setString(1, shulkerId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    String serialized = rs.getString("contents");
                    return deserializeContents(serialized);
                }
            }

        } catch (SQLException | IOException | ClassNotFoundException e) {
            plugin.getLogger().log(Level.WARNING, "Error loading shulker: " + shulkerId, e);
        }
        return null;
    }

    public CompletableFuture<Map<String, ItemStack[]>> loadAllShulkers() {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, ItemStack[]> result = new ConcurrentHashMap<>();

            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(SELECT_ALL)) {

                int successCount = 0;
                int failCount = 0;

                while (rs.next()) {
                    String shulkerId = rs.getString("shulker_id");
                    String serialized = rs.getString("contents");

                    try {
                        ItemStack[] contents = deserializeContents(serialized);
                        result.put(shulkerId, contents);
                        successCount++;
                    } catch (IOException | ClassNotFoundException e) {
                        plugin.getLogger().log(Level.WARNING, "Deserialization error for shulker: " + shulkerId, e);
                        failCount++;
                    }
                }

                plugin.getLogger().info("Loaded " + successCount + " shulkers from database" +
                        (failCount > 0 ? " (" + failCount + " failed)" : ""));

            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Error loading shulkers", e);
            }

            return result;
        }, executor);
    }

    public CompletableFuture<Void> deleteShulker(String shulkerId) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement pstmt = connection.prepareStatement(DELETE_BY_ID)) {
                pstmt.setString(1, shulkerId);
                int deleted = pstmt.executeUpdate();
                if (deleted > 0) {
                    plugin.getLogger().fine("Deleted shulker: " + shulkerId);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Error deleting shulker: " + shulkerId, e);
            }
        }, executor);
    }

    public CompletableFuture<Integer> migrateFromDatFile() {
        return CompletableFuture.supplyAsync(() -> {
            File datFile = new File(plugin.getDataFolder(), "shulkers.dat");

            if (!datFile.exists()) {
                return 0;
            }

            plugin.getLogger().info("Migrating from shulkers.dat to SQLite...");

            Map<String, ItemStack[]> legacyData = new ConcurrentHashMap<>();

            try (FileInputStream fis = new FileInputStream(datFile);
                 BukkitObjectInputStream ois = new BukkitObjectInputStream(fis)) {

                int size = ois.readInt();

                for (int i = 0; i < size; i++) {
                    String shulkerId = ois.readUTF();
                    ItemStack[] contents = (ItemStack[]) ois.readObject();
                    legacyData.put(shulkerId, contents);
                }

            } catch (IOException | ClassNotFoundException e) {
                plugin.getLogger().log(Level.SEVERE, "Error reading .dat file", e);
                return 0;
            }

            if (legacyData.isEmpty()) {
                return 0;
            }

            try {
                connection.setAutoCommit(false);

                try (PreparedStatement pstmt = connection.prepareStatement(INSERT_OR_REPLACE)) {
                    for (Map.Entry<String, ItemStack[]> entry : legacyData.entrySet()) {
                        String serialized = serializeContents(entry.getValue());
                        pstmt.setString(1, entry.getKey());
                        pstmt.setString(2, serialized);
                        pstmt.addBatch();
                    }
                    pstmt.executeBatch();
                }

                connection.commit();
                connection.setAutoCommit(true);

                File backupFile = new File(plugin.getDataFolder(), "shulkers.dat.backup");
                if (datFile.renameTo(backupFile)) {
                    plugin.getLogger().info("Renamed .dat to .dat.backup");
                }

                plugin.getLogger().info("Migration complete: " + legacyData.size() + " shulkers");
                return legacyData.size();

            } catch (SQLException | IOException e) {
                try {
                    connection.rollback();
                    connection.setAutoCommit(true);
                } catch (SQLException ex) {
                    plugin.getLogger().log(Level.SEVERE, "Rollback error", ex);
                }
                plugin.getLogger().log(Level.SEVERE, "Migration error", e);
                return 0;
            }
        }, executor);
    }

    private void cleanupOldBackups() {
        CompletableFuture.runAsync(() -> {
            long cutoffTime = System.currentTimeMillis() / 1000 - BACKUP_RETENTION_SECONDS;

            try (PreparedStatement pstmt = connection.prepareStatement(CLEANUP_OLD_BACKUPS)) {
                pstmt.setLong(1, cutoffTime);
                int deleted = pstmt.executeUpdate();

                if (deleted > 0) {
                    plugin.getLogger().info("Cleaned up " + deleted + " old backup entries");
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to cleanup old backups: " + e.getMessage());
            }
        }, executor);
    }

    public void close() {
        if (connection != null) {
            try {
                connection.close();
                plugin.getLogger().info("Database connection closed");
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Error closing database", e);
            }
        }
    }

    private String serializeContents(ItemStack[] contents) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeInt(contents.length);

            for (int i = 0; i < contents.length; i++) {
                ItemStack item = contents[i];

                if (item == null || item.getType().isAir()) {
                    dos.writeBoolean(false);
                } else {
                    try {
                        dos.writeBoolean(true);

                        ByteArrayOutputStream itemBaos = new ByteArrayOutputStream();
                        try (BukkitObjectOutputStream itemOos = new BukkitObjectOutputStream(itemBaos)) {
                            itemOos.writeObject(item);
                            itemOos.flush();
                        }

                        byte[] itemBytes = itemBaos.toByteArray();
                        dos.writeInt(itemBytes.length);
                        dos.write(itemBytes);

                    } catch (Exception e) {
                        plugin.getLogger().warning(
                                "Failed to serialize item at slot " + i + ": " +
                                        item.getType() + " (amount: " + item.getAmount() + ") - " + e.getMessage()
                        );
                        dos.writeBoolean(false);
                    }
                }
            }

            dos.flush();
        }

        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private ItemStack[] deserializeContents(String serialized) throws IOException, ClassNotFoundException {
        byte[] data = Base64.getDecoder().decode(serialized);

        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data))) {
            int length = dis.readInt();
            ItemStack[] contents = new ItemStack[length];

            int successCount = 0;
            int failCount = 0;

            for (int i = 0; i < length; i++) {
                boolean hasItem = dis.readBoolean();

                if (hasItem) {
                    try {
                        int itemLength = dis.readInt();
                        byte[] itemBytes = new byte[itemLength];
                        dis.readFully(itemBytes);

                        try (BukkitObjectInputStream itemOis = new BukkitObjectInputStream(new ByteArrayInputStream(itemBytes))) {
                            contents[i] = (ItemStack) itemOis.readObject();
                            successCount++;
                        } catch (ClassNotFoundException e) {
                            plugin.getLogger().warning("Failed to deserialize item at slot " + i +
                                    " (class not found): " + e.getMessage());
                            contents[i] = null;
                            failCount++;
                        }

                    } catch (Exception e) {
                        plugin.getLogger().warning("Failed to deserialize item at slot " + i + ": " + e.getMessage());
                        contents[i] = null;
                        failCount++;
                    }
                } else {
                    contents[i] = null;
                }
            }

            if (failCount > 0) {
                plugin.getLogger().warning("Deserialization completed with " + successCount +
                        " successful items and " + failCount + " failed items");
            }

            return contents;
        }
    }

    public boolean isConnected() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }
}