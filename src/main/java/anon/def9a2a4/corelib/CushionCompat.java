package anon.def9a2a4.corelib;

import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jspecify.annotations.Nullable;

/**
 * Version-safe access to the 26.3 cushion — a rideable entity resting on a block (NMS
 * {@code decoration.Cushion extends BlockAttachedEntity}), NOT a block.
 *
 * <p>The plugin compiles against 1.21.11 and must load on servers that predate cushions, so nothing here (or
 * anywhere else) names {@code org.bukkit.entity.Cushion}: the type is resolved once by registry key, and on an
 * older server it is null and every check is false. Colour comes from {@link org.bukkit.material.Colorable},
 * which the cushion implements and which exists on every supported version.
 */
final class CushionCompat {

    private static final @Nullable EntityType TYPE = resolve();

    private CushionCompat() {}

    private static @Nullable EntityType resolve() {
        try {
            return Registry.ENTITY_TYPE.get(NamespacedKey.minecraft("cushion"));
        } catch (Throwable t) {
            return null; // registry lookup unsupported → treat as "no cushions"
        }
    }

    /** True when this server has cushions at all (26.3+). */
    static boolean available() { return TYPE != null; }

    static boolean isCushion(@Nullable Entity e) { return TYPE != null && e != null && e.getType() == TYPE; }

    /**
     * The item a broken cushion drops: {@code <COLOR>_CUSHION} carrying the entity's custom name — the same data
     * vanilla's own drop carries (colour + CUSTOM_NAME). Null when the material can't be resolved.
     */
    static @Nullable ItemStack pickItem(Entity cushion) {
        DyeColor color = cushion instanceof org.bukkit.material.Colorable c ? c.getColor() : null;
        Material m = Material.matchMaterial((color != null ? color : DyeColor.WHITE).name() + "_CUSHION");
        if (m == null) m = Material.matchMaterial("WHITE_CUSHION");
        if (m == null) return null;
        ItemStack item = new ItemStack(m);
        net.kyori.adventure.text.Component name = cushion.customName();
        if (name != null) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.customName(name);
                item.setItemMeta(meta);
            }
        }
        return item;
    }
}
