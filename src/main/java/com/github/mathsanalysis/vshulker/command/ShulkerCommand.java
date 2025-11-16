package com.github.mathsanalysis.vshulker.command;

import com.github.mathsanalysis.vshulker.VirtualShulkerPlugin;
import com.github.mathsanalysis.vshulker.config.Config;
import com.github.mathsanalysis.vshulker.utils.MessageUtil;
import org.bukkit.command.CommandSender;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.util.Locale;

@Command("virtualshulker")
public record ShulkerCommand(VirtualShulkerPlugin plugin) {

    @Subcommand("reload")
    @CommandPermission("virtualshulker.admin.reload")
    public void reload(CommandSender sender) {
        plugin.reload();
        sender.sendMessage(Config.getMessageReload());
    }
}
