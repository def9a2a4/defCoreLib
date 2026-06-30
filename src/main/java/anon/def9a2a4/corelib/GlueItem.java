package anon.def9a2a4.corelib;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * The "slime glue" authoring item. A configurable vanilla item (default {@code SLIME_BALL}) tagged with a
 * PDC key so it is distinguishable from a plain stack. Modelled on the rotation wrench
 * ({@code RotationBlocks#createWrench}). Identity is by PDC tag only (material-agnostic), so changing the
 * config material does not orphan items already crafted.
 */
final class GlueItem {

    static final NamespacedKey GLUE_ITEM_KEY = new NamespacedKey("corelib", "glue_item");

    private GlueItem() {}

    static boolean isGlueItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(GLUE_ITEM_KEY);
    }

    static ItemStack create(Material material) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.displayName(Component.text("Slime Glue", NamedTextColor.GREEN)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            lore("Right-click a door/rotator hinge to edit"),
            lore("Left-click: glue  ·  Shift-left-click: unglue"),
            lore("Right-click two blocks: cuboid fill"),
            lore("Right-click air: reset corner  ·  Sneak-right hinge: clear")));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(GLUE_ITEM_KEY, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private static Component lore(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }
}
