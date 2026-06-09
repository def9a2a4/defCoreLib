package anon.def9a2a4.corelib;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Loads and provides grindstone grinding recipes from YAML.
 */
final class GrindRecipes {

    private final Map<Material, ItemStack> recipes = new HashMap<>();

    GrindRecipes() {}

    /** Load recipes from a YAML input stream. */
    int load(InputStream input, Logger logger) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(input));
        List<Map<?, ?>> list = yaml.getMapList("recipes");
        int count = 0;
        for (Map<?, ?> entry : list) {
            try {
                String inputName = String.valueOf(entry.get("input")).toUpperCase();
                String outputName = String.valueOf(entry.get("output")).toUpperCase();
                int amount = entry.get("amount") instanceof Number n ? n.intValue() : 1;

                Material inputMat = Material.matchMaterial(inputName);
                Material outputMat = Material.matchMaterial(outputName);
                if (inputMat == null || outputMat == null) {
                    logger.warning("Grind recipe: unknown material in " + inputName + " → " + outputName);
                    continue;
                }
                recipes.put(inputMat, new ItemStack(outputMat, amount));
                count++;
            } catch (Exception e) {
                logger.warning("Grind recipe parse error: " + e.getMessage());
            }
        }
        return count;
    }

    /** Get the grind result for an input material, or null if no recipe. */
    @Nullable ItemStack getResult(Material input) {
        ItemStack result = recipes.get(input);
        return result != null ? result.clone() : null;
    }

    boolean hasRecipe(Material input) {
        return recipes.containsKey(input);
    }
}
