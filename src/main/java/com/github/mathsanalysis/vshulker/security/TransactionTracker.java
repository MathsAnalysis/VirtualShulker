package com.github.mathsanalysis.vshulker.security;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class TransactionTracker {

    private final Map<UUID, PlayerTransactionLog> playerLogs;
    private final Map<UUID, Long> lastOperationTime;
    private static final long OPERATION_COOLDOWN_MS = 50;

    public TransactionTracker() {
        this.playerLogs = new ConcurrentHashMap<>();
        this.lastOperationTime = new ConcurrentHashMap<>();
    }

    public boolean recordTransaction(Player player, TransactionType type, int slot, ItemStack item) {
        UUID playerId = player.getUniqueId();

        if (!checkRateLimit(playerId)) {
            return false;
        }

        PlayerTransactionLog log = playerLogs.computeIfAbsent(playerId, k -> new PlayerTransactionLog());
        log.addTransaction(new Transaction(type, slot, item, System.currentTimeMillis()));
        
        lastOperationTime.put(playerId, System.currentTimeMillis());
        return true;
    }

    private boolean checkRateLimit(UUID playerId) {
        Long lastTime = lastOperationTime.get(playerId);
        if (lastTime == null) {
            return true;
        }

        long timeSinceLastOp = System.currentTimeMillis() - lastTime;
        return timeSinceLastOp >= OPERATION_COOLDOWN_MS;
    }

    public boolean detectSuspiciousActivity(Player player) {
        UUID playerId = player.getUniqueId();
        PlayerTransactionLog log = playerLogs.get(playerId);
        
        if (log == null) {
            return false;
        }

        if (log.getRecentTransactionCount(1000) > 50) {
            return true;
        }

        if (log.hasImpossibleSequence()) {
            return true;
        }

        return false;
    }

    public List<Transaction> getHistory(Player player, int limit) {
        PlayerTransactionLog log = playerLogs.get(player.getUniqueId());
        if (log == null) {
            return Collections.emptyList();
        }
        return log.getRecentTransactions(limit);
    }

    public void clearPlayer(UUID playerId) {
        playerLogs.remove(playerId);
        lastOperationTime.remove(playerId);
    }

    public void createCheckpoint(Player player, ItemStack[] contents) {
        UUID playerId = player.getUniqueId();
        PlayerTransactionLog log = playerLogs.computeIfAbsent(playerId, k -> new PlayerTransactionLog());
        log.setCheckpoint(contents);
    }

    public ItemStack[] getCheckpoint(Player player) {
        PlayerTransactionLog log = playerLogs.get(player.getUniqueId());
        return log != null ? log.getCheckpoint() : null;
    }

    private static class PlayerTransactionLog {
        private final Deque<Transaction> transactions = new ArrayDeque<>(100);
        private ItemStack[] checkpoint;

        void addTransaction(Transaction transaction) {
            transactions.addLast(transaction);

            while (transactions.size() > 100) {
                transactions.removeFirst();
            }
        }

        int getRecentTransactionCount(long timeWindowMs) {
            long cutoff = System.currentTimeMillis() - timeWindowMs;
            int count = 0;
            for (Transaction t : transactions) {
                if (t.timestamp() > cutoff) {
                    count++;
                }
            }
            return count;
        }

        boolean hasImpossibleSequence() {
            return false;
        }

        List<Transaction> getRecentTransactions(int limit) {
            List<Transaction> allTransactions = new ArrayList<>(transactions);
            int size = allTransactions.size();
            int start = Math.max(0, size - limit);

            return new ArrayList<>(allTransactions.subList(start, size));
        }

        void setCheckpoint(ItemStack[] contents) {
            this.checkpoint = contents != null ? contents.clone() : null;
        }

        ItemStack[] getCheckpoint() {
            return checkpoint != null ? checkpoint.clone() : null;
        }
    }

    public record Transaction(
            TransactionType type,
            int slot,
            ItemStack item,
            long timestamp
    ) {}

    public enum TransactionType {
        CLICK,
        DRAG,
        SHIFT_CLICK,
        NUMBER_KEY,
        DROP,
        SWAP_OFFHAND,
        DOUBLE_CLICK,
        PICKUP_ALL,
        OPEN,
        CLOSE
    }
}