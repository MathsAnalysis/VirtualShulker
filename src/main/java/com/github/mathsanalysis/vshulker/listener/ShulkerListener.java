package com.github.mathsanalysis.vshulker.listener;

import com.github.mathsanalysis.vshulker.config.Config;
import com.github.mathsanalysis.vshulker.manager.VirtualShulkerManager;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Set;

public record ShulkerListener(VirtualShulkerManager manager) implements Listener {

    private static final Set<Material> SHULKER_BOXES = Set.of(
            Material.SHULKER_BOX,
            Material.WHITE_SHULKER_BOX,
            Material.ORANGE_SHULKER_BOX,
            Material.MAGENTA_SHULKER_BOX,
            Material.LIGHT_BLUE_SHULKER_BOX,
            Material.YELLOW_SHULKER_BOX,
            Material.LIME_SHULKER_BOX,
            Material.PINK_SHULKER_BOX,
            Material.GRAY_SHULKER_BOX,
            Material.LIGHT_GRAY_SHULKER_BOX,
            Material.CYAN_SHULKER_BOX,
            Material.PURPLE_SHULKER_BOX,
            Material.BLUE_SHULKER_BOX,
            Material.BROWN_SHULKER_BOX,
            Material.GREEN_SHULKER_BOX,
            Material.RED_SHULKER_BOX,
            Material.BLACK_SHULKER_BOX
    );

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (!player.isSneaking()) {
            return;
        }

        if (event.getAction() != Action.RIGHT_CLICK_AIR &&
                event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        ItemStack itemInHand = player.getInventory().getItemInMainHand();

        if (!isShulkerBox(itemInHand)) {
            itemInHand = player.getInventory().getItemInOffHand();
            if (!isShulkerBox(itemInHand)) {
                return;
            }
        }

        if (!player.hasPermission(Config.PERMISSION_USE)) {
            return;
        }

        event.setCancelled(true);

        if (manager.isLoading(player) || manager.hasOpenShulker(player)) {
            return;
        }

        String shulkerId = manager.getShulkerIdFromItem(itemInHand);
        if (shulkerId != null && manager.isShulkerInUse(shulkerId)) {
            return;
        }

        manager.openShulker(player, itemInHand);
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

        if (!manager.isValidSession(player, event.getInventory())) {
            event.setCancelled(true);
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        Inventory clickedInv = event.getClickedInventory();
        Inventory topInv = event.getView().getTopInventory();

        if (isShulkerBox(cursor) && clickedInv != null && clickedInv.equals(topInv)) {
            event.setCancelled(true);
            return;
        }

        if (isShulkerBox(clicked) && clickedInv != null && clickedInv.equals(topInv)) {
            event.setCancelled(true);
            return;
        }

        if (event.isShiftClick() && isShulkerBox(clicked)) {
            event.setCancelled(true);
            return;
        }

        if (event.getClick().isKeyboardClick()) {
            int hotbar = event.getHotbarButton();
            if (hotbar >= 0) {
                ItemStack hotbarItem = player.getInventory().getItem(hotbar);
                if (isShulkerBox(hotbarItem) || isShulkerBox(clicked)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }

        String openShulkerId = manager.getOpenShulkerId(player);
        if (openShulkerId != null && clickedInv != null && !clickedInv.equals(topInv)) {
            String clickedId = manager.getShulkerIdFromItem(clicked);
            if (openShulkerId.equals(clickedId)) {
                event.setCancelled(true);
                return;
            }
        }

        if (event.getClick() == ClickType.CREATIVE && isShulkerBox(clicked)) {
            String clickedId = manager.getShulkerIdFromItem(clicked);
            if (clickedId != null) {
                event.setCancelled(true);
                return;
            }
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
            return;
        }

        ItemStack dragged = event.getOldCursor();
        if (isShulkerBox(dragged)) {
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

        ItemStack droppedItem = event.getItemDrop().getItemStack();

        if (isShulkerBox(droppedItem)) {
            event.setCancelled(true);
            return;
        }

        String openId = manager.getOpenShulkerId(player);
        String droppedId = manager.getShulkerIdFromItem(droppedItem);
        if (openId != null && openId.equals(droppedId)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerSwapHand(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();

        if (!manager.hasOpenShulker(player)) {
            return;
        }

        if (isShulkerBox(event.getMainHandItem()) || isShulkerBox(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        if (!manager.hasOpenShulker(player)) {
            return;
        }

        ItemStack item = event.getItem().getItemStack();
        if (isShulkerBox(item)) {
            String openId = manager.getOpenShulkerId(player);
            String pickupId = manager.getShulkerIdFromItem(item);
            if (openId != null && openId.equals(pickupId)) {
                event.setCancelled(true);
            }
        }
    }

    /**
     * PROTEZIONE ANTI-DUPLICAZIONE CREATIVA
     *
     * Questo evento previene l'exploit di duplicazione delle shulker in modalità creativa.
     *
     * Problema originale:
     * 1. Player A ha una shulker con UUID "abc-123"
     * 2. Player B duplica quella shulker in creativa (stesso UUID "abc-123")
     * 3. Player B modifica il contenuto della sua shulker
     * 4. Anche la shulker di Player A viene modificata (stesso shulker_id!)
     *
     * Soluzione:
     * - Quando un player in creativa duplica/prende una shulker che ha già un UUID
     * - Genera automaticamente un NUOVO UUID univoco
     * - Copia il contenuto dal database della shulker originale
     * - Questo garantisce che ogni shulker duplicata sia indipendente
     *
     * Nota: Se la shulker è attualmente in uso, la duplicazione viene bloccata completamente
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCreativeInventory(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (player.getGameMode() != GameMode.CREATIVE) {
            return;
        }

        ItemStack item = event.getCursor();
        if (isShulkerBox(item)) {
            String originalId = manager.getShulkerIdFromItem(item);
            if (originalId != null) {
                // Se la shulker è in uso, blocca completamente la duplicazione
                if (manager.isShulkerInUse(originalId)) {
                    event.setCancelled(true);
                    manager.getPlugin().getLogger().warning(
                            "Blocked creative duplication of in-use shulker: " + originalId +
                                    " by " + player.getName()
                    );
                    return;
                }

                // Genera sempre un nuovo UUID per le shulker duplicate in creativa
                // per prevenire modifiche cross-player
                ItemStack newItem = item.clone();
                manager.regenerateShulkerId(newItem, originalId);
                event.setCursor(newItem);

                manager.getPlugin().getLogger().info(
                        "Regenerated shulker ID for creative duplication by " + player.getName() +
                                ": " + originalId + " -> " + manager.getShulkerIdFromItem(newItem)
                );
            }
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

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        manager.loadPlayerCache(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        if (manager.hasOpenShulker(player)) {
            manager.closeShulker(player, true, true);
        } else if (manager.isLoading(player)) {
            manager.cancelLoading(player);
        }

        manager.unloadPlayerCache(player);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();

        if (manager.hasOpenShulker(player)) {
            manager.closeShulker(player, true, true);
        } else if (manager.isLoading(player)) {
            manager.cancelLoading(player);
        }
    }

    private static boolean isShulkerBox(ItemStack item) {
        return item != null && SHULKER_BOXES.contains(item.getType());
    }
}