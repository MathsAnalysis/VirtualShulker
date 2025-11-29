package com.github.mathsanalysis.vshulker.manager;

import com.github.mathsanalysis.vshulker.VirtualShulkerPlugin;
import com.github.mathsanalysis.vshulker.config.Config;
import net.kyori.adventure.text.Component;
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
    private final DatabaseManager database;

    private final Map<String, UUID> shulkerLocks;
    private final Set<String> lockedShulkerIds;

    private final Set<Location> placedShulkerLocations;

    private final Map<UUID, Map<String, ItemStack[]>> playerCache;
    private final Map<UUID, Set<String>> playerShulkerIds;

    private final Map<UUID, ShulkerSession> activeSessions;
    private final Set<UUID> loadingPlayers;
    private final Map<UUID, ItemStack> openedShulkerItems;

    private final ReentrantLock globalLock = new ReentrantLock();

    private VirtualShulkerManager(VirtualShulkerPlugin plugin) {
        this.plugin = plugin;
        this.shulkerIdKey = new NamespacedKey(plugin, "shulker_id");
        this.database = new DatabaseManager(plugin);

        this.shulkerLocks = new ConcurrentHashMap<>();
        this.lockedShulkerIds = ConcurrentHashMap.newKeySet();
        this.placedShulkerLocations = ConcurrentHashMap.newKeySet();

        this.playerCache = new ConcurrentHashMap<>();
        this.playerShulkerIds = new ConcurrentHashMap<>();
        this.activeSessions = new ConcurrentHashMap<>();
        this.loadingPlayers = ConcurrentHashMap.newKeySet();
        this.openedShulkerItems = new ConcurrentHashMap<>();
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

    public NamespacedKey getShulkerIdKey() {
        return shulkerIdKey;
    }

    public CompletableFuture<Void> initialize() {
        return database.initialize()
                .thenCompose(v -> database.migrateFromDatFile())
                .thenRun(() -> {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        loadPlayerCache(player);
                    }
                    plugin.getLogger().info("Database ready, anti-dupe protection active");
                });
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

    public void registerPlacedShulker(Location location) {
        placedShulkerLocations.add(location.getBlock().getLocation());
    }

    public void unregisterPlacedShulker(Location location) {
        placedShulkerLocations.remove(location.getBlock().getLocation());
    }

    public boolean isPlacedVirtualShulker(Location location) {
        return placedShulkerLocations.contains(location.getBlock().getLocation());
    }

    public void loadPlayerCache(Player player) {
        UUID playerId = player.getUniqueId();

        if (playerCache.containsKey(playerId)) {
            return;
        }

        playerCache.put(playerId, new ConcurrentHashMap<>());
        playerShulkerIds.put(playerId, ConcurrentHashMap.newKeySet());

        CompletableFuture.runAsync(() -> {
            Set<String> shulkerIds = new HashSet<>();

            for (ItemStack item : player.getInventory().getContents()) {
                String id = getShulkerIdFromItem(item);
                if (id != null) shulkerIds.add(id);
            }

            for (ItemStack item : player.getEnderChest().getContents()) {
                String id = getShulkerIdFromItem(item);
                if (id != null) shulkerIds.add(id);
            }

            for (String shulkerId : shulkerIds) {
                ItemStack[] contents = database.loadShulkerSync(shulkerId);
                if (contents != null) {
                    Map<String, ItemStack[]> cache = playerCache.get(playerId);
                    if (cache != null) {
                        cache.put(shulkerId, contents);
                        playerShulkerIds.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet()).add(shulkerId);
                    }
                }
            }

            if (!shulkerIds.isEmpty()) {
                plugin.getLogger().info("Loaded " + shulkerIds.size() + " shulkers for " + player.getName());
            }
        }, plugin.getVirtualThreadExecutor());
    }

    public void unloadPlayerCache(Player player) {
        UUID playerId = player.getUniqueId();

        shulkerLocks.entrySet().removeIf(entry -> entry.getValue().equals(playerId));

        Map<String, ItemStack[]> cache = playerCache.remove(playerId);
        playerShulkerIds.remove(playerId);

        if (cache != null && !cache.isEmpty()) {
            for (Map.Entry<String, ItemStack[]> entry : cache.entrySet()) {
                database.saveShulkerSync(entry.getKey(), entry.getValue());
            }
            plugin.getLogger().info("Saved " + cache.size() + " shulkers for " + player.getName());
        }
    }

    public String getShulkerIdFromItem(ItemStack item) {
        if (!isShulkerBox(item)) return null;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc.get(shulkerIdKey, PersistentDataType.STRING);
    }

    public void openShulker(Player player, ItemStack shulkerBox) {
        if (!isShulkerBox(shulkerBox)) {
            return;
        }

        UUID playerId = player.getUniqueId();

        if (activeSessions.containsKey(playerId) || loadingPlayers.contains(playerId)) {
            return;
        }

        String shulkerId = getOrCreateShulkerId(shulkerBox);

        if (!tryLockShulker(shulkerId, playerId)) {
            plugin.getLogger().warning("Blocked duplicate shulker access: " + shulkerId + " by " + player.getName());
            return;
        }

        loadingPlayers.add(playerId);
        ItemStack shulkerClone = shulkerBox.clone();

        Map<String, ItemStack[]> cache = playerCache.get(playerId);
        if (cache != null) {
            ItemStack[] cached = cache.get(shulkerId);
            if (cached != null) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        finalizeOpen(player, shulkerId, shulkerClone, cloneContents(cached))
                );
                return;
            }
        }

        database.loadShulker(shulkerId).thenAccept(contents -> {
            if (!player.isOnline()) {
                loadingPlayers.remove(playerId);
                releaseLock(shulkerId);
                return;
            }

            ItemStack[] finalContents;

            if (contents != null) {
                if (cache != null) {
                    cache.put(shulkerId, contents);
                    playerShulkerIds.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet()).add(shulkerId);
                }
                finalContents = cloneContents(contents);
            } else {
                ItemStack[] nbtContents = extractFromNBT(shulkerClone);
                if (nbtContents != null) {
                    if (cache != null) {
                        cache.put(shulkerId, cloneContents(nbtContents));
                        playerShulkerIds.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet()).add(shulkerId);
                    }
                    database.saveShulker(shulkerId, nbtContents);
                }
                finalContents = nbtContents;
            }

            Bukkit.getScheduler().runTask(plugin, () ->
                    finalizeOpen(player, shulkerId, shulkerClone, finalContents)
            );
        }).exceptionally(ex -> {
            plugin.getLogger().warning("Error loading shulker: " + ex.getMessage());
            loadingPlayers.remove(playerId);
            releaseLock(shulkerId);
            return null;
        });
    }

    private void finalizeOpen(Player player, String shulkerId, ItemStack shulkerBox, ItemStack[] contents) {
        UUID playerId = player.getUniqueId();

        loadingPlayers.remove(playerId);

        if (!player.isOnline() || activeSessions.containsKey(playerId)) {
            releaseLock(shulkerId);
            return;
        }

        confirmLock(shulkerId, playerId);

        Inventory inventory = createInventory(contents);

        ShulkerSession session = new ShulkerSession(inventory, shulkerId, System.currentTimeMillis());
        activeSessions.put(playerId, session);

        openedShulkerItems.put(playerId, shulkerBox);

        player.openInventory(inventory);
    }

    public boolean isLoading(Player player) {
        return loadingPlayers.contains(player.getUniqueId());
    }

    public void cancelLoading(Player player) {
        UUID playerId = player.getUniqueId();
        loadingPlayers.remove(playerId);

        lockedShulkerIds.removeIf(id -> {
            UUID holder = shulkerLocks.get(id);
            return holder != null && holder.equals(playerId);
        });
    }

    public void closeShulker(Player player, boolean save) {
        closeShulker(player, save, false);
    }

    public void closeShulker(Player player, boolean save, boolean synchronous) {
        UUID playerId = player.getUniqueId();

        ShulkerSession session = activeSessions.remove(playerId);
        openedShulkerItems.remove(playerId);
        loadingPlayers.remove(playerId);

        if (session == null) {
            return;
        }

        releaseLock(session.shulkerId);

        if (save) {
            ItemStack[] contents = session.inventory.getContents();

            Map<String, ItemStack[]> cache = playerCache.get(playerId);
            if (cache != null) {
                cache.put(session.shulkerId, cloneContents(contents));
            }

            updateShulkerNBTForPlayer(player, session.shulkerId, contents);

            if (synchronous) {
                database.saveShulkerSync(session.shulkerId, contents);
            } else {
                database.saveShulker(session.shulkerId, contents);
            }
        }
    }

    private void updateShulkerNBTForPlayer(Player player, String shulkerId, ItemStack[] contents) {
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
            updateShulkerNBT(item, contents);
            return true;
        }

        return false;
    }

    public void closeShulkerOnDamage(Player player) {
        UUID playerId = player.getUniqueId();

        ShulkerSession session = activeSessions.remove(playerId);
        loadingPlayers.remove(playerId);

        if (session == null) {
            return;
        }

        releaseLock(session.shulkerId);

        ItemStack[] contents = session.inventory.getContents();

        Map<String, ItemStack[]> cache = playerCache.get(playerId);
        if (cache != null) {
            cache.put(session.shulkerId, cloneContents(contents));
        }

        updateShulkerNBTForPlayer(player, session.shulkerId, contents);
        database.saveShulkerSync(session.shulkerId, contents);

        openedShulkerItems.remove(playerId);
        player.closeInventory();
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

    public ItemStack[] getShulkerContents(String shulkerId) {
        for (Map<String, ItemStack[]> cache : playerCache.values()) {
            ItemStack[] contents = cache.get(shulkerId);
            if (contents != null) {
                return cloneContents(contents);
            }
        }

        ItemStack[] fromDb = database.loadShulkerSync(shulkerId);
        return fromDb != null ? cloneContents(fromDb) : null;
    }

    public void updateShulkerData(String shulkerId, ItemStack[] contents) {
        ItemStack[] cloned = cloneContents(contents);

        for (Map.Entry<UUID, Set<String>> entry : playerShulkerIds.entrySet()) {
            if (entry.getValue().contains(shulkerId)) {
                Map<String, ItemStack[]> cache = playerCache.get(entry.getKey());
                if (cache != null) {
                    cache.put(shulkerId, cloned);
                }
            }
        }
        database.saveShulkerSync(shulkerId, contents);
    }

    private ItemStack[] extractFromNBT(ItemStack shulkerBox) {
        try {
            if (!(shulkerBox.getItemMeta() instanceof BlockStateMeta meta)) {
                return null;
            }

            if (!(meta.getBlockState() instanceof ShulkerBox box)) {
                return null;
            }

            ItemStack[] contents = box.getInventory().getContents();

            boolean hasItems = false;
            for (ItemStack item : contents) {
                if (item != null && item.getType() != Material.AIR) {
                    hasItems = true;
                    break;
                }
            }

            return hasItems ? contents : null;
        } catch (Exception e) {
            plugin.getLogger().warning("Error extracting NBT from shulker: " + e.getMessage());
            return null;
        }
    }

    private void updateShulkerNBT(ItemStack shulkerBox, ItemStack[] newContents) {
        try {
            ItemMeta meta = shulkerBox.getItemMeta();
            if (!(meta instanceof BlockStateMeta blockMeta)) {
                return;
            }

            ShulkerBox box = (ShulkerBox) blockMeta.getBlockState();
            box.getInventory().setContents(newContents);

            blockMeta.setBlockState(box);
            shulkerBox.setItemMeta(blockMeta);
        } catch (Exception e) {
            plugin.getLogger().warning("Error updating shulker NBT: " + e.getMessage());
        }
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

    public boolean isShulkerBox(ItemStack item) {
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
            inventory.setContents(cloneContents(contents));
        }

        return inventory;
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

    public void shutdown() {
        for (Map.Entry<UUID, ShulkerSession> entry : new HashMap<>(activeSessions).entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                closeShulker(player, true, true);
            }
        }

        for (Map.Entry<UUID, Map<String, ItemStack[]>> entry : playerCache.entrySet()) {
            for (Map.Entry<String, ItemStack[]> shulker : entry.getValue().entrySet()) {
                database.saveShulkerSync(shulker.getKey(), shulker.getValue());
            }
        }

        shulkerLocks.clear();
        lockedShulkerIds.clear();
        placedShulkerLocations.clear();
        playerCache.clear();
        playerShulkerIds.clear();
        activeSessions.clear();
        loadingPlayers.clear();
        openedShulkerItems.clear();

        database.close();
    }

    public void clearCache() {
        shulkerLocks.clear();
        lockedShulkerIds.clear();
        playerCache.clear();
        playerShulkerIds.clear();
        activeSessions.clear();
        loadingPlayers.clear();
        openedShulkerItems.clear();
    }

    public void reloadCache() {
        clearCache();
        for (Player player : Bukkit.getOnlinePlayers()) {
            loadPlayerCache(player);
        }
    }

    public DatabaseManager getDatabase() {
        return database;
    }

    private record ShulkerSession(Inventory inventory, String shulkerId, long openedAt) {}
}