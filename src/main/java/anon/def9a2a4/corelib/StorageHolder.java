package anon.def9a2a4.corelib;

import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Inventory holder for custom block storage.
 * Tracks the block location for PDC save-back and multi-player viewing.
 */
final class StorageHolder implements InventoryHolder {

    private final Location location;
    private final Inventory inventory;

    StorageHolder(Location location, Inventory inventory) {
        this.location = location;
        this.inventory = inventory;
    }

    Location location() { return location; }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
