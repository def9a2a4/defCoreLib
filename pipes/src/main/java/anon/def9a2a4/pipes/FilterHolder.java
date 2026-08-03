package anon.def9a2a4.pipes;

import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jspecify.annotations.Nullable;

/**
 * Inventory holder for a filter pipe's config GUI. Tracks the block location (for PDC save-back),
 * the tier's {@link PipeVariant.FilterSpec} (which slots/toggles exist), and the live toggle state
 * so button clicks can flip it before the next save. Inventory is set after construction.
 */
final class FilterHolder implements InventoryHolder {

    private final Location location;
    private final PipeVariant.FilterSpec spec;
    private boolean blacklist;
    private boolean exactMatch;
    private @Nullable Inventory inventory;

    FilterHolder(Location location, PipeVariant.FilterSpec spec, boolean blacklist, boolean exactMatch) {
        this.location = location;
        this.spec = spec;
        this.blacklist = blacklist;
        this.exactMatch = exactMatch;
    }

    Location location() { return location; }
    PipeVariant.FilterSpec spec() { return spec; }
    boolean blacklist() { return blacklist; }
    boolean exactMatch() { return exactMatch; }
    void setBlacklist(boolean v) { this.blacklist = v; }
    void setExactMatch(boolean v) { this.exactMatch = v; }

    void setInventory(Inventory inventory) { this.inventory = inventory; }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
