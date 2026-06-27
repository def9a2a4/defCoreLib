package anon.def9a2a4.corelib;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Keyed;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public class LargeBannerRecipes implements Listener {

    static final NamespacedKey LARGE_BANNER_KEY = new NamespacedKey("corelib", "large_banner");
    static final NamespacedKey HUGE_BANNER_KEY = new NamespacedKey("corelib", "huge_banner");

    private static final List<Material> BANNER_MATERIALS = List.of(
            Material.WHITE_BANNER, Material.ORANGE_BANNER, Material.MAGENTA_BANNER,
            Material.LIGHT_BLUE_BANNER, Material.YELLOW_BANNER, Material.LIME_BANNER,
            Material.PINK_BANNER, Material.GRAY_BANNER, Material.LIGHT_GRAY_BANNER,
            Material.CYAN_BANNER, Material.PURPLE_BANNER, Material.BLUE_BANNER,
            Material.BROWN_BANNER, Material.GREEN_BANNER, Material.RED_BANNER,
            Material.BLACK_BANNER);

    private final NamespacedKey recipeKey;

    public LargeBannerRecipes(JavaPlugin plugin) {
        recipeKey = new NamespacedKey(plugin, "large_banner");
        ItemStack placeholder = new ItemStack(Material.WHITE_BANNER);
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, placeholder);
        recipe.shape("WWW", "WBW", "WWW");
        recipe.setIngredient('W', new RecipeChoice.MaterialChoice(Tag.WOOL));
        recipe.setIngredient('B', new RecipeChoice.MaterialChoice(BANNER_MATERIALS));
        Bukkit.addRecipe(recipe);
    }

    /** Unregister the recipe on plugin disable so a server /reload doesn't fail with a
     *  duplicate-key error when the constructor re-adds it. */
    void unregister() {
        Bukkit.removeRecipe(recipeKey);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        modifyResult(event.getInventory());
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onCraft(CraftItemEvent event) {
        modifyResult(event.getInventory());
    }

    private void modifyResult(CraftingInventory inv) {
        if (inv.getRecipe() == null) return;
        if (!(inv.getRecipe() instanceof Keyed keyed)) return;
        if (!keyed.getKey().equals(recipeKey)) return;

        ItemStack[] matrix = inv.getMatrix();
        ItemStack banner = matrix[4]; // center slot
        if (banner == null || !isBanner(banner.getType())) return;

        if (isHugeBanner(banner)) {
            inv.setResult(null);
            return;
        }

        boolean upgradeToHuge = isLargeBanner(banner);
        String newTier = upgradeToHuge ? "Huge" : "Large";
        String color = colorName(banner.getType());

        ItemStack result = banner.asQuantity(1);
        var meta = result.getItemMeta();
        var pdc = meta.getPersistentDataContainer();
        if (upgradeToHuge) {
            pdc.remove(LARGE_BANNER_KEY);
            pdc.set(HUGE_BANNER_KEY, PersistentDataType.BYTE, (byte) 1);
        } else {
            pdc.set(LARGE_BANNER_KEY, PersistentDataType.BYTE, (byte) 1);
        }

        // Only (re)generate the display name when it's absent or one of our auto-generated tier
        // names — never clobber a genuine player-given (anvil) name.
        if (!meta.hasDisplayName() || isAutoTierName(meta.displayName(), color)) {
            meta.displayName(Component.text(newTier + " " + color + " Banner",
                    NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        }

        // Rebuild the tier lore line deterministically: strip any prior tier label, add the new one,
        // preserving all other (user) lore.
        List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.removeIf(LargeBannerRecipes::isTierLoreLine);
        lore.add(Component.text(newTier, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));

        meta.lore(lore);
        result.setItemMeta(meta);
        inv.setResult(result);
    }

    /** A lore line is one of our tier labels ("Large"/"Huge"), tolerant of formatting/whitespace. */
    private static boolean isTierLoreLine(Component line) {
        String text = PlainTextComponentSerializer.plainText().serialize(line).trim();
        return text.equalsIgnoreCase("Large") || text.equalsIgnoreCase("Huge");
    }

    /** True if {@code name} matches a name this plugin would auto-generate for some tier of this
     *  colour (e.g. "Large Red Banner" / "Huge Red Banner"), so it is safe to overwrite. */
    private static boolean isAutoTierName(Component name, String color) {
        if (name == null) return false;
        String text = PlainTextComponentSerializer.plainText().serialize(name).trim();
        return text.equalsIgnoreCase("Large " + color + " Banner")
                || text.equalsIgnoreCase("Huge " + color + " Banner");
    }

    static boolean isBanner(Material mat) {
        return mat.name().endsWith("_BANNER") && !mat.name().contains("WALL");
    }

    static boolean isLargeBanner(ItemStack item) {
        if (item == null || !isBanner(item.getType())) return false;
        if (!item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer()
                .has(LARGE_BANNER_KEY, PersistentDataType.BYTE);
    }

    static boolean isHugeBanner(ItemStack item) {
        if (item == null || !isBanner(item.getType())) return false;
        if (!item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer()
                .has(HUGE_BANNER_KEY, PersistentDataType.BYTE);
    }

    /** Does this banner satisfy the required tier? NORMAL = a banner with no tier marker. */
    static boolean matches(ItemStack item, BannerTier tier) {
        return switch (tier) {
            case NORMAL -> isBanner(item.getType()) && !isLargeBanner(item) && !isHugeBanner(item);
            case LARGE -> isLargeBanner(item);
            case HUGE -> isHugeBanner(item);
        };
    }

    /** Return a copy of the banner with its tier marker and auto-generated tier name/lore removed,
     *  so a captured blade renders/labels as its plain base colour. No-op for plain banners. */
    static ItemStack stripTier(ItemStack banner) {
        ItemStack out = banner.clone();
        if (!out.hasItemMeta()) return out;
        var meta = out.getItemMeta();
        var pdc = meta.getPersistentDataContainer();
        pdc.remove(LARGE_BANNER_KEY);
        pdc.remove(HUGE_BANNER_KEY);
        if (meta.hasDisplayName() && isAutoTierName(meta.displayName(), colorName(out.getType()))) {
            meta.displayName(null);
        }
        if (meta.hasLore()) {
            List<Component> lore = new ArrayList<>(meta.lore());
            lore.removeIf(LargeBannerRecipes::isTierLoreLine);
            meta.lore(lore.isEmpty() ? null : lore);
        }
        out.setItemMeta(meta);
        return out;
    }

    private static String colorName(Material bannerMat) {
        String raw = bannerMat.name().replace("_BANNER", "");
        String[] parts = raw.split("_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(parts[i].charAt(0)).append(parts[i].substring(1).toLowerCase());
        }
        return sb.toString();
    }
}
