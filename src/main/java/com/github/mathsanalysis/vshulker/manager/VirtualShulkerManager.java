package com.github.mathsanalysis.vshulker.manager;

import com.github.mathsanalysis.vshulker.VirtualShulkerPlugin;
import com.github.mathsanalysis.vshulker.config.Config;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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

public final class VirtualShulkerManager {

    private static VirtualShulkerManager instance;

    private final VirtualShulkerPlugin plugin;

    private final Map<UUID, ShulkerSession> activeSessions;

    private final Set<UUID> loadingPlayers;

    private final Set<Location> placedShulkerLocations;

    public VirtualShulkerManager(VirtualShulkerPlugin plugin) {
        this.plugin = plugin;
        this.activeSessions = new ConcurrentHashMap<>();
        this.loadingPlayers = ConcurrentHashMap.newKeySet();
        this.placedShulkerLocations = ConcurrentHashMap.newKeySet();
    }

    public static VirtualShulkerManager getInstance(VirtualShulkerPlugin plugin) {
        if (instance == null) {
            instance = new VirtualShulkerManager(plugin);
        }
        return instance;
    }

    public void initialize() {
        plugin.getLogger().info("VirtualShulkerManager initialized (NBT-only, NO ID system)");
    }

    public void openShulker(Player player, ItemStack shulkerBox) {
        if (!isShulkerBox(shulkerBox)) {
            return;
        }

        UUID playerId = player.getUniqueId();

        if (activeSessions.containsKey(playerId)) {
            player.sendMessage(Component.text("You already have a shulker open!", NamedTextColor.YELLOW));
            return;
        }

        if (loadingPlayers.contains(playerId)) {
            player.sendMessage(Component.text("Loading shulker, please wait...", NamedTextColor.YELLOW));
            return;
        }

        loadingPlayers.add(playerId);

        try {
            ShulkerSlot slot = findShulkerSlot(player, shulkerBox);

            if (slot == null) {
                plugin.getLogger().warning("Could not find shulker in player inventory: " + player.getName());
                player.sendMessage(Component.text("Error: Could not locate shulker", NamedTextColor.RED));
                return;
            }

            ItemStack[] contents = getContentsFromNBT(shulkerBox);

            Inventory inventory = createInventory(contents);

            ShulkerSession session = new ShulkerSession(inventory, slot, shulkerBox.clone());
            activeSessions.put(playerId, session);

            player.openInventory(inventory);

            plugin.getLogger().fine("Opened shulker for " + player.getName() + " from slot: " + slot);

        } finally {
            loadingPlayers.remove(playerId);
        }
    }

    public void closeShulker(Player player, boolean save) {
        UUID playerId = player.getUniqueId();

        ShulkerSession session = activeSessions.remove(playerId);
        loadingPlayers.remove(playerId);

        if (session == null) {
            return;
        }

        if (save) {
            ItemStack currentShulker = getCurrentShulkerInSlot(player, session.slot);

            if (currentShulker == null || !isShulkerBox(currentShulker)) {
                plugin.getLogger().severe("═══════════════════════════════════════════════");
                plugin.getLogger().severe("ANTI-DUPE: Shulker disappeared/invalid");
                plugin.getLogger().severe("Player: " + player.getName());
                plugin.getLogger().severe("Slot: " + session.slot);
                plugin.getLogger().severe("Action: BLOCK SAVE - NO DUPLICATION ALLOWED");
                plugin.getLogger().severe("═══════════════════════════════════════════════");

                player.sendMessage(Component.text("Session closed: Shulker was moved!", NamedTextColor.RED));
                player.sendMessage(Component.text("Changes NOT saved!", NamedTextColor.GOLD));
                return;
            }

            if (!isSameShulker(currentShulker, session.originalShulker)) {
                plugin.getLogger().severe("═══════════════════════════════════════════════");
                plugin.getLogger().severe("ANTI-DUPE: Shulker was replaced");
                plugin.getLogger().severe("Player: " + player.getName());
                plugin.getLogger().severe("Slot: " + session.slot);
                plugin.getLogger().severe("Original: " + session.originalShulker.getType());
                plugin.getLogger().severe("Current: " + currentShulker.getType());
                plugin.getLogger().severe("Action: BLOCK SAVE - NO DUPLICATION ALLOWED");
                plugin.getLogger().severe("═══════════════════════════════════════════════");

                player.sendMessage(Component.text("Session closed: Shulker was replaced!", NamedTextColor.RED));
                player.sendMessage(Component.text("Changes NOT saved!", NamedTextColor.GOLD));
                return;
            }

            ItemStack[] contents = session.inventory.getContents();
            updateShulkerInSlot(player, session.slot, contents);

            plugin.getLogger().fine("Saved shulker for " + player.getName() + " to slot: " + session.slot);
        }
    }

    public boolean isOpenedShulker(Player player, ItemStack item) {
        if (item == null || !isShulkerBox(item)) {
            return false;
        }

        ShulkerSession session = activeSessions.get(player.getUniqueId());
        if (session == null) {
            return false;
        }

        return isSameShulker(item, session.originalShulker);
    }

    public boolean isOpenedShulkerInInventory(Player player) {
        ShulkerSession session = activeSessions.get(player.getUniqueId());
        if (session == null) {
            return false;
        }

        return session.slot.type == SlotType.MAIN_HAND ||
                session.slot.type == SlotType.OFF_HAND ||
                session.slot.type == SlotType.INVENTORY;
    }

