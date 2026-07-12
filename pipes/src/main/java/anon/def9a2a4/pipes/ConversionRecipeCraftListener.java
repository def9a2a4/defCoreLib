package anon.def9a2a4.pipes;

import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

/**
 * Handles conversion recipe crafting to ensure catalyst items (like water buckets)
 * remain in the crafting grid after crafting, and prevents duplication exploits.
 */
public class ConversionRecipeCraftListener implements Listener {

    private final PipesPlugin plugin;
    private final RecipeManager recipeManager;

    public ConversionRecipeCraftListener(PipesPlugin plugin, RecipeManager recipeManager) {
        this.plugin = plugin;
        this.recipeManager = recipeManager;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        Recipe recipe = event.getRecipe();
        if (recipe == null) return;

        NamespacedKey key = getRecipeKey(recipe);
        if (key == null || !recipeManager.isConversionRecipe(key)) return;

        // Recipe is valid, result will be set by Bukkit
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCraftItem(CraftItemEvent event) {
        Recipe recipe = event.getRecipe();
        if (recipe == null) return;

        NamespacedKey key = getRecipeKey(recipe);
        if (key == null || !recipeManager.isConversionRecipe(key)) return;

        Material catalyst = recipeManager.getConversionCatalyst(key);
        if (catalyst == null) return;

        CraftingInventory inventory = event.getInventory();

        // Handle shift-click: craft one at a time, replacing water bucket with empty bucket
        if (event.isShiftClick()) {
            event.setCancelled(true);

            ItemStack result = event.getRecipe().getResult().clone();

            ItemStack[] matrix = inventory.getMatrix();
            boolean consumed = false;

            for (int i = 0; i < matrix.length; i++) {
                ItemStack item = matrix[i];
                if (item == null || item.getType() == Material.AIR) continue;

                if (item.getType() == catalyst) {
                    // Replace water bucket with empty bucket
                    matrix[i] = new ItemStack(Material.BUCKET);
                } else if (plugin.isPipeItem(item) && !consumed) {
                    if (item.getAmount() > 1) {
                        item.setAmount(item.getAmount() - 1);
                    } else {
                        matrix[i] = null;
                    }
                    consumed = true;
                }
            }

            if (consumed) {
                inventory.setMatrix(matrix);

                if (event.getWhoClicked().getInventory().firstEmpty() != -1) {
                    event.getWhoClicked().getInventory().addItem(result);
                } else {
                    event.getWhoClicked().getWorld().dropItem(
                            event.getWhoClicked().getLocation(), result);
                }
            }
        }

        // Normal click: Bukkit handles it naturally - water bucket becomes empty bucket
    }

    private NamespacedKey getRecipeKey(Recipe recipe) {
        if (recipe instanceof Keyed keyed) {
            return keyed.getKey();
        }
        return null;
    }
}
