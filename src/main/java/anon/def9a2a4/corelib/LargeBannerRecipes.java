package anon.def9a2a4.corelib;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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
    static final NamespacedKey EXTRA_LARGE_BANNER_KEY = new NamespacedKey("corelib", "extra_large_banner");

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

        if (isExtraLargeBanner(banner)) {
            inv.setResult(null);
            return;
        }

        boolean upgradeToExtraLarge = isLargeBanner(banner);
        String color = colorName(banner.getType());

        ItemStack result = banner.asQuantity(1);
        var meta = result.getItemMeta();
        List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();

        if (upgradeToExtraLarge) {
            meta.getPersistentDataContainer().remove(LARGE_BANNER_KEY);
            meta.getPersistentDataContainer().set(EXTRA_LARGE_BANNER_KEY, PersistentDataType.BYTE, (byte) 1);
            meta.displayName(Component.text("Extra Large " + color + " Banner",
                    NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
            lore.removeIf(c -> c instanceof TextComponent tc && tc.content().equals("Large"));
            lore.add(Component.text("Extra Large", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        } else {
            meta.getPersistentDataContainer().set(LARGE_BANNER_KEY, PersistentDataType.BYTE, (byte) 1);
            meta.displayName(Component.text("Large " + color + " Banner",
                    NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Large", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }

        meta.lore(lore);
        result.setItemMeta(meta);
        inv.setResult(result);
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

    static boolean isExtraLargeBanner(ItemStack item) {
        if (item == null || !isBanner(item.getType())) return false;
        if (!item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer()
                .has(EXTRA_LARGE_BANNER_KEY, PersistentDataType.BYTE);
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
