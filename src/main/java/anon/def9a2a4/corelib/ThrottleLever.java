package anon.def9a2a4.corelib;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.AnaloguePowerable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashMap;
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
 * <p>Right-click steps the output up by one; sneak-right-click steps it down (clamped to 0-15). The
 * chosen level is stored durably in a plugin-owned chunk-PDC index ({@link #LEVELS_KEY}); a spruce
 * fence "handle" display tilts from {@value MIN_ANGLE}° (level 0) to {@value MAX_ANGLE}° (level 15).
 */
final class ThrottleLever implements Listener {

    static final String BLOCK_ID = "mech:throttle_lever";
    private static final Material PLATE = Material.HEAVY_WEIGHTED_PRESSURE_PLATE;

    /** Durable per-block level store, chunk PDC: {@code "x,y,z=n;x,y,z=n;..."} (bare blocks have no
     *  tile PDC — mirrors the registry's own {@code corelib:bare_blocks} index). */
    private static final NamespacedKey LEVELS_KEY = new NamespacedKey("mech", "throttle_levels");

    // ── Handle (spruce fence) angle: level 0 → MIN_ANGLE, level 15 → MAX_ANGLE ────────────────────
    private static final String HANDLE_TAG = "handle";
    private static final float MIN_ANGLE = -45f;
    private static final float MAX_ANGLE = 45f;
    private static final float HANDLE_SCALE = 0.5f;                      // 2x smaller
    private static final Vector3f TILT_AXIS = new Vector3f(1f, 0f, 0f);  // tilt about X → tip swings in ±Z
    /** Fence bottom-centre in block-model space (a block model spans [0,1]³). */
    private static final Vector3f MODEL_BASE = new Vector3f(0.5f, 0f, 0.5f);
    /** Where the fence base should sit, relative to the display's origin. DisplayUtil.spawnBlock spawns
     *  at the block CENTRE; the floor centre is half a block below, and the base is lifted 0.2 above
     *  that (Y = -0.3) so the handle clears the dressing blocks. */
    private static final Vector3f HANDLE_BASE = new Vector3f(0f, -0.3f, 0f);
    private static final AxisAngle4f R_IDENTITY = new AxisAngle4f(0f, 0f, 0f, 1f);

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
            .onChunkLoad((b, state) -> onLoad(b)) // also fires on initial placement
            // Drop the cache entry only — the durable level lives in the chunk PDC and must survive an
            // unload (unlike onBlockRemoved's forget, which deletes the PDC cell).
            .onChunkUnload(b -> levelCache.remove(CustomBlockRegistry.LocationKey.of(b)))
            .onBlockRemoved((b, state) -> forget(b))
            .build());
        // Interaction (right-click ±1) and the output pinning are handled by our own listeners rather
        // than the builder's onInteract, because CoreLibPlugin only routes SNEAK-right-clicks to
        // onInteract when a wrench is held — we need the plain sneak-click for "step down".
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

    // ── Interaction: right-click steps up, sneak-right-click steps down ───────────────────────────

    @EventHandler(ignoreCancelled = true)
    public void onRightClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        Block b = event.getClickedBlock();
        if (b == null || b.getType() != PLATE) return;
        if (levelCache.get(CustomBlockRegistry.LocationKey.of(b)) == null && !isThrottle(b)) return;

        event.setCancelled(true); // never place/interact against the lever
        int delta = event.getPlayer().isSneaking() ? -1 : +1;
        int level = clamp(levelOf(b) + delta);
        setLevel(b, level);

        Player player = event.getPlayer();
        b.getWorld().playSound(b.getLocation().add(0.5, 0.5, 0.5),
                Sound.BLOCK_LEVER_CLICK, 0.6f, 0.7f + level * 0.04f);
        player.sendActionBar(Component.text(
                "Throttle: " + level + (level == 0 ? " (off)" : ""),
                level == 0 ? NamedTextColor.GRAY : NamedTextColor.RED));
    }

    private void setLevel(Block b, int level) {
        level = clamp(level);
        writeLevel(b, level);
        levelCache.put(CustomBlockRegistry.LocationKey.of(b), level);
        applyPower(b, level);
        updateHandle(b, level);
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
    }

    // ── Physics: float the plate; a piston breaks it cleanly ──────────────────────────────────────

    /** A weighted pressure plate needs a solid support block below and pops off (dropping a vanilla
     *  plate + orphaning our displays/PDC) when it's removed. The registry's pop-off guard only covers
     *  head materials, so cancel physics for our plate to float it like a custom head. Fires for every
     *  block update in the world — bail on the material check before anything costlier. */
    @EventHandler(ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        Block b = event.getBlock();
        if (b.getType() != PLATE) return;
        if (levelCache.get(CustomBlockRegistry.LocationKey.of(b)) == null && !isThrottle(b)) return;
        event.setCancelled(true);
    }

    /** A pressure plate's DESTROY push-reaction means a piston moving into it just breaks it (it's never
     *  in {@code getBlocks()}, so CoreLibPlugin's handlePiston never sees it) — vanilla drops a raw
     *  plate and leaves our displays/PDC behind. Intercept the destination cells and break it ourselves:
     *  drop the throttle-lever item and clean up, clearing the cell so vanilla drops nothing. Only extend
     *  matters — a retract never pushes into (or pulls) a DESTROY-reaction plate.
     *
     *  <p>Runs at MONITOR/ignoreCancelled so it only fires once the push is final — a region plugin that
     *  cancels the extend at HIGHEST runs first, so we never destroy a lever whose push was blocked. The
     *  event is still dispatched before the blocks actually move, so clearing the cell here suppresses
     *  the vanilla plate drop as intended. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        BlockFace dir = event.getDirection();
        breakLever(event.getBlock().getRelative(dir)); // cell right in front of the piston head
        for (Block b : event.getBlocks()) {
            breakLever(b.getRelative(dir));            // each pushed block's destination cell
        }
    }

    private void breakLever(Block b) {
        if (!isLeverPlate(b)) return;
        CustomHeadBlock type = registry.getType(BLOCK_ID);
        if (type == null) return;
        registry.onBlockRemoved(b, type);                                  // displays + tracking + forget
        ItemStack drop = type.createItem(1);
        b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), drop);
        b.setType(Material.AIR, false);                                    // no vanilla plate drop
    }

    /** True if the block is one of our throttle-lever plates (cache fast-path, then registry lookup). */
    private boolean isLeverPlate(Block b) {
        if (b.getType() != PLATE) return false;
        return levelCache.get(CustomBlockRegistry.LocationKey.of(b)) != null || isThrottle(b);
    }

    private void applyPower(Block b, int level) {
        BlockData data = b.getBlockData();
        if (data instanceof AnaloguePowerable ap) {
            ap.setPower(clamp(level));
            b.setBlockData(data, true); // physics=true → redstone propagates; the veto keeps it pinned
        }
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
}
