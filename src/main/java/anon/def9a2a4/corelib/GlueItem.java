package anon.def9a2a4.corelib;

import org.bukkit.inventory.ItemStack;

/**
 * The "slime glue" authoring item. Declared in {@code corelib-items.yml} as the registry item
 * {@code corelib:glue_item} (SLIME_BALL); identity is the registry {@code block_type} PDC. This holder
 * just recognizes a held glue item for {@code GlueAuthoring}.
 */
final class GlueItem {

    private GlueItem() {}

    static boolean isGlueItem(ItemStack item) {
        return "corelib:glue_item".equals(CustomBlockRegistry.getItemTypeId(item));
    }
}
