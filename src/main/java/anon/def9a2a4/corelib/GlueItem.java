package anon.def9a2a4.corelib;

import org.bukkit.inventory.ItemStack;

/**
 * The "glue brush" authoring item. Declared in {@code corelib-items.yml} as the registry item
 * {@code mech:glue_item} (a real BRUSH); identity is the registry {@code block_type} PDC. This holder
 * just recognizes a held glue brush for {@code GlueAuthoring}, which suppresses vanilla brushing and
 * wears the brush down per glued block.
 */
final class GlueItem {

    private GlueItem() {}

    static boolean isGlueItem(ItemStack item) {
        return "mech:glue_item".equals(CustomBlockRegistry.getItemTypeId(item));
    }
}
