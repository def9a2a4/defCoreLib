package anon.def9a2a4.pipes;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapelessRecipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages conversion recipe registration. Shaped recipes are handled by CoreLib via pipes.yml.
 */
public class RecipeManager {

    private final PipesPlugin plugin;
    private final List<NamespacedKey> registeredRecipeKeys = new ArrayList<>();
    private final Map<NamespacedKey, Material> conversionRecipeCatalysts = new HashMap<>();

    public RecipeManager(PipesPlugin plugin) {
        this.plugin = plugin;
    }

    public void registerRecipes() {
        int count = registerConversionRecipes();
        if (count > 0) {
            plugin.getLogger().info("Registered " + count + " conversion recipe(s)");
        }
    }

    private int registerConversionRecipes() {
        int count = 0;
        conversionRecipeCatalysts.clear();

        ConfigurationSection section = plugin.getConfig().getConfigurationSection("conversion-recipes");
        if (section == null) return count;

        VariantRegistry variantRegistry = plugin.getVariantRegistry();

        for (String toVariantId : section.getKeys(false)) {
            ConfigurationSection recipeSection = section.getConfigurationSection(toVariantId);
            if (recipeSection == null) continue;

            String fromVariantId = recipeSection.getString("from-variant");
            String catalystStr = recipeSection.getString("catalyst");
            int resultAmount = recipeSection.getInt("result-amount", 1);

            if (fromVariantId == null || catalystStr == null) {
                plugin.getLogger().warning("Invalid conversion recipe for '" + toVariantId + "': missing from-variant or catalyst");
                continue;
            }

            Material catalyst;
            try {
                catalyst = Material.valueOf(catalystStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid catalyst material '" + catalystStr + "' for conversion recipe: " + toVariantId);
                continue;
            }

            PipeVariant fromVariant = variantRegistry.getVariant(fromVariantId);
            PipeVariant toVariant = variantRegistry.getVariant(toVariantId);

            if (fromVariant == null) {
                plugin.getLogger().warning("Source variant '" + fromVariantId + "' not found for conversion recipe: " + toVariantId);
                continue;
            }
            if (toVariant == null) {
                plugin.getLogger().warning("Target variant '" + toVariantId + "' not found for conversion recipe");
                continue;
            }

            String recipeKey = toVariantId + "_conversion";
            NamespacedKey key = new NamespacedKey(plugin, recipeKey);

            ItemStack resultItem = plugin.getPipeItem(toVariant);
            if (resultItem == null) continue;
            ItemStack result = resultItem.clone();
            result.setAmount(resultAmount);

            ItemStack sourceItem = plugin.getPipeItem(fromVariant);
            if (sourceItem == null) continue;

            ShapelessRecipe shapelessRecipe = new ShapelessRecipe(key, result);
            shapelessRecipe.addIngredient(new RecipeChoice.ExactChoice(sourceItem));
            shapelessRecipe.addIngredient(catalyst);

            try {
                Bukkit.addRecipe(shapelessRecipe);
                registeredRecipeKeys.add(key);
                conversionRecipeCatalysts.put(key, catalyst);
                count++;
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to register conversion recipe '" + recipeKey + "': " + e.getMessage());
            }
        }
        return count;
    }

    public void unregisterRecipes() {
        for (NamespacedKey key : registeredRecipeKeys) {
            Bukkit.removeRecipe(key);
        }
        registeredRecipeKeys.clear();
    }

    public void discoverAllRecipes(Player player) {
        for (NamespacedKey key : registeredRecipeKeys) {
            player.discoverRecipe(key);
        }
    }

    public void undiscoverAllRecipes(Player player) {
        for (NamespacedKey key : registeredRecipeKeys) {
            player.undiscoverRecipe(key);
        }
    }

    public List<NamespacedKey> getRecipeKeys() {
        return Collections.unmodifiableList(registeredRecipeKeys);
    }

    public boolean isConversionRecipe(NamespacedKey key) {
        return conversionRecipeCatalysts.containsKey(key);
    }

    public Material getConversionCatalyst(NamespacedKey key) {
        return conversionRecipeCatalysts.get(key);
    }
}
