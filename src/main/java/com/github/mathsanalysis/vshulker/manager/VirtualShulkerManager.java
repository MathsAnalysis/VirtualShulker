package com.github.mathsanalysis.vshulker.manager;

import com.github.mathsanalysis.vshulker.VirtualShulkerPlugin;
import com.github.mathsanalysis.vshulker.config.Config;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.block.ShulkerBox;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class VirtualShulkerManager {

    private static VirtualShulkerManager instance;

    private final VirtualShulkerPlugin plugin;
    private final NamespacedKey shulkerIdKey;

    private final Map<String, UUID> shulkerLocks;
    private final Set<String> lockedShulkerIds;
    private final ReentrantLock globalLock = new ReentrantLock();

    private final Map<String, UUID> shulkerOwnership = new ConcurrentHashMap<>();

    private final Map<UUID, ShulkerSession> activeSessions;
    private final Set<UUID> loadingPlayers;
    private final Map<UUID, Long> loadingTimestamps;

    private final Set<Location> placedShulkerLocations;

    private static final long LOADING_TIMEOUT_MS = 5000;

    private VirtualShulkerManager(VirtualShulkerPlugin plugin) {
        this.plugin = plugin;
        this.shulkerIdKey = new NamespacedKey(plugin, "shulker_id");

        this.shulkerLocks = new ConcurrentHashMap<>();
        this.lockedShulkerIds = ConcurrentHashMap.newKeySet();
        this.activeSessions = new ConcurrentHashMap<>();
        this.loadingPlayers = ConcurrentHashMap.newKeySet();
        this.loadingTimestamps = new ConcurrentHashMap<>();
        this.placedShulkerLocations = ConcurrentHashMap.newKeySet();
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

    public VirtualShulkerPlugin getPlugin() {
        return plugin;
    }

    public NamespacedKey getShulkerIdKey() {
        return shulkerIdKey;
    }

    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            MigrationManager migrationManager = new MigrationManager(plugin, this);
            int migrated = migrationManager.migrateFromDatabase();

            if (migrated > 0) {
                plugin.getLogger().info("Successfully migrated " + migrated + " shulkers from database to NBT");
            }

            for (Player player : Bukkit.getOnlinePlayers()) {
                loadPlayerOwnership(player);
            }

            plugin.getLogger().info("VirtualShulkerManager initialized (NBT-only mode)");
        }, plugin.getVirtualThreadExecutor());
    }

    private boolean tryLockShulker(String shulkerId, UUID playerId) {
        globalLock.lock();
        try {
            if (lockedShulkerIds.contains(shulkerId)) {
                return false;
            }

            UUID currentHolder = shulkerLocks.get(shulkerId);
            if (currentHolder != null && !currentHolder.equals(playerId)) {
                return false;
            }

            lockedShulkerIds.add(shulkerId);
            return true;
        } finally {
            globalLock.unlock();
        }
    }

    private void confirmLock(String shulkerId, UUID playerId) {
        globalLock.lock();
        try {
            lockedShulkerIds.remove(shulkerId);
            shulkerLocks.put(shulkerId, playerId);
        } finally {
            globalLock.unlock();
        }
    }

    private void releaseLock(String shulkerId) {
        globalLock.lock();
        try {
            lockedShulkerIds.remove(shulkerId);
            shulkerLocks.remove(shulkerId);
        } finally {
            globalLock.unlock();
        }
    }

    public boolean isShulkerInUse(String shulkerId) {
        return shulkerLocks.containsKey(shulkerId) || lockedShulkerIds.contains(shulkerId);
    }

    public void loadPlayerOwnership(Player player) {
        UUID playerId = player.getUniqueId();

        for (ItemStack item : player.getInventory().getContents()) {
            String id = getShulkerIdFromItem(item);
            if (id != null) {
                shulkerOwnership.putIfAbsent(id, playerId);
            }
        }

        for (ItemStack item : player.getEnderChest().getContents()) {
            String id = getShulkerIdFromItem(item);
            if (id != null) {
                shulkerOwnership.putIfAbsent(id, playerId);
            }
        }

        plugin.getLogger().fine("Loaded ownership tracking for " + player.getName());
    }

    public void migrateLazyPlayer(Player player) {
        MigrationManager migrationManager = new MigrationManager(plugin, this);
        migrationManager.migrateLazyPlayer(player);
    }

    public void unloadPlayerOwnership(Player player) {
        UUID playerId = player.getUniqueId();

        shulkerLocks.entrySet().removeIf(entry -> entry.getValue().equals(playerId));

        Set<String> playerShulkerIds = new HashSet<>();

        for (ItemStack item : player.getInventory().getContents()) {
            String id = getShulkerIdFromItem(item);
            if (id != null) playerShulkerIds.add(id);
        }

        for (ItemStack item : player.getEnderChest().getContents()) {
            String id = getShulkerIdFromItem(item);
            if (id != null) playerShulkerIds.add(id);
        }

        shulkerOwnership.entrySet().removeIf(entry ->
                entry.getValue().equals(playerId) && !playerShulkerIds.contains(entry.getKey())
        );
    }

    public boolean isDuplicateShulker(String shulkerId, Player player) {
        UUID currentOwner = shulkerOwnership.get(shulkerId);

        if (currentOwner == null) {
            shulkerOwnership.put(shulkerId, player.getUniqueId());
            return false;
        }

        boolean isDuplicate = !currentOwner.equals(player.getUniqueId());

        if (isDuplicate) {
            plugin.getLogger().warning(
                    "DUPLICATE SHULKER DETECTED! ID: " + shulkerId +
                            " | Original owner: " + Bukkit.getOfflinePlayer(currentOwner).getName() +
                            " | Attempting player: " + player.getName()
            );
        }

        return isDuplicate;
    }

    public String getShulkerIdFromItem(ItemStack item) {
        if (!isShulkerBox(item)) return null;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc.get(shulkerIdKey, PersistentDataType.STRING);
    }

    public String getOrCreateShulkerId(ItemStack shulkerBox) {
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
            ItemMeta meta = shulkerBox.getItemMeta();
            if (!(meta instanceof BlockStateMeta blockMeta)) {
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

    public void regenerateShulkerId(ItemStack shulkerItem, String originalId, UUID newOwner) {
        if (!isShulkerBox(shulkerItem)) {
            return;
        }

        ItemStack[] originalContents = getContentsFromNBT(shulkerItem);

        String newId = UUID.randomUUID().toString();

        ItemMeta meta = shulkerItem.getItemMeta();
        if (meta != null) {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(shulkerIdKey, PersistentDataType.STRING, newId);
            shulkerItem.setItemMeta(meta);
        }

        setContentsToNBT(shulkerItem, originalContents);

        if (newOwner != null) {
            shulkerOwnership.put(newId, newOwner);
        }

        plugin.getLogger().info("Regenerated shulker ID: " + originalId + " -> " + newId +
                (newOwner != null ? " (owner: " + Bukkit.getOfflinePlayer(newOwner).getName() + ")" : ""));
    }

    private void updateShulkerInPlayerInventory(Player player, String oldId, ItemStack regeneratedShulker) {
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (isMatchingShulker(mainHand, oldId)) {
            player.getInventory().setItemInMainHand(regeneratedShulker.clone());
            return;
        }

        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (isMatchingShulker(offHand, oldId)) {
            player.getInventory().setItemInOffHand(regeneratedShulker.clone());
            return;
        }

        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (isMatchingShulker(item, oldId)) {
                player.getInventory().setItem(i, regeneratedShulker.clone());
                return;
            }
        }

        for (int i = 0; i < player.getEnderChest().getSize(); i++) {
            ItemStack item = player.getEnderChest().getItem(i);
            if (isMatchingShulker(item, oldId)) {
                player.getEnderChest().setItem(i, regeneratedShulker.clone());
                return;
            }
        }
    }

    private boolean isMatchingShulker(ItemStack item, String targetId) {
        if (!isShulkerBox(item)) {
            return false;
        }

        String itemId = getShulkerIdFromItem(item);
        return targetId.equals(itemId);
    }

    public void openShulker(Player player, ItemStack shulkerBox) {
        if (!isShulkerBox(shulkerBox)) {
            return;
        }

        UUID playerId = player.getUniqueId();

        cleanupStaleLoadingState(playerId);

        if (activeSessions.containsKey(playerId)) {
            player.sendMessage(Component.text("You already have a shulker open!", NamedTextColor.YELLOW));
            return;
        }

        if (loadingPlayers.contains(playerId)) {
            player.sendMessage(Component.text("Loading shulker, please wait...", NamedTextColor.YELLOW));
            return;
        }

        String shulkerId = getOrCreateShulkerId(shulkerBox);

        if (isDuplicateShulker(shulkerId, player)) {
            String oldId = shulkerId;

            plugin.getLogger().warning(
                    "Auto-regenerating duplicate shulker for " + player.getName() +
                            " (original ID: " + oldId + ")"
            );

            regenerateShulkerId(shulkerBox, oldId, playerId);
            updateShulkerInPlayerInventory(player, oldId, shulkerBox);

            shulkerId = getShulkerIdFromItem(shulkerBox);

            plugin.getLogger().info("Shulker regenerated with preserved contents: " + oldId + " -> " + shulkerId);
        }

        if (!tryLockShulker(shulkerId, playerId)) {
            plugin.getLogger().warning("Blocked access to in-use shulker: " + shulkerId + " by " + player.getName());
            player.sendMessage(Component.text("This shulker is already in use!", NamedTextColor.RED));
            return;
        }

        loadingPlayers.add(playerId);
        loadingTimestamps.put(playerId, System.currentTimeMillis());

        ItemStack[] contents = getContentsFromNBT(shulkerBox);

        confirmLock(shulkerId, playerId);

        Inventory inventory = createInventory(contents);

        ShulkerSession session = new ShulkerSession(inventory, shulkerId, shulkerBox.clone());
        activeSessions.put(playerId, session);

        loadingPlayers.remove(playerId);
        loadingTimestamps.remove(playerId);

        player.openInventory(inventory);

        plugin.getLogger().fine("Opened shulker " + shulkerId + " for " + player.getName());
    }

    public void closeShulker(Player player, boolean save) {
        closeShulker(player, save, false);
    }

    public void closeShulker(Player player, boolean save, boolean synchronous) {
        UUID playerId = player.getUniqueId();

        ShulkerSession session = activeSessions.remove(playerId);
        loadingPlayers.remove(playerId);
        loadingTimestamps.remove(playerId);

        if (session == null) {
            return;
        }

        releaseLock(session.shulkerId);

        if (save) {
            ItemStack[] contents = session.inventory.getContents();

            updateShulkerNBTInPlayerInventory(player, session.shulkerId, contents);

            plugin.getLogger().fine("Saved shulker " + session.shulkerId + " to NBT for " + player.getName());
        }
    }

    private void updateShulkerNBTInPlayerInventory(Player player, String shulkerId, ItemStack[] contents) {
        if (tryUpdateShulkerNBT(player.getInventory().getItemInMainHand(), shulkerId, contents)) {
            return;
        }

        if (tryUpdateShulkerNBT(player.getInventory().getItemInOffHand(), shulkerId, contents)) {
            return;
        }

        for (ItemStack item : player.getInventory().getContents()) {
            if (tryUpdateShulkerNBT(item, shulkerId, contents)) {
                return;
            }
        }

        for (ItemStack item : player.getEnderChest().getContents()) {
            if (tryUpdateShulkerNBT(item, shulkerId, contents)) {
                return;
            }
        }

        plugin.getLogger().warning("Could not find shulker " + shulkerId +
                " in " + player.getName() + "'s inventory to update NBT");
    }

    private boolean tryUpdateShulkerNBT(ItemStack item, String targetShulkerId, ItemStack[] contents) {
        if (!isShulkerBox(item)) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String itemShulkerId = pdc.get(shulkerIdKey, PersistentDataType.STRING);

        if (itemShulkerId != null && itemShulkerId.equals(targetShulkerId)) {
            setContentsToNBT(item, contents);
            return true;
        }

        return false;
    }

    public void closeShulkerOnDamage(Player player) {
        UUID playerId = player.getUniqueId();

        ShulkerSession session = activeSessions.remove(playerId);
        loadingPlayers.remove(playerId);
        loadingTimestamps.remove(playerId);

        if (session == null) {
            return;
        }

        releaseLock(session.shulkerId);

        ItemStack[] contents = session.inventory.getContents();
        updateShulkerNBTInPlayerInventory(player, session.shulkerId, contents);

        player.closeInventory();

        plugin.getLogger().fine("Saved shulker " + session.shulkerId + " (damage close) for " + player.getName());
    }

    public boolean hasOpenShulker(Player player) {
        return activeSessions.containsKey(player.getUniqueId());
    }

    public boolean isValidSession(Player player, Inventory inventory) {
        ShulkerSession session = activeSessions.get(player.getUniqueId());
        return session != null && session.inventory.equals(inventory);
    }

    public String getOpenShulkerId(Player player) {
        ShulkerSession session = activeSessions.get(player.getUniqueId());
        return session != null ? session.shulkerId : null;
    }

    public boolean isLoading(Player player) {
        return loadingPlayers.contains(player.getUniqueId());
    }

    public void cancelLoading(Player player) {
        UUID playerId = player.getUniqueId();
        loadingPlayers.remove(playerId);
        loadingTimestamps.remove(playerId);

        globalLock.lock();
        try {
            lockedShulkerIds.removeIf(id -> {
                UUID holder = shulkerLocks.get(id);
                return holder != null && holder.equals(playerId);
            });
        } finally {
            globalLock.unlock();
        }
    }

    private void cleanupStaleLoadingState(UUID playerId) {
        Long loadingTime = loadingTimestamps.get(playerId);
        if (loadingTime != null && System.currentTimeMillis() - loadingTime > LOADING_TIMEOUT_MS) {
            plugin.getLogger().warning("Force-clearing stuck loading state for player: " +
                    Bukkit.getOfflinePlayer(playerId).getName());
            loadingPlayers.remove(playerId);
            loadingTimestamps.remove(playerId);

            globalLock.lock();
            try {
                lockedShulkerIds.removeIf(id -> {
                    UUID holder = shulkerLocks.get(id);
                    return holder != null && holder.equals(playerId);
                });
            } finally {
                globalLock.unlock();
            }
        }
    }

    public void forceCleanupPlayer(Player player) {
        UUID playerId = player.getUniqueId();

        plugin.getLogger().info("Force cleanup initiated for: " + player.getName());

        loadingPlayers.remove(playerId);
        loadingTimestamps.remove(playerId);

        ShulkerSession session = activeSessions.remove(playerId);
        if (session != null) {
            releaseLock(session.shulkerId);
            plugin.getLogger().info("Closed active session for shulker: " + session.shulkerId);
        }

        globalLock.lock();
        try {
            shulkerLocks.entrySet().removeIf(entry -> {
                if (entry.getValue().equals(playerId)) {
                    plugin.getLogger().info("Released lock on shulker: " + entry.getKey());
                    return true;
                }
                return false;
            });

            lockedShulkerIds.clear();
        } finally {
            globalLock.unlock();
        }

        plugin.getLogger().info("Force cleanup completed for: " + player.getName());
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

    public void updateShulkerData(String shulkerId, ItemStack[] contents) {
        plugin.getLogger().fine("Shulker " + shulkerId + " updated via native NBT");
    }

    public ItemStack[] getShulkerContents(String shulkerId) {
        for (ShulkerSession session : activeSessions.values()) {
            if (session.shulkerId.equals(shulkerId)) {
                return session.inventory.getContents();
            }
        }
        return null;
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

    public void shutdown() {
        plugin.getLogger().info("Shutting down VirtualShulkerManager...");

        int closedSessions = 0;
        for (Map.Entry<UUID, ShulkerSession> entry : new HashMap<>(activeSessions).entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                closeShulker(player, true, true);
                closedSessions++;
            }
        }
        if (closedSessions > 0) {
            plugin.getLogger().info("Closed " + closedSessions + " active sessions");
        }

        shulkerLocks.clear();
        lockedShulkerIds.clear();
        placedShulkerLocations.clear();
        activeSessions.clear();
        loadingPlayers.clear();
        loadingTimestamps.clear();
        shulkerOwnership.clear();

        plugin.getLogger().info("VirtualShulkerManager shutdown complete (NBT-only mode)");
    }

    public void reloadCache() {
        shulkerLocks.clear();
        lockedShulkerIds.clear();
        activeSessions.clear();
        loadingPlayers.clear();
        loadingTimestamps.clear();
        shulkerOwnership.clear();

        for (Player player : Bukkit.getOnlinePlayers()) {
            loadPlayerOwnership(player);
        }
    }

    public Map<String, UUID> getActiveLocks() {
        return new HashMap<>(shulkerLocks);
    }

    public Set<String> getLockedIds() {
        return new HashSet<>(lockedShulkerIds);
    }

    public Map<UUID, Long> getLoadingTimestamps() {
        return new HashMap<>(loadingTimestamps);
    }

    public DatabaseManager getDatabase() {
        return null;
    }

    private record ShulkerSession(Inventory inventory, String shulkerId, ItemStack originalShulker) {}
}