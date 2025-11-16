package com.github.mathsanalysis.vshulker.config;

import com.github.mathsanalysis.vshulker.utils.MessageUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class Config {

    public static String PERMISSION_USE;
    public static String PERMISSION_ADMIN;

    private static int shulkerSize;
    private static String shulkerTitle;
    private static String messageOpened;
    private static String messageClosed;
    private static String messageClosedDamage;
    private static String messageNoPermission;
    private static String messageReload;

    public static void load(JavaPlugin plugin) {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        boolean configExists = new java.io.File(plugin.getDataFolder(), "config.yml").exists();
        plugin.saveDefaultConfig();

        if (!configExists) {
            plugin.getLogger().info("Config file created with default settings.");
            plugin.reloadConfig();
            return;
        }

        FileConfiguration config = plugin.getConfig();

        shulkerSize = config.getInt("shulker.size", 27);
        shulkerTitle = config.getString("shulker.title", "&6Virtual Shulker");

        messageOpened = config.getString("messages.opened", "&aShulker aperto!");
        messageClosed = config.getString("messages.closed", "&cShulker chiuso!");
        messageClosedDamage = config.getString("messages.closed-damage", "&cShulker chiuso per danno!");
        messageNoPermission = config.getString("messages.no-permission", "&cNon hai il permesso!");
        messageReload = config.getString("messages.reload", "&aPlugin ricaricato!");

        PERMISSION_USE = config.getString("permissions.use", "virtualshulker.use");
        PERMISSION_ADMIN = config.getString("permissions.admin", "virtualshulker.admin");
    }

    public static int getShulkerSize() {
        return shulkerSize;
    }

    public static Component getShulkerTitle() {
        return MessageUtil.parseMessage(shulkerTitle);
    }

    public static Component getMessageOpened() {
        return MessageUtil.parseMessage(messageOpened);
    }

    public static Component getMessageClosed() {
        return MessageUtil.parseMessage(messageClosed);
    }

    public static Component getMessageClosedDamage() {
        return MessageUtil.parseMessage(messageClosedDamage);
    }

    public static Component getMessageNoPermission() {
        return MessageUtil.parseMessage(messageNoPermission);
    }

    public static Component getMessageReload() {
        return MessageUtil.parseMessage(messageReload);
    }
}