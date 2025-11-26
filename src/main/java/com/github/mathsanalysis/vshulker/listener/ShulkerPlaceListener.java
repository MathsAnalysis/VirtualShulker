package com.github.mathsanalysis.vshulker.listener;

import com.github.mathsanalysis.vshulker.VirtualShulkerPlugin;
import com.github.mathsanalysis.vshulker.manager.VirtualShulkerManager;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.ShulkerBox;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Set;

public class ShulkerPlaceListener implements Listener {

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

    public ShulkerPlaceListener(VirtualShulkerPlugin plugin, VirtualShulkerManager manager) {
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

        ItemStack[] savedContents = manager.getShulkerContents(shulkerId);

        if (savedContents == null) {
            return;
        }

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!(event.getBlock().getState() instanceof ShulkerBox shulkerBlock)) {
                return;
            }

            shulkerBlock.getInventory().setContents(savedContents);
            shulkerBlock.update();

            plugin.getLogger().info("Sincronizzati contenuti shulker piazzata: " + shulkerId);
        });
    }
}