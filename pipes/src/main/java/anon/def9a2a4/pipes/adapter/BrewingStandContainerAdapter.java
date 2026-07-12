package anon.def9a2a4.pipes.adapter;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BrewingStand;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.ItemStack;

/**
 * Adapter for brewing stands.
 * - Bottle slots (0-2): insertion and extraction
 * - Ingredient slot (3): insertion only
 * - Fuel slot (4): blaze powder only
 * - No extraction while brewing is active
 */
public class BrewingStandContainerAdapter implements ContainerAdapter {

    private static final int BOTTLE_SLOTS = 3;    // slots 0, 1, 2
    private static final int INGREDIENT_SLOT = 3;
    private static final int FUEL_SLOT = 4;

    @Override
    public boolean canReceive(Block block) {
        return block.getState() instanceof BrewingStand;
    }

    @Override
    public ItemStack insert(Block block, ItemStack item) {
        if (!(block.getState() instanceof BrewingStand stand)) return item;
        BrewerInventory inv = stand.getInventory();

        // Blaze powder goes to fuel slot
        if (item.getType() == Material.BLAZE_POWDER) {
            return ContainerAdapter.tryInsertSlot(inv, FUEL_SLOT, item);
        }

        // Bottles go to bottle slots (0-2)
        if (isBottle(item)) {
            ItemStack remaining = item;
            for (int i = 0; i < BOTTLE_SLOTS && remaining != null; i++) {
                remaining = ContainerAdapter.tryInsertSlot(inv, i, remaining);
            }
            return remaining;
        }

        // Everything else goes to ingredient slot
        return ContainerAdapter.tryInsertSlot(inv, INGREDIENT_SLOT, item);
    }

    @Override
    public ItemStack peekExtract(Block block, int maxAmount) {
        if (!(block.getState() instanceof BrewingStand stand)) return null;
        if (stand.getBrewingTime() > 0) return null; // Don't extract while brewing

        BrewerInventory inv = stand.getInventory();
        // Extract from bottle slots only
        for (int i = 0; i < BOTTLE_SLOTS; i++) {
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
        if (!(block.getState() instanceof BrewingStand stand)) return;
        ContainerAdapter.removeFromSlots(stand.getInventory(), extracted, 0, BOTTLE_SLOTS);
    }

    @Override
    public boolean hasItems(Block block) {
        if (!(block.getState() instanceof BrewingStand stand)) return false;
        if (stand.getBrewingTime() > 0) return false;
        BrewerInventory inv = stand.getInventory();
        for (int i = 0; i < BOTTLE_SLOTS; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && !item.getType().isAir()) return true;
        }
        return false;
    }

    private boolean isBottle(ItemStack item) {
        Material type = item.getType();
        return type == Material.GLASS_BOTTLE
            || type == Material.POTION
            || type == Material.SPLASH_POTION
            || type == Material.LINGERING_POTION;
    }

}
