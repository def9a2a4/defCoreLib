package anon.def9a2a4.corelib;

import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jspecify.annotations.Nullable;

/**
 * Inventory holder for the rotator angle-selection GUI. Carries the rotator head's location so clicks
 * can write the chosen target angle back to that block. Identifies our menu in click events via the
 * standard {@code getHolder() instanceof} pattern (see {@link RedstoneDynamoModeHolder}).
 */
final class RotatorModeHolder implements InventoryHolder {

    private final Location blockLocation;
    private @Nullable Inventory inventory;

    RotatorModeHolder(Location blockLocation) {
        this.blockLocation = blockLocation;
    }

    Location blockLocation() { return blockLocation; }

    void setInventory(Inventory inventory) { this.inventory = inventory; }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
