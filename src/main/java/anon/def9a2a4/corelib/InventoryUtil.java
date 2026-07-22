package anon.def9a2a4.corelib;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.logging.Logger;

/** Small inventory helpers shared across pick-block handlers. */
final class InventoryUtil {

    private InventoryUtil() {}

    /**
     * Serialize an inventory's contents to a Base64 string: a size-prefixed list of {@link ItemStack}s
     * via {@link org.bukkit.util.io.BukkitObjectOutputStream}. Holder-neutral — used to persist both
     * custom-block storage (block PDC) and cart inventories (entity PDC). Returns null on failure.
     */
    static String encode(Inventory inv) {
        try {
            var stream = new java.io.ByteArrayOutputStream();
            var oos = new org.bukkit.util.io.BukkitObjectOutputStream(stream);
            oos.writeInt(inv.getSize());
            for (int i = 0; i < inv.getSize(); i++) {
                oos.writeObject(inv.getItem(i));
            }
            oos.close();
            return java.util.Base64.getEncoder().encodeToString(stream.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Restore inventory contents from a string produced by {@link #encode(Inventory)}. No-op if
     * {@code data} is null. Logs (via {@code log}) and swallows any failure, leaving {@code inv} as-is.
     */
    static void decode(String data, Inventory inv, Logger log) {
        if (data == null) return;
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(data);
            var stream = new java.io.ByteArrayInputStream(bytes);
            var ois = new org.bukkit.util.io.BukkitObjectInputStream(stream);
            int size = ois.readInt();
            for (int i = 0; i < size && i < inv.getSize(); i++) {
                inv.setItem(i, (ItemStack) ois.readObject());
            }
            ois.close();
        } catch (Exception e) {
            if (log != null) log.warning("Failed to load inventory: " + e.getMessage());
        }
    }

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
