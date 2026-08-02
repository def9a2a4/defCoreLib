package anon.def9a2a4.corelib;

import org.bukkit.GameMode;
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
     * Pick-block entry point shared by all custom-block/cart handlers. Creative mints the item
     * (delegates to {@link #pickInto}). Survival/adventure NEVER mints: it selects a matching item
     * only if the player already owns one, via a lossless swap — it never overwrites or deletes an
     * existing stack. Matching is by custom type id ({@code BLOCK_TYPE_KEY} PDC), so a plain or
     * {@code enrichDrop}-enriched copy of the same block both count as "owned".
     */
    static void pickCustom(Player player, ItemStack item, int targetSlot) {
        if (player.getGameMode() == GameMode.CREATIVE) {
            pickInto(player, item, targetSlot);   // the only path that creates an item
            return;
        }
        String typeId = CustomBlockRegistry.getItemTypeId(item);
        if (typeId == null) return;
        var inv = player.getInventory();

        // Already in the hotbar (0-8) → just select it; no item ever moves.
        for (int i = 0; i < 9; i++) {
            if (typeId.equals(CustomBlockRegistry.getItemTypeId(inv.getItem(i)))) {
                inv.setHeldItemSlot(i);
                return;
            }
        }
        // In main storage (9-35) → lossless swap into the held hotbar slot (vanilla behavior).
        // Snapshot BOTH stacks (defensive clone) before any write, then write both, so the swap
        // never re-reads a just-written slot and can never duplicate or delete an item. targetSlot
        // is guaranteed 0-8 by PlayerPickItemEvent#getTargetSlot (@Range 0..8), so no bounds guard.
        for (int i = 9; i < 36; i++) {
            if (typeId.equals(CustomBlockRegistry.getItemTypeId(inv.getItem(i)))) {
                ItemStack held  = inv.getItem(targetSlot); if (held  != null) held  = held.clone();
                ItemStack found = inv.getItem(i);          if (found != null) found = found.clone();
                inv.setItem(targetSlot, found);   // targetSlot := the owned custom item
                inv.setItem(i, held);             // slot i := whatever was held (may be null)
                inv.setHeldItemSlot(targetSlot);
                return;
            }
        }
        // Not owned → do nothing (caller already cancelled the event; matches vanilla's
        // non-creative no-op, and stops vanilla grabbing a plain lookalike).
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
