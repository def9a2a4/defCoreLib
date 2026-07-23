package anon.def9a2a4.corelib;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.AnaloguePowerable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Throttle Lever — a hand-set analog redstone source disguised as a lever.
 *
 * <p>Physically a real {@code HEAVY_WEIGHTED_PRESSURE_PLATE} placed as a bare block (identity in the
 * chunk PDC, no tile entity). A weighted pressure plate is the only vanilla block whose emitted 0-15
 * level is settable via {@link AnaloguePowerable} block data, so its own {@code power} carries the
 * signal and drives adjacent redstone directly. To stop it behaving like an actual pressure plate we
 * pin the output with a {@link BlockRedstoneEvent} override (see {@link #onRedstone}) — stepping on
 * it can't move the signal — and cancel the vanilla step for players so there's no click.
 *
 * <p>Right-click opens a 0-15 picker (number head-font icons, same as the Redstone Dynamo menu). The
 * chosen level is stored durably in a plugin-owned chunk-PDC index ({@link #LEVELS_KEY}); a spruce
 * fence "handle" display tilts from {@value MIN_ANGLE}° (level 0) to {@value MAX_ANGLE}° (level 15).
 */
final class ThrottleLever implements Listener {

    static final String BLOCK_ID = "mech:throttle_lever";
    private static final Material PLATE = Material.HEAVY_WEIGHTED_PRESSURE_PLATE;

    /** Durable per-block level store, chunk PDC: {@code "x,y,z=n;x,y,z=n;..."} (bare blocks have no
     *  tile PDC — mirrors the registry's own {@code corelib:bare_blocks} index). */
    private static final NamespacedKey LEVELS_KEY = new NamespacedKey("mech", "throttle_levels");

    // ── Menu layout: 18 slots — slot 0 info, slots 1-16 = levels 0-15, slot 17 filler ─────────────
    private static final int MENU_SIZE = 18;
    private static final int INFO_SLOT = 0;
    private static final int FIRST_BUTTON = 1; // level L lives at slot FIRST_BUTTON + L

    // ── Handle (spruce fence) angle: level 0 → MIN_ANGLE, level 15 → MAX_ANGLE ────────────────────
    private static final String HANDLE_TAG = "handle";
    private static final float MIN_ANGLE = -45f;
    private static final float MAX_ANGLE = 45f;
    private static final float HANDLE_SCALE = 0.5f;                       // 2x smaller
    private static final Vector3f TILT_AXIS = new Vector3f(1f, 0f, 0f);   // tilt about X → tip swings in ±Z
    /** Fence bottom-centre in block-model space (a block model spans [0,1]³). */
    private static final Vector3f MODEL_BASE = new Vector3f(0.5f, 0f, 0.5f);
    /** Where the fence base should sit, relative to the display's origin. DisplayUtil.spawnBlock spawns
     *  at the block CENTRE; the floor centre is half a block below, and the base is lifted 0.2 above
     *  that (Y = -0.3) so the handle clears the dressing blocks. */
    private static final Vector3f HANDLE_BASE = new Vector3f(0f, -0.3f, 0f);
    private static final AxisAngle4f R_IDENTITY = new AxisAngle4f(0f, 0f, 0f, 1f);

    /** Number head-font textures 0-15 for the menu buttons (from {@code redstonedisplays:digital_indicator}). */
    private static final String[] NUM_TEX = {
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNGIzZmE3NjE5ZTFlZTliNGU1NmFjMDViZWI3MzAxMjc2ZGE5YTkxOGQ0MjhjZDE4Mzg4MWEzMmEwN2FjZWM4YyJ9fX0=",
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOTNmYTBhMTQ4MjIxYTQ5Y2MwOGRmZjU3ZDg3YjhhZWVlZjllZjc3MTBkMzIzMTIwNTMyYmFkMjg0NjAwNmUwMSJ9fX0=",
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzY2MjRlNDlkYjg3NjUyYTA0ZDNhYWIyNmU2ZWUxMWYxYTBiY2MwZDIxOTk2NDJiYTkwNWRhNDY1MDczZmZjMyJ9fX0=",
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNDdhNTJhNGVhZWU5NzdmNGNkZGJkNmI1ODQxMGI2N2M1MzZjM2Q5MjJkMjI4ODZjZjA1ZTVhZDVhMDg3OThhNCJ9fX0=",
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzY4Nzk0NWZjMjFmODA2NjJiM2Y2MzI0MDQ2Y2NmNDJjOGYwMmRiYmIyYWQ0NDM0ZWQ2NDI4ZWYzOTUwYWExNCJ9fX0=",
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzZmODNjM2RiMTNkMjJlZmIxZTUyNDU2OGJiN2Y2OGRmZmU4ZWNjY2UxZDAwZjg3YWE1OGI1ZWZkZjg5YWI1MSJ9fX0=",
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZjA3NjBiNTMyMzAzNWQxNDg2NWVjZGY5NTVjOTIwNmNiOGRkNTBkOWU5M2Y2ZDU5NmU3MjUwZGU1NDVjZGE1ZSJ9fX0=",
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjQ2ZDlhMGZiYWZlNzcwZDgwMGZmNGNhM2RiNTVmMTU5ZGI4ZDZjOGM3ZDU4NGE0NTg5NjAxMDM2NWM0MDZlZSJ9fX0=",
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZmRiZGFjM2VjYzhmMDUzOTMyYTE3MDc2ZDVkNTM0MjE0N2YxODcyYzk3ODMwZmQ0NWY4YzUxMzc0ZjEwNGQ5ZCJ9fX0=",
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvY2I0Y2E3YjI1M2NhZGNhNGJlZDI2NzQxNTA4MjRjMjVkZTg2ZDI0ZTQ2NDY3MDU5MGIwNzYyNjA4ZmYzNTkwMCJ9fX0=",
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGU4ZjI1NzdkYzI5YThhNmM4NGEyNjkwYzE1NGY2Nzc0YmZhZmZiYTQzMTBhNDk1YjJmYWQ2Mzk1MDZiY2ZhNyJ9fX0=",
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMmExMWQ5MjkxMmJhYTIyNDdlNDFhMzFkN2JiMWQyYzE2MzVhOGVkZTA3ODkzYWFhNjE1NjBlODA2OGFjMDIwMyJ9fX0=",
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZDcxOGFhYWFlOGVjNTk2NzdjYWIyYWI2NGJkMTcxZjdkZWVmNGU0OTkwYjlmZDBkOGQxZWEzMDc3NTgyOWExNiJ9fX0=",
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzEwYzExOGQxYTUxYjg1MDliNGU1MjUwNjhjNTcwYzIxNjhhNTY4MTE1M2VhYmM2NDdhNWIxMzJiMjM3ZjM5MSJ9fX0=",
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNWE5OTBkNjEzYmE1NTNkZGM1NTAxZTA0MzZiYWFiYzE3Y2UyMmViNGRjNjU2ZDAxZTc3NzUxOWY4YzlhZjIzYSJ9fX0=",
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTNlNWEwZDIzMmJlZGUzMDI1M2NkYzMxN2FiMTIxYTc3N2Y5Yjg2NDNiZWVkZThlOGNhOGIzYjA1NDQ3NDZiMyJ9fX0=",
    };

    private final CustomBlockRegistry registry;
    /** Runtime cache of each loaded lever's level, keyed by location — the fast path for the frequent
     *  {@link #onRedstone} veto (rebuilt from the chunk PDC on load). */
    private final Map<CustomBlockRegistry.LocationKey, Integer> levelCache = new HashMap<>();

    ThrottleLever(CustomBlockRegistry registry) {
        this.registry = registry;
    }

    void register() {
        CustomHeadBlock block = registry.getType(BLOCK_ID);
        if (block == null) {
            registry.getPlugin().getLogger().warning(
                    "ThrottleLever: block '" + BLOCK_ID + "' not found — skipping overlay");
            return;
        }
        registry.register(block.toBuilder()
            .drillable(false)
            .onInteract(this::onInteract)
            .onChunkLoad((b, state) -> onLoad(b)) // also fires on initial placement
            .onBlockRemoved((b, state) -> forget(b))
            .build());
        registry.getPlugin().getServer().getPluginManager().registerEvents(this, registry.getPlugin());
    }

    // ── Load / unload lifecycle ───────────────────────────────────────────────────────────────────

    private void onLoad(Block b) {
        int level = readLevel(b);
        levelCache.put(CustomBlockRegistry.LocationKey.of(b), level);
        // Defer to next tick: displays are (re)spawned by applyConfig around this callback, and we'd
        // rather not drive a physics update mid chunk-load. The plate's power persists with the block,
        // so only re-assert it if it actually drifted (e.g. an entity was on the plate at unload).
        Bukkit.getScheduler().runTask(registry.getPlugin(), () -> {
            if (!isThrottle(b)) return;
            if (!(b.getBlockData() instanceof AnaloguePowerable ap) || ap.getPower() != level) {
                applyPower(b, level);
            }
            updateHandle(b, level);
        });
    }

    private void forget(Block b) {
        levelCache.remove(CustomBlockRegistry.LocationKey.of(b));
        removeCell(b);
    }

    // ── Emission: pin the plate's output so it never behaves like a real pressure plate ───────────

    /** Veto any change to a throttle lever's redstone output, forcing it back to the configured level.
     *  Fires for every redstone change in the world, so bail on the material check before anything
     *  costlier. */
    @EventHandler
    public void onRedstone(BlockRedstoneEvent event) {
        Block b = event.getBlock();
        if (b.getType() != PLATE) return;
        var key = CustomBlockRegistry.LocationKey.of(b);
        Integer level = levelCache.get(key);
        if (level == null) {
            if (!isThrottle(b)) return; // a vanilla weighted plate, not ours
            level = readLevel(b);
            levelCache.put(key, level);
        }
        event.setNewCurrent(level);
    }

    /** Suppress the vanilla plate trigger (click sound + press) when a player steps on it. Mobs/items
     *  can't be silenced this way, but the {@link #onRedstone} veto still keeps the signal pinned. */
    @EventHandler(ignoreCancelled = true)
    public void onStep(PlayerInteractEvent event) {
        if (event.getAction() != Action.PHYSICAL) return;
        Block b = event.getClickedBlock();
        if (b == null || b.getType() != PLATE) return;
        if (levelCache.get(CustomBlockRegistry.LocationKey.of(b)) == null && !isThrottle(b)) return;
        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
    }

    private void applyPower(Block b, int level) {
        BlockData data = b.getBlockData();
        if (data instanceof AnaloguePowerable ap) {
            ap.setPower(clamp(level));
            b.setBlockData(data, true); // physics=true → redstone propagates; the veto keeps it pinned
        }
    }

    // ── Interaction: right-click → level picker ───────────────────────────────────────────────────

    private boolean onInteract(Block b, PlayerInteractEvent event) {
        openMenu(event.getPlayer(), b);
        return true; // consume the event
    }

    private void openMenu(Player player, Block b) {
        ThrottleLeverHolder holder = new ThrottleLeverHolder(b.getLocation());
        Inventory inv = Bukkit.createInventory(holder, MENU_SIZE,
                Component.text("Throttle Lever", NamedTextColor.DARK_RED));
        holder.setInventory(inv);
        populate(inv, levelOf(b));
        player.openInventory(inv);
    }

    private void populate(Inventory inv, int current) {
        ItemStack filler = named(new ItemStack(Material.GRAY_STAINED_GLASS_PANE), Component.empty(), List.of());
        inv.setItem(MENU_SIZE - 1, filler);
        inv.setItem(INFO_SLOT, named(new ItemStack(Material.REDSTONE_TORCH),
                Component.text("Signal Strength", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
                List.of(text("Pick the output strength (0-15).", NamedTextColor.GRAY),
                        text("Powers adjacent redstone directly.", NamedTextColor.GRAY),
                        text("Ignores footsteps.", NamedTextColor.DARK_GRAY))));
        for (int level = 0; level <= 15; level++) {
            inv.setItem(FIRST_BUTTON + level, levelButton(level, level == current));
        }
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ThrottleLeverHolder holder)) return;
        event.setCancelled(true); // read-only menu
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Block b = holder.blockLocation().getBlock();
        if (!isThrottle(b)) { player.closeInventory(); return; } // block gone

        int level = event.getRawSlot() - FIRST_BUTTON;
        if (level < 0 || level > 15) return;

        setLevel(b, level);
        b.getWorld().playSound(b.getLocation().add(0.5, 0.5, 0.5),
                Sound.BLOCK_LEVER_CLICK, 0.6f, 0.7f + level * 0.04f);
        populate(event.getInventory(), level); // refresh highlight in place
    }

    private void setLevel(Block b, int level) {
        level = clamp(level);
        writeLevel(b, level);
        levelCache.put(CustomBlockRegistry.LocationKey.of(b), level);
        applyPower(b, level);
        updateHandle(b, level);
    }

    // ── Handle display: tilt the spruce fence to reflect the level ────────────────────────────────

    private void updateHandle(Block b, int level) {
        CustomHeadBlock type = registry.getType(BLOCK_ID);
        if (type == null) return;
        String prefix = DisplayUtil.blockTagPrefix(type.namespace(), type.typeId(), b.getLocation());
        String handleTag = DisplayUtil.blockTag(type.namespace(), type.typeId(), b.getLocation(), HANDLE_TAG);
        Transformation transform = handleTransform(level);
        for (Display d : DisplayUtil.findByTag(b.getLocation(), prefix, 1.5)) {
            if (d.getScoreboardTags().contains(handleTag)) {
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(3); // brief smooth swing to the new angle
                d.setTransformation(transform);
                break;
            }
        }
    }

    /** Tilt the fence about its base by the level's angle, at {@link #HANDLE_SCALE}. The display's
     *  origin is the block CENTRE (DisplayUtil.spawnBlock) and a BlockDisplay renders its [0,1]³ model
     *  from there, so we translate by {@code HANDLE_BASE − R·(scale·MODEL_BASE)} to pin the fence base
     *  to the lifted floor centre while the handle swings. */
    private static Transformation handleTransform(int level) {
        float t = clamp(level) / 15f;
        float rad = (float) Math.toRadians(MIN_ANGLE + (MAX_ANGLE - MIN_ANGLE) * t);
        AxisAngle4f r = new AxisAngle4f(rad, TILT_AXIS.x, TILT_AXIS.y, TILT_AXIS.z);
        Vector3f scaledBase = new Vector3f(MODEL_BASE).mul(HANDLE_SCALE);
        Vector3f rsb = r.transform(scaledBase); // mutates → R·(scale·MODEL_BASE)
        Vector3f translation = new Vector3f(HANDLE_BASE).sub(rsb);
        return new Transformation(translation, r,
                new Vector3f(HANDLE_SCALE, HANDLE_SCALE, HANDLE_SCALE), R_IDENTITY);
    }

    // ── Durable per-block level: plugin-owned chunk PDC "x,y,z=n;" index ──────────────────────────

    private int levelOf(Block b) {
        Integer cached = levelCache.get(CustomBlockRegistry.LocationKey.of(b));
        return cached != null ? cached : readLevel(b);
    }

    private static String cellKey(Block b) {
        return b.getX() + "," + b.getY() + "," + b.getZ();
    }

    private int readLevel(Block b) {
        String s = b.getChunk().getPersistentDataContainer().get(LEVELS_KEY, PersistentDataType.STRING);
        if (s == null) return 0;
        String cell = cellKey(b);
        for (String part : s.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(cell)) {
                try { return clamp(Integer.parseInt(part.substring(eq + 1))); }
                catch (NumberFormatException e) { return 0; }
            }
        }
        return 0;
    }

    private void writeLevel(Block b, int level) {
        putCell(b, level);
    }

    private void removeCell(Block b) {
        putCell(b, null);
    }

    /** Insert/replace ({@code level != null}) or drop ({@code level == null}) this block's cell in the
     *  chunk-PDC index, preserving every other lever's entry in the chunk. */
    private void putCell(Block b, Integer level) {
        var pdc = b.getChunk().getPersistentDataContainer();
        String existing = pdc.get(LEVELS_KEY, PersistentDataType.STRING);
        String cell = cellKey(b);
        StringBuilder sb = new StringBuilder();
        boolean replaced = false;
        if (existing != null) {
            for (String part : existing.split(";")) {
                if (part.isEmpty()) continue;
                int eq = part.indexOf('=');
                String key = eq > 0 ? part.substring(0, eq) : part;
                if (key.equals(cell)) {
                    replaced = true;
                    if (level != null) sb.append(cell).append('=').append(clamp(level)).append(';');
                } else {
                    sb.append(part).append(';');
                }
            }
        }
        if (!replaced && level != null) sb.append(cell).append('=').append(clamp(level)).append(';');
        if (sb.length() == 0) pdc.remove(LEVELS_KEY);
        else pdc.set(LEVELS_KEY, PersistentDataType.STRING, sb.toString());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────────

    private boolean isThrottle(Block b) {
        CustomHeadBlock type = registry.getTypeFromBlock(b);
        return type != null && BLOCK_ID.equals(type.fullId());
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(15, v));
    }

    private static ItemStack levelButton(int level, boolean selected) {
        Component name = Component.text(level == 0 ? "Off" : "Signal " + level,
                selected ? NamedTextColor.GREEN : NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false);
        List<Component> lore = List.of(text(selected ? "✔ Selected" : "Click to set",
                selected ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        ItemStack head = HeadUtil.createHead(NUM_TEX[clamp(level)], 1, name, lore, Map.of());
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
