package anon.def9a2a4.corelib;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
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
import org.bukkit.util.Transformation;
import org.jspecify.annotations.Nullable;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

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

    /** What network quantity the dynamo reports. Menu icon: font head Σ / + / −. */
    enum Mode {
        TOTAL("Total Power", "Total rotation supplied to the network", Tex.STONE_SIGMA, Tex.RS_SIGMA),
        USED("Used Power", "Power currently demanded by machines", Tex.STONE_PLUS, Tex.RS_PLUS),
        UNUSED("Unused Power", "Surplus power: supply minus demand", Tex.STONE_MINUS, Tex.RS_MINUS);
        final String label, desc, stoneTex, selTex;
        Mode(String label, String desc, String stoneTex, String selTex) {
            this.label = label; this.desc = desc; this.stoneTex = stoneTex; this.selTex = selTex;
        }
    }

    /** How the raw value is mapped into a 0-15 redstone level. Menu icon: font head = / % / /. */
    enum Scaling {
        CLAMP("Clamp 0-15", "Value capped at 15", Tex.STONE_EQ, Tex.RS_EQ),
        MOD15("Modulo 15", "Wraps around: value % 15", Tex.STONE_PCT, Tex.RS_PCT),
        DIV15("Divide by 15", "Floor division: value / 15", Tex.STONE_SLASH, Tex.RS_SLASH);
        final String label, desc, stoneTex, selTex;
        Scaling(String label, String desc, String stoneTex, String selTex) {
            this.label = label; this.desc = desc; this.stoneTex = stoneTex; this.selTex = selTex;
        }
    }

    /** Base64 head textures for the menu buttons (from ../HeadSmith/data/heads-db-b64.csv): the
     *  stone font (Font (Cleanstone)) when unselected, the redstone-block font when selected.
     *  Held in a separate class so the enum constants above can reference them (an enum constant
     *  can't forward-reference a static field of its own class). */
    private static final class Tex {
        static final String STONE_SIGMA = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjdhMjNlNDM3MGJlMWM1NzRmNzZiOGMwMGViZjg0ZjgwZmNjNzIwMjYyN2MwYmFjMGE2MTg5MDM0MzQ2YTJkYiJ9fX0=";
        static final String STONE_PLUS  = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMGEyMWViNGM1Nzc1MDcyOWE0OGI4OGU5YmJkYjk4N2ViNjI1MGE1YmMyMTU3YjU5MzE2ZjVmMTg4N2RiNSJ9fX0=";
        static final String STONE_MINUS = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYThjNjdmZWQ3YTI0NzJiN2U5YWZkOGQ3NzJjMTNkYjdiODJjMzJjZWVmZjhkYjk3NzQ3NGMxMWU0NjExIn19fQ==";
        static final String STONE_EQ    = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNGZkMWZjMzBmYTU3ODE2M2NhNjVjNTllMmZmZGVjYWNlYjg0NmMwZjIxOWMxMmJjM2UxMDEyYjhhOWMzYmYifX19";
        static final String STONE_SLASH = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMmQ1OTNmMDk0NWNiYjg1YThlMGJlN2Q5YTUyNjAxMGVlNzc0ODEwZjJiYzQyOGNkNGEyM2U0ZDIzMmVmZjgifX19";
        static final String STONE_PCT   = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjg1YmU3NmRlMjhkZGNiMzlkMjgzZTNkNzFmNmVkNjNkZTg1NGY4Mzk2MjNlYzE4YTUzODBjODRmMWMyNWY5In19fQ==";
        static final String RS_SIGMA = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYjM3MjgxNzhhMTMzOTQzMGFhYzQ4NGZmMjI1NzNmOGVlNzRlZmY1ZGJkNGFlOTVkYjhmMmRmY2ZjMzUzYzEzMiJ9fX0=";
        static final String RS_PLUS  = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZjlmZjk4ODQyY2I2NjQwNWQyMGE4ZTZiMmVmZmFjNDYwMTBiOGY1NjAyZWE3MzI2ZDRkMTg1YjliNWRjZTA2In19fQ==";
        static final String RS_MINUS = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZjhkNWEzOGQ2YmZjYTU5Nzg2NDE3MzM2M2QyODRhOGQzMjljYWFkOTAxOGM2MzgxYjFiNDI5OWI4YjhiOTExYyJ9fX0=";
        static final String RS_EQ    = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZjczNjFhYTg4Zjc2YWZhYTk0NDZhOGRhMTU1NDI2M2YxMmFhM2ViMmUzMDJiMzlhNjFlODhiZTcxYzQ1MGJlMyJ9fX0=";
        static final String RS_SLASH = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOWJjZDllMTk5ODAzYmUzYjljZDFmZDZmMmI5M2EzYzdlYWM2MmRlMGRkYWY1ZDlkMThlZDZmNTMzNDYzNjFmMiJ9fX0=";
        static final String RS_PCT   = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOTMzZDhjZDU3NzMzYTBlZWI3ZDg4OTgwZDViZmZkMDVjNjcxNGQ3YTZjZDhjODg1OGEzNmE3YzgzOThlMjNjMSJ9fX0=";
        private Tex() {}
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
            // Orient the disguise head along the barrel's facing (like a shaft aligns to its axis).
            .displayTransformResolver((b, state, dec, idx) -> orientHead(b, dec.transform()))
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

    // ── Head orientation (points the disguise head along the barrel's facing) ──────────────────────

    private static final AxisAngle4f R_IDENTITY = new AxisAngle4f(0f, 0f, 0f, 1f);
    /** Tiny world-Y offset of the head's visual centre off exact block-centre (negative = lower).
     *  Applied identically for all six facings. Visual-tuning knob — |value| must stay under the
     *  head's slack over the barrel face (0.25·scale − 0.5; 0.0015 at the YAML scale of 2.006) or
     *  the barrel's opposite face pokes through the disguise. */
    private static final float UP_NUDGE = -0.0005f;
    /** The head item model's visible centre sits this far below the display's transform origin, in
     *  MODEL units (× scaleY ⇒ ≈ −0.5 block at scale 2.001). Rotation happens about that origin, so
     *  centring must compensate by −R·c (see {@link #orientHead}). Empirical; calibrate in-game. */
    private static final float HEAD_CENTER = -0.25f;

    /** Transform for the single fwd-art disguise head: rotate the model's +Z (front) onto the barrel's
     *  facing, then translate so the head's VISUAL centre lands at block-centre (+ tiny nudge) for
     *  every facing. The display matrix is T·R·S about the model origin, and the head's visible centre
     *  is offset {@code c = (0, HEAD_CENTER·scaleY, 0)} from that origin — so rotating swings it unless
     *  we set {@code T = (0, UP_NUDGE, 0) − R·c}, which yields worldCentre = (0, UP_NUDGE, 0) exactly.
     *  Scale is carried through from the YAML transform (authoritative). */
    private static Transformation orientHead(Block b, Transformation base) {
        BlockFace facing = (b.getBlockData() instanceof Directional d) ? d.getFacing() : BlockFace.UP;
        AxisAngle4f r = rotationForFace(facing);
        Vector3f rc = r.transform(new Vector3f(0f, HEAD_CENTER * base.getScale().y, 0f));
        Vector3f t = new Vector3f(0f, UP_NUDGE, 0f).sub(rc);
        return new Transformation(t, r, base.getScale(), R_IDENTITY);
    }

    /** Rotation mapping the model's +Z (front face) onto {@code facing}. Walls rotate about +Y only,
     *  so the art stays upright and faces out of each wall distinctly (no 180° roll — the piston's
     *  {@code faceRotation} table); UP/DOWN tip the front skyward/floorward about X. */
    private static AxisAngle4f rotationForFace(BlockFace facing) {
        float h = (float) (Math.PI / 2), p = (float) Math.PI;
        return switch (facing) {
            case SOUTH -> R_IDENTITY;                      // +Z → +Z
            case NORTH -> new AxisAngle4f(p, 0f, 1f, 0f);  // +Z → -Z
            case EAST  -> new AxisAngle4f(h, 0f, 1f, 0f);  // +Z → +X
            case WEST  -> new AxisAngle4f(-h, 0f, 1f, 0f); // +Z → -X
            case UP    -> new AxisAngle4f(-h, 1f, 0f, 0f); // +Z → +Y (front skyward, top of art → N)
            case DOWN  -> new AxisAngle4f(h, 1f, 0f, 0f);  // +Z → -Y (front floorward)
            default    -> R_IDENTITY;
        };
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
        if (level > 0 && MachineActedEvent.hasListeners()) {
            org.bukkit.Bukkit.getPluginManager().callEvent(new MachineActedEvent(b, "mech:redstone_dynamo"));
        }
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

        inv.setItem(4, named(new ItemStack(Material.COMPARATOR),
                Component.text("Measure & Scale", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
                List.of(text("Top row: what to measure", NamedTextColor.GRAY),
                        text("Bottom row: how to scale to 0-15", NamedTextColor.GRAY))));

        for (int i = 0; i < MODES.length; i++) {
            Mode m = MODES[i];
            inv.setItem(MODE_SLOTS[i], toggle(m.stoneTex, m.selTex, m.label, m.desc, m == curMode));
        }
        for (int i = 0; i < SCALINGS.length; i++) {
            Scaling s = SCALINGS[i];
            inv.setItem(SCALING_SLOTS[i], toggle(s.stoneTex, s.selTex, s.label, s.desc, s == curScaling));
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

    private static ItemStack toggle(String stoneTex, String selTex, String label, String desc, boolean selected) {
        Component name = Component.text(label, selected ? NamedTextColor.GREEN : NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false);
        List<Component> lore = List.of(
                text(desc, NamedTextColor.GRAY),
                text(selected ? "✔ Selected" : "Click to select",
                        selected ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        // Font head: stone glyph when unselected, redstone-block glyph when selected.
        ItemStack head = HeadUtil.createHead(selected ? selTex : stoneTex, 1, name, lore, Map.of());
        if (selected) {
            var meta = head.getItemMeta();
            meta.setEnchantmentGlintOverride(true);
            head.setItemMeta(meta);
        }
        return head;
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
