package anon.def9a2a4.pipes.adapter;

import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Abstraction for interacting with container blocks.
 * Each implementation handles slot routing and extraction rules
 * for a specific container type (furnace, brewing stand, etc.).
 */
public interface ContainerAdapter {

    /**
     * Check if this adapter handles the given block.
     */
    boolean canReceive(Block block);

    /**
     * Check if this adapter accepts items entering from the given face.
     * Defaults to accepting from any face.
     */
    default boolean canReceiveFrom(Block block, org.bukkit.block.BlockFace approachFace) {
        return canReceive(block);
    }

    /**
     * Insert items into the container. Returns leftover items that didn't fit, or null if fully inserted.
     */
    ItemStack insert(Block block, ItemStack item);

    /**
     * Preview extraction without modifying the inventory. Returns a clone of what would be extracted.
     */
    ItemStack peekExtract(Block block, int maxAmount);

    /**
     * Actually remove items from the inventory after a confirmed delivery.
     * The extracted ItemStack should match what peekExtract returned (same type and amount).
     */
    void commitExtract(Block block, ItemStack extracted);

    /**
     * Check if the container has any extractable items.
     */
    boolean hasItems(Block block);

    /**
     * Remove items matching {@code extracted} from slots [{@code fromSlot}, {@code toSlotExclusive}).
     * Shared by adapters that extract from a contiguous slot range.
     */
    static void removeFromSlots(Inventory inv, ItemStack extracted, int fromSlot, int toSlotExclusive) {
        int remaining = extracted.getAmount();
        for (int i = fromSlot; i < toSlotExclusive && remaining > 0; i++) {
            ItemStack slot = inv.getItem(i);
            if (slot != null && slot.isSimilar(extracted)) {
                int take = Math.min(slot.getAmount(), remaining);
                slot.setAmount(slot.getAmount() - take);
                if (slot.getAmount() <= 0) {
                    inv.setItem(i, null);
                }
                remaining -= take;
            }
        }
    }

    /**
     * Try to insert {@code item} into a specific {@code slot} of {@code inv}.
     * If the slot is empty, places the item. If it contains a similar stack, merges.
     * Returns leftover items that didn't fit, or null if fully inserted.
     */
    static ItemStack tryInsertSlot(Inventory inv, int slot, ItemStack item) {
        ItemStack existing = inv.getItem(slot);
        if (existing == null || existing.getType().isAir()) {
            inv.setItem(slot, item.clone());
            return null;
        }
        if (existing.isSimilar(item)) {
            int space = existing.getMaxStackSize() - existing.getAmount();
            if (space > 0) {
                int toAdd = Math.min(space, item.getAmount());
                existing.setAmount(existing.getAmount() + toAdd);
                inv.setItem(slot, existing);
                if (toAdd >= item.getAmount()) return null;
                ItemStack leftover = item.clone();
                leftover.setAmount(item.getAmount() - toAdd);
                return leftover;
            }
        }
        return item;
    }
}
