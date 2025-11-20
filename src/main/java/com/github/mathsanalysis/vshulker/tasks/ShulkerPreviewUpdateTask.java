package com.github.mathsanalysis.vshulker.tasks;

import com.github.mathsanalysis.vshulker.VirtualShulkerPlugin;
import com.github.mathsanalysis.vshulker.manager.VirtualShulkerManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.block.ShulkerBox;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

public final class ShulkerPreviewUpdateTask extends BukkitRunnable {

    private final VirtualShulkerPlugin plugin;
    private final VirtualShulkerManager manager;
    private final NamespacedKey shulkerIdKey;

    public ShulkerPreviewUpdateTask(VirtualShulkerPlugin plugin, VirtualShulkerManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.shulkerIdKey = new NamespacedKey(plugin, "shulker_id");
    }

    @Override
    public void run() {
        Bukkit.getWorlds().forEach(world -> world.getEntitiesByClass(Item.class).forEach(itemEntity -> {
            ItemStack item = itemEntity.getItemStack();
            if (isShulkerBox(item.getType())) {
                updateShulkerPreview(item);
            }
        }));
    }

    private void updateShulkerPreview(ItemStack shulkerItem) {
        try {
            ItemMeta meta = shulkerItem.getItemMeta();
            if (meta == null) {
                return;
            }

            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            String shulkerId = pdc.get(shulkerIdKey, PersistentDataType.STRING);

            if (shulkerId == null) {
                return;
            }

            ItemStack[] contents = manager.getShulkerContents(shulkerId);

            if (contents == null || contents.length == 0) {
                return;
            }

            if (meta instanceof BlockStateMeta blockMeta) {
                ShulkerBox box = (ShulkerBox) blockMeta.getBlockState();
                box.getInventory().setContents(contents);

                blockMeta.setBlockState(box);
                shulkerItem.setItemMeta(blockMeta);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to update shulker preview: " + e.getMessage());
        }
    }

    private boolean isShulkerBox(Material material) {
        return material == Material.SHULKER_BOX ||
                material == Material.WHITE_SHULKER_BOX ||
                material == Material.ORANGE_SHULKER_BOX ||
                material == Material.MAGENTA_SHULKER_BOX ||
                material == Material.LIGHT_BLUE_SHULKER_BOX ||
                material == Material.YELLOW_SHULKER_BOX ||
                material == Material.LIME_SHULKER_BOX ||
                material == Material.PINK_SHULKER_BOX ||
                material == Material.GRAY_SHULKER_BOX ||
                material == Material.LIGHT_GRAY_SHULKER_BOX ||
                material == Material.CYAN_SHULKER_BOX ||
                material == Material.PURPLE_SHULKER_BOX ||
                material == Material.BLUE_SHULKER_BOX ||
                material == Material.BROWN_SHULKER_BOX ||
                material == Material.GREEN_SHULKER_BOX ||
                material == Material.RED_SHULKER_BOX ||
                material == Material.BLACK_SHULKER_BOX;
    }

    public void start() {
        this.runTaskTimerAsynchronously(plugin, 20L, 20L);
    }
}