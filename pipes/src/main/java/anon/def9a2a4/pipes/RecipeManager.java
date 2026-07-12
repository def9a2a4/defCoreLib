package anon.def9a2a4.pipes;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages recipe registration, unregistration, and player discovery.
 */
public class RecipeManager {

    private final PipesPlugin plugin;
    private final List<NamespacedKey> registeredRecipeKeys = new ArrayList<>();
    private final List<ConversionRecipeDefinition> conversionRecipes = new ArrayList<>();
    // Maps recipe key to the catalyst material that should remain after crafting
    private final Map<NamespacedKey, Material> conversionRecipeCatalysts = new HashMap<>();

    public RecipeManager(PipesPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Register all pipe recipes with Bukkit.
     */
    public void registerRecipes() {
        int shapedCount = registerShapedRecipes();
        int conversionCount = registerConversionRecipes();
        int total = shapedCount + conversionCount;
        if (total > 0) {
            plugin.getLogger().info("Registered " + total + " recipes (" + shapedCount + " shaped, " + conversionCount + " conversion)");
        }
    }

    private int registerShapedRecipes() {
        int count = 0;
        ConfigurationSection variantsSection = plugin.getConfig().getConfigurationSection("variants");
        if (variantsSection == null) return count;

        VariantRegistry variantRegistry = plugin.getVariantRegistry();

        for (PipeVariant variant : variantRegistry.getAllVariants()) {
            String variantId = variant.getId();
            ConfigurationSection variantSection = variantsSection.getConfigurationSection(variantId);
            if (variantSection == null) continue;

            List<RecipeDefinition> recipes = variantRegistry.parseRecipes(variantId, variantSection);
            ItemStack resultItem = plugin.getPipeItem(variant);
            if (resultItem == null) continue;

            // Corner pipes only register the first recipe
            if (variant.getBehaviorType() == BehaviorType.CORNER && recipes.size() > 1) {
                recipes = List.of(recipes.get(0));
            }

            for (RecipeDefinition recipe : recipes) {
                NamespacedKey recipeKey = new NamespacedKey(plugin, recipe.key());

                ItemStack result = resultItem.clone();
                result.setAmount(recipe.resultAmount());

                ShapedRecipe shapedRecipe = new ShapedRecipe(recipeKey, result);
                shapedRecipe.shape(recipe.shape());

                for (Map.Entry<Character, Material> entry : recipe.ingredients().entrySet()) {
                    shapedRecipe.setIngredient(entry.getKey(), entry.getValue());
                }

                try {
                    Bukkit.addRecipe(shapedRecipe);
                    registeredRecipeKeys.add(recipeKey);
                    count++;
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to register recipe '" + recipe.key() + "': " + e.getMessage());
                }
            }
        }
        return count;
    }

    private int registerConversionRecipes() {
        int count = 0;
        conversionRecipes.clear();
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
            ConversionRecipeDefinition def = new ConversionRecipeDefinition(
                    recipeKey, fromVariantId, toVariantId, catalyst, resultAmount
            );
            conversionRecipes.add(def);

            // Create shapeless recipe
            NamespacedKey key = new NamespacedKey(plugin, recipeKey);
            ItemStack resultItem = plugin.getPipeItem(toVariant);
            if (resultItem == null) continue;

            ItemStack result = resultItem.clone();
            result.setAmount(resultAmount);

            ShapelessRecipe shapelessRecipe = new ShapelessRecipe(key, result);

            // Use ExactChoice for the pipe item to match our custom item
            ItemStack sourceItem = plugin.getPipeItem(fromVariant);
            if (sourceItem == null) continue;

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

    /**
     * Unregister all previously registered recipes.
     */
    public void unregisterRecipes() {
        for (NamespacedKey key : registeredRecipeKeys) {
            Bukkit.removeRecipe(key);
        }
        registeredRecipeKeys.clear();
    }

    /**
     * Discover all pipe recipes for a player (makes them visible in recipe book).
     */
    public void discoverAllRecipes(Player player) {
        for (NamespacedKey key : registeredRecipeKeys) {
            player.discoverRecipe(key);
        }
    }

    /**
     * Undiscover all pipe recipes for a player (hides them from recipe book).
     */
    public void undiscoverAllRecipes(Player player) {
        for (NamespacedKey key : registeredRecipeKeys) {
            player.undiscoverRecipe(key);
        }
    }

    /**
     * Check if a player has discovered all pipe recipes.
     */
    public boolean hasDiscoveredAllRecipes(Player player) {
        for (NamespacedKey key : registeredRecipeKeys) {
            if (!player.hasDiscoveredRecipe(key)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Get an unmodifiable list of all registered recipe keys.
     */
    public List<NamespacedKey> getRecipeKeys() {
        return Collections.unmodifiableList(registeredRecipeKeys);
    }

    /**
     * Check if a recipe key is a conversion recipe (has a catalyst that should remain).
     */
    public boolean isConversionRecipe(NamespacedKey key) {
        return conversionRecipeCatalysts.containsKey(key);
    }

    /**
     * Get the catalyst material for a conversion recipe.
     * Returns null if the recipe is not a conversion recipe.
     */
    public Material getConversionCatalyst(NamespacedKey key) {
        return conversionRecipeCatalysts.get(key);
    }
}
