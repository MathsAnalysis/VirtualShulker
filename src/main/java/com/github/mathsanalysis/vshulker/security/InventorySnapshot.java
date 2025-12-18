package com.github.mathsanalysis.vshulker.security;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class InventorySnapshot {

    private final Map<UUID, SnapshotData> playerSnapshots;
    private final Map<UUID, List<String>> inventoryHistory;
    
    private static final int MAX_HISTORY = 10;

    public InventorySnapshot() {
        this.playerSnapshots = new ConcurrentHashMap<>();
        this.inventoryHistory = new ConcurrentHashMap<>();
    }

    public void createSnapshot(Player player, ItemStack[] shulkerContents) {
        UUID playerId = player.getUniqueId();

        ItemStack[] playerInv = player.getInventory().getContents();
        ItemStack[] enderChest = player.getEnderChest().getContents();
        
        String playerInvHash = calculateHash(playerInv);
        String shulkerHash = calculateHash(shulkerContents);
        String enderChestHash = calculateHash(enderChest);
        String combinedHash = calculateCombinedHash(playerInvHash, shulkerHash, enderChestHash);
        
        SnapshotData snapshot = new SnapshotData(
                System.currentTimeMillis(),
                playerInvHash,
                shulkerHash,
                enderChestHash,
                combinedHash,
                deepClone(playerInv),
                deepClone(shulkerContents),
                deepClone(enderChest)
        );
        
        playerSnapshots.put(playerId, snapshot);

        List<String> history = inventoryHistory.computeIfAbsent(playerId, k -> new ArrayList<>());
        history.add(combinedHash);

        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
    }

    public ValidationResult validateAgainstSnapshot(Player player, ItemStack[] currentShulkerContents) {
        UUID playerId = player.getUniqueId();
        SnapshotData snapshot = playerSnapshots.get(playerId);
        
        if (snapshot == null) {
            return ValidationResult.valid();
        }

        ItemStack[] currentPlayerInv = player.getInventory().getContents();
        ItemStack[] currentEnderChest = player.getEnderChest().getContents();
        
        String currentPlayerHash = calculateHash(currentPlayerInv);
        String currentShulkerHash = calculateHash(currentShulkerContents);
        String currentEnderHash = calculateHash(currentEnderChest);

        if (currentPlayerHash.equals(snapshot.playerInventoryHash)) {

            if (!currentShulkerHash.equals(snapshot.shulkerContentsHash)) {
                return ValidationResult.invalid("Inventory rollback detected: Player inventory restored while shulker modified");
            }
        }

        ItemCount snapshotCount = countAllItems(snapshot.playerInventory, snapshot.shulkerContents, snapshot.enderChestContents);
        ItemCount currentCount = countAllItems(currentPlayerInv, currentShulkerContents, currentEnderChest);
        
        if (currentCount.totalItems > snapshotCount.totalItems) {
            int diff = currentCount.totalItems - snapshotCount.totalItems;
            return ValidationResult.invalid("Item duplication detected: " + diff + " items added from nowhere");
        }

        List<String> history = inventoryHistory.get(playerId);
        if (history != null) {
            String currentCombinedHash = calculateCombinedHash(currentPlayerHash, currentShulkerHash, currentEnderHash);

            long occurrences = 0;
            for (var h : history) {
                if (h.equals(currentCombinedHash)) {
                    occurrences++;
                }
            }
            
            if (occurrences > 2) {
                return ValidationResult.invalid("Suspicious pattern: Identical state repeated " + occurrences + " times");
            }
        }

        int restoredSlots = 0;
        for (int i = 0; i < currentPlayerInv.length && i < snapshot.playerInventory.length; i++) {
            if (areItemsIdentical(currentPlayerInv[i], snapshot.playerInventory[i])) {
                restoredSlots++;
            }
        }

        float restoredPercentage = (float) restoredSlots / currentPlayerInv.length;
        if (restoredPercentage > 0.8f && !currentShulkerHash.equals(snapshot.shulkerContentsHash)) {
            return ValidationResult.invalid("Partial inventory rollback detected: " + 
                    String.format("%.0f%%", restoredPercentage * 100) + " slots restored");
        }

        return ValidationResult.valid();
    }

    public ValidationResult detectImpossibleModifications(Player player, ItemStack[] shulkerBefore, ItemStack[] shulkerAfter) {
        UUID playerId = player.getUniqueId();
        SnapshotData snapshot = playerSnapshots.get(playerId);
        
        if (snapshot == null) {
            return ValidationResult.valid();
        }

        ItemStack[] currentPlayerInv = player.getInventory().getContents();

        ItemCount beforeTotal = countAllItems(snapshot.playerInventory, shulkerBefore, snapshot.enderChestContents);
        ItemCount afterTotal = countAllItems(currentPlayerInv, shulkerAfter, player.getEnderChest().getContents());

        if (afterTotal.totalItems > beforeTotal.totalItems) {
            return ValidationResult.invalid("Impossible modification: Items increased during session");
        }

        Map<String, Integer> beforeItems = countItemTypes(snapshot.playerInventory, shulkerBefore);
        Map<String, Integer> afterItems = countItemTypes(currentPlayerInv, shulkerAfter);
        
        for (Map.Entry<String, Integer> entry : afterItems.entrySet()) {
            String itemType = entry.getKey();
            int afterCount = entry.getValue();
            int beforeCount = beforeItems.getOrDefault(itemType, 0);
            
            if (afterCount > beforeCount) {
                return ValidationResult.invalid("Item type increased: " + itemType + " (before: " + beforeCount + ", after: " + afterCount + ")");
            }
        }

        return ValidationResult.valid();
    }

    public void clearSnapshot(UUID playerId) {
        playerSnapshots.remove(playerId);
        inventoryHistory.remove(playerId);
    }

    public long getSnapshotAge(UUID playerId) {
        SnapshotData snapshot = playerSnapshots.get(playerId);
        return snapshot != null ? System.currentTimeMillis() - snapshot.timestamp : -1;
    }

    private String calculateHash(ItemStack[] items) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            
            for (ItemStack item : items) {
                if (item == null) {
                    digest.update((byte) 0);
                } else {
                    digest.update(item.getType().name().getBytes());
                    digest.update((byte) item.getAmount());
                    
                    if (item.hasItemMeta()) {
                        digest.update(item.getItemMeta().toString().getBytes());
                    }
                }
            }
            
            byte[] hash = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            
            return hexString.toString();
            
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String calculateCombinedHash(String... hashes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            
            for (String hash : hashes) {
                digest.update(hash.getBytes());
            }
            
            byte[] combined = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : combined) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            
            return hexString.toString();
            
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private ItemCount countAllItems(ItemStack[] playerInv, ItemStack[] shulkerInv, ItemStack[] enderChest) {
        int total = 0;
        
        for (ItemStack item : playerInv) {
            if (item != null) total += item.getAmount();
        }
        
        for (ItemStack item : shulkerInv) {
            if (item != null) total += item.getAmount();
        }
        
        for (ItemStack item : enderChest) {
            if (item != null) total += item.getAmount();
        }
        
        return new ItemCount(total);
    }

    private Map<String, Integer> countItemTypes(ItemStack[] inv1, ItemStack[] inv2) {
        Map<String, Integer> counts = new HashMap<>();
        
        for (ItemStack item : inv1) {
            if (item != null) {
                String key = item.getType().name();
                counts.put(key, counts.getOrDefault(key, 0) + item.getAmount());
            }
        }
        
        for (ItemStack item : inv2) {
            if (item != null) {
                String key = item.getType().name();
                counts.put(key, counts.getOrDefault(key, 0) + item.getAmount());
            }
        }
        
        return counts;
    }

    private boolean areItemsIdentical(ItemStack item1, ItemStack item2) {
        if (item1 == null && item2 == null) return true;
        if (item1 == null || item2 == null) return false;
        
        if (item1.getType() != item2.getType()) return false;
        if (item1.getAmount() != item2.getAmount()) return false;
        
        if (item1.hasItemMeta() != item2.hasItemMeta()) return false;
        
        if (item1.hasItemMeta() && item2.hasItemMeta()) {
            return item1.getItemMeta().equals(item2.getItemMeta());
        }
        
        return true;
    }

    private ItemStack[] deepClone(ItemStack[] items) {
        if (items == null) return null;
        
        ItemStack[] cloned = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            cloned[i] = items[i] != null ? items[i].clone() : null;
        }
        return cloned;
    }

    private record SnapshotData(
            long timestamp,
            String playerInventoryHash,
            String shulkerContentsHash,
            String enderChestHash,
            String combinedHash,
            ItemStack[] playerInventory,
            ItemStack[] shulkerContents,
            ItemStack[] enderChestContents
    ) {}

    private record ItemCount(int totalItems) {}
}