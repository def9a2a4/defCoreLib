package anon.def9a2a4.pipes.adapter;

import org.bukkit.block.Block;
import org.bukkit.block.Furnace;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.ItemStack;

/**
 * Adapter for furnaces (furnace, blast furnace, smoker).
 * Matches vanilla hopper behavior:
 * - Input (slot 0): accepts smeltable items
 * - Fuel (slot 1): only tops up existing fuel stack (same type)
 * - Result (slot 2): extraction only
 */
public class FurnaceContainerAdapter implements ContainerAdapter {

    @Override
    public boolean canReceive(Block block) {
        return block.getState() instanceof Furnace;
    }

    @Override
    public ItemStack insert(Block block, ItemStack item) {
        if (!(block.getState() instanceof Furnace furnace)) return item;
        FurnaceInventory inv = furnace.getInventory();

        // Try fuel slot first — only if a matching fuel type is already there (vanilla hopper parity)
        ItemStack fuel = inv.getFuel();
        if (fuel != null && !fuel.getType().isAir() && fuel.isSimilar(item)) {
            int space = fuel.getMaxStackSize() - fuel.getAmount();
            if (space > 0) {
                int toAdd = Math.min(space, item.getAmount());
                fuel.setAmount(fuel.getAmount() + toAdd);
                inv.setFuel(fuel);
                if (toAdd >= item.getAmount()) return null;
                ItemStack leftover = item.clone();
                leftover.setAmount(item.getAmount() - toAdd);
                return leftover;
            }
        }

        // Try smelting input slot
        ItemStack smelting = inv.getSmelting();
        if (smelting == null || smelting.getType().isAir()) {
            inv.setSmelting(item.clone());
            return null;
        }
        if (smelting.isSimilar(item)) {
            int space = smelting.getMaxStackSize() - smelting.getAmount();
            if (space > 0) {
                int toAdd = Math.min(space, item.getAmount());
                smelting.setAmount(smelting.getAmount() + toAdd);
                inv.setSmelting(smelting);
                if (toAdd >= item.getAmount()) return null;
                ItemStack leftover = item.clone();
                leftover.setAmount(item.getAmount() - toAdd);
                return leftover;
            }
        }

        return item;
    }

    @Override
    public ItemStack peekExtract(Block block, int maxAmount) {
        if (!(block.getState() instanceof Furnace furnace)) return null;
        // Only extract from result slot (vanilla hopper parity)
        ItemStack result = furnace.getInventory().getResult();
        if (result == null || result.getType().isAir()) return null;
        ItemStack extracted = result.clone();
        extracted.setAmount(Math.min(extracted.getAmount(), maxAmount));
        return extracted;
    }

    @Override
    public void commitExtract(Block block, ItemStack extracted) {
        if (!(block.getState() instanceof Furnace furnace)) return;
        FurnaceInventory inv = furnace.getInventory();
        ItemStack result = inv.getResult();
        if (result == null) return;
        result.setAmount(result.getAmount() - extracted.getAmount());
        if (result.getAmount() <= 0) {
            inv.setResult(null);
        } else {
            inv.setResult(result);
        }
    }

    @Override
    public boolean hasItems(Block block) {
        if (!(block.getState() instanceof Furnace furnace)) return false;
        ItemStack result = furnace.getInventory().getResult();
        return result != null && !result.getType().isAir();
    }
}
