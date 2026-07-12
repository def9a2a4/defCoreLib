package anon.def9a2a4.pipes;

import org.bukkit.Material;

import java.util.Map;

/**
 * Represents a crafting recipe definition loaded from configuration.
 */
public record RecipeDefinition(
        String key,
        String[] shape,
        Map<Character, Material> ingredients,
        int resultAmount
) {
    public RecipeDefinition {
        if (shape == null || shape.length != 3) {
            throw new IllegalArgumentException("Recipe shape must have exactly 3 rows");
        }
        if (ingredients == null || ingredients.isEmpty()) {
            throw new IllegalArgumentException("Recipe must have at least one ingredient");
        }
        if (resultAmount < 1) {
            resultAmount = 1;
        }
    }
}
