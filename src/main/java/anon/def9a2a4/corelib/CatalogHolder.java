package anon.def9a2a4.corelib;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Inventory holder for the reusable item catalog GUI (opened via {@code /defcorelib catalog}).
 *
 * <p>Carries both the <b>context</b> needed to re-open this exact view (its {@link View}, category
 * {@code path}, optional {@code search} query, {@code page}, and {@code detailId}) and a {@code parent}
 * pointer forming a navigation stack for the Back button. Paging re-opens with the same {@code parent};
 * drilling in sets the new view's parent to the holder it came from.
 *
 * <p>Per-slot click actions are filled in while the inventory is populated: {@link #branchSlots} open a
 * deeper category tree, {@link #leafSlots} open a filtered item list, {@link #itemSlots} open an item's
 * detail (or give it on an admin right-click), and {@link #drillSlots} open a recipe ingredient's detail.
 */
final class CatalogHolder implements InventoryHolder {

    enum View { TREE, ITEMS, DETAIL }

    final View view;
    final String path;                 // TREE: level being browsed; ITEMS: category prefix ("" = all)
    final @Nullable String search;     // ITEMS: search query, or null
    final int page;
    final @Nullable String detailId;   // DETAIL: fullId shown
    final @Nullable CatalogHolder parent;

    // slot → action payloads (filled during populate)
    final Map<Integer, String> branchSlots = new HashMap<>(); // → category path to open as a deeper TREE
    final Map<Integer, String> leafSlots   = new HashMap<>(); // → category path to open as ITEMS
    final Map<Integer, String> itemSlots   = new HashMap<>(); // → fullId (left: detail, right: give)
    final Map<Integer, String> drillSlots  = new HashMap<>(); // → fullId (detail of a recipe ingredient)

    // nav-bar slots (−1 when absent)
    int prevSlot = -1, nextSlot = -1, backSlot = -1, closeSlot = -1, searchSlot = -1;

    private @Nullable Inventory inventory;

    CatalogHolder(View view, String path, @Nullable String search, int page,
                  @Nullable String detailId, @Nullable CatalogHolder parent) {
        this.view = view;
        this.path = path;
        this.search = search;
        this.page = page;
        this.detailId = detailId;
        this.parent = parent;
    }

    void setInventory(Inventory inventory) { this.inventory = inventory; }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
