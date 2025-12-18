package com.github.mathsanalysis.vshulker.listener;

import com.github.mathsanalysis.vshulker.VirtualShulkerPlugin;
import com.github.mathsanalysis.vshulker.manager.VirtualShulkerManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.Iterator;
import java.util.Set;

public class ShulkerBlockListener implements Listener {

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

    private final VirtualShulkerPlugin plugin;
    private final VirtualShulkerManager manager;

    public ShulkerBlockListener(VirtualShulkerPlugin plugin, VirtualShulkerManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();

        if (!SHULKER_BOXES.contains(item.getType())) {
            return;
        }

        if (manager.isOpenedShulker(event.getPlayer(), item)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(net.kyori.adventure.text.Component.text(
                    "Cannot place the opened shulker!",
                    net.kyori.adventure.text.format.NamedTextColor.RED
            ));
            return;
        }

        Location location = event.getBlock().getLocation();
        manager.registerPlacedShulker(location);

        plugin.getLogger().fine("Registered placed shulker at: " + location);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!SHULKER_BOXES.contains(event.getBlock().getType())) {
            return;
        }

        Location location = event.getBlock().getLocation();

        if (manager.isPlacedVirtualShulker(location)) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (manager.hasOpenShulker(online)) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        manager.closeShulker(online, true, true);
                        online.closeInventory();
                    });
                }
            }
        }

        manager.unregisterPlacedShulker(location);
        plugin.getLogger().fine("Unregistered placed shulker at: " + location);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        Inventory source = event.getSource();
        Inventory destination = event.getDestination();

        if (source.getType() == InventoryType.SHULKER_BOX && source.getLocation() != null) {
            if (manager.isPlacedVirtualShulker(source.getLocation())) {
                event.setCancelled(true);
                return;
            }
        }

        if (destination.getType() == InventoryType.SHULKER_BOX && destination.getLocation() != null) {
            if (manager.isPlacedVirtualShulker(destination.getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        handleExplosion(event.blockList().iterator());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        handleExplosion(event.blockList().iterator());
    }

    private void handleExplosion(Iterator<Block> blocks) {
        while (blocks.hasNext()) {
            Block block = blocks.next();

            if (!SHULKER_BOXES.contains(block.getType())) {
                continue;
            }

            if (!(block.getState() instanceof ShulkerBox shulkerBlock)) {
                continue;
            }

            ItemStack[] contents = shulkerBlock.getInventory().getContents();

            shulkerBlock.getInventory().clear();
            shulkerBlock.update();

            manager.unregisterPlacedShulker(block.getLocation());

            ItemStack drop = new ItemStack(block.getType());
            if (drop.getItemMeta() instanceof BlockStateMeta blockMeta) {
                ShulkerBox box = (ShulkerBox) blockMeta.getBlockState();
                box.getInventory().setContents(contents);
                blockMeta.setBlockState(box);
                drop.setItemMeta(blockMeta);
            }

            block.getWorld().dropItemNaturally(block.getLocation(), drop);

            blocks.remove();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block block : event.getBlocks()) {
            if (SHULKER_BOXES.contains(block.getType()) && manager.isPlacedVirtualShulker(block.getLocation())) {
                event.setCancelled(true);
                plugin.getLogger().fine("Blocked piston extension of virtual shulker");
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block block : event.getBlocks()) {
            if (SHULKER_BOXES.contains(block.getType()) && manager.isPlacedVirtualShulker(block.getLocation())) {
                event.setCancelled(true);
                plugin.getLogger().fine("Blocked piston retraction of virtual shulker");
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Block block = event.getClickedBlock();
        if (block == null || !SHULKER_BOXES.contains(block.getType())) {
            return;
        }

        Player player = event.getPlayer();

        if (manager.hasOpenShulker(player)) {
            event.setCancelled(true);
            player.sendMessage(net.kyori.adventure.text.Component.text(
                    "Close your virtual shulker first!",
                    net.kyori.adventure.text.format.NamedTextColor.RED
            ));
            return;
        }

        if (player.isSneaking() && player.getInventory().getItemInMainHand().getType().isBlock()) {
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            return;
        }

        Block block = event.getBlock();
        if (!SHULKER_BOXES.contains(block.getType())) {
            return;
        }

        if (manager.hasOpenShulker(event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(net.kyori.adventure.text.Component.text(
                    "Close your virtual shulker first!",
                    net.kyori.adventure.text.format.NamedTextColor.RED
            ));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        Block toBlock = event.getToBlock();
        if (SHULKER_BOXES.contains(toBlock.getType())) {
            if (manager.isPlacedVirtualShulker(toBlock.getLocation())) {
                event.setCancelled(true);
            }
        }
    }
}