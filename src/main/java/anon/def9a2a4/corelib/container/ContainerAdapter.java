package anon.def9a2a4.corelib.container;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public interface ContainerAdapter {

    boolean canReceive(Block block);

    default boolean canReceiveFrom(Block block, BlockFace approachFace) {
        return canReceive(block);
    }

    ItemStack insert(Block block, ItemStack item);

    ItemStack peekExtract(Block block, int maxAmount);

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
