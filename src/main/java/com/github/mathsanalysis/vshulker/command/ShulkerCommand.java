package com.github.mathsanalysis.vshulker.command;

import com.github.mathsanalysis.vshulker.VirtualShulkerPlugin;
import com.github.mathsanalysis.vshulker.config.Config;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.bukkit.annotation.CommandPermission;

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

        var manager = plugin.getManager();

        sender.sendMessage(Component.text("=== DEBUG INFO for " + target.getName() + " ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Has open shulker: " + manager.hasOpenShulker(target), NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("Is loading: " + manager.isLoading(target), NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("=== END DEBUG INFO ===", NamedTextColor.GOLD));
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

        sender.sendMessage(Component.text("=== VIRTUALSHULKER STATISTICS ===", NamedTextColor.GOLD));

        int playersWithSessions = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (manager.hasOpenShulker(player) || manager.isLoading(player)) {
                playersWithSessions++;
            }
        }

        sender.sendMessage(Component.text("Online players: " + Bukkit.getOnlinePlayers().size(), NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("Players with active sessions: " + playersWithSessions, NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("System: NBT-only (no database, no IDs)", NamedTextColor.GREEN));

        sender.sendMessage(Component.text("=== END STATISTICS ===", NamedTextColor.GOLD));
    }
}