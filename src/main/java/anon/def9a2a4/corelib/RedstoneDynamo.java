package anon.def9a2a4.corelib;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Redstone Dynamo — a barrel-backed rotation-network sensor disguised as a head.
 *
 * <p>Physically it is a real {@code BARREL} (its facing encodes the rotation axis) wearing an
 * oversized head display; identity/state live in the barrel's tile-entity PDC (see the barrel path in
 * {@link CustomBlockRegistry}). It transmits rotation along its axis like a shaft (TRANSMITTER, no
 * power draw) and, each tick, fills its own hidden barrel so an adjacent vanilla comparator reads a
 * 0-15 signal derived from the network's power. Right-click opens a mode menu (what to measure, how to
 * scale it); a wrench shows the usual network debug info.
 */
final class RedstoneDynamo implements Listener {

    static final String BLOCK_ID = "mech:redstone_dynamo";

    /** What network quantity the dynamo reports. */
    enum Mode {
        TOTAL("Total Power", "Total rotation supplied to the network", Material.LIME_CONCRETE),
        USED("Used Power", "Power currently demanded by machines", Material.ORANGE_CONCRETE),
        UNUSED("Unused Power", "Surplus power: supply minus demand", Material.LIGHT_BLUE_CONCRETE);
        final String label, desc;
        final Material icon;
        Mode(String label, String desc, Material icon) { this.label = label; this.desc = desc; this.icon = icon; }
    }

    /** How the raw value is mapped into a 0-15 redstone level. */
    enum Scaling {
        CLAMP("Clamp 0-15", "Value capped at 15", Material.COMPARATOR),
        MOD15("Modulo 15", "Wraps around: value % 15", Material.REPEATER),
        DIV15("Divide by 15", "value / 15 — for big networks", Material.REDSTONE_TORCH);
        final String label, desc;
        final Material icon;
        Scaling(String label, String desc, Material icon) { this.label = label; this.desc = desc; this.icon = icon; }
    }

    private static final NamespacedKey MODE_KEY = new NamespacedKey("mech", "dynamo_mode");
    private static final NamespacedKey SCALING_KEY = new NamespacedKey("mech", "dynamo_scaling");

    // ── Comparator fill: represent a 0-15 level as barrel contents ──────────────────────────────
    private static final Material FILLER = Material.REDSTONE; // invisible inside the locked barrel
    private static final int BARREL_SLOTS = 27;
    private static final int FILLER_MAX = 64;
    /** For each level 0-15, the filler-item count that yields exactly that comparator signal when placed
     *  as sequential full stacks + a remainder — matching vanilla's container-fullness formula
     *  {@code signal = floor(fill/slots * 14) + (anyItems ? 1 : 0)}. */
    private static final int[] LEVEL_TO_COUNT = computeLevelCounts();

    // ── Menu layout ─────────────────────────────────────────────────────────────────────────────
    private static final int[] MODE_SLOTS = {10, 12, 14};
    private static final Mode[] MODES = {Mode.TOTAL, Mode.USED, Mode.UNUSED};
    private static final int[] SCALING_SLOTS = {19, 21, 23};
    private static final Scaling[] SCALINGS = {Scaling.CLAMP, Scaling.MOD15, Scaling.DIV15};

    private final CustomBlockRegistry registry;
    private final RotationNetwork network;
    private final Mode defaultMode;
    private final Scaling defaultScaling;
    private final int tickInterval;

    /** Last comparator level applied per block, so a tick only rewrites the barrel (and nudges the
     *  comparator) when the value actually changes. */
    private final Map<CustomBlockRegistry.LocationKey, Integer> lastLevel = new HashMap<>();

    RedstoneDynamo(CustomBlockRegistry registry, RotationNetwork network, RotationConfig config) {
        this.registry = registry;
        this.network = network;
        this.defaultMode = parseMode(config.dynamoDefaultMode, Mode.TOTAL);
        this.defaultScaling = parseScaling(config.dynamoDefaultScaling, Scaling.CLAMP);
        this.tickInterval = Math.max(1, config.dynamoTickInterval);
    }

    void register() {
        CustomHeadBlock block = registry.getType(BLOCK_ID);
        if (block == null) {
            registry.getPlugin().getLogger().warning(
                    "RotationBlocks: block '" + BLOCK_ID + "' not found — skipping overlay");
            return;
        }
        registry.register(block.toBuilder()
            .drillable(false)
            .reactsToNeighbors(true)
            .tickInterval(tickInterval)
            .onNeighborChange((b, face) -> recalcIfKnown(b))
            .onInteract(this::onInteract)
            .onTick(this::tick)
            .onChunkLoad((b, state) -> network.addNode(b, BLOCK_ID, axisOf(b),
                RotationNetwork.NodeRole.TRANSMITTER, 0, false))
            .onChunkUnload(this::forget)
            .onBlockRemoved((b, state) -> forget(b))
            .build());
        registry.getPlugin().getServer().getPluginManager().registerEvents(this, registry.getPlugin());
    }

    // ── Rotation-network lifecycle ────────────────────────────────────────────────────────────────

    private void forget(Block b) {
        var key = CustomBlockRegistry.LocationKey.of(b);
        lastLevel.remove(key);
        network.removeNode(key);
    }

    private void recalcIfKnown(Block b) {
        var key = CustomBlockRegistry.LocationKey.of(b);
        if (network.getNode(key) != null) network.recalculate(key);
    }

    private static RotationNetwork.Axis axisOf(Block b) {
        if (b.getBlockData() instanceof Directional d) return RotationNetwork.axisFromFace(d.getFacing());
        return RotationNetwork.Axis.Y;
    }

    // ── Tick: recompute the level and drive the barrel/comparator ─────────────────────────────────

    private void tick(Block b) {
        if (!(b.getState() instanceof Container container)) return; // barrel replaced/removed
        var key = CustomBlockRegistry.LocationKey.of(b);
        int[] stats = network.getNetworkStats(key);
        int level = stats == null ? 0 : scale(rawValue(stats, mode(b)), scaling(b));
        Integer prev = lastLevel.get(key);
        if (prev != null && prev == level) return;
        applyLevel(container.getInventory(), level);
        lastLevel.put(key, level);
    }

    private static int rawValue(int[] stats, Mode mode) {
        int supply = stats[0], demand = stats[1];
        return switch (mode) {
            case TOTAL -> supply;
            case USED -> demand;
            case UNUSED -> Math.max(0, supply - demand);
        };
    }

    private static int scale(int raw, Scaling scaling) {
        int v = switch (scaling) {
            case CLAMP -> raw;
            case MOD15 -> raw % 15;
            case DIV15 -> raw / 15;
        };
        return Math.max(0, Math.min(15, v));
    }

    /** Set the barrel contents so a comparator reads exactly {@code level} (0-15). */
    private static void applyLevel(Inventory inv, int level) {
        inv.clear();
        int count = LEVEL_TO_COUNT[Math.max(0, Math.min(15, level))];
        int slot = 0;
        while (count > 0 && slot < BARREL_SLOTS) {
            int put = Math.min(FILLER_MAX, count);
            inv.setItem(slot++, new ItemStack(FILLER, put));
            count -= put;
        }
    }

    private static int[] computeLevelCounts() {
        int[] counts = new int[16]; // counts[0] = 0 (empty barrel → signal 0)
        int max = BARREL_SLOTS * FILLER_MAX; // 1728
        for (int count = 1; count <= max; count++) {
            int sig = (int) Math.floor(count * 14.0 / max) + 1;
            if (sig >= 1 && sig <= 15 && counts[sig] == 0) counts[sig] = count;
        }
        return counts;
    }

    // ── Interaction: wrench → debug, otherwise → mode menu ────────────────────────────────────────

    private boolean onInteract(Block b, PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (RotationBlocks.isWrench(player.getInventory().getItemInMainHand())) {
            return RotationBlocks.debugInteract(b, event, network, registry);
        }
        // Empty/other hand → open the mode menu (return true cancels the event, so the barrel never opens)
        openMenu(player, b);
        return true;
    }

    private void openMenu(Player player, Block b) {
        RedstoneDynamoModeHolder holder = new RedstoneDynamoModeHolder(b.getLocation());
        Inventory inv = Bukkit.createInventory(holder, 27,
                Component.text("Redstone Dynamo", NamedTextColor.DARK_RED));
        holder.setInventory(inv);
        populate(inv, mode(b), scaling(b));
        player.openInventory(inv);
    }

    private void populate(Inventory inv, Mode curMode, Scaling curScaling) {
        ItemStack filler = named(new ItemStack(Material.GRAY_STAINED_GLASS_PANE), Component.empty(), List.of());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);

        inv.setItem(4, named(new ItemStack(Material.BARREL),
                Component.text("Measure & Scale", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
                List.of(text("Top row: what to measure", NamedTextColor.GRAY),
                        text("Bottom row: how to scale to 0-15", NamedTextColor.GRAY))));

        for (int i = 0; i < MODES.length; i++) {
            Mode m = MODES[i];
            inv.setItem(MODE_SLOTS[i], toggle(m.icon, m.label, m.desc, m == curMode));
        }
        for (int i = 0; i < SCALINGS.length; i++) {
            Scaling s = SCALINGS[i];
            inv.setItem(SCALING_SLOTS[i], toggle(s.icon, s.label, s.desc, s == curScaling));
        }
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof RedstoneDynamoModeHolder holder)) return;
        event.setCancelled(true); // read-only menu
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Block b = holder.blockLocation().getBlock();
        if (registry.getTypeFromBlock(b) == null) { player.closeInventory(); return; } // block gone

        int slot = event.getRawSlot();
        Mode m = modeForSlot(slot);
        Scaling s = scalingForSlot(slot);
        if (m == null && s == null) return;

        if (m != null) writePdc(b, MODE_KEY, m.name());
        if (s != null) writePdc(b, SCALING_KEY, s.name());

        // Apply the new reading immediately, then refresh the menu highlights in place.
        lastLevel.remove(CustomBlockRegistry.LocationKey.of(b));
        tick(b);
        populate(event.getInventory(), mode(b), scaling(b));
    }

    private static @Nullable Mode modeForSlot(int slot) {
        for (int i = 0; i < MODE_SLOTS.length; i++) if (MODE_SLOTS[i] == slot) return MODES[i];
        return null;
    }

    private static @Nullable Scaling scalingForSlot(int slot) {
        for (int i = 0; i < SCALING_SLOTS.length; i++) if (SCALING_SLOTS[i] == slot) return SCALINGS[i];
        return null;
    }

    // ── Per-block mode/scaling persistence (barrel PDC) ───────────────────────────────────────────

    private Mode mode(Block b) {
        return parseMode(readPdc(b, MODE_KEY), defaultMode);
    }

    private Scaling scaling(Block b) {
        return parseScaling(readPdc(b, SCALING_KEY), defaultScaling);
    }

    private static @Nullable String readPdc(Block b, NamespacedKey key) {
        if (b.getState() instanceof TileState ts) {
            return ts.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        }
        return null;
    }

    private static void writePdc(Block b, NamespacedKey key, String value) {
        if (b.getState() instanceof TileState ts) {
            ts.getPersistentDataContainer().set(key, PersistentDataType.STRING, value);
            ts.update();
        }
    }

    private static Mode parseMode(@Nullable String s, Mode def) {
        if (s == null) return def;
        try { return Mode.valueOf(s.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { return def; }
    }

    private static Scaling parseScaling(@Nullable String s, Scaling def) {
        if (s == null) return def;
        try { return Scaling.valueOf(s.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { return def; }
    }

    // ── Item helpers ──────────────────────────────────────────────────────────────────────────────

    private static ItemStack toggle(Material icon, String label, String desc, boolean selected) {
        ItemStack it = new ItemStack(selected ? icon : Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        Component name = Component.text(label, selected ? NamedTextColor.GREEN : NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false);
        List<Component> lore = List.of(
                text(desc, NamedTextColor.GRAY),
                text(selected ? "✔ Selected" : "Click to select",
                        selected ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        ItemStack out = named(it, name, lore);
        if (selected) {
            var meta = out.getItemMeta();
            meta.setEnchantmentGlintOverride(true);
            out.setItemMeta(meta);
        }
        return out;
    }

    private static ItemStack named(ItemStack it, Component name, List<Component> lore) {
        var meta = it.getItemMeta();
        meta.displayName(name);
        if (!lore.isEmpty()) meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private static Component text(String s, NamedTextColor color) {
        return Component.text(s, color).decoration(TextDecoration.ITALIC, false);
    }
}
