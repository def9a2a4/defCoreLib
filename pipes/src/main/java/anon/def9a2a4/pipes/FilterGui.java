package anon.def9a2a4.pipes;

import anon.def9a2a4.corelib.HeadUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Right-click config GUI for filter pipes: a 54-slot (double-chest) inventory whose first
 * {@code FilterSpec.slots} slots hold the filter items (real, consumed from the player), the
 * rest of the top rows are locked filler, and the bottom row carries tier-gated toggle buttons.
 * State persists via {@link PipeFilterStore}; edits refresh {@link PipeManager}'s filter cache.
 *
 * <p>Open/click/close mirror the RotationRotator angle-menu pattern.
 */
final class FilterGui implements Listener {

    private static final int SIZE = 54;

    // Bottom-row button slots (45..53).
    private static final int MODE_SLOT = 45;          // whitelist / blacklist (white / black dye)
    private static final int MATCH_SIMILAR_SLOT = 48; // "match by material" head
    private static final int MATCH_EXACT_SLOT = 49;   // "match exact item" head

    // Exact/similar toggle skins (provided by the user): selected vs unselected per mode.
    private static final String SIMILAR_SELECTED = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvM2NiODdkNDkyMTUwNGYyODU3ZjhhYzMyYTYxNjU0YjI4MWEyNDE5OTgzOGFhYTU2NWRmNTFjNGVkY2NiYjczZSJ9fX0=";
    private static final String EXACT_SELECTED = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZjczNjFhYTg4Zjc2YWZhYTk0NDZhOGRhMTU1NDI2M2YxMmFhM2ViMmUzMDJiMzlhNjFlODhiZTcxYzQ1MGJlMyJ9fX0=";
    private static final String SIMILAR_UNSELECTED = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNThmOTgyZmRiODQxM2MwNmI1MGU3N2Q2NmY2OTMzNjllODQ3YmU3NzgxYjM3MTdiOGRmOTljNThjNzg2NzQwOSJ9fX0=";
    private static final String EXACT_UNSELECTED = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNGZkMWZjMzBmYTU3ODE2M2NhNjVjNTllMmZmZGVjYWNlYjg0NmMwZjIxOWMxMmJjM2UxMDEyYjhhOWMzYmYifX19";

    private final PipesPlugin plugin;

    FilterGui(PipesPlugin plugin) {
        this.plugin = plugin;
    }

    void open(Player player, Block block, PipeVariant variant) {
        PipeVariant.FilterSpec spec = variant.filter();
        if (spec == null) return;

        PipeFilterStore.FilterData data = PipeFilterStore.read(block);
        FilterHolder holder = new FilterHolder(block.getLocation(), spec, data.blacklist(), data.exactMatch());
        Inventory inv = Bukkit.createInventory(holder, SIZE,
            Component.text("Item Filter", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        holder.setInventory(inv);

        paintDecorations(inv, holder);

        // Load saved filter items into the editable slots.
        List<ItemStack> items = data.items();
        for (int i = 0; i < spec.slots() && i < items.size(); i++) {
            inv.setItem(i, items.get(i));
        }

        player.openInventory(inv);
    }

    // ── Painting ─────────────────────────────────────────────────────────────────────────────────

    private void paintDecorations(Inventory inv, FilterHolder holder) {
        PipeVariant.FilterSpec spec = holder.spec();
        ItemStack filler = filler();
        for (int i = spec.slots(); i < SIZE; i++) {
            inv.setItem(i, filler);
        }
        repaintButtons(inv, holder);
    }

    private void repaintButtons(Inventory inv, FilterHolder holder) {
        PipeVariant.FilterSpec spec = holder.spec();
        if (spec.allowBlacklistToggle()) {
            inv.setItem(MODE_SLOT, modeButton(holder.blacklist()));
        }
        if (spec.allowExactToggle()) {
            inv.setItem(MATCH_SIMILAR_SLOT, matchSimilarButton(holder.exactMatch()));
            inv.setItem(MATCH_EXACT_SLOT, matchExactButton(holder.exactMatch()));
        }
    }

    private static ItemStack modeButton(boolean blacklist) {
        Material dye = blacklist ? Material.BLACK_DYE : Material.WHITE_DYE;
        String label = blacklist ? "Blacklist" : "Whitelist";
        NamedTextColor color = blacklist ? NamedTextColor.RED : NamedTextColor.GREEN;
        List<Component> lore = List.of(
            line(blacklist ? "Blocks the listed items; everything else passes."
                           : "Passes only the listed items."),
            line("Click to switch mode."));
        return named(new ItemStack(dye),
            Component.text(label, color).decoration(TextDecoration.ITALIC, false), lore);
    }

    private static ItemStack matchSimilarButton(boolean exactMatch) {
        boolean selected = !exactMatch;
        List<Component> lore = List.of(
            line("Match by item type (ignores name, enchants, damage)."),
            line(selected ? "✔ Active" : "Click to use"));
        return glint(HeadUtil.createHead(selected ? SIMILAR_SELECTED : SIMILAR_UNSELECTED, 1,
            Component.text("Match: Material", selected ? NamedTextColor.GREEN : NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false),
            lore, Map.of()), selected);
    }

    private static ItemStack matchExactButton(boolean exactMatch) {
        boolean selected = exactMatch;
        List<Component> lore = List.of(
            line("Match the exact item (name, enchants, components)."),
            line(selected ? "✔ Active" : "Click to use"));
        return glint(HeadUtil.createHead(selected ? EXACT_SELECTED : EXACT_UNSELECTED, 1,
            Component.text("Match: Exact", selected ? NamedTextColor.GREEN : NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false),
            lore, Map.of()), selected);
    }

    private static ItemStack filler() {
        return named(new ItemStack(Material.GRAY_STAINED_GLASS_PANE), Component.empty(), List.of());
    }

    private static ItemStack named(ItemStack it, Component name, List<Component> lore) {
        var meta = it.getItemMeta();
        meta.displayName(name);
        if (!lore.isEmpty()) meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private static ItemStack glint(ItemStack it, boolean on) {
        if (on) {
            var meta = it.getItemMeta();
            meta.setEnchantmentGlintOverride(true);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static Component line(String s) {
        return Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    // ── Slot classification ───────────────────────────────────────────────────────────────────────

    private static boolean isFilterSlot(PipeVariant.FilterSpec spec, int raw) {
        return raw >= 0 && raw < spec.slots();
    }

    // ── Events ────────────────────────────────────────────────────────────────────────────────────

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof FilterHolder holder)) return;
        PipeVariant.FilterSpec spec = holder.spec();
        int raw = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();

        // Shift-click / collect-to-cursor can scatter items into locked or button slots. Allow it only
        // when it moves an item OUT of a filter slot; otherwise cancel.
        InventoryAction action = event.getAction();
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY
                || action == InventoryAction.COLLECT_TO_CURSOR) {
            if (!(raw >= 0 && raw < topSize && isFilterSlot(spec, raw))) {
                event.setCancelled(true);
            }
            return;
        }

        // Clicks in the player's own inventory (below the top) are unrestricted.
        if (raw < 0 || raw >= topSize) return;

        if (isFilterSlot(spec, raw)) return; // editable — allow the item move

        // Otherwise it's a locked filler or a toggle button: never let items land here.
        event.setCancelled(true);

        Block block = holder.location().getBlock();
        boolean changed = false;
        if (spec.allowBlacklistToggle() && raw == MODE_SLOT) {
            holder.setBlacklist(!holder.blacklist());
            changed = true;
        } else if (spec.allowExactToggle() && raw == MATCH_SIMILAR_SLOT) {
            if (holder.exactMatch()) { holder.setExactMatch(false); changed = true; }
        } else if (spec.allowExactToggle() && raw == MATCH_EXACT_SLOT) {
            if (!holder.exactMatch()) { holder.setExactMatch(true); changed = true; }
        }

        if (changed) {
            repaintButtons(event.getInventory(), holder);
            save(holder);
            block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5),
                Sound.BLOCK_COPPER_PLACE, 0.6f, 1.4f);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof FilterHolder holder)) return;
        PipeVariant.FilterSpec spec = holder.spec();
        int topSize = event.getView().getTopInventory().getSize();
        for (int raw : event.getRawSlots()) {
            if (raw >= 0 && raw < topSize && !isFilterSlot(spec, raw)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof FilterHolder holder)) return;
        save(holder);
        // A mid-chain filter edit may need to wake an upstream extractor that slept while everything was
        // blocked; do it once on close rather than on every toggle-click save.
        PipeManager manager = plugin.getPipeManager(holder.location().getWorld());
        if (manager != null) manager.wakeAll();
    }

    /** Persist the current filter items + toggle flags to the block PDC and refresh the cache. */
    private void save(FilterHolder holder) {
        Inventory inv = holder.getInventory();
        if (inv == null) return;
        PipeVariant.FilterSpec spec = holder.spec();
        List<ItemStack> items = new ArrayList<>(spec.slots());
        for (int i = 0; i < spec.slots(); i++) {
            items.add(inv.getItem(i));
        }
        Block block = holder.location().getBlock();
        PipeFilterStore.write(block, items, holder.blacklist(), holder.exactMatch());

        PipeManager manager = plugin.getPipeManager(block.getWorld());
        if (manager != null) manager.refreshFilter(block.getLocation());
    }
}
