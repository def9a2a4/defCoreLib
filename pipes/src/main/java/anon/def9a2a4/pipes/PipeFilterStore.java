package anon.def9a2a4.pipes;

import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-block filter state for filter pipes, stored on the head block's PDC.
 *
 * <p>Filter items are REAL items consumed from the player into the config GUI — we keep the
 * actual stacks (with amounts) so the pipe can drop them back verbatim when broken. Matching
 * itself ignores amount: a stack passes when its type (or full identity, in exact mode) matches
 * any filter item, XORed with the whitelist/blacklist mode.
 *
 * <p>Items serialize via Paper's {@link ItemStack#serializeItemsAsBytes} into a PDC byte array;
 * the two mode flags are single PDC bytes.
 */
public final class PipeFilterStore {

    private static final NamespacedKey ITEMS_KEY = new NamespacedKey("pipes", "filter_items");
    private static final NamespacedKey BLACKLIST_KEY = new NamespacedKey("pipes", "filter_blacklist");
    private static final NamespacedKey EXACT_KEY = new NamespacedKey("pipes", "filter_exact");

    private PipeFilterStore() {}

    /** Parsed, cache-friendly filter snapshot. Immutable; the transfer loop reads {@link #test}. */
    public record FilterData(List<ItemStack> items, boolean blacklist, boolean exactMatch) {

        public static final FilterData EMPTY = new FilterData(List.of(), false, false);

        /** Whether {@code stack} is allowed through this filter. */
        public boolean test(ItemStack stack) {
            if (stack == null || stack.getType().isAir()) return false;
            boolean matches = false;
            for (ItemStack f : items) {
                if (f == null || f.getType().isAir()) continue;
                if (exactMatch ? f.isSimilar(stack) : f.getType() == stack.getType()) {
                    matches = true;
                    break;
                }
            }
            return matches != blacklist; // XOR: whitelist keeps matches, blacklist inverts
        }

        /** The stacks to drop when the pipe is broken (non-empty entries only). */
        public List<ItemStack> dropContents() {
            List<ItemStack> out = new ArrayList<>();
            for (ItemStack it : items) {
                if (it != null && !it.getType().isAir()) out.add(it);
            }
            return out;
        }
    }

    public static FilterData read(Block block) {
        if (!(block.getState() instanceof TileState tile)) return FilterData.EMPTY;
        PersistentDataContainer pdc = tile.getPersistentDataContainer();
        List<ItemStack> items = decode(pdc.get(ITEMS_KEY, PersistentDataType.BYTE_ARRAY));
        boolean blacklist = pdc.getOrDefault(BLACKLIST_KEY, PersistentDataType.BYTE, (byte) 0) != 0;
        boolean exact = pdc.getOrDefault(EXACT_KEY, PersistentDataType.BYTE, (byte) 0) != 0;
        return new FilterData(items, blacklist, exact);
    }

    public static void write(Block block, List<ItemStack> items, boolean blacklist, boolean exact) {
        if (!(block.getState() instanceof TileState tile)) return;
        PersistentDataContainer pdc = tile.getPersistentDataContainer();

        List<ItemStack> nonEmpty = new ArrayList<>();
        for (ItemStack it : items) {
            if (it != null && !it.getType().isAir()) nonEmpty.add(it);
        }
        if (nonEmpty.isEmpty()) {
            pdc.remove(ITEMS_KEY);
        } else {
            pdc.set(ITEMS_KEY, PersistentDataType.BYTE_ARRAY,
                ItemStack.serializeItemsAsBytes(nonEmpty));
        }
        pdc.set(BLACKLIST_KEY, PersistentDataType.BYTE, (byte) (blacklist ? 1 : 0));
        pdc.set(EXACT_KEY, PersistentDataType.BYTE, (byte) (exact ? 1 : 0));
        tile.update();
    }

    private static List<ItemStack> decode(byte[] raw) {
        List<ItemStack> items = new ArrayList<>();
        if (raw == null || raw.length == 0) return items;
        try {
            for (ItemStack it : ItemStack.deserializeItemsFromBytes(raw)) {
                if (it != null) items.add(it);
            }
        } catch (Exception ignored) {
            // Corrupt data: treat as no filter (nothing passes for whitelist) rather than crash.
        }
        return items;
    }
}
