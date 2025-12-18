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
@CommandPermission("virtualshulker.command.use")
public record ShulkerCommand(VirtualShulkerPlugin plugin) {

    @Subcommand("reload")
    @CommandPermission("virtualshulker.command.reload")
    public void reload(CommandSender sender) {
        plugin.reload();
        sender.sendMessage(Config.getMessageReload());
    }

    @Subcommand("debug")
    @CommandPermission("virtualshulker.command.debug")
    public void debug(CommandSender sender, @Optional Player target) {
        if (target == null && sender instanceof Player) {
            target = (Player) sender;
        }

        if (target == null) {
            sender.sendMessage(Component.text("Usage: /virtualshulker debug <player>", NamedTextColor.RED));
            return;
        }

        var manager = plugin.getManager();

        sender.sendMessage(Component.text("╔═══════════════════════════════════════╗", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("║  DEBUG INFO: " + target.getName(), NamedTextColor.GOLD));
        sender.sendMessage(Component.text("╠═══════════════════════════════════════╣", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  Shulker open: " + manager.hasOpenShulker(target), NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  Loading: " + manager.isLoading(target), NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("╚═══════════════════════════════════════╝", NamedTextColor.GOLD));
    }

    @Subcommand("cleanup")
    @CommandPermission("virtualshulker.command.cleanup")
    public void cleanup(CommandSender sender, @Optional Player target) {
        if (target == null && sender instanceof Player) {
            target = (Player) sender;
        }

        if (target == null) {
            sender.sendMessage(Component.text("Usage: /virtualshulker cleanup <player>", NamedTextColor.RED));
            return;
        }

        sender.sendMessage(Component.text("⚠ Cleaning state for " + target.getName() + "...", NamedTextColor.YELLOW));
        plugin.getManager().forceCleanupPlayer(target);
        sender.sendMessage(Component.text("✓ Cleanup completed!", NamedTextColor.GREEN));
    }

    @Subcommand("stats")
    @CommandPermission("virtualshulker.command.stats")
    public void stats(CommandSender sender) {
        var manager = plugin.getManager();

        int playersWithSessions = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (manager.hasOpenShulker(player) || manager.isLoading(player)) {
                playersWithSessions++;
            }
        }

        sender.sendMessage(Component.text("╔═══════════════════════════════════════╗", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("║    VIRTUALSHULKER STATISTICS         ║", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("╠═══════════════════════════════════════╣", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  Online players: " + Bukkit.getOnlinePlayers().size(), NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  Active sessions: " + playersWithSessions, NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  System: NBT-ONLY (Direct Save)", NamedTextColor.GREEN));
        sender.sendMessage(Component.text("  Database: NONE", NamedTextColor.GREEN));
        sender.sendMessage(Component.text("  Cache: NONE", NamedTextColor.GREEN));
        sender.sendMessage(Component.text("╚═══════════════════════════════════════╝", NamedTextColor.GOLD));
    }

    @Subcommand("help")
    @CommandPermission("virtualshulker.command.help")
    public void help(CommandSender sender) {
        sender.sendMessage(Component.text("╔═══════════════════════════════════════╗", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("║      VIRTUALSHULKER COMMANDS         ║", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("╠═══════════════════════════════════════╣", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  /vs reload", NamedTextColor.YELLOW)
                .append(Component.text(" - Reload the plugin", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /vs debug [player]", NamedTextColor.YELLOW)
                .append(Component.text(" - Debug information", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /vs cleanup [player]", NamedTextColor.YELLOW)
                .append(Component.text(" - Force cleanup", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /vs stats", NamedTextColor.YELLOW)
                .append(Component.text(" - System statistics", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /vs help", NamedTextColor.YELLOW)
                .append(Component.text(" - Show this menu", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("╠═══════════════════════════════════════╣", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  System: DIRECT NBT Save", NamedTextColor.GREEN));
        sender.sendMessage(Component.text("╚═══════════════════════════════════════╝", NamedTextColor.GOLD));
    }
}