package com.github.mathsanalysis.vshulker.manager;

import com.github.mathsanalysis.vshulker.VirtualShulkerPlugin;
import com.github.mathsanalysis.vshulker.config.Config;
import com.github.mathsanalysis.vshulker.security.NBTValidator;
import com.github.mathsanalysis.vshulker.security.TransactionTracker;
import com.github.mathsanalysis.vshulker.security.InventorySnapshot;
import com.github.mathsanalysis.vshulker.security.ValidationResult;
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
    private final TransactionTracker transactionTracker;
    private final InventorySnapshot inventorySnapshot;
    private final Map<UUID, Long> lastOpenTime;
    private final Map<UUID, Integer> autoSaveScheduled;

    private static final long OPEN_COOLDOWN_MS = 200;
    private static final int AUTO_SAVE_DELAY_TICKS = 2;

    public VirtualShulkerManager(VirtualShulkerPlugin plugin) {
        this.plugin = plugin;
        this.activeSessions = new ConcurrentHashMap<>();
        this.loadingPlayers = ConcurrentHashMap.newKeySet();
        this.placedShulkerLocations = ConcurrentHashMap.newKeySet();
        this.transactionTracker = new TransactionTracker();
        this.inventorySnapshot = new InventorySnapshot();
        this.lastOpenTime = new ConcurrentHashMap<>();
        this.autoSaveScheduled = new ConcurrentHashMap<>();
    }

    public static VirtualShulkerManager getInstance(VirtualShulkerPlugin plugin) {
        if (instance == null) {
            instance = new VirtualShulkerManager(plugin);
        }
        return instance;
    }

    public void initialize() {
        plugin.getLogger().info("═══════════════════════════════════════════════");
        plugin.getLogger().info("VirtualShulkerManager initialized");
        plugin.getLogger().info("Mode: MAXIMUM SECURITY");
        plugin.getLogger().info("Features:");
        plugin.getLogger().info("  ✓ Transaction Tracking");
        plugin.getLogger().info("  ✓ NBT Validation");
        plugin.getLogger().info("  ✓ Rate Limiting");
        plugin.getLogger().info("  ✓ Packet Desync Prevention");
        plugin.getLogger().info("  ✓ Continuous Validation");
        plugin.getLogger().info("  ✓ Multi-point Saving");
        plugin.getLogger().info("═══════════════════════════════════════════════");
    }

    public void openShulker(Player player, ItemStack shulkerBox) {
        if (!isShulkerBox(shulkerBox)) {
            return;
        }

        UUID playerId = player.getUniqueId();

        if (!checkOpenCooldown(playerId)) {
            player.sendMessage(Component.text("Please wait before opening another shulker!", NamedTextColor.RED));
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

        ValidationResult validation = NBTValidator.validate(shulkerBox);
        if (!validation.isValid()) {
            plugin.getLogger().severe("═══════════════════════════════════════════════");
            plugin.getLogger().severe("NBT VALIDATION FAILED");
            plugin.getLogger().severe("Player: " + player.getName());
            plugin.getLogger().severe("Reason: " + validation.getReason());
            plugin.getLogger().severe("ACTION: Open blocked, shulker sanitized");
            plugin.getLogger().severe("═══════════════════════════════════════════════");

            player.sendMessage(Component.text("This shulker contains invalid data!", NamedTextColor.RED));
            player.sendMessage(Component.text("Reason: " + validation.getReason(), NamedTextColor.GRAY));

            notifyAdmins(player, "Invalid NBT: " + validation.getReason());
            return;
        }

        loadingPlayers.add(playerId);
        lastOpenTime.put(playerId, System.currentTimeMillis());

        try {
            ShulkerSlot slot = findShulkerSlot(player, shulkerBox);

            if (slot == null) {
                plugin.getLogger().warning("Could not find shulker in player inventory: " + player.getName());
                player.sendMessage(Component.text("Error: Could not locate shulker", NamedTextColor.RED));
                return;
            }

            ItemStack[] contents = getContentsFromNBT(shulkerBox);
            
            ValidationResult contentsValidation = NBTValidator.validateInventory(contents);
            if (!contentsValidation.isValid()) {
                plugin.getLogger().severe("Shulker contents validation failed: " + contentsValidation.getReason());
                player.sendMessage(Component.text("Shulker contains invalid items!", NamedTextColor.RED));
                return;
            }

            Inventory inventory = createInventory(contents);

            transactionTracker.createCheckpoint(player, contents);

            inventorySnapshot.createSnapshot(player, contents);

            ShulkerSession session = new ShulkerSession(
                    inventory,
                    slot,
                    shulkerBox.clone(),
                    System.currentTimeMillis()
            );
            activeSessions.put(playerId, session);

            transactionTracker.recordTransaction(player, TransactionTracker.TransactionType.OPEN, -1, shulkerBox);

            player.openInventory(inventory);

            plugin.getLogger().fine("Opened shulker for " + player.getName() + " from slot: " + slot);

        } finally {
            loadingPlayers.remove(playerId);
        }
    }

    public void closeShulker(Player player, boolean save) {
        closeShulker(player, save, false);
    }

    public void closeShulker(Player player, boolean save, boolean scheduled) {
        UUID playerId = player.getUniqueId();

        ShulkerSession session = activeSessions.remove(playerId);
        loadingPlayers.remove(playerId);

        Integer taskId = autoSaveScheduled.remove(playerId);
        if (taskId != null) {
            Bukkit.getScheduler().cancelTask(taskId);
        }

        if (session == null) {
            return;
        }

        transactionTracker.recordTransaction(player, TransactionTracker.TransactionType.CLOSE, -1, null);

        if (save) {
            if (!scheduled) {
                Bukkit.getScheduler().runTask(plugin, () -> performSave(player, session));
                return;
            }

            performSave(player, session);
        } else {
            plugin.getLogger().info("Shulker closed without save for " + player.getName());
        }
    }

    private void performSave(Player player, ShulkerSession session) {
        UUID playerId = player.getUniqueId();

        if (transactionTracker.detectSuspiciousActivity(player)) {
            plugin.getLogger().severe("═══════════════════════════════════════════════");
            plugin.getLogger().severe("SUSPICIOUS ACTIVITY DETECTED");
            plugin.getLogger().severe("Player: " + player.getName());
            plugin.getLogger().severe("ACTION: Save blocked");
            plugin.getLogger().severe("═══════════════════════════════════════════════");

            notifyAdmins(player, "Suspicious transaction pattern detected");
            player.sendMessage(Component.text("ANTI-DUPE: Unusual activity detected!", NamedTextColor.DARK_RED));
            player.sendMessage(Component.text("Changes NOT saved!", NamedTextColor.GOLD));
            return;
        }

        ItemStack currentShulker = getCurrentShulkerInSlot(player, session.slot);

        if (currentShulker == null || !isShulkerBox(currentShulker)) {
            String reason = currentShulker == null ? "Shulker disappeared" : "Not a shulker box";

            plugin.getLogger().severe("═══════════════════════════════════════════════");
            plugin.getLogger().severe("ANTI-DUPE: Shulker disappeared/invalid");
            plugin.getLogger().severe("Player: " + player.getName());
            plugin.getLogger().severe("Slot: " + session.slot);
            plugin.getLogger().severe("Session Duration: " + (System.currentTimeMillis() - session.openTimestamp) + "ms");
            plugin.getLogger().severe("ACTION: Blocking save");
            plugin.getLogger().severe("═══════════════════════════════════════════════");

            notifyAdmins(player, reason);

            player.sendMessage(Component.text("Session closed: Shulker was moved!", NamedTextColor.RED));
            player.sendMessage(Component.text("Changes NOT saved!", NamedTextColor.GOLD));
            return;
        }

        if (!isSameShulker(currentShulker, session.originalShulker)) {
            String reason = "Shulker replaced (original: " + session.originalShulker.getType() +
                    ", current: " + currentShulker.getType() + ")";

            plugin.getLogger().severe("═══════════════════════════════════════════════");
            plugin.getLogger().severe("ANTI-DUPE: Shulker was replaced");
            plugin.getLogger().severe("Player: " + player.getName());
            plugin.getLogger().severe("Slot: " + session.slot);
            plugin.getLogger().severe("Original: " + session.originalShulker.getType());
            plugin.getLogger().severe("Current: " + currentShulker.getType());
            plugin.getLogger().severe("ACTION: Blocking save");
            plugin.getLogger().severe("═══════════════════════════════════════════════");

            notifyAdmins(player, reason);

            player.sendMessage(Component.text("Session closed: Shulker was replaced!", NamedTextColor.RED));
            player.sendMessage(Component.text("Changes NOT saved!", NamedTextColor.GOLD));
            return;
        }

        ItemStack[] contents = session.inventory.getContents();

        ValidationResult snapshotValidation = inventorySnapshot.validateAgainstSnapshot(player, contents);
        if (!snapshotValidation.isValid()) {
            plugin.getLogger().severe("═══════════════════════════════════════════════");
            plugin.getLogger().severe("ANTI-DUPE: Inventory manipulation detected");
            plugin.getLogger().severe("Player: " + player.getName());
            plugin.getLogger().severe("Reason: " + snapshotValidation.getReason());
            plugin.getLogger().severe("Detection: UIUtils copy-paste exploit");
            plugin.getLogger().severe("ACTION: Blocking save");
            plugin.getLogger().severe("═══════════════════════════════════════════════");

            notifyAdmins(player, "Copy-paste manipulation: " + snapshotValidation.getReason());
            player.sendMessage(Component.text("ANTI-DUPE: Inventory manipulation detected!", NamedTextColor.DARK_RED));
            player.sendMessage(Component.text("Changes NOT saved!", NamedTextColor.GOLD));
            return;
        }

        ItemStack[] originalContents = transactionTracker.getCheckpoint(player);
        if (originalContents != null) {
            ValidationResult modificationCheck = inventorySnapshot.detectImpossibleModifications(
                    player, originalContents, contents);

            if (!modificationCheck.isValid()) {
                plugin.getLogger().severe("═══════════════════════════════════════════════");
                plugin.getLogger().severe("ANTI-DUPE: Impossible modification detected");
                plugin.getLogger().severe("Player: " + player.getName());
                plugin.getLogger().severe("Reason: " + modificationCheck.getReason());
                plugin.getLogger().severe("ACTION: Blocking save");
                plugin.getLogger().severe("═══════════════════════════════════════════════");

                notifyAdmins(player, "Impossible modification: " + modificationCheck.getReason());
                player.sendMessage(Component.text("ANTI-DUPE: Impossible changes detected!", NamedTextColor.DARK_RED));
                player.sendMessage(Component.text("Changes NOT saved!", NamedTextColor.GOLD));
                return;
            }
        }

        ValidationResult validation = NBTValidator.validateInventory(contents);
        if (!validation.isValid()) {
            plugin.getLogger().severe("═══════════════════════════════════════════════");
            plugin.getLogger().severe("ANTI-DUPE: Invalid contents on save");
            plugin.getLogger().severe("Player: " + player.getName());
            plugin.getLogger().severe("Reason: " + validation.getReason());
            plugin.getLogger().severe("ACTION: Blocking save, rolling back to checkpoint");
            plugin.getLogger().severe("═══════════════════════════════════════════════");

            notifyAdmins(player, "Invalid NBT on save: " + validation.getReason());
            player.sendMessage(Component.text("ANTI-DUPE: Invalid data detected!", NamedTextColor.DARK_RED));
            player.sendMessage(Component.text("Changes NOT saved!", NamedTextColor.GOLD));
            return;
        }

        updateShulkerInSlot(player, session.slot, contents);

        plugin.getLogger().fine("Saved shulker for " + player.getName() + " to slot: " + session.slot);

        transactionTracker.clearPlayer(playerId);
        inventorySnapshot.clearSnapshot(playerId);
    }

    private boolean checkOpenCooldown(UUID playerId) {
        Long lastTime = lastOpenTime.get(playerId);
        if (lastTime == null) {
            return true;
        }

        long timeSinceLastOpen = System.currentTimeMillis() - lastTime;
        return timeSinceLastOpen >= OPEN_COOLDOWN_MS;
    }

    public boolean isOpenedShulker(Player player, ItemStack item) {
        if (!isShulkerBox(item)) {
            return false;
        }

        ShulkerSession session = activeSessions.get(player.getUniqueId());
        if (session == null) {
            return false;
        }

        return isSameShulker(item, session.originalShulker);
    }

    public void scheduleAutoSave(Player player) {
        UUID playerId = player.getUniqueId();

        Integer existingTask = autoSaveScheduled.get(playerId);
        if (existingTask != null) {
            Bukkit.getScheduler().cancelTask(existingTask);
        }

        int taskId = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            performAutoSave(player);
            autoSaveScheduled.remove(playerId);
        }, AUTO_SAVE_DELAY_TICKS).getTaskId();

        autoSaveScheduled.put(playerId, taskId);
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

        ValidationResult snapshotValidation = inventorySnapshot.validateAgainstSnapshot(player, contents);
        if (!snapshotValidation.isValid()) {
            plugin.getLogger().warning("AUTO-SAVE BLOCKED: Inventory manipulation - " + snapshotValidation.getReason());
            return;
        }

        ValidationResult validation = NBTValidator.validateInventory(contents);
        if (!validation.isValid()) {
            plugin.getLogger().warning("AUTO-SAVE BLOCKED: Invalid contents for " + player.getName());
            return;
        }

        updateShulkerInSlot(player, session.slot, contents);
    }

    public boolean recordTransaction(Player player, TransactionTracker.TransactionType type, int slot, ItemStack item) {
        return transactionTracker.recordTransaction(player, type, slot, item);
    }

    public void performImmediateValidation(Player player) {
        UUID playerId = player.getUniqueId();
        ShulkerSession session = activeSessions.get(playerId);

        if (session == null) {
            return;
        }

        ItemStack currentShulker = getCurrentShulkerInSlot(player, session.slot);

        if (currentShulker == null || !isShulkerBox(currentShulker) || !isSameShulker(currentShulker, session.originalShulker)) {
            String reason = currentShulker == null ? "Shulker NULL" :
                    !isShulkerBox(currentShulker) ? "Not a shulker" :
                            "Shulker replaced";

            plugin.getLogger().severe("═══════════════════════════════════════════════");
            plugin.getLogger().severe("IMMEDIATE VALIDATION FAILED");
            plugin.getLogger().severe("Player: " + player.getName());
            plugin.getLogger().severe("Reason: " + reason);
            plugin.getLogger().severe("ACTION: Closing session without save, scheduling close");
            plugin.getLogger().severe("═══════════════════════════════════════════════");

            notifyAdmins(player, reason);

            activeSessions.remove(playerId);

            String finalReason = reason;
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.closeInventory();
                player.sendMessage(Component.text("ANTI-DUPE: Manipulation detected!", NamedTextColor.DARK_RED));
                player.sendMessage(Component.text("Reason: " + finalReason, NamedTextColor.RED));
                player.sendMessage(Component.text("Changes NOT saved!", NamedTextColor.GOLD));
            });
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
                plugin.getLogger().severe("═══════════════════════════════════════════════");
                plugin.getLogger().severe("ANTI-DUPE SYSTEM TRIGGERED");
                plugin.getLogger().severe("═══════════════════════════════════════════════");
                plugin.getLogger().severe("Player: " + player.getName() + " (UUID: " + player.getUniqueId() + ")");
                plugin.getLogger().severe("Reason: " + reason);
                plugin.getLogger().severe("Slot Type: " + session.slot.type);
                plugin.getLogger().severe("Slot Index: " + session.slot.slotIndex);
                plugin.getLogger().severe("Session Age: " + (System.currentTimeMillis() - session.openTimestamp) + "ms");
                plugin.getLogger().severe("Location: " + player.getLocation().getBlockX() + ", " +
                        player.getLocation().getBlockY() + ", " +
                        player.getLocation().getBlockZ());
                plugin.getLogger().severe("World: " + player.getWorld().getName());
                plugin.getLogger().severe("═══════════════════════════════════════════════");
                plugin.getLogger().severe("ACTION: Closing session without save (scheduled)");
                plugin.getLogger().severe("═══════════════════════════════════════════════");

                activeSessions.remove(entry.getKey());

                notifyAdmins(player, reason);

                String finalReason = reason;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.closeInventory();
                    player.sendMessage(Component.text("ANTI-DUPE: Manipulation detected!", NamedTextColor.DARK_RED));
                    player.sendMessage(Component.text("Reason: " + finalReason, NamedTextColor.RED));
                    player.sendMessage(Component.text("Changes NOT saved!", NamedTextColor.GOLD));
                });
            }
        }
    }

    private void notifyAdmins(Player violator, String reason) {
        Component adminMessage = Component.text()
                .append(Component.text("[ANTI-DUPE] ", NamedTextColor.DARK_RED))
                .append(Component.text(violator.getName(), NamedTextColor.RED))
                .append(Component.text(" attempted duplication!", NamedTextColor.YELLOW))
                .append(Component.newline())
                .append(Component.text("Reason: ", NamedTextColor.GRAY))
                .append(Component.text(reason, NamedTextColor.WHITE))
                .build();

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission(Config.PERMISSION_ADMIN)) {
                online.sendMessage(adminMessage);
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

            if (contents != null) {
                ItemStack[] cloned = new ItemStack[contents.length];
                for (int i = 0; i < contents.length; i++) {
                    cloned[i] = contents[i] != null ? contents[i].clone() : null;
                }
                return cloned;
            }

            return new ItemStack[27];

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

            ItemStack[] cloned = new ItemStack[contents.length];
            for (int i = 0; i < contents.length; i++) {
                cloned[i] = contents[i] != null ? contents[i].clone() : null;
            }

            box.getInventory().setContents(cloned);

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
        lastOpenTime.remove(playerId);
        transactionTracker.clearPlayer(playerId);
        inventorySnapshot.clearSnapshot(playerId);

        Integer taskId = autoSaveScheduled.remove(playerId);
        if (taskId != null) {
            Bukkit.getScheduler().cancelTask(taskId);
        }

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
        inventory.setContents(contents);
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
                closeShulker(player, true, true);
                closedSessions++;
            }
        }

        if (closedSessions > 0) {
            plugin.getLogger().info("Closed " + closedSessions + " active sessions");
        }

        for (Integer taskId : autoSaveScheduled.values()) {
            Bukkit.getScheduler().cancelTask(taskId);
        }

        activeSessions.clear();
        loadingPlayers.clear();
        placedShulkerLocations.clear();
        lastOpenTime.clear();
        autoSaveScheduled.clear();

        plugin.getLogger().info("VirtualShulkerManager shutdown complete");
    }

    private record ShulkerSession(
            Inventory inventory,
            ShulkerSlot slot,
            ItemStack originalShulker,
            long openTimestamp
    ) {}

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