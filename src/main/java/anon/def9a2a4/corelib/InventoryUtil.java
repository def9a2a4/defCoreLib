package anon.def9a2a4.corelib;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Small inventory helpers shared across pick-block handlers. */
final class InventoryUtil {

    private InventoryUtil() {}

    /**
     * Place {@code item} into the player's hotbar for a "pick block" action, without clobbering
     * existing items: if {@code targetSlot} is occupied, prefer an already-matching hotbar slot, else
     * the first empty hotbar slot, else overwrite the target (matches vanilla creative pick). Selects
     * the resulting slot as held.
     */
    static void pickInto(Player player, ItemStack item, int targetSlot) {
        ItemStack existing = player.getInventory().getItem(targetSlot);
        if (existing != null && existing.getType() != Material.AIR) {
            // Check if player already has this item in hotbar
            for (int i = 0; i < 9; i++) {
                ItemStack hotbar = player.getInventory().getItem(i);
                if (hotbar != null && hotbar.isSimilar(item)) {
                    player.getInventory().setHeldItemSlot(i);
                    return;
                }
            }
            // Find empty hotbar slot
            int emptySlot = -1;
            for (int i = 0; i < 9; i++) {
                ItemStack hotbar = player.getInventory().getItem(i);
                if (hotbar == null || hotbar.getType() == Material.AIR) {
                    emptySlot = i;
                    break;
                }
            }
            if (emptySlot != -1) {
                targetSlot = emptySlot;
            }
            // If no empty slot in creative, overwrite is acceptable (matches vanilla behavior)
        }

        player.getInventory().setItem(targetSlot, item);
        player.getInventory().setHeldItemSlot(targetSlot);
    }
}
