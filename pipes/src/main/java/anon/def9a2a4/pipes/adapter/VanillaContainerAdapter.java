package anon.def9a2a4.pipes.adapter;

import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;

/**
 * Generic container adapter for chests, hoppers, barrels, dispensers, etc.
 * Uses Bukkit's built-in addItem/slot iteration.
 */
public class VanillaContainerAdapter implements ContainerAdapter {

    @Override
    public boolean canReceive(Block block) {
        return block.getState() instanceof Container;
    }

    @Override
    public ItemStack insert(Block block, ItemStack item) {
        if (!(block.getState() instanceof Container container)) return item;
        HashMap<Integer, ItemStack> leftover = container.getInventory().addItem(item.clone());
        if (leftover.isEmpty()) return null;
        return leftover.values().iterator().next();
    }

    @Override
    public ItemStack peekExtract(Block block, int maxAmount) {
        if (!(block.getState() instanceof Container container)) return null;
        Inventory inv = container.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && !item.getType().isAir()) {
                ItemStack result = item.clone();
                result.setAmount(Math.min(result.getAmount(), maxAmount));
                return result;
            }
        }
        return null;
    }

    @Override
    public void commitExtract(Block block, ItemStack extracted) {
        if (!(block.getState() instanceof Container container)) return;
        Inventory inv = container.getInventory();
        ContainerAdapter.removeFromSlots(inv, extracted, 0, inv.getSize());
    }

    @Override
    public boolean hasItems(Block block) {
        if (!(block.getState() instanceof Container container)) return false;
        Inventory inv = container.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && !item.getType().isAir()) return true;
        }
        return false;
    }
}
