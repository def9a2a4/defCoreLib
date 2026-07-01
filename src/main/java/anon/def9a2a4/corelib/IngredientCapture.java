package anon.def9a2a4.corelib;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Skull;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Renders the crafting ingredients a block was made from onto its display entities.
 *
 * <p>The nugget: a display piece's {@code tag} names its own PDC slot, and the stored value is
 * simply the {@link ItemStack} to show. A block declares {@code slot → tag} pairs plus how to
 * derive the display item from an ingredient ({@link #derive}) and how to label it
 * ({@link #describe}). This engine captures at craft, round-trips the data item↔skull on
 * place/break, resolves each tagged display, and lists the materials in lore.
 *
 * <p>Windmill banners are a separate (older) bespoke path; see docs/todo/TODO.md for migrating
 * them onto this system.
 */
public record IngredientCapture(
        Map<Integer, String> slotTags,          // recipe matrix slot → display-entity tag (names the PDC slot)
        Function<ItemStack, ItemStack> derive,  // ingredient → display item (null ⇒ skip that slot)
        Function<ItemStack, String> describe,   // display item → lore label
        @Nullable String loreHeader,            // e.g. "Paddles:"; null ⇒ no lore
        boolean replaceLore) {                  // append to existing lore (false) or replace it (true)

    /** PDC key holding the captured display item for a display tag. */
    static NamespacedKey key(String tag) {
        return new NamespacedKey("corelib", "capture_" + tag);
    }

    /** At craft: derive a display item per slot, stamp it under its tag's key + build lore. */
    static void capture(CraftingInventory inv, CustomBlockRegistry registry) {
        ItemStack result = inv.getResult();
        if (result == null || !(result.getItemMeta() instanceof SkullMeta meta)) return;
        String typeId = meta.getPersistentDataContainer()
                .get(CustomBlockRegistry.BLOCK_TYPE_KEY, PersistentDataType.STRING);
        if (typeId == null) return;
        CustomHeadBlock type = registry.getType(typeId);
        if (type == null || type.ingredientCapture() == null) return;
        IngredientCapture spec = type.ingredientCapture();

        ItemStack[] matrix = inv.getMatrix();
        List<ItemStack> captured = new ArrayList<>();
        ItemStack out = result.clone();
        ItemMeta outMeta = out.getItemMeta();
        PersistentDataContainer pdc = outMeta.getPersistentDataContainer();
        for (var e : spec.slotTags.entrySet()) {
            int slot = e.getKey();
            if (slot < 0 || slot >= matrix.length) continue;
            ItemStack ingredient = matrix[slot];
            if (ingredient == null || ingredient.getType().isAir()) continue;
            ItemStack display = spec.derive.apply(ingredient);
            if (display == null) continue;
            pdc.set(key(e.getValue()), PersistentDataType.BYTE_ARRAY, display.serializeAsBytes());
            captured.add(display);
        }
        if (captured.isEmpty()) return; // nothing derived — leave the recipe result untouched
        spec.applyLore(outMeta, captured);
        out.setItemMeta(outMeta);
        inv.setResult(out);
    }

    /** Copy every captured slot between two PDCs (item↔skull). */
    void copyPdc(PersistentDataContainer src, PersistentDataContainer dst) {
        for (String tag : slotTags.values()) {
            byte[] data = src.get(key(tag), PersistentDataType.BYTE_ARRAY);
            if (data != null) dst.set(key(tag), PersistentDataType.BYTE_ARRAY, data);
        }
    }

    /** The captured display item for a display tag, or null (⇒ keep the YAML default). */
    static @Nullable ItemStack resolveDisplay(Block block, String tagSuffix) {
        if (!(block.getState() instanceof Skull skull)) return null;
        byte[] data = skull.getPersistentDataContainer().get(key(tagSuffix), PersistentDataType.BYTE_ARRAY);
        if (data == null) return null;
        try {
            return ItemStack.deserializeBytes(data);
        } catch (Exception ex) {
            return null;
        }
    }

    /** Copy captured data skull→item and re-apply lore (for drops / pick-block). */
    ItemStack enrich(Skull skull, ItemStack item) {
        ItemStack out = item.clone();
        ItemMeta meta = out.getItemMeta();
        copyPdc(skull.getPersistentDataContainer(), meta.getPersistentDataContainer());
        List<ItemStack> captured = new ArrayList<>();
        for (String tag : slotTags.values()) {
            byte[] data = skull.getPersistentDataContainer().get(key(tag), PersistentDataType.BYTE_ARRAY);
            if (data == null) continue;
            try {
                captured.add(ItemStack.deserializeBytes(data));
            } catch (Exception ignored) {
                // skip an unreadable slot
            }
        }
        if (!captured.isEmpty()) applyLore(meta, captured);
        out.setItemMeta(meta);
        return out;
    }

    /** "&lt;header&gt;" + one "• Nx &lt;label&gt;" line per distinct captured item (first-seen order). */
    void applyLore(ItemMeta meta, List<ItemStack> captured) {
        if (loreHeader == null || captured.isEmpty()) return;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemStack it : captured) counts.merge(describe.apply(it), 1, Integer::sum);
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text(loreHeader, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        for (var e : counts.entrySet()) {
            String prefix = e.getValue() > 1 ? e.getValue() + "x " : "";
            lines.add(Component.text(" • " + prefix + e.getKey(), NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        List<Component> lore = (replaceLore || !meta.hasLore())
                ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.addAll(lines);
        meta.lore(lore);
    }

    // ── Wood helpers (reusable by any plank-derived block) ──────────────────────────────────
    /** The plank material for a wooden slab (BAMBOO_MOSAIC_SLAB → BAMBOO_PLANKS), OAK fallback. */
    public static Material plankForSlab(Material slab) {
        if (slab == Material.BAMBOO_MOSAIC_SLAB) return Material.BAMBOO_PLANKS;
        Material m = Material.matchMaterial(slab.name().replace("_SLAB", "_PLANKS"));
        return m != null ? m : Material.OAK_PLANKS;
    }

    /** A material label: strip a trailing "_PLANKS" and title-case ("DARK_OAK_PLANKS" → "Dark Oak"). */
    public static String describeMaterial(ItemStack it) {
        String raw = it.getType().name();
        if (raw.endsWith("_PLANKS")) raw = raw.substring(0, raw.length() - "_PLANKS".length());
        StringBuilder sb = new StringBuilder();
        for (String w : raw.split("_")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }
}