    public void scheduleAutoSave(Player player) {
        performAutoSave(player);
    }

    private void performAutoSave(Player player) {
        UUID playerId = player.getUniqueId();
        ShulkerSession session = activeSessions.get(playerId);

        if (session == null) {
            return;
        }

        ItemStack currentShulker = getCurrentShulkerInSlot(player, session.slot);

        if (currentShulker == null || !isShulkerBox(currentShulker) || !isSameShulker(currentShulker, session.originalShulker)) {
            plugin.getLogger().warning("AUTO-SAVE BLOCKED: Shulker validation failed for " + player.getName());
            return;
        }

        ItemStack[] contents = session.inventory.getContents();
        updateShulkerInSlot(player, session.slot, contents);
    }

    public void performImmediateValidation(Player player) {
        UUID playerId = player.getUniqueId();
        ShulkerSession session = activeSessions.get(playerId);

        if (session == null) {
            return;
        }

        ItemStack currentShulker = getCurrentShulkerInSlot(player, session.slot);

        if (currentShulker == null || !isShulkerBox(currentShulker) || !isSameShulker(currentShulker, session.originalShulker)) {
            plugin.getLogger().severe("IMMEDIATE VALIDATION FAILED - Closing session for " + player.getName());

            activeSessions.remove(playerId);
            player.closeInventory();
            player.sendMessage(Component.text("ANTI-DUPE: Manipulation detected!", NamedTextColor.DARK_RED));
            player.sendMessage(Component.text("Changes NOT saved!", NamedTextColor.GOLD));
        }
    }

    public void validateAllSessions() {
        for (Map.Entry<UUID, ShulkerSession> entry : new HashMap<>(activeSessions).entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                activeSessions.remove(entry.getKey());
                continue;
            }

            ShulkerSession session = entry.getValue();
            ItemStack currentShulker = getCurrentShulkerInSlot(player, session.slot);

            boolean manipulated = false;
            String reason = "";

            if (currentShulker == null) {
                manipulated = true;
                reason = "Shulker is NULL";
            } else if (!isShulkerBox(currentShulker)) {
                manipulated = true;
                reason = "Item is not a shulker box (type: " + currentShulker.getType() + ")";
            } else if (!isSameShulker(currentShulker, session.originalShulker)) {
                manipulated = true;
                reason = "Shulker was replaced (original: " + session.originalShulker.getType() + ", current: " + currentShulker.getType() + ")";
            }

            if (manipulated) {
                activeSessions.remove(entry.getKey());

                String finalReason = reason;

                Component text = Component.text("════════════════════════", NamedTextColor.RED).decorate(TextDecoration.STRIKETHROUGH)
                        .append(Component.newline())
                        .append(Component.text("MANIPULATION DETECTED: " + finalReason, NamedTextColor.RED))
                        .append(Component.text("Player: " + player.getName(), NamedTextColor.RED))
                        .append(Component.text("Slot: " + session.slot, NamedTextColor.RED))
                        .append(Component.text("Action: FORCE CLOSE WITHOUT SAVE", NamedTextColor.RED))
                        .append(Component.newline())
                        .append(Component.text("════════════════════════", NamedTextColor.RED).decorate(TextDecoration.STRIKETHROUGH));

                for (Player allPlayers : Bukkit.getOnlinePlayers()) {
                    if (allPlayers.hasPermission("virtualshulker.admin")) {
                         allPlayers.sendMessage(text);
                    }
                }

                Bukkit.getScheduler().runTask(plugin, () -> player.closeInventory());
            }
        }
    }

    private ItemStack getCurrentShulkerInSlot(Player player, ShulkerSlot slot) {
        return switch (slot.type) {
            case MAIN_HAND -> player.getInventory().getItemInMainHand();
            case OFF_HAND -> player.getInventory().getItemInOffHand();
            case INVENTORY -> player.getInventory().getItem(slot.slotIndex);
            case ENDER_CHEST -> player.getEnderChest().getItem(slot.slotIndex);
        };
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

        if (!(item1.getItemMeta() instanceof BlockStateMeta meta1)) return false;
        if (!(item2.getItemMeta() instanceof BlockStateMeta meta2)) return false;

        boolean sameDisplayName = Objects.equals(meta1.displayName(), meta2.displayName());
        boolean sameLore = Objects.equals(meta1.lore(), meta2.lore());
        boolean sameEnchants = Objects.equals(meta1.getEnchants(), meta2.getEnchants());

        return sameDisplayName && sameLore && sameEnchants;
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

        loadingPlayers.remove(playerId);

        ShulkerSession session = activeSessions.remove(playerId);
        if (session != null) {
            plugin.getLogger().info("Force cleaned up session for: " + player.getName());
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

        plugin.getLogger().info("VirtualShulkerManager shutdown complete");
    }

    private record ShulkerSession(Inventory inventory, ShulkerSlot slot, ItemStack originalShulker) {}

    private record ShulkerSlot(SlotType type, int slotIndex) {}

    private enum SlotType {
        MAIN_HAND,
        OFF_HAND,
        INVENTORY,
        ENDER_CHEST
    }

    public VirtualShulkerPlugin getPlugin() {
        return plugin;
    }
}