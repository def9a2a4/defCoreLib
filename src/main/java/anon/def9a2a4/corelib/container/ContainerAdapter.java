package anon.def9a2a4.corelib.container;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public interface ContainerAdapter {

    boolean canReceive(Block block);

    /** Whether this adapter reads/writes the block's REAL tile inventory. Adapters serving a
     *  plugin-side virtual inventory (e.g. {@link RotationMachineAdapter}) override to false —
     *  the locked-container guard in {@link ContainerAdapterRegistry} only applies to real ones. */
    default boolean usesRealInventory() {
        return true;
    }

    default boolean canReceiveFrom(Block block, BlockFace approachFace) {
        return canReceive(block);
    }

    ItemStack insert(Block block, ItemStack item);

    ItemStack peekExtract(Block block, int maxAmount);

    /**
     * Peek the next extractable stack that satisfies {@code accept}, without removing it.
     * Used by filter pipes to pull only matching item types out of a container.
     * <p>The default implementation only inspects the single stack that plain
     * {@link #peekExtract(Block, int)} would return — good enough for adapters whose
     * "next extractable" is well-defined (furnace output, brewing, virtual machines) but
     * unable to skip past a non-matching first slot. Adapters over a multi-slot real
     * inventory (see {@link VanillaContainerAdapter}) override this to scan every slot.
     */
    default ItemStack peekExtract(Block block, int maxAmount, java.util.function.Predicate<ItemStack> accept) {
        ItemStack candidate = peekExtract(block, maxAmount);
        return (candidate != null && accept.test(candidate)) ? candidate : null;
    }

    void commitExtract(Block block, ItemStack extracted);

    boolean hasItems(Block block);

    static void removeFromSlots(Inventory inv, ItemStack extracted, int fromSlot, int toSlotExclusive) {
        int remaining = extracted.getAmount();
        for (int i = fromSlot; i < toSlotExclusive && remaining > 0; i++) {
            ItemStack slot = inv.getItem(i);
            if (slot == null || !slot.isSimilar(extracted)) continue;
            int take = Math.min(slot.getAmount(), remaining);
            slot.setAmount(slot.getAmount() - take);
            if (slot.getAmount() <= 0) inv.setItem(i, null);
            else inv.setItem(i, slot);
            remaining -= take;
        }
    }

    static ItemStack tryInsertSlot(Inventory inv, int slot, ItemStack item) {
        ItemStack existing = inv.getItem(slot);
        if (existing == null || existing.getType().isAir()) {
            inv.setItem(slot, item.clone());
            return null;
        }
        if (!existing.isSimilar(item)) return item;
        int space = existing.getMaxStackSize() - existing.getAmount();
        if (space <= 0) return item;
        int toAdd = Math.min(space, item.getAmount());
        existing.setAmount(existing.getAmount() + toAdd);
        inv.setItem(slot, existing);
        if (toAdd >= item.getAmount()) return null;
        ItemStack leftover = item.clone();
        leftover.setAmount(item.getAmount() - toAdd);
        return leftover;
    }
}
