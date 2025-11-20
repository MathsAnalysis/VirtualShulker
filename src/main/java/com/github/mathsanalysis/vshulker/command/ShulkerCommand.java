package com.github.mathsanalysis.vshulker.command;

import com.github.mathsanalysis.vshulker.VirtualShulkerPlugin;
import com.github.mathsanalysis.vshulker.config.Config;
import org.bukkit.command.CommandSender;
import revxrsal.commands.annotation.Command;
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
}