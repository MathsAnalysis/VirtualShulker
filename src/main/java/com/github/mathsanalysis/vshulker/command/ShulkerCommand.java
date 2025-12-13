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
            sender.sendMessage(Component.text("Utilizzo: /virtualshulker debug <player>", NamedTextColor.RED));
            return;
        }

        var manager = plugin.getManager();

        sender.sendMessage(Component.text("╔═══════════════════════════════════════╗", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("║  DEBUG INFO: " + target.getName(), NamedTextColor.GOLD));
        sender.sendMessage(Component.text("╠═══════════════════════════════════════╣", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  Shulker aperto: " + manager.hasOpenShulker(target), NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  In caricamento: " + manager.isLoading(target), NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("╚═══════════════════════════════════════╝", NamedTextColor.GOLD));
    }

    @Subcommand("cleanup")
    @CommandPermission("virtualshulker.admin")
    public void cleanup(CommandSender sender, @Optional Player target) {
        if (target == null && sender instanceof Player) {
            target = (Player) sender;
        }

        if (target == null) {
            sender.sendMessage(Component.text("Utilizzo: /virtualshulker cleanup <player>", NamedTextColor.RED));
            return;
        }

        sender.sendMessage(Component.text("⚠ Pulizia stato per " + target.getName() + "...", NamedTextColor.YELLOW));
        plugin.getManager().forceCleanupPlayer(target);
        sender.sendMessage(Component.text("✓ Pulizia completata!", NamedTextColor.GREEN));
    }

    @Subcommand("stats")
    @CommandPermission("virtualshulker.admin")
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
        sender.sendMessage(Component.text("  Giocatori online: " + Bukkit.getOnlinePlayers().size(), NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  Sessioni attive: " + playersWithSessions, NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  Sistema: NBT-ONLY (Direct Save)", NamedTextColor.GREEN));
        sender.sendMessage(Component.text("  Database: NONE", NamedTextColor.GREEN));
        sender.sendMessage(Component.text("  Cache: NONE", NamedTextColor.GREEN));
        sender.sendMessage(Component.text("╚═══════════════════════════════════════╝", NamedTextColor.GOLD));
    }

    @Subcommand("help")
    public void help(CommandSender sender) {
        sender.sendMessage(Component.text("╔═══════════════════════════════════════╗", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("║      VIRTUALSHULKER COMMANDS         ║", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("╠═══════════════════════════════════════╣", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  /vs reload", NamedTextColor.YELLOW)
                .append(Component.text(" - Ricarica il plugin", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /vs debug [player]", NamedTextColor.YELLOW)
                .append(Component.text(" - Info debug", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /vs cleanup [player]", NamedTextColor.YELLOW)
                .append(Component.text(" - Pulizia forzata", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /vs stats", NamedTextColor.YELLOW)
                .append(Component.text(" - Statistiche sistema", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /vs help", NamedTextColor.YELLOW)
                .append(Component.text(" - Mostra questo menu", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("╠═══════════════════════════════════════╣", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  Sistema: Salvataggio DIRETTO NBT", NamedTextColor.GREEN));
        sender.sendMessage(Component.text("╚═══════════════════════════════════════╝", NamedTextColor.GOLD));
    }
}