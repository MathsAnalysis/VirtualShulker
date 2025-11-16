package com.github.mathsanalysis.vshulker.manager;

import com.github.mathsanalysis.vshulker.config.Config;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class VirtualShulkerManager {

    private static VirtualShulkerManager instance;

    private final Map<UUID, ItemStack[]> shulkerData;
    private final Map<UUID, InventorySession> activeSessions;
    private final Map<UUID, ReentrantLock> playerLocks;

    private VirtualShulkerManager() {
        this.shulkerData = new ConcurrentHashMap<>();
        this.activeSessions = new ConcurrentHashMap<>();
        this.playerLocks = new ConcurrentHashMap<>();
    }

    public static VirtualShulkerManager getInstance() {
        if (instance == null) {
            instance = new VirtualShulkerManager();
        }
        return instance;
    }

    public void openShulker(Player player) {
        UUID playerId = player.getUniqueId();
        ReentrantLock lock = playerLocks.computeIfAbsent(playerId, k -> new ReentrantLock());

        lock.lock();
        try {
            if (activeSessions.containsKey(playerId)) {
                return;
            }

            ItemStack[] contents = loadInventory(playerId);
            Inventory inventory = createInventory(player, contents);

            InventorySession session = new InventorySession(inventory, System.currentTimeMillis());
            activeSessions.put(playerId, session);

            player.openInventory(inventory);
            player.sendMessage(Config.getMessageOpened());
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
            InventorySession session = activeSessions.remove(playerId);

            if (session == null) {
                return;
            }

            if (save) {
                saveInventory(playerId, session.inventory);
            }

            player.closeInventory();
            player.sendMessage(Config.getMessageClosed());
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
            InventorySession session = activeSessions.remove(playerId);

            if (session == null) {
                return;
            }

            saveInventory(playerId, session.inventory);
            player.closeInventory();
            player.sendMessage(Config.getMessageClosedDamage());
        } finally {
            lock.unlock();
        }
    }

    public boolean hasOpenShulker(Player player) {
        return activeSessions.containsKey(player.getUniqueId());
    }

    public boolean isValidSession(Player player, Inventory inventory) {
        InventorySession session = activeSessions.get(player.getUniqueId());
        return session != null && session.inventory.equals(inventory);
    }

    private Inventory createInventory(Player player, ItemStack[] contents) {
        Component title = Config.getShulkerTitle();
        Inventory inventory = Bukkit.createInventory(null, Config.getShulkerSize(), title);

        if (contents != null) {
            inventory.setContents(cloneContents(contents));
        }

        return inventory;
    }

    private ItemStack[] loadInventory(UUID playerId) {
        ItemStack[] original = shulkerData.get(playerId);
        return original != null ? cloneContents(original) : null;
    }

    private void saveInventory(UUID playerId, Inventory inventory) {
        ItemStack[] contents = inventory.getContents();
        shulkerData.put(playerId, cloneContents(contents));
    }

    private ItemStack[] cloneContents(ItemStack[] contents) {
        if (contents == null) {
            return null;
        }

        ItemStack[] cloned = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            cloned[i] = contents[i] != null ? contents[i].clone() : null;
        }

        return cloned;
    }

    public void clearData() {
        shulkerData.clear();
        activeSessions.clear();
        playerLocks.clear();
    }

    private record InventorySession(Inventory inventory, long openedAt) {}
}