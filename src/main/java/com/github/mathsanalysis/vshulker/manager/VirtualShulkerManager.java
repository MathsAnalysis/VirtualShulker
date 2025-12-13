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

public final class VirtualShulkerManager {

    private static VirtualShulkerManager instance;

    private final VirtualShulkerPlugin plugin;

    private final Map<UUID, ShulkerSession> activeSessions;

    private final Set<UUID> loadingPlayers;

    private final Set<Location> placedShulkerLocations;

    private VirtualShulkerManager(VirtualShulkerPlugin plugin) {
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
        plugin.getLogger().info("╔════════════════════════════════════════╗");
        plugin.getLogger().info("║  VirtualShulkerManager Initialized    ║");
        plugin.getLogger().info("║  Mode: NBT-ONLY (Direct Save)         ║");
        plugin.getLogger().info("║  No Database • No Cache • No IDs      ║");
        plugin.getLogger().info("╚════════════════════════════════════════╝");
    }

    public void openShulker(Player player, ItemStack shulkerBox) {
        if (!isShulkerBox(shulkerBox)) {
            return;
        }

        UUID playerId = player.getUniqueId();

        if (activeSessions.containsKey(playerId)) {
            player.sendMessage(Component.text("Hai già uno shulker aperto!", NamedTextColor.YELLOW));
            return;
        }

        if (loadingPlayers.contains(playerId)) {
            player.sendMessage(Component.text("Caricamento shulker in corso...", NamedTextColor.YELLOW));
            return;
        }

        loadingPlayers.add(playerId);

        try {
            ShulkerSlot slot = findShulkerSlot(player, shulkerBox);

            if (slot == null) {
                plugin.getLogger().warning("Impossibile trovare lo shulker nell'inventario di: " + player.getName());
                player.sendMessage(Component.text("Errore: Shulker non trovato", NamedTextColor.RED));
                return;
            }

            ItemStack[] contents = readContentsFromNBT(shulkerBox);

            Inventory inventory = createInventory(contents);

            ShulkerSession session = new ShulkerSession(inventory, slot);
            activeSessions.put(playerId, session);

            player.openInventory(inventory);

            plugin.getLogger().fine("✓ Shulker aperto per " + player.getName() + " da: " + slot);

        } finally {
            loadingPlayers.remove(playerId);
        }
    }

    public void saveShulkerContents(Player player) {
        UUID playerId = player.getUniqueId();
        ShulkerSession session = activeSessions.get(playerId);

        if (session == null) {
            return;
        }

        ItemStack[] contents = session.inventory.getContents();

        writeContentsToSlot(player, session.slot, contents);

        plugin.getLogger().finest("✓ Contenuti salvati in tempo reale per: " + player.getName());
    }

    public void closeShulker(Player player, boolean save) {
        UUID playerId = player.getUniqueId();

        ShulkerSession session = activeSessions.remove(playerId);
        loadingPlayers.remove(playerId);

        if (session == null) {
            return;
        }

        if (save) {
            ItemStack[] contents = session.inventory.getContents();
            writeContentsToSlot(player, session.slot, contents);

            plugin.getLogger().fine("✓ Shulker salvato per " + player.getName() + " in: " + session.slot);
        }
    }

    private ItemStack[] readContentsFromNBT(ItemStack shulkerBox) {
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
            plugin.getLogger().warning("⚠ Errore lettura NBT: " + e.getMessage());
            return new ItemStack[27];
        }
    }

    private void writeContentsToNBT(ItemStack shulkerBox, ItemStack[] contents) {
        try {
            if (!(shulkerBox.getItemMeta() instanceof BlockStateMeta blockMeta)) {
                return;
            }

            ShulkerBox box = (ShulkerBox) blockMeta.getBlockState();
            box.getInventory().setContents(contents);

            blockMeta.setBlockState(box);
            shulkerBox.setItemMeta(blockMeta);

        } catch (Exception e) {
            plugin.getLogger().severe("✗ ERRORE CRITICO - Scrittura NBT fallita: " + e.getMessage());
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

    private void writeContentsToSlot(Player player, ShulkerSlot slot, ItemStack[] contents) {
        ItemStack shulkerItem = null;

        switch (slot.type) {
            case MAIN_HAND:
                shulkerItem = player.getInventory().getItemInMainHand();
                if (isShulkerBox(shulkerItem)) {
                    writeContentsToNBT(shulkerItem, contents);
                    player.getInventory().setItemInMainHand(shulkerItem);
                }
                break;

            case OFF_HAND:
                shulkerItem = player.getInventory().getItemInOffHand();
                if (isShulkerBox(shulkerItem)) {
                    writeContentsToNBT(shulkerItem, contents);
                    player.getInventory().setItemInOffHand(shulkerItem);
                }
                break;

            case INVENTORY:
                shulkerItem = player.getInventory().getItem(slot.slotIndex);
                if (isShulkerBox(shulkerItem)) {
                    writeContentsToNBT(shulkerItem, contents);
                    player.getInventory().setItem(slot.slotIndex, shulkerItem);
                }
                break;

            case ENDER_CHEST:
                shulkerItem = player.getEnderChest().getItem(slot.slotIndex);
                if (isShulkerBox(shulkerItem)) {
                    writeContentsToNBT(shulkerItem, contents);
                    player.getEnderChest().setItem(slot.slotIndex, shulkerItem);
                }
                break;
        }

        if (shulkerItem == null || !isShulkerBox(shulkerItem)) {
            plugin.getLogger().severe("╔═══════════════════════════════════════════╗");
            plugin.getLogger().severe("║  ERRORE CRITICO: SHULKER SCOMPARSO!      ║");
            plugin.getLogger().severe("║  Slot: " + slot);
            plugin.getLogger().severe("║  Contenuti in memoria preservati         ║");
            plugin.getLogger().severe("║  SEGNALARE QUESTO BUG IMMEDIATAMENTE!    ║");
            plugin.getLogger().severe("╚═══════════════════════════════════════════╝");
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
        loadingPlayers.remove(playerId);

        ShulkerSession session = activeSessions.remove(playerId);
        if (session != null) {
            plugin.getLogger().info("⚠ Cleanup forzato per: " + player.getName());
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
        plugin.getLogger().info("╔════════════════════════════════════════╗");
        plugin.getLogger().info("║  Shutting down VirtualShulkerManager  ║");
        plugin.getLogger().info("╚════════════════════════════════════════╝");

        int closedSessions = 0;
        for (Map.Entry<UUID, ShulkerSession> entry : new HashMap<>(activeSessions).entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                closeShulker(player, true);
                closedSessions++;
            }
        }

        if (closedSessions > 0) {
            plugin.getLogger().info("✓ Salvate " + closedSessions + " sessioni attive");
        }

        activeSessions.clear();
        loadingPlayers.clear();
        placedShulkerLocations.clear();

        plugin.getLogger().info("✓ Shutdown completato");
    }

    private record ShulkerSession(Inventory inventory, ShulkerSlot slot) {}

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