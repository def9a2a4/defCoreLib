package anon.def9a2a4.corelib;

import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jspecify.annotations.Nullable;

/**
 * Inventory holder for the Redstone Dynamo mode-selection GUI. Carries the dynamo block's location
 * so clicks can write the chosen mode/scaling back to that block. Identifies our menu in click
 * events via the standard {@code getHolder() instanceof} pattern (see {@link StonecutterSelectHolder}).
 */
final class RedstoneDynamoModeHolder implements InventoryHolder {

    private final Location blockLocation;
    private @Nullable Inventory inventory;

    RedstoneDynamoModeHolder(Location blockLocation) {
        this.blockLocation = blockLocation;
    }

    Location blockLocation() { return blockLocation; }

    void setInventory(Inventory inventory) { this.inventory = inventory; }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
