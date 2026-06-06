package anon.def9a2a4.corelib;

import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jspecify.annotations.Nullable;

/**
 * Inventory holder for custom block storage.
 * Tracks the block location for PDC save-back and multi-player viewing.
 * Inventory is set after construction (circular dependency with Bukkit.createInventory).
 */
final class StorageHolder implements InventoryHolder {

    private final Location location;
    private @Nullable Inventory inventory;

    StorageHolder(Location location) {
        this.location = location;
    }

    Location location() { return location; }

    void setInventory(Inventory inventory) { this.inventory = inventory; }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
