package com.github.mathsanalysis.vshulker.listener;

import com.github.mathsanalysis.vshulker.VirtualShulkerPlugin;
import com.github.mathsanalysis.vshulker.manager.VirtualShulkerManager;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

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
    private final NamespacedKey shulkerIdKey;

    public ShulkerBlockListener(VirtualShulkerPlugin plugin, VirtualShulkerManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.shulkerIdKey = new NamespacedKey(plugin, "shulker_id");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();

        if (!SHULKER_BOXES.contains(item.getType())) {
            return;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String shulkerId = pdc.get(shulkerIdKey, PersistentDataType.STRING);

        if (shulkerId == null) {
            return;
        }

        if (manager.isShulkerInUse(shulkerId)) {
            event.setCancelled(true);
            plugin.getLogger().warning("Blocked placing shulker that is in use: " + shulkerId);
            return;
        }

        Location location = event.getBlock().getLocation();

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!(event.getBlock().getState() instanceof ShulkerBox shulkerBlock)) {
                return;
            }

            PersistentDataContainer blockPdc = shulkerBlock.getPersistentDataContainer();
            blockPdc.set(shulkerIdKey, PersistentDataType.STRING, shulkerId);

            ItemStack[] savedContents = manager.getShulkerContents(shulkerId);
            if (savedContents != null) {
                shulkerBlock.getInventory().setContents(savedContents);
            }

            shulkerBlock.update();

            manager.registerPlacedShulker(location);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!SHULKER_BOXES.contains(event.getBlock().getType())) {
            return;
        }

        if (!(event.getBlock().getState() instanceof ShulkerBox shulkerBlock)) {
            return;
        }

        PersistentDataContainer blockPdc = shulkerBlock.getPersistentDataContainer();
        String shulkerId = blockPdc.get(shulkerIdKey, PersistentDataType.STRING);

        if (shulkerId == null) {
            return;
        }

        if (manager.isShulkerInUse(shulkerId)) {
            event.setCancelled(true);
            plugin.getLogger().warning("Blocked breaking shulker that is in use: " + shulkerId);
            return;
        }

        Location location = event.getBlock().getLocation();

        ItemStack[] currentContents = shulkerBlock.getInventory().getContents();

        manager.updateShulkerData(shulkerId, currentContents);

        event.setDropItems(false);

        manager.unregisterPlacedShulker(location);

        ItemStack drop = new ItemStack(event.getBlock().getType());
        ItemMeta meta = drop.getItemMeta();

        if (meta != null) {
            PersistentDataContainer itemPdc = meta.getPersistentDataContainer();
            itemPdc.set(shulkerIdKey, PersistentDataType.STRING, shulkerId);

            if (meta instanceof BlockStateMeta blockMeta) {
                ShulkerBox boxState = (ShulkerBox) blockMeta.getBlockState();
                boxState.getInventory().setContents(currentContents);
                blockMeta.setBlockState(boxState);
            }

            drop.setItemMeta(meta);
        }

        event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), drop);
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

            PersistentDataContainer pdc = shulkerBlock.getPersistentDataContainer();
            String shulkerId = pdc.get(shulkerIdKey, PersistentDataType.STRING);

            if (shulkerId != null) {
                ItemStack[] contents = shulkerBlock.getInventory().getContents();
                manager.updateShulkerData(shulkerId, contents);

                shulkerBlock.getInventory().clear();
                shulkerBlock.update();

                manager.unregisterPlacedShulker(block.getLocation());

                ItemStack drop = new ItemStack(block.getType());
                ItemMeta meta = drop.getItemMeta();
                if (meta != null) {
                    meta.getPersistentDataContainer().set(shulkerIdKey, PersistentDataType.STRING, shulkerId);
                    if (meta instanceof BlockStateMeta blockMeta) {
                        ShulkerBox box = (ShulkerBox) blockMeta.getBlockState();
                        box.getInventory().setContents(contents);
                        blockMeta.setBlockState(box);
                    }
                    drop.setItemMeta(meta);
                }

                block.getWorld().dropItemNaturally(block.getLocation(), drop);

                blocks.remove();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block block : event.getBlocks()) {
            if (SHULKER_BOXES.contains(block.getType()) && manager.isPlacedVirtualShulker(block.getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block block : event.getBlocks()) {
            if (SHULKER_BOXES.contains(block.getType()) && manager.isPlacedVirtualShulker(block.getLocation())) {
                event.setCancelled(true);
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

        if (manager.isPlacedVirtualShulker(block.getLocation())) {
            Player player = event.getPlayer();
            if (player.isSneaking() && player.getInventory().getItemInMainHand().getType().isBlock()) {
                return;
            }

            event.setCancelled(true);
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

        if (!(block.getState() instanceof ShulkerBox shulkerBlock)) {
            return;
        }

        String shulkerId = shulkerBlock.getPersistentDataContainer().get(shulkerIdKey, PersistentDataType.STRING);

        if (shulkerId != null && manager.isShulkerInUse(shulkerId)) {
            event.setCancelled(true);
        }
    }
}