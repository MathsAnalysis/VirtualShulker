package com.github.mathsanalysis.vshulker.listener;

import com.github.mathsanalysis.vshulker.config.Config;
import com.github.mathsanalysis.vshulker.manager.VirtualShulkerManager;
import com.github.mathsanalysis.vshulker.security.TransactionTracker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
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

        if (Config.PERMISSION_USE != null && !Config.PERMISSION_USE.isEmpty()
                && !player.hasPermission(Config.PERMISSION_USE)) {
            player.sendMessage(Config.getMessageNoPermission());
            return;
        }

        event.setCancelled(true);

        if (manager.isLoading(player) || manager.hasOpenShulker(player)) {
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

        Bukkit.getScheduler().runTask(manager.getPlugin(), () -> {
            manager.closeShulker(player, true, true);
            player.closeInventory();
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClickPreventMove(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (!manager.hasOpenShulker(player)) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        ItemStack cursor = event.getCursor();

        if (manager.isOpenedShulker(player, clicked) || manager.isOpenedShulker(player, cursor)) {
            event.setCancelled(true);
            event.setResult(Event.Result.DENY);
            player.sendMessage(Component.text("Cannot move the opened shulker!", NamedTextColor.RED));
            return;
        }

        if (event.getClick().isKeyboardClick()) {
            int hotbar = event.getHotbarButton();
            if (hotbar >= 0) {
                ItemStack hotbarItem = player.getInventory().getItem(hotbar);
                if (manager.isOpenedShulker(player, hotbarItem)) {
                    event.setCancelled(true);
                    event.setResult(Event.Result.DENY);
                    player.sendMessage(Component.text("Cannot move the opened shulker!", NamedTextColor.RED));
                    return;
                }
            }
        }

        if (event.getClick() == ClickType.SWAP_OFFHAND) {
            ItemStack offHand = player.getInventory().getItemInOffHand();
            if (manager.isOpenedShulker(player, offHand)) {
                event.setCancelled(true);
                event.setResult(Event.Result.DENY);
                player.sendMessage(Component.text("Cannot move the opened shulker!", NamedTextColor.RED));
                return;
            }
        }

        if (event.getClick() == ClickType.DOUBLE_CLICK) {
            if (manager.isOpenedShulker(player, cursor)) {
                event.setCancelled(true);
                event.setResult(Event.Result.DENY);
                player.sendMessage(Component.text("Cannot move the opened shulker!", NamedTextColor.RED));
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInventoryClickValidateFirst(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (!manager.hasOpenShulker(player)) {
            return;
        }

        manager.performImmediateValidation(player);

        manager.recordTransaction(
                player,
                getTransactionType(event.getClick()),
                event.getSlot(),
                event.getCurrentItem()
        );
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
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
        Inventory bottomInv = event.getView().getBottomInventory();

        if (manager.isOpenedShulker(player, clicked)) {
            event.setCancelled(true);
            player.sendMessage(Component.text("Cannot move the opened shulker!", NamedTextColor.RED));
            return;
        }

        if (manager.isOpenedShulker(player, cursor)) {
            event.setCancelled(true);
            player.sendMessage(Component.text("Cannot move the opened shulker!", NamedTextColor.RED));
            return;
        }

        if (isShulkerBox(cursor) && clickedInv != null && clickedInv.equals(topInv)) {
            event.setCancelled(true);
            player.sendMessage(Component.text("Cannot place shulker boxes inside!", NamedTextColor.RED));
            return;
        }

        if (isShulkerBox(clicked) && clickedInv != null && clickedInv.equals(topInv)) {
            event.setCancelled(true);
            player.sendMessage(Component.text("Cannot place shulker boxes inside!", NamedTextColor.RED));
            return;
        }

        if (event.isShiftClick() && isShulkerBox(clicked)) {
            event.setCancelled(true);
            player.sendMessage(Component.text("Cannot move shulker boxes!", NamedTextColor.RED));
            return;
        }

        if (event.getClick().isKeyboardClick()) {
            int hotbar = event.getHotbarButton();
            if (hotbar >= 0) {
                ItemStack hotbarItem = player.getInventory().getItem(hotbar);
                if (isShulkerBox(hotbarItem) || isShulkerBox(clicked)) {
                    event.setCancelled(true);
                    player.sendMessage(Component.text("Cannot swap shulker boxes!", NamedTextColor.RED));
                    return;
                }

                if (manager.isOpenedShulker(player, hotbarItem)) {
                    event.setCancelled(true);
                    player.sendMessage(Component.text("Cannot move the opened shulker!", NamedTextColor.RED));
                    return;
                }
            }
        }

        if (clickedInv != null && clickedInv.equals(bottomInv)) {
            if (event.getClick() == ClickType.NUMBER_KEY) {
                int slot = event.getHotbarButton();
                ItemStack hotbarItem = player.getInventory().getItem(slot);
                if (manager.isOpenedShulker(player, hotbarItem)) {
                    event.setCancelled(true);
                    player.sendMessage(Component.text("Cannot move the opened shulker!", NamedTextColor.RED));
                    return;
                }
            }

            if (event.getClick() == ClickType.SWAP_OFFHAND) {
                ItemStack offHand = player.getInventory().getItemInOffHand();
                if (manager.isOpenedShulker(player, offHand)) {
                    event.setCancelled(true);
                    player.sendMessage(Component.text("Cannot move the opened shulker!", NamedTextColor.RED));
                    return;
                }
            }

            if (event.getClick() == ClickType.DOUBLE_CLICK) {
                if (manager.isOpenedShulker(player, cursor)) {
                    event.setCancelled(true);
                    player.sendMessage(Component.text("Cannot move the opened shulker!", NamedTextColor.RED));
                    return;
                }
            }
        }

        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            if (isShulkerBox(cursor) || manager.isOpenedShulker(player, cursor)) {
                event.setCancelled(true);
                player.sendMessage(Component.text("Cannot collect shulker boxes!", NamedTextColor.RED));
                return;
            }
        }

        if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            if (isShulkerBox(clicked) || manager.isOpenedShulker(player, clicked)) {
                event.setCancelled(true);
                player.sendMessage(Component.text("Cannot move shulker boxes!", NamedTextColor.RED));
                return;
            }
        }

        if (event.getClick() == ClickType.CREATIVE || event.getClick() == ClickType.MIDDLE) {
            if (isShulkerBox(clicked) || manager.isOpenedShulker(player, clicked)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClickAutoSave(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (!manager.hasOpenShulker(player)) {
            return;
        }

        if (!manager.isValidSession(player, event.getInventory())) {
            return;
        }

        Inventory clickedInv = event.getClickedInventory();
        Inventory topInv = event.getView().getTopInventory();

        if (clickedInv != null && clickedInv.equals(topInv)) {
            manager.scheduleAutoSave(player);
        } else if (event.isShiftClick()) {
            manager.scheduleAutoSave(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
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
            player.sendMessage(Component.text("Cannot drag shulker boxes!", NamedTextColor.RED));
            return;
        }

        if (manager.isOpenedShulker(player, dragged)) {
            event.setCancelled(true);
            player.sendMessage(Component.text("Cannot move the opened shulker!", NamedTextColor.RED));
            return;
        }

        manager.recordTransaction(
                player,
                TransactionTracker.TransactionType.DRAG,
                -1,
                dragged
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDragAutoSave(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (!manager.hasOpenShulker(player)) {
            return;
        }

        if (!manager.isValidSession(player, event.getInventory())) {
            return;
        }

        Inventory topInv = event.getView().getTopInventory();

        for (int slot : event.getRawSlots()) {
            if (slot < topInv.getSize()) {
                manager.scheduleAutoSave(player);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();

        if (!manager.hasOpenShulker(player)) {
            return;
        }

        ItemStack droppedItem = event.getItemDrop().getItemStack();

        if (isShulkerBox(droppedItem)) {
            event.setCancelled(true);
            player.sendMessage(Component.text("Cannot drop shulker boxes!", NamedTextColor.RED));
            return;
        }

        if (manager.isOpenedShulker(player, droppedItem)) {
            event.setCancelled(true);
            player.sendMessage(Component.text("Cannot drop the opened shulker!", NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerSwapHand(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();

        if (!manager.hasOpenShulker(player)) {
            return;
        }

        if (isShulkerBox(event.getMainHandItem()) || isShulkerBox(event.getOffHandItem())) {
            event.setCancelled(true);
            player.sendMessage(Component.text("Cannot swap shulker boxes!", NamedTextColor.RED));
            return;
        }

        if (manager.isOpenedShulker(player, event.getMainHandItem()) ||
                manager.isOpenedShulker(player, event.getOffHandItem())) {
            event.setCancelled(true);
            player.sendMessage(Component.text("Cannot swap the opened shulker!", NamedTextColor.RED));
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

        Bukkit.getScheduler().runTask(manager.getPlugin(), () -> {
            manager.closeShulker(player, true, true);
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        if (manager.hasOpenShulker(player)) {
            manager.closeShulker(player, true, true);
        } else if (manager.isLoading(player)) {
            manager.cancelLoading(player);
        }
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

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onItemHeldChange(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();

        if (!manager.hasOpenShulker(player)) {
            return;
        }

        ItemStack newItem = player.getInventory().getItem(event.getNewSlot());
        ItemStack oldItem = player.getInventory().getItem(event.getPreviousSlot());

        if (manager.isOpenedShulker(player, newItem) || manager.isOpenedShulker(player, oldItem)) {
            event.setCancelled(true);
            player.sendMessage(Component.text("Cannot change held item!", NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();

        if (manager.hasOpenShulker(player)) {
            Bukkit.getScheduler().runTask(manager.getPlugin(), () -> {
                manager.closeShulker(player, true, true);
                player.closeInventory();
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();

        if (manager.hasOpenShulker(player)) {
            Bukkit.getScheduler().runTask(manager.getPlugin(), () -> {
                manager.closeShulker(player, true, true);
                player.closeInventory();
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerKick(PlayerKickEvent event) {
        Player player = event.getPlayer();

        if (manager.hasOpenShulker(player)) {
            manager.closeShulker(player, true, true);
        }
    }

    private static boolean isShulkerBox(ItemStack item) {
        return item != null && SHULKER_BOXES.contains(item.getType());
    }

    private TransactionTracker.TransactionType getTransactionType(ClickType clickType) {
        return switch (clickType) {
            case LEFT, RIGHT, WINDOW_BORDER_LEFT, WINDOW_BORDER_RIGHT -> TransactionTracker.TransactionType.CLICK;
            case SHIFT_LEFT, SHIFT_RIGHT -> TransactionTracker.TransactionType.SHIFT_CLICK;
            case NUMBER_KEY -> TransactionTracker.TransactionType.NUMBER_KEY;
            case DROP, CONTROL_DROP -> TransactionTracker.TransactionType.DROP;
            case SWAP_OFFHAND -> TransactionTracker.TransactionType.SWAP_OFFHAND;
            case DOUBLE_CLICK -> TransactionTracker.TransactionType.DOUBLE_CLICK;
            default -> TransactionTracker.TransactionType.CLICK;
        };
    }
}