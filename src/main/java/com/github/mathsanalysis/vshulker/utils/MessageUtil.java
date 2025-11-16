package com.github.mathsanalysis.vshulker.utils;

import com.github.mathsanalysis.vshulker.VirtualShulkerPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;

public interface MessageUtil {

    MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    static Component parseMessage(String message) {
        if (message == null || message.isEmpty()) {
            return Component.empty();
        }

        if (message.contains("§")) {
            message = convertLegacyToMiniMessage(message);
        }

        try {
            return MINI_MESSAGE.deserialize(message);
        } catch (Exception e) {
            VirtualShulkerPlugin.getInstance().getLogger().warning("Failed to parse MiniMessage: " + message);
            return Component.text(message);
        }
    }

    private static String convertLegacyToMiniMessage(String message) {
        String converted = message
                .replace("§0", "<black>")
                .replace("§1", "<dark_blue>")
                .replace("§2", "<dark_green>")
                .replace("§3", "<dark_aqua>")
                .replace("§4", "<dark_red>")
                .replace("§5", "<dark_purple>")
                .replace("§6", "<gold>")
                .replace("§7", "<gray>")
                .replace("§8", "<dark_gray>")
                .replace("§9", "<blue>")
                .replace("§a", "<green>")
                .replace("§b", "<aqua>")
                .replace("§c", "<red>")
                .replace("§d", "<light_purple>")
                .replace("§e", "<yellow>")
                .replace("§f", "<white>")

                .replace("§k", "<obfuscated>")
                .replace("§l", "<bold>")
                .replace("§m", "<strikethrough>")
                .replace("§n", "<underline>")
                .replace("§o", "<italic>")
                .replace("§r", "<reset>");

        converted = converted
                .replace("&0", "<black>")
                .replace("&1", "<dark_blue>")
                .replace("&2", "<dark_green>")
                .replace("&3", "<dark_aqua>")
                .replace("&4", "<dark_red>")
                .replace("&5", "<dark_purple>")
                .replace("&6", "<gold>")
                .replace("&7", "<gray>")
                .replace("&8", "<dark_gray>")
                .replace("&9", "<blue>")
                .replace("&a", "<green>")
                .replace("&b", "<aqua>")
                .replace("&c", "<red>")
                .replace("&d", "<light_purple>")
                .replace("&e", "<yellow>")
                .replace("&f", "<white>")

                .replace("&k", "<obfuscated>")
                .replace("&l", "<bold>")
                .replace("&m", "<strikethrough>")
                .replace("&n", "<underline>")
                .replace("&o", "<italic>")
                .replace("&r", "<reset>");

        return converted;
    }

    static String replacePlaceholders(String message, Object... placeholders) {
        String result = message;
        for (int i = 0; i < placeholders.length; i += 2) {
            if (i + 1 < placeholders.length) {
                String key = String.valueOf(placeholders[i]);
                String value = String.valueOf(placeholders[i + 1]);
                result = result.replace(key, value);
            }
        }
        return result;
    }

    static Component parseAndReplace(String message, Object... placeholders) {
        String replaced = replacePlaceholders(message, placeholders);
        return parseMessage(replaced);
    }

    static Component empty() {
        return Component.empty();
    }

    static Component text(String text, NamedTextColor color) {
        return Component.text(text, color);
    }

}