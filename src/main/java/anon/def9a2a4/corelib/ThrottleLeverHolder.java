package anon.def9a2a4.corelib;

import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jspecify.annotations.Nullable;

/**
 * Inventory holder for the Throttle Lever's signal-strength picker. Carries the lever block's
 * location so clicks can write the chosen level (0-15) back to that block. Identifies our menu in
 * click events via the standard {@code getHolder() instanceof} pattern (mirrors
 * {@link RedstoneDynamoModeHolder}).
 */
final class ThrottleLeverHolder implements InventoryHolder {

    private final Location blockLocation;
    private @Nullable Inventory inventory;

    ThrottleLeverHolder(Location blockLocation) {
        this.blockLocation = blockLocation;
    }

    Location blockLocation() { return blockLocation; }

    void setInventory(Inventory inventory) { this.inventory = inventory; }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
