package com.github.mathsanalysis.vshulker.command;

import com.github.mathsanalysis.vshulker.VirtualShulkerPlugin;
import com.github.mathsanalysis.vshulker.config.Config;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Command("virtualshulker")
public record ShulkerCommand(VirtualShulkerPlugin plugin) {

    @Subcommand("reload")
    @CommandPermission("virtualshulker.admin")
    public void reload(CommandSender sender) {
        plugin.reload();
        sender.sendMessage(Config.getMessageReload());
    }

    @Subcommand("debug")
    @CommandPermission("virtualshulker.admin")
    public void debug(CommandSender sender, @Optional Player target) {
        if (target == null && sender instanceof Player) {
            target = (Player) sender;
        }

        if (target == null) {
            sender.sendMessage(Component.text("Usage: /virtualshulker debug <player>", NamedTextColor.RED));
            return;
        }

        UUID playerId = target.getUniqueId();
        var manager = plugin.getManager();

        sender.sendMessage(Component.text("=== DEBUG INFO for " + target.getName() + " ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Has open shulker: " + manager.hasOpenShulker(target), NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("Is loading: " + manager.isLoading(target), NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("Open shulker ID: " + manager.getOpenShulkerId(target), NamedTextColor.YELLOW));

        Map<UUID, Long> loadingTimestamps = manager.getLoadingTimestamps();
        if (loadingTimestamps.containsKey(playerId)) {
            long loadingTime = loadingTimestamps.get(playerId);
            long elapsedMs = System.currentTimeMillis() - loadingTime;
            sender.sendMessage(Component.text("Loading since: " + elapsedMs + "ms ago",
                    elapsedMs > 5000 ? NamedTextColor.RED : NamedTextColor.YELLOW));
        }

        Map<String, UUID> activeLocks = manager.getActiveLocks();
        Set<String> lockedIds = manager.getLockedIds();

        sender.sendMessage(Component.text("=== GLOBAL LOCK STATUS ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Active permanent locks: " + activeLocks.size(), NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("Temporary locks: " + lockedIds.size(), NamedTextColor.YELLOW));

        int playerLocks = 0;
        for (Map.Entry<String, UUID> entry : activeLocks.entrySet()) {
            if (entry.getValue().equals(playerId)) {
                playerLocks++;
                sender.sendMessage(Component.text("  - " + entry.getKey().substring(0, 8) + "...", NamedTextColor.GRAY));
            }
        }
        sender.sendMessage(Component.text("Locks held by player: " + playerLocks, NamedTextColor.YELLOW));

        sender.sendMessage(Component.text("=== END DEBUG INFO ===", NamedTextColor.GOLD));
    }

    @Subcommand("pending-saves")
    @CommandPermission("virtualshulker.admin")
    public void pendingSaves(CommandSender sender) {
        var manager = plugin.getManager();
        Map<String, ItemStack[]> pendingSaves = manager.getPendingSaves();

        sender.sendMessage(Component.text("=== PENDING SAVES STATUS ===", NamedTextColor.GOLD));

        if (pendingSaves.isEmpty()) {
            sender.sendMessage(Component.text("✓ No pending saves (all good!)", NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("⚠ Found " + pendingSaves.size() + " pending saves:", NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("These shulkers were modified but items weren't found in inventories", NamedTextColor.GRAY));
            sender.sendMessage(Component.text("Contents are saved and will be restored when opened again", NamedTextColor.GRAY));
            sender.sendMessage(Component.empty());

            int displayed = 0;
            for (Map.Entry<String, ItemStack[]> entry : pendingSaves.entrySet()) {
                if (displayed >= 10) {
                    int remaining = pendingSaves.size() - displayed;
                    sender.sendMessage(Component.text("... and " + remaining + " more", NamedTextColor.GRAY));
                    break;
                }

                String shulkerId = entry.getKey();
                ItemStack[] contents = entry.getValue();
                int itemCount = 0;
                for (ItemStack item : contents) {
                    if (item != null && !item.getType().isAir()) {
                        itemCount++;
                    }
                }

                sender.sendMessage(Component.text(
                        "  • " + shulkerId.substring(0, 8) + "... (" + itemCount + " items)",
                        NamedTextColor.YELLOW
                ));
                displayed++;
            }

            sender.sendMessage(Component.empty());
            sender.sendMessage(Component.text("NOTE: If you see many pending saves, investigate:", NamedTextColor.RED));
            sender.sendMessage(Component.text("  - Check server lag/TPS", NamedTextColor.GRAY));
            sender.sendMessage(Component.text("  - Check for plugin conflicts (PlayerVaults, etc)", NamedTextColor.GRAY));
            sender.sendMessage(Component.text("  - Check logs for errors", NamedTextColor.GRAY));
        }

        sender.sendMessage(Component.text("=== END PENDING SAVES ===", NamedTextColor.GOLD));
    }

    @Subcommand("cleanup")
    @CommandPermission("virtualshulker.admin")
    public void cleanup(CommandSender sender, @Optional Player target) {
        if (target == null && sender instanceof Player) {
            target = (Player) sender;
        }

        if (target == null) {
            sender.sendMessage(Component.text("Usage: /virtualshulker cleanup <player>", NamedTextColor.RED));
            return;
        }

        sender.sendMessage(Component.text("Force cleaning state for " + target.getName() + "...", NamedTextColor.YELLOW));
        plugin.getManager().forceCleanupPlayer(target);
        sender.sendMessage(Component.text("State cleanup completed!", NamedTextColor.GREEN));
    }

    @Subcommand("stats")
    @CommandPermission("virtualshulker.admin")
    public void stats(CommandSender sender) {
        var manager = plugin.getManager();
        var database = manager.getDatabase();

        sender.sendMessage(Component.text("=== VIRTUALSHULKER STATISTICS ===", NamedTextColor.GOLD));

        int onlinePlayersWithCache = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (manager.hasOpenShulker(player) || manager.isLoading(player)) {
                onlinePlayersWithCache++;
            }
        }

        sender.sendMessage(Component.text("Online players: " + Bukkit.getOnlinePlayers().size(), NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("Players with active sessions: " + onlinePlayersWithCache, NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("Active locks: " + manager.getActiveLocks().size(), NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("Temporary locks: " + manager.getLockedIds().size(), NamedTextColor.YELLOW));

        int pendingSavesCount = manager.getPendingSaves().size();
        if (pendingSavesCount > 0) {
            sender.sendMessage(Component.text("⚠ Pending saves: " + pendingSavesCount, NamedTextColor.RED));
        } else {
            sender.sendMessage(Component.text("✓ Pending saves: 0", NamedTextColor.GREEN));
        }

        sender.sendMessage(Component.text("Database connected: " + (database != null && database.isConnected()), NamedTextColor.YELLOW));

        sender.sendMessage(Component.text("=== END STATISTICS ===", NamedTextColor.GOLD));
    }
}