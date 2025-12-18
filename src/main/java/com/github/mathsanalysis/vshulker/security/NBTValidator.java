package com.github.mathsanalysis.vshulker.security;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.block.ShulkerBox;

import java.util.HashSet;
import java.util.Set;

public final class NBTValidator {

    private static final int MAX_NBT_SIZE = 2097152;
    private static final int MAX_DISPLAY_NAME_LENGTH = 256;
    private static final int MAX_LORE_LINES = 50;
    private static final int MAX_LORE_LINE_LENGTH = 256;

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

    public static ValidationResult validate(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return ValidationResult.valid();
        }

        if (isShulkerBox(item)) {
            ValidationResult nestedCheck = checkNestedShulkers(item, 0);
            if (!nestedCheck.isValid()) {
                return nestedCheck;
            }
        }

        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();

            if (meta.hasDisplayName()) {
                String displayName = meta.getDisplayName();
                if (displayName.length() > MAX_DISPLAY_NAME_LENGTH) {
                    return ValidationResult.invalid("Display name exceeds maximum length");
                }
            }

            if (meta.hasLore()) {
                var lore = meta.getLore();
                if (lore != null) {
                    if (lore.size() > MAX_LORE_LINES) {
                        return ValidationResult.invalid("Lore exceeds maximum lines");
                    }
                    for (String line : lore) {
                        if (line.length() > MAX_LORE_LINE_LENGTH) {
                            return ValidationResult.invalid("Lore line exceeds maximum length");
                        }
                    }
                }
            }
        }

        return ValidationResult.valid();
    }

    public static ValidationResult validateInventory(ItemStack[] contents) {
        if (contents == null) {
            return ValidationResult.valid();
        }

        for (int i = 0; i < contents.length; i++) {
            ValidationResult result = validate(contents[i]);
            if (!result.isValid()) {
                return ValidationResult.invalid("Slot " + i + ": " + result.getReason());
            }
        }

        return ValidationResult.valid();
    }

    private static ValidationResult checkNestedShulkers(ItemStack item, int depth) {
        if (depth > 3) {
            return ValidationResult.invalid("Shulker nesting depth exceeds limit");
        }

        if (!isShulkerBox(item)) {
            return ValidationResult.valid();
        }

        if (!(item.getItemMeta() instanceof BlockStateMeta meta)) {
            return ValidationResult.valid();
        }

        if (!(meta.getBlockState() instanceof ShulkerBox shulkerBox)) {
            return ValidationResult.valid();
        }

        ItemStack[] contents = shulkerBox.getInventory().getContents();
        if (contents == null) {
            return ValidationResult.valid();
        }

        for (ItemStack contained : contents) {
            if (contained == null) continue;

            if (isShulkerBox(contained)) {
                return ValidationResult.invalid("Nested shulker boxes are not allowed");
            }

            ValidationResult result = checkNestedShulkers(contained, depth + 1);
            if (!result.isValid()) {
                return result;
            }
        }

        return ValidationResult.valid();
    }

    public static ItemStack sanitize(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return item;
        }

        ItemStack sanitized = item.clone();
        ItemMeta meta = sanitized.getItemMeta();

        if (meta.hasDisplayName()) {
            String displayName = meta.getDisplayName();
            if (displayName.length() > MAX_DISPLAY_NAME_LENGTH) {
                meta.setDisplayName(displayName.substring(0, MAX_DISPLAY_NAME_LENGTH));
            }
        }

        if (meta.hasLore()) {
            var lore = meta.getLore();
            if (lore != null) {
                if (lore.size() > MAX_LORE_LINES) {
                    lore = lore.subList(0, MAX_LORE_LINES);
                }

                for (int i = 0; i < lore.size(); i++) {
                    String line = lore.get(i);
                    if (line.length() > MAX_LORE_LINE_LENGTH) {
                        lore.set(i, line.substring(0, MAX_LORE_LINE_LENGTH));
                    }
                }

                meta.setLore(lore);
            }
        }

        sanitized.setItemMeta(meta);
        return sanitized;
    }

    public static ItemStack removeNestedShulkers(ItemStack item) {
        if (item == null || !isShulkerBox(item)) {
            return item;
        }

        ItemStack cleaned = item.clone();

        if (!(cleaned.getItemMeta() instanceof BlockStateMeta meta)) {
            return cleaned;
        }

        if (!(meta.getBlockState() instanceof ShulkerBox shulkerBox)) {
            return cleaned;
        }

        ItemStack[] contents = shulkerBox.getInventory().getContents();
        if (contents == null) {
            return cleaned;
        }

        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null && isShulkerBox(contents[i])) {
                contents[i] = null;
            }
        }

        shulkerBox.getInventory().setContents(contents);
        meta.setBlockState(shulkerBox);
        cleaned.setItemMeta(meta);

        return cleaned;
    }

    private static boolean isShulkerBox(ItemStack item) {
        return item != null && SHULKER_BOXES.contains(item.getType());
    }
}