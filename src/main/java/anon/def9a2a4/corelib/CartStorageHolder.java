package anon.def9a2a4.corelib;

import org.bukkit.entity.Minecart;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jspecify.annotations.Nullable;

/**
 * Inventory holder for a custom cart's plugin-managed inventory (coal tender / blast-furnace cart).
 * Mirrors {@link StorageHolder} but keyed by the cart entity instead of a block location: the close /
 * click / drag listeners in {@link CustomCartManager} recognise a cart GUI via {@code instanceof
 * CartStorageHolder} and use the cart identity for PDC save-back. The inventory is set after
 * construction (circular dependency with {@code Bukkit.createInventory}).
 */
final class CartStorageHolder implements InventoryHolder {

    private final Minecart cart;
    private @Nullable Inventory inventory;

    CartStorageHolder(Minecart cart) {
        this.cart = cart;
    }

    Minecart cart() { return cart; }

    void setInventory(Inventory inventory) { this.inventory = inventory; }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
