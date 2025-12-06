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
import java.util.stream.Collectors;

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
    private final Map<UUID, Long> loadingTimestamps;
    private final Map<UUID, ItemStack> openedShulkerItems;

    private final ReentrantLock globalLock = new ReentrantLock();

    private final Map<String, UUID> shulkerOwnership = new ConcurrentHashMap<>();

    private static final long LOADING_TIMEOUT_MS = 5000;
    private static final long STALE_LOCK_TIMEOUT_MS = 30000;

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
        this.loadingTimestamps = new ConcurrentHashMap<>();
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

    public VirtualShulkerPlugin getPlugin() {
        return plugin;
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

        openedShulkerItems.remove(playerId);

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

    public boolean isDuplicateShulker(String shulkerId, Player player) {
        UUID currentOwner = shulkerOwnership.get(shulkerId);

        if (currentOwner == null) {
            shulkerOwnership.put(shulkerId, player.getUniqueId());
            return false;
        }

        boolean isDuplicate = !currentOwner.equals(player.getUniqueId());

        if (isDuplicate) {
            plugin.getLogger().warning(
                    "Duplicate shulker detected! ID: " + shulkerId +
                            " | Original owner: " + Bukkit.getOfflinePlayer(currentOwner).getName() +
                            " | Attempting player: " + player.getName()
            );
        }

        return isDuplicate;
    }

    private void setShulkerOwnership(String shulkerId, UUID playerId) {
        shulkerOwnership.put(shulkerId, playerId);
    }

    private void clearShulkerOwnership(String shulkerId) {
        shulkerOwnership.remove(shulkerId);
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
                if (id != null) {
                    shulkerIds.add(id);
                    shulkerOwnership.putIfAbsent(id, playerId);
                }
            }

            for (ItemStack item : player.getEnderChest().getContents()) {
                String id = getShulkerIdFromItem(item);
                if (id != null) {
                    shulkerIds.add(id);
                    shulkerOwnership.putIfAbsent(id, playerId);
                }
            }

            int loadedCount = 0;
            for (String shulkerId : shulkerIds) {
                ItemStack[] contents = database.loadShulkerSync(shulkerId);
                if (contents != null) {
                    Map<String, ItemStack[]> cache = playerCache.get(playerId);
                    if (cache != null) {
                        cache.put(shulkerId, contents);
                        playerShulkerIds.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet()).add(shulkerId);
                        loadedCount++;
                    }
                }
            }

            if (loadedCount > 0) {
                plugin.getLogger().info("Loaded " + loadedCount + " shulkers for " + player.getName());
            }
        }, plugin.getVirtualThreadExecutor()).exceptionally(ex -> {
            plugin.getLogger().severe("Error loading player cache for " + player.getName() + ": " + ex.getMessage());
            ex.printStackTrace();
            return null;
        });
    }

    public void unloadPlayerCache(Player player) {
        UUID playerId = player.getUniqueId();

        shulkerLocks.entrySet().removeIf(entry -> entry.getValue().equals(playerId));

        Map<String, ItemStack[]> cache = playerCache.remove(playerId);
        playerShulkerIds.remove(playerId);

        if (cache != null && !cache.isEmpty()) {
            int savedCount = 0;
            for (Map.Entry<String, ItemStack[]> entry : cache.entrySet()) {
                try {
                    database.saveShulkerSync(entry.getKey(), entry.getValue());
                    savedCount++;
                } catch (Exception e) {
                    plugin.getLogger().severe("Failed to save shulker " + entry.getKey() +
                        " for " + player.getName() + ": " + e.getMessage());
                }
            }
            if (savedCount > 0) {
                plugin.getLogger().info("Saved " + savedCount + " shulkers for " + player.getName());
            }
        }
    }

    public String getShulkerIdFromItem(ItemStack item) {
        if (!isShulkerBox(item)) return null;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc.get(shulkerIdKey, PersistentDataType.STRING);
    }

    public void regenerateShulkerId(ItemStack shulkerItem, String originalId) {
        regenerateShulkerId(shulkerItem, originalId, null);
    }

    public void regenerateShulkerId(ItemStack shulkerItem, String originalId, UUID newOwner) {
        if (!isShulkerBox(shulkerItem)) {
            return;
        }

        ItemStack[] originalContents = getShulkerContents(originalId);
        String newId = UUID.randomUUID().toString();

        ItemMeta meta = shulkerItem.getItemMeta();
        if (meta != null) {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(shulkerIdKey, PersistentDataType.STRING, newId);
            shulkerItem.setItemMeta(meta);
        }

        if (originalContents != null) {
            updateShulkerNBT(shulkerItem, originalContents);
            database.saveShulker(newId, originalContents);

            if (newOwner != null) {
                Map<String, ItemStack[]> cache = playerCache.get(newOwner);
                if (cache != null) {
                    cache.put(newId, cloneContents(originalContents));
                    playerShulkerIds.computeIfAbsent(newOwner, k -> ConcurrentHashMap.newKeySet()).add(newId);
                }
            }
        }

        if (newOwner != null) {
            setShulkerOwnership(newId, newOwner);
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

            plugin.getLogger().info("Shulker regenerated and persisted: " + oldId + " -> " + shulkerId);
        }

        if (!tryLockShulker(shulkerId, playerId)) {
            plugin.getLogger().warning("Blocked access to in-use shulker: " + shulkerId + " by " + player.getName());
            player.sendMessage(Component.text("This shulker is already in use!", NamedTextColor.RED));
            return;
        }

        loadingPlayers.add(playerId);
        loadingTimestamps.put(playerId, System.currentTimeMillis());
        ItemStack shulkerClone = shulkerBox.clone();

        Map<String, ItemStack[]> cache = playerCache.get(playerId);
        if (cache != null) {
            ItemStack[] cached = cache.get(shulkerId);
            if (cached != null) {
                String finalShulkerId = shulkerId;
                Bukkit.getScheduler().runTask(plugin, () ->
                        finalizeOpen(player, finalShulkerId, shulkerClone, cloneContents(cached))
                );
                return;
            }
        }

        String finalShulkerId = shulkerId;
        database.loadShulker(shulkerId).thenAccept(contents -> {
            if (!player.isOnline()) {
                plugin.getLogger().info("Player " + player.getName() + " went offline during shulker load");
                loadingPlayers.remove(playerId);
                loadingTimestamps.remove(playerId);
                releaseLock(finalShulkerId);
                return;
            }

            ItemStack[] finalContents;

            if (contents != null) {
                plugin.getLogger().fine("Loaded shulker " + finalShulkerId + " from database with " +
                    Arrays.stream(contents).filter(Objects::nonNull).count() + " items");

                if (cache != null) {
                    cache.put(finalShulkerId, contents);
                    playerShulkerIds.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet()).add(finalShulkerId);
                }
                finalContents = cloneContents(contents);
            } else {
                plugin.getLogger().fine("No database entry for " + finalShulkerId + ", extracting from NBT");
                ItemStack[] nbtContents = extractFromNBT(shulkerClone);

                if (nbtContents != null) {
                    if (cache != null) {
                        cache.put(finalShulkerId, cloneContents(nbtContents));
                        playerShulkerIds.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet()).add(finalShulkerId);
                    }
                    database.saveShulker(finalShulkerId, nbtContents);
                    plugin.getLogger().fine("Saved NBT contents to database for " + finalShulkerId);
                } else {
                    plugin.getLogger().fine("No NBT contents found for " + finalShulkerId);
                }

                finalContents = nbtContents;
            }

            Bukkit.getScheduler().runTask(plugin, () ->
                    finalizeOpen(player, finalShulkerId, shulkerClone, finalContents)
            );
        }).exceptionally(ex -> {
            plugin.getLogger().severe("CRITICAL: Failed to load shulker " + finalShulkerId +
                " for " + player.getName() + ": " + ex.getMessage());
            ex.printStackTrace();

            loadingPlayers.remove(playerId);
            loadingTimestamps.remove(playerId);
            releaseLock(finalShulkerId);

            Bukkit.getScheduler().runTask(plugin, () ->
                player.sendMessage(Component.text("Error loading shulker! Please contact an administrator.",
                    NamedTextColor.RED))
            );

            return null;
        });
    }

    private void finalizeOpen(Player player, String shulkerId, ItemStack shulkerBox, ItemStack[] contents) {
        UUID playerId = player.getUniqueId();

        loadingPlayers.remove(playerId);
        loadingTimestamps.remove(playerId);

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

        plugin.getLogger().fine("Opened shulker " + shulkerId + " for " + player.getName());
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

    public void closeShulker(Player player, boolean save) {
        closeShulker(player, save, false);
    }

    public void closeShulker(Player player, boolean save, boolean synchronous) {
        UUID playerId = player.getUniqueId();

        ShulkerSession session = activeSessions.remove(playerId);
        openedShulkerItems.remove(playerId);
        loadingPlayers.remove(playerId);
        loadingTimestamps.remove(playerId);

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

            try {
                if (synchronous) {
                    database.saveShulkerSync(session.shulkerId, contents);
                } else {
                    database.saveShulker(session.shulkerId, contents);
                }
                plugin.getLogger().fine("Saved shulker " + session.shulkerId + " for " + player.getName());
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to save shulker " + session.shulkerId +
                    " for " + player.getName() + ": " + e.getMessage());
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
            updateShulkerNBT(item, contents);
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

        Map<String, ItemStack[]> cache = playerCache.get(playerId);
        if (cache != null) {
            cache.put(session.shulkerId, cloneContents(contents));
        }

        updateShulkerNBTForPlayer(player, session.shulkerId, contents);

        try {
            database.saveShulkerSync(session.shulkerId, contents);
            plugin.getLogger().fine("Saved shulker " + session.shulkerId + " (damage close) for " + player.getName());
        } catch (Exception e) {
            plugin.getLogger().severe("CRITICAL: Failed to save shulker on damage close: " + e.getMessage());
        }

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

        try {
            database.saveShulkerSync(shulkerId, contents);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to update shulker data: " + e.getMessage());
        }
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

        int savedShulkers = 0;
        for (Map.Entry<UUID, Map<String, ItemStack[]>> entry : playerCache.entrySet()) {
            for (Map.Entry<String, ItemStack[]> shulker : entry.getValue().entrySet()) {
                try {
                    database.saveShulkerSync(shulker.getKey(), shulker.getValue());
                    savedShulkers++;
                } catch (Exception e) {
                    plugin.getLogger().severe("Failed to save shulker during shutdown: " + e.getMessage());
                }
            }
        }
        if (savedShulkers > 0) {
            plugin.getLogger().info("Saved " + savedShulkers + " cached shulkers");
        }

        shulkerLocks.clear();
        lockedShulkerIds.clear();
        placedShulkerLocations.clear();
        playerCache.clear();
        playerShulkerIds.clear();
        activeSessions.clear();
        loadingPlayers.clear();
        loadingTimestamps.clear();
        openedShulkerItems.clear();
        shulkerOwnership.clear();

        database.close();

        plugin.getLogger().info("VirtualShulkerManager shutdown complete");
    }

    public void clearCache() {
        shulkerLocks.clear();
        lockedShulkerIds.clear();
        playerCache.clear();
        playerShulkerIds.clear();
        activeSessions.clear();
        loadingPlayers.clear();
        loadingTimestamps.clear();
        openedShulkerItems.clear();
        shulkerOwnership.clear();
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

    public Map<String, UUID> getActiveLocks() {
        return new HashMap<>(shulkerLocks);
    }

    public Set<String> getLockedIds() {
        return new HashSet<>(lockedShulkerIds);
    }

    public Map<UUID, Long> getLoadingTimestamps() {
        return new HashMap<>(loadingTimestamps);
    }

    private record ShulkerSession(Inventory inventory, String shulkerId, long openedAt) {}
}