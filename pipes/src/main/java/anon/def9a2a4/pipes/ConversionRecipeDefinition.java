package anon.def9a2a4.pipes;

import org.bukkit.Material;

/**
 * Represents a shapeless conversion recipe where a pipe variant is converted
 * to another variant using a catalyst item that remains after crafting.
 */
public record ConversionRecipeDefinition(
        String key,
        String fromVariantId,
        String toVariantId,
        Material catalyst,
        int resultAmount
) {
    public ConversionRecipeDefinition {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Recipe key cannot be null or empty");
        }
        if (fromVariantId == null || fromVariantId.isEmpty()) {
            throw new IllegalArgumentException("Source variant ID cannot be null or empty");
        }
        if (toVariantId == null || toVariantId.isEmpty()) {
            throw new IllegalArgumentException("Target variant ID cannot be null or empty");
        }
        if (catalyst == null) {
            throw new IllegalArgumentException("Catalyst material cannot be null");
        }
        if (resultAmount < 1) {
            resultAmount = 1;
        }
    }
}
