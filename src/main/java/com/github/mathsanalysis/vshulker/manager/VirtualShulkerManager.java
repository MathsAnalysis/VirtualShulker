package com.github.mathsanalysis.vshulker.manager;

import com.github.mathsanalysis.vshulker.VirtualShulkerPlugin;
import com.github.mathsanalysis.vshulker.config.Config;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.block.ShulkerBox;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class VirtualShulkerManager {

    private static VirtualShulkerManager instance;
    private final VirtualShulkerPlugin plugin;
    private final Map<UUID, ShulkerSession> activeSessions;
    private final Set<UUID> loadingPlayers;
    private final Set<Location> placedShulkerLocations;
    private final Map<String, UUID> shulkerLocks;
    private final Map<UUID, ReentrantLock> playerLocks;
    private final Map<UUID, Long> lastOpenTimestamp;
    private static final long OPEN_COOLDOWN_MS = 100;

    public VirtualShulkerManager(VirtualShulkerPlugin plugin) {
        this.plugin = plugin;
        this.activeSessions = new ConcurrentHashMap<>();
        this.loadingPlayers = ConcurrentHashMap.newKeySet();
        this.placedShulkerLocations = ConcurrentHashMap.newKeySet();
        this.shulkerLocks = new ConcurrentHashMap<>();
        this.playerLocks = new ConcurrentHashMap<>();
        this.lastOpenTimestamp = new ConcurrentHashMap<>();
    }

    public static VirtualShulkerManager getInstance(VirtualShulkerPlugin plugin) {
        if (instance == null) {
            instance = new VirtualShulkerManager(plugin);
        }
        return instance;
    }

    public void initialize() {
        plugin.getLogger().info("VirtualShulkerManager initialized (NBT-only, WITH ANTI-DUPE PROTECTION)");
    }

    private String generateShulkerHash(ItemStack shulkerBox) {
        if (shulkerBox == null || !isShulkerBox(shulkerBox)) {
            return null;
        }

        int hash = shulkerBox.hashCode();
        if (shulkerBox.hasItemMeta()) {
            hash = 31 * hash + Objects.hashCode(shulkerBox.getItemMeta());
        }

        return "shulker_" + hash + "_" + shulkerBox.getType().name();
    }

    public void openShulker(Player player, ItemStack shulkerBox) {
        if (!isShulkerBox(shulkerBox)) {
            return;
        }

        UUID playerId = player.getUniqueId();
        ReentrantLock playerLock = playerLocks.computeIfAbsent(playerId, k -> new ReentrantLock());

        if (!playerLock.tryLock()) {
            plugin.getLogger().warning("ANTI-DUPE: Blocked concurrent shulker operation for " + player.getName());
            player.sendMessage(Component.text("Please wait, operation in progress...", NamedTextColor.YELLOW));
            return;
        }

        try {
            Long lastOpen = lastOpenTimestamp.get(playerId);
            long now = System.currentTimeMillis();
            if (lastOpen != null && (now - lastOpen) < OPEN_COOLDOWN_MS) {
                plugin.getLogger().warning("ANTI-DUPE: Blocked too fast shulker open for " + player.getName() +
                        " (cooldown: " + (now - lastOpen) + "ms)");
                return;
            }

            if (activeSessions.containsKey(playerId)) {
                player.sendMessage(Component.text("You already have a shulker open!", NamedTextColor.YELLOW));
                return;
            }

            if (loadingPlayers.contains(playerId)) {
                player.sendMessage(Component.text("Loading shulker, please wait...", NamedTextColor.YELLOW));
                return;
            }

            String shulkerHash = generateShulkerHash(shulkerBox);
            if (shulkerHash == null) {
                plugin.getLogger().severe("Failed to generate shulker hash!");
                return;
            }

            UUID lockOwner = shulkerLocks.get(shulkerHash);
            if (lockOwner != null && !lockOwner.equals(playerId)) {
                Player otherPlayer = Bukkit.getPlayer(lockOwner);
                if (otherPlayer != null && otherPlayer.isOnline()) {
                    plugin.getLogger().warning("ANTI-DUPE: Blocked duplicate shulker open attempt by " + player.getName() +
                            " - same shulker already open by " + otherPlayer.getName());
                    player.sendMessage(Component.text("This shulker is already being accessed!", NamedTextColor.RED));
                    return;
                } else {
                    shulkerLocks.remove(shulkerHash);
                    plugin.getLogger().info("Removed stale shulker lock for hash: " + shulkerHash);
                }
            }

            loadingPlayers.add(playerId);
            lastOpenTimestamp.put(playerId, now);

            try {
                ShulkerSlot slot = findShulkerSlot(player, shulkerBox);

                if (slot == null) {
                    plugin.getLogger().warning("Could not find shulker in player inventory: " + player.getName());
                    player.sendMessage(Component.text("Error: Could not locate shulker", NamedTextColor.RED));
                    return;
                }

                if (!verifyShulkerStillInSlot(player, slot, shulkerBox)) {
                    plugin.getLogger().warning("ANTI-DUPE: Shulker disappeared/changed during open for " + player.getName());
                    return;
                }

                ItemStack[] contents = getContentsFromNBT(shulkerBox);
                Inventory inventory = createInventory(contents);
                shulkerLocks.put(shulkerHash, playerId);
                ShulkerSession session = new ShulkerSession(inventory, slot, shulkerHash);
                activeSessions.put(playerId, session);
                player.openInventory(inventory);
                plugin.getLogger().fine("Opened shulker for " + player.getName() + " from slot: " + slot +
                        " [hash: " + shulkerHash + "]");

            } finally {
                loadingPlayers.remove(playerId);
            }

        } finally {
            playerLock.unlock();
        }
    }

    public void closeShulker(Player player, boolean save) {
        UUID playerId = player.getUniqueId();
        ReentrantLock playerLock = playerLocks.computeIfAbsent(playerId, k -> new ReentrantLock());

        playerLock.lock();
        try {
            ShulkerSession session = activeSessions.remove(playerId);
            loadingPlayers.remove(playerId);

            if (session == null) {
                return;
            }

            if (session.shulkerHash != null) {
                UUID lockOwner = shulkerLocks.remove(session.shulkerHash);
                if (lockOwner != null && !lockOwner.equals(playerId)) {
                    plugin.getLogger().warning("ANTI-DUPE WARNING: Lock owner mismatch on close! " +
                            "Expected: " + playerId + ", Got: " + lockOwner);
                }
            }

            if (save) {
                if (!verifyShulkerStillInSlot(player, session.slot, null)) {
                    plugin.getLogger().severe("ANTI-DUPE: Shulker disappeared during close for " + player.getName() +
                            " - CONTENTS MAY BE LOST!");
                    dropContentsToPlayer(player, session.inventory.getContents());
                    return;
                }

                ItemStack[] contents = session.inventory.getContents();
                updateShulkerInSlot(player, session.slot, contents);
                plugin.getLogger().fine("Saved shulker for " + player.getName() + " to slot: " + session.slot);
            }
        } finally {
            playerLock.unlock();
        }
    }

    private boolean verifyShulkerStillInSlot(Player player, ShulkerSlot slot, ItemStack originalShulker) {
        ItemStack currentItem = null;

        switch (slot.type) {
            case MAIN_HAND:
                currentItem = player.getInventory().getItemInMainHand();
                break;
            case OFF_HAND:
                currentItem = player.getInventory().getItemInOffHand();
                break;
            case INVENTORY:
                currentItem = player.getInventory().getItem(slot.slotIndex);
                break;
            case ENDER_CHEST:
                currentItem = player.getEnderChest().getItem(slot.slotIndex);
                break;
        }

        if (currentItem == null || !isShulkerBox(currentItem)) {
            return false;
        }

        if (originalShulker != null) {
            return isSameShulker(currentItem, originalShulker);
        }

        return true;
    }

    private void dropContentsToPlayer(Player player, ItemStack[] contents) {
        if (contents == null) return;

        int droppedCount = 0;
        for (ItemStack item : contents) {
            if (item != null && item.getType() != Material.AIR) {
                player.getWorld().dropItemNaturally(player.getLocation(), item);
                droppedCount++;
            }
        }

        if (droppedCount > 0) {
            player.sendMessage(Component.text("Warning: Shulker disappeared! Dropped " + droppedCount +
                    " items at your location.", NamedTextColor.RED));
            plugin.getLogger().warning("Dropped " + droppedCount + " items from lost shulker for " + player.getName());
        }
    }

    private ItemStack[] getContentsFromNBT(ItemStack shulkerBox) {
        try {
            if (!(shulkerBox.getItemMeta() instanceof BlockStateMeta meta)) {
                return new ItemStack[27];
            }

            if (!(meta.getBlockState() instanceof ShulkerBox box)) {
                return new ItemStack[27];
            }

            ItemStack[] contents = box.getInventory().getContents();
            return contents != null ? contents : new ItemStack[27];

        } catch (Exception e) {
            plugin.getLogger().warning("Error reading NBT contents: " + e.getMessage());
            return new ItemStack[27];
        }
    }

    private void setContentsToNBT(ItemStack shulkerBox, ItemStack[] contents) {
        try {
            if (!(shulkerBox.getItemMeta() instanceof BlockStateMeta blockMeta)) {
                return;
            }

            ShulkerBox box = (ShulkerBox) blockMeta.getBlockState();
            box.getInventory().setContents(contents);

            blockMeta.setBlockState(box);
            shulkerBox.setItemMeta(blockMeta);

        } catch (Exception e) {
            plugin.getLogger().warning("Error writing NBT contents: " + e.getMessage());
        }
    }

    private ShulkerSlot findShulkerSlot(Player player, ItemStack targetShulker) {
        if (isSameShulker(player.getInventory().getItemInMainHand(), targetShulker)) {
            return new ShulkerSlot(SlotType.MAIN_HAND, -1);
        }

        if (isSameShulker(player.getInventory().getItemInOffHand(), targetShulker)) {
            return new ShulkerSlot(SlotType.OFF_HAND, -1);
        }

        for (int i = 0; i < player.getInventory().getSize(); i++) {
            if (isSameShulker(player.getInventory().getItem(i), targetShulker)) {
                return new ShulkerSlot(SlotType.INVENTORY, i);
            }
        }

        for (int i = 0; i < player.getEnderChest().getSize(); i++) {
            if (isSameShulker(player.getEnderChest().getItem(i), targetShulker)) {
                return new ShulkerSlot(SlotType.ENDER_CHEST, i);
            }
        }

        return null;
    }

    private void updateShulkerInSlot(Player player, ShulkerSlot slot, ItemStack[] contents) {
        ItemStack shulkerItem = null;

        switch (slot.type) {
            case MAIN_HAND:
                shulkerItem = player.getInventory().getItemInMainHand();
                if (isShulkerBox(shulkerItem)) {
                    setContentsToNBT(shulkerItem, contents);
                    player.getInventory().setItemInMainHand(shulkerItem);
                }
                break;

            case OFF_HAND:
                shulkerItem = player.getInventory().getItemInOffHand();
                if (isShulkerBox(shulkerItem)) {
                    setContentsToNBT(shulkerItem, contents);
                    player.getInventory().setItemInOffHand(shulkerItem);
                }
                break;

            case INVENTORY:
                shulkerItem = player.getInventory().getItem(slot.slotIndex);
                if (isShulkerBox(shulkerItem)) {
                    setContentsToNBT(shulkerItem, contents);
                    player.getInventory().setItem(slot.slotIndex, shulkerItem);
                }
                break;

            case ENDER_CHEST:
                shulkerItem = player.getEnderChest().getItem(slot.slotIndex);
                if (isShulkerBox(shulkerItem)) {
                    setContentsToNBT(shulkerItem, contents);
                    player.getEnderChest().setItem(slot.slotIndex, shulkerItem);
                }
                break;
        }

        if (shulkerItem == null || !isShulkerBox(shulkerItem)) {
            plugin.getLogger().warning("CRITICAL: Shulker disappeared from slot during close: " + slot);
            plugin.getLogger().warning("Contents preserved in memory but could not update NBT!");
            plugin.getLogger().warning("This should never happen - please report this bug!");
        }
    }

    private boolean isSameShulker(ItemStack item1, ItemStack item2) {
        if (item1 == null || item2 == null) return false;
        if (!isShulkerBox(item1) || !isShulkerBox(item2)) return false;
        if (item1.getType() != item2.getType()) return false;

        return Objects.equals(item1.getItemMeta(), item2.getItemMeta());
    }

    public boolean hasOpenShulker(Player player) {
        return activeSessions.containsKey(player.getUniqueId());
    }

    public boolean isValidSession(Player player, Inventory inventory) {
        ShulkerSession session = activeSessions.get(player.getUniqueId());
        return session != null && session.inventory.equals(inventory);
    }

    public boolean isLoading(Player player) {
        return loadingPlayers.contains(player.getUniqueId());
    }

    public void cancelLoading(Player player) {
        loadingPlayers.remove(player.getUniqueId());
    }

    public void forceCleanupPlayer(Player player) {
        UUID playerId = player.getUniqueId();

        ReentrantLock playerLock = playerLocks.get(playerId);
        if (playerLock != null) {
            playerLock.lock();
            try {
                loadingPlayers.remove(playerId);

                ShulkerSession session = activeSessions.remove(playerId);
                if (session != null) {
                    if (session.shulkerHash != null) {
                        shulkerLocks.remove(session.shulkerHash);
                    }
                    plugin.getLogger().info("Force cleaned up session for: " + player.getName());
                }

                lastOpenTimestamp.remove(playerId);
            } finally {
                playerLock.unlock();
            }
        }
    }

    public boolean isShulkerBox(ItemStack item) {
        if (item == null) return false;
        return isShulkerBox(item.getType());
    }

    public boolean isShulkerBox(Material material) {
        return material == Material.SHULKER_BOX ||
                material == Material.WHITE_SHULKER_BOX ||
                material == Material.ORANGE_SHULKER_BOX ||
                material == Material.MAGENTA_SHULKER_BOX ||
                material == Material.LIGHT_BLUE_SHULKER_BOX ||
                material == Material.YELLOW_SHULKER_BOX ||
                material == Material.LIME_SHULKER_BOX ||
                material == Material.PINK_SHULKER_BOX ||
                material == Material.GRAY_SHULKER_BOX ||
                material == Material.LIGHT_GRAY_SHULKER_BOX ||
                material == Material.CYAN_SHULKER_BOX ||
                material == Material.PURPLE_SHULKER_BOX ||
                material == Material.BLUE_SHULKER_BOX ||
                material == Material.BROWN_SHULKER_BOX ||
                material == Material.GREEN_SHULKER_BOX ||
                material == Material.RED_SHULKER_BOX ||
                material == Material.BLACK_SHULKER_BOX;
    }

    private Inventory createInventory(ItemStack[] contents) {
        Component title = Config.getShulkerTitle();
        Inventory inventory = Bukkit.createInventory(null, Config.getShulkerSize(), title);

        if (contents != null) {
            inventory.setContents(contents);
        }

        return inventory;
    }

    public void registerPlacedShulker(Location location) {
        placedShulkerLocations.add(location.getBlock().getLocation());
    }

    public void unregisterPlacedShulker(Location location) {
        placedShulkerLocations.remove(location.getBlock().getLocation());
    }

    public boolean isPlacedVirtualShulker(Location location) {
        return placedShulkerLocations.contains(location.getBlock().getLocation());
    }

    public void shutdown() {
        plugin.getLogger().info("Shutting down VirtualShulkerManager...");

        int closedSessions = 0;
        for (Map.Entry<UUID, ShulkerSession> entry : new HashMap<>(activeSessions).entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                closeShulker(player, true);
                closedSessions++;
            }
        }

        if (closedSessions > 0) {
            plugin.getLogger().info("Closed " + closedSessions + " active sessions");
        }

        activeSessions.clear();
        loadingPlayers.clear();
        placedShulkerLocations.clear();
        shulkerLocks.clear();
        playerLocks.clear();
        lastOpenTimestamp.clear();

        plugin.getLogger().info("VirtualShulkerManager shutdown complete");
    }

    private record ShulkerSession(Inventory inventory, ShulkerSlot slot, String shulkerHash) {}

    private record ShulkerSlot(SlotType type, int slotIndex) {}

    private enum SlotType {
        MAIN_HAND,
        OFF_HAND,
        INVENTORY,
        ENDER_CHEST
    }


    public void saveShulkerContents(Player player, ItemStack[] contents) {
        UUID playerId = player.getUniqueId();
        ShulkerSession session = activeSessions.get(playerId);

        if (session == null) {
            plugin.getLogger().warning("Attempted to save shulker contents for player without active session: " + player.getName());
            return;
        }

        if (!verifyShulkerStillInSlot(player, session.slot, null)) {
            plugin.getLogger().warning("Shulker disappeared during manual save for " + player.getName());
            dropContentsToPlayer(player, contents);
            return;
        }

        updateShulkerInSlot(player, session.slot, contents);
        plugin.getLogger().fine("Manually saved shulker contents for " + player.getName());
    }
}