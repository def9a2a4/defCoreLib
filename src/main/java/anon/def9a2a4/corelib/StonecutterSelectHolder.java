package anon.def9a2a4.corelib;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Inventory holder for the custom stonecutter selection GUI.
 * Used to identify our custom menus in click events (the standard Bukkit pattern).
 */
final class StonecutterSelectHolder implements InventoryHolder {

    private final String inputBlockId;
    private final List<CustomBlockRegistry.HeadStonecutterRecipe> recipes;
    private @Nullable Inventory inventory;

    StonecutterSelectHolder(String inputBlockId, List<CustomBlockRegistry.HeadStonecutterRecipe> recipes) {
        this.inputBlockId = inputBlockId;
        this.recipes = recipes;
    }

    String inputBlockId() { return inputBlockId; }
    List<CustomBlockRegistry.HeadStonecutterRecipe> recipes() { return recipes; }

    void setInventory(Inventory inventory) { this.inventory = inventory; }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
