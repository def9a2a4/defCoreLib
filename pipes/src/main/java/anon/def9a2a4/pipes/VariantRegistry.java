package anon.def9a2a4.pipes;

import anon.def9a2a4.pipes.config.DisplayConfig;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.logging.Logger;

/**
 * Registry for pipe variants loaded from configuration.
 * Provides lookup methods by ID and PDC key.
 */
public class VariantRegistry {
    private final Plugin plugin;
    private final Logger logger;
    private final Map<String, PipeVariant> variants = new LinkedHashMap<>();
    private final Map<NamespacedKey, PipeVariant> variantsByKey = new HashMap<>();

    public VariantRegistry(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /**
     * Load variants from the configuration's variants section.
     * @param variantsSection The variants section from config.yml
     * @param displayConfig The display config for validating texture-set references
     */
    public void loadFromConfig(ConfigurationSection variantsSection, DisplayConfig displayConfig) {
        variants.clear();
        variantsByKey.clear();

        if (variantsSection == null) {
            logger.warning("No variants section found in config!");
            return;
        }

        for (String variantId : variantsSection.getKeys(false)) {
            ConfigurationSection section = variantsSection.getConfigurationSection(variantId);
            if (section == null) {
                logger.warning("Invalid variant section: " + variantId);
                continue;
            }

            try {
                PipeVariant variant = parseVariant(variantId, section, displayConfig);
                variants.put(variantId, variant);
                variantsByKey.put(variant.getPdcKey(), variant);
            } catch (Exception e) {
                logger.severe("Failed to load variant '" + variantId + "': " + e.getMessage());
            }
        }

        logger.info("Loaded " + variants.size() + " pipe variant(s)");
    }

    private PipeVariant parseVariant(String id, ConfigurationSection section, DisplayConfig displayConfig) {
        // Parse behavior type
        String behaviorStr = section.getString("behavior", "REGULAR");
        BehaviorType behavior;
        try {
            behavior = BehaviorType.valueOf(behaviorStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid behavior type: " + behaviorStr);
        }

        // Parse display name with MiniMessage
        String nameStr = section.getString("display-name", "<white>" + id);
        var displayName = MiniMessage.miniMessage().deserialize(nameStr);

        // Parse transfer settings
        int intervalTicks = section.getInt("transfer.interval-ticks", 10);
        int itemsPerTransfer = section.getInt("transfer.items-per-transfer", 1);

        // Parse texture-set reference
        String textureSetId = section.getString("texture-set");
        if (textureSetId == null || textureSetId.isEmpty()) {
            throw new IllegalArgumentException("Missing texture-set field");
        }
        if (!displayConfig.hasTextureSet(textureSetId)) {
            throw new IllegalArgumentException("Texture set not found in display.yml: " + textureSetId);
        }

        // Create namespaced key
        NamespacedKey pdcKey = new NamespacedKey(plugin, id);

        return new PipeVariant(id, behavior, displayName, intervalTicks,
                itemsPerTransfer, pdcKey, textureSetId);
    }

    /**
     * Parse recipe definitions from a variant's config section.
     */
    public List<RecipeDefinition> parseRecipes(String variantId, ConfigurationSection variantSection) {
        List<RecipeDefinition> recipes = new ArrayList<>();

        List<?> recipesList = variantSection.getList("recipes");
        if (recipesList == null) return recipes;

        int recipeIndex = 0;
        for (Object recipeObj : recipesList) {
            if (!(recipeObj instanceof Map<?, ?> recipeMap)) {
                logger.warning("Invalid recipe format for variant: " + variantId);
                continue;
            }

            try {
                RecipeDefinition recipe = parseRecipe(variantId, recipeIndex, recipeMap);
                recipes.add(recipe);
                recipeIndex++;
            } catch (Exception e) {
                logger.warning("Failed to parse recipe " + recipeIndex + " for variant '" + variantId + "': " + e.getMessage());
            }
        }

        return recipes;
    }

    @SuppressWarnings("unchecked")
    private RecipeDefinition parseRecipe(String variantId, int index, Map<?, ?> recipeMap) {
        // Parse shape
        List<?> shapeList = (List<?>) recipeMap.get("shape");
        if (shapeList == null || shapeList.size() != 3) {
            throw new IllegalArgumentException("Recipe shape must have exactly 3 rows");
        }
        String[] shape = new String[3];
        for (int i = 0; i < 3; i++) {
            shape[i] = String.valueOf(shapeList.get(i));
            // Pad to 3 characters if needed
            while (shape[i].length() < 3) {
                shape[i] += " ";
            }
        }

        // Parse ingredients
        Map<?, ?> ingredientsMap = (Map<?, ?>) recipeMap.get("ingredients");
        if (ingredientsMap == null || ingredientsMap.isEmpty()) {
            throw new IllegalArgumentException("Recipe must have at least one ingredient");
        }

        Map<Character, Material> ingredients = new HashMap<>();
        for (Map.Entry<?, ?> entry : ingredientsMap.entrySet()) {
            String keyStr = String.valueOf(entry.getKey());
            if (keyStr.length() != 1) {
                throw new IllegalArgumentException("Ingredient key must be a single character: " + keyStr);
            }
            char key = keyStr.charAt(0);

            String materialStr = String.valueOf(entry.getValue());
            Material material;
            try {
                material = Material.valueOf(materialStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid material: " + materialStr);
            }

            ingredients.put(key, material);
        }

        // Parse result amount
        int resultAmount = 1;
        if (recipeMap.containsKey("result-amount")) {
            Object amount = recipeMap.get("result-amount");
            if (amount instanceof Number) {
                resultAmount = ((Number) amount).intValue();
            }
        }

        // Generate unique recipe key
        String recipeKey = variantId + "_recipe_" + index;

        return new RecipeDefinition(recipeKey, shape, ingredients, resultAmount);
    }

    /**
     * Get a variant by its ID.
     */
    public PipeVariant getVariant(String id) {
        return variants.get(id);
    }

    /**
     * Get a variant by its PDC key.
     */
    public PipeVariant getVariantByKey(NamespacedKey key) {
        return variantsByKey.get(key);
    }

    /**
     * Get all registered variants.
     */
    public Collection<PipeVariant> getAllVariants() {
        return Collections.unmodifiableCollection(variants.values());
    }

    /**
     * Get the first registered variant as a fallback.
     */
    public PipeVariant getDefaultVariant() {
        return variants.values().stream().findFirst().orElse(null);
    }

    /**
     * Check if any variants are registered.
     */
    public boolean hasVariants() {
        return !variants.isEmpty();
    }
}
