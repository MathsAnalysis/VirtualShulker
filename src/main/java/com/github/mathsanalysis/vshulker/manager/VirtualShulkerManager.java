package com.github.mathsanalysis.vshulker.manager;

import com.github.mathsanalysis.vshulker.VirtualShulkerPlugin;
import com.github.mathsanalysis.vshulker.config.Config;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class VirtualShulkerManager {

    private static VirtualShulkerManager instance;

    private final VirtualShulkerPlugin plugin;
    private final NamespacedKey shulkerIdKey;
    private final File dataFile;

    private final Map<String, ItemStack[]> shulkerData;
    private final Map<UUID, ShulkerSession> activeSessions;
    private final Map<UUID, ReentrantLock> playerLocks;

    private VirtualShulkerManager(VirtualShulkerPlugin plugin) {
        this.plugin = plugin;
        this.shulkerIdKey = new NamespacedKey(plugin, "shulker_id");
        this.dataFile = new File(plugin.getDataFolder(), "shulkers.dat");

        this.shulkerData = new ConcurrentHashMap<>();
        this.activeSessions = new ConcurrentHashMap<>();
        this.playerLocks = new ConcurrentHashMap<>();

        loadData();
    }

    public static VirtualShulkerManager getInstance(VirtualShulkerPlugin plugin) {
        if (instance == null) {
            instance = new VirtualShulkerManager(plugin);
        }
        return instance;
    }

    public static VirtualShulkerManager getInstance() {
        return instance;
    }

    public void openShulker(Player player, ItemStack shulkerBox) {
        if (!isShulkerBox(shulkerBox)) {
            return;
        }

        UUID playerId = player.getUniqueId();
        ReentrantLock lock = playerLocks.computeIfAbsent(playerId, k -> new ReentrantLock());

        lock.lock();
        try {
            if (activeSessions.containsKey(playerId)) {
                return;
            }

            String shulkerId = getOrCreateShulkerId(shulkerBox);
            ItemStack[] contents = loadInventory(shulkerId);
            Inventory inventory = createInventory(contents);

            ShulkerSession session = new ShulkerSession(inventory, shulkerId, System.currentTimeMillis());
            activeSessions.put(playerId, session);

            player.openInventory(inventory);
        } finally {
            lock.unlock();
        }
    }

    public void closeShulker(Player player, boolean save) {
        UUID playerId = player.getUniqueId();
        ReentrantLock lock = playerLocks.get(playerId);

        if (lock == null) {
            return;
        }

        lock.lock();
        try {
            ShulkerSession session = activeSessions.remove(playerId);

            if (session == null) {
                return;
            }

            if (save) {
                saveInventory(session.shulkerId, session.inventory);
                saveDataAsync();
            }

            player.closeInventory();
        } finally {
            lock.unlock();
        }
    }

    public void closeShulkerOnDamage(Player player) {
        UUID playerId = player.getUniqueId();
        ReentrantLock lock = playerLocks.get(playerId);

        if (lock == null) {
            return;
        }

        lock.lock();
        try {
            ShulkerSession session = activeSessions.remove(playerId);

            if (session == null) {
                return;
            }

            saveInventory(session.shulkerId, session.inventory);
            saveDataAsync();
            player.closeInventory();
        } finally {
            lock.unlock();
        }
    }

    public boolean hasOpenShulker(Player player) {
        return activeSessions.containsKey(player.getUniqueId());
    }

    public boolean isValidSession(Player player, Inventory inventory) {
        ShulkerSession session = activeSessions.get(player.getUniqueId());
        return session != null && session.inventory.equals(inventory);
    }

    private String getOrCreateShulkerId(ItemStack shulkerBox) {
        ItemMeta meta = shulkerBox.getItemMeta();
        if (meta == null) {
            meta = Bukkit.getItemFactory().getItemMeta(shulkerBox.getType());
            if (meta != null) {
                shulkerBox.setItemMeta(meta);
            } else {
                return UUID.randomUUID().toString();
            }
        }

        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        if (pdc.has(shulkerIdKey, PersistentDataType.STRING)) {
            return pdc.get(shulkerIdKey, PersistentDataType.STRING);
        }

        String newId = UUID.randomUUID().toString();
        pdc.set(shulkerIdKey, PersistentDataType.STRING, newId);
        shulkerBox.setItemMeta(meta);

        return newId;
    }

    private boolean isShulkerBox(ItemStack item) {
        if (item == null) return false;
        Material type = item.getType();
        return type == Material.SHULKER_BOX ||
                type == Material.WHITE_SHULKER_BOX ||
                type == Material.ORANGE_SHULKER_BOX ||
                type == Material.MAGENTA_SHULKER_BOX ||
                type == Material.LIGHT_BLUE_SHULKER_BOX ||
                type == Material.YELLOW_SHULKER_BOX ||
                type == Material.LIME_SHULKER_BOX ||
                type == Material.PINK_SHULKER_BOX ||
                type == Material.GRAY_SHULKER_BOX ||
                type == Material.LIGHT_GRAY_SHULKER_BOX ||
                type == Material.CYAN_SHULKER_BOX ||
                type == Material.PURPLE_SHULKER_BOX ||
                type == Material.BLUE_SHULKER_BOX ||
                type == Material.BROWN_SHULKER_BOX ||
                type == Material.GREEN_SHULKER_BOX ||
                type == Material.RED_SHULKER_BOX ||
                type == Material.BLACK_SHULKER_BOX;
    }

    private Inventory createInventory(ItemStack[] contents) {
        Component title = Config.getShulkerTitle();
        Inventory inventory = Bukkit.createInventory(null, Config.getShulkerSize(), title);

        if (contents != null) {
            inventory.setContents(cloneContents(contents));
        }

        return inventory;
    }

    private ItemStack[] loadInventory(String shulkerId) {
        ItemStack[] original = shulkerData.get(shulkerId);
        return original != null ? cloneContents(original) : null;
    }

    private void saveInventory(String shulkerId, Inventory inventory) {
        ItemStack[] contents = inventory.getContents();
        shulkerData.put(shulkerId, cloneContents(contents));
    }

    private ItemStack[] cloneContents(ItemStack[] contents) {
        if (contents == null) {
            return null;
        }

        ItemStack[] cloned = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            cloned[i] = item != null && item.getType() != Material.AIR ? item.clone() : null;
        }

        return cloned;
    }

    private void saveDataAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::saveData);
    }

    public void saveData() {
        try {
            if (!dataFile.getParentFile().exists()) {
                dataFile.getParentFile().mkdirs();
            }

            try (FileOutputStream fos = new FileOutputStream(dataFile);
                 BukkitObjectOutputStream oos = new BukkitObjectOutputStream(fos)) {

                oos.writeInt(shulkerData.size());

                for (Map.Entry<String, ItemStack[]> entry : shulkerData.entrySet()) {
                    oos.writeUTF(entry.getKey());
                    oos.writeObject(entry.getValue());
                }
            }

            plugin.getLogger().info("Saved " + shulkerData.size() + " shulker inventories");
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save shulker data: " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }

    private void loadData() {
        if (!dataFile.exists()) {
            plugin.getLogger().info("No shulker data file found, starting fresh");
            return;
        }

        try (FileInputStream fis = new FileInputStream(dataFile);
             BukkitObjectInputStream ois = new BukkitObjectInputStream(fis)) {

            int size = ois.readInt();

            for (int i = 0; i < size; i++) {
                String shulkerId = ois.readUTF();
                ItemStack[] contents = (ItemStack[]) ois.readObject();
                shulkerData.put(shulkerId, contents);
            }

            plugin.getLogger().info("Loaded " + shulkerData.size() + " shulker inventories");
        } catch (IOException | ClassNotFoundException e) {
            plugin.getLogger().severe("Failed to load shulker data: " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }

    public void clearData() {
        shulkerData.clear();
        activeSessions.clear();
        playerLocks.clear();
    }

    private record ShulkerSession(Inventory inventory, String shulkerId, long openedAt) {}
}