package com.github.mathsanalysis.vshulker.config;

import com.github.mathsanalysis.vshulker.utils.MessageUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.FileWriter;

public final class Config {

    public static String PERMISSION_USE;
    public static String PERMISSION_ADMIN;

    private static int shulkerSize;
    private static String shulkerTitle;
    private static String messageNoPermission;
    private static String messageReload;

    public static void load(JavaPlugin plugin) {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        java.io.File configFile = new java.io.File(plugin.getDataFolder(), "config.yml");

        if (!configFile.exists()) {
            try {
                plugin.saveDefaultConfig();
                plugin.getLogger().info("Config file created from resources.");
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("config.yml not found in resources, creating manually...");
                createDefaultConfig(configFile);
            }
        }

        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        shulkerSize = config.getInt("shulker.size", 27);
        shulkerTitle = config.getString("shulker.title", "<gold><bold>Virtual Shulker");

        messageNoPermission = config.getString("messages.no-permission", "<red>Non hai il permesso per usare questo comando!");
        messageReload = config.getString("messages.reload", "<green>Plugin ricaricato con successo!");

        PERMISSION_USE = config.getString("shulker.use", "");
        PERMISSION_ADMIN = config.getString("shulker.admin", "virtualshulker.admin");

        if (PERMISSION_USE != null && (PERMISSION_USE.equals("*") || PERMISSION_USE.trim().isEmpty())) {
            PERMISSION_USE = null;
            plugin.getLogger().info("Virtual shulker access: EVERYONE (no permission required)");
        } else {
            plugin.getLogger().info("Virtual shulker access: Permission required (" + PERMISSION_USE + ")");
        }

        plugin.getLogger().info("Config caricato: size=" + shulkerSize);
    }

    private static void createDefaultConfig(java.io.File configFile) {
        try {
            FileWriter writer = new FileWriter(configFile);

            writer.write("# Settings file for VirtualShulker\n");
            writer.write("shulker:\n");
            writer.write("  # Dimensione inventario (deve essere multiplo di 9: 9, 18, 27, 36, 45, 54)\n");
            writer.write("  size: 27\n");
            writer.write("  # Titolo dell'inventario (supporta MiniMessage e legacy color codes)\n");
            writer.write("  title: \"<gold><bold>Virtual Shulker\"\n\n");

            writer.write("# Messages Support minimessage and legacy\n");
            writer.write("messages:\n");
            writer.write("  opened: \"<green>Shulker aperto!\"\n");
            writer.write("  closed: \"<red>Shulker chiuso!\"\n");
            writer.write("  closed-damage: \"<red>Shulker chiuso automaticamente per danno subito!\"\n");
            writer.write("  no-permission: \"<red>Non hai il permesso per usare questo comando!\"\n");
            writer.write("  reload: \"<green>Plugin ricaricato con successo!\"\n\n");

            writer.write("# Permissions plugin\n");
            writer.write("permissions:\n");
            writer.write("  # Permesso per usare la shulker virtuale (shift + right-click)\n");
            writer.write("  # Lascia vuoto (\"\") o usa \"*\" per permettere a tutti\n");
            writer.write("  # Esempi:\n");
            writer.write("  #   use: \"\"                        # Tutti possono usare\n");
            writer.write("  #   use: \"*\"                       # Tutti possono usare\n");
            writer.write("  #   use: \"virtualshulker.use\"      # Solo con permesso\n");
            writer.write("  use: \"\"\n");
            writer.write("  # Permesso per comandi admin (sempre richiesto)\n");
            writer.write("  admin: \"virtualshulker.admin\"\n");

            writer.close();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to create default config.yml", e);
        }
    }

    public static int getShulkerSize() {
        return shulkerSize;
    }

    public static Component getShulkerTitle() {
        return MessageUtil.parseMessage(shulkerTitle);
    }

    public static Component getMessageNoPermission() {
        return MessageUtil.parseMessage(messageNoPermission);
    }

    public static Component getMessageReload() {
        return MessageUtil.parseMessage(messageReload);
    }
}