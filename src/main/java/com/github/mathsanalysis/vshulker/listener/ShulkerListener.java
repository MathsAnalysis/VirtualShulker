package com.github.mathsanalysis.vshulker.listener;

import com.github.mathsanalysis.vshulker.config.Config;
import com.github.mathsanalysis.vshulker.manager.VirtualShulkerManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

public record ShulkerListener(VirtualShulkerManager manager) implements Listener {

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (!player.isSneaking()) {
            return;
        }

        if (!event.getAction().isRightClick()) {
            return;
        }

        if (!player.hasPermission(Config.PERMISSION_USE)) {
            player.sendMessage(Config.getMessageNoPermission());
            return;
        }

        event.setCancelled(true);
        manager.openShulker(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        if (!manager.hasOpenShulker(player)) {
            return;
        }

        manager.closeShulkerOnDamage(player);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (!manager.hasOpenShulker(player)) {
            return;
        }

        Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            return;
        }

        if (!manager.isValidSession(player, event.getInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (!manager.hasOpenShulker(player)) {
            return;
        }

        if (!manager.isValidSession(player, event.getInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        Inventory source = event.getSource();
        Inventory destination = event.getDestination();

        for (Player player : source.getViewers().stream()
                .filter(viewer -> viewer instanceof Player)
                .map(viewer -> (Player) viewer)
                .toList()) {
            if (manager.hasOpenShulker(player) && !manager.isValidSession(player, source)) {
                event.setCancelled(true);
                return;
            }
        }

        for (Player player : destination.getViewers().stream()
                .filter(viewer -> viewer instanceof Player)
                .map(viewer -> (Player) viewer)
                .toList()) {
            if (manager.hasOpenShulker(player) && !manager.isValidSession(player, destination)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();

        if (!manager.hasOpenShulker(player)) {
            return;
        }

        if (player.getOpenInventory().getTopInventory().equals(event.getPlayer().getInventory())) {
            return;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        if (!manager.hasOpenShulker(player)) {
            return;
        }

        if (!manager.isValidSession(player, event.getInventory())) {
            return;
        }

        manager.closeShulker(player, true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        if (manager.hasOpenShulker(player)) {
            manager.closeShulker(player, true);
        }
    }
}