package anon.def9a2a4.corelib;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Skull;
import org.bukkit.block.data.Rotatable;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Chain Hoist (Create's "Rope Pulley"): a floor-placed vertical winch. It draws rotational power from
 * any adjacent shaft/gear (omni consumer) and, by spin direction, <b>lowers</b> (CW) or <b>raises</b>
 * (CCW) the platform directly beneath it — the block below plus its casing-connected blob (and any
 * authored glue) — as a moving {@link Mechanism}, exactly like {@link ExtendablePistonManager} but on
 * a fixed vertical axis and with no pole line. Travel depth is bounded by an internal <b>chain
 * reserve</b> loaded into the block: descending N cells spends N chains and pays out N blocks of rope;
 * rising refunds them.
 *
 * <p>At rest the lowered platform is just <b>real world blocks</b> at depth {@code D} plus a static
 * rope display, so only {@code D} + the reserve persist (in the hoist's skull PDC) — no live mechanism
 * is serialized, mirroring the piston landing real blocks each stroke. Carried machinery stays live in
 * transit automatically via {@code MechanismRotationDriver} (built at the assemble choke point).
 */
final class ChainHoistManager {

    static final String HOIST_ID = "mech:chain_hoist";
    private static final String MECH_TYPE = "mech:chain_hoist";
    /** Non-hoist tag family for the rope so the block-display refresh (which wipes
     *  {@code corelib:mech:chain_hoist:*} on a state change) can't delete it — mirrors the pulley strand. */
    private static final String ROPE_TYPE = "chain_strand";

    private static final NamespacedKey RESERVE_KEY = new NamespacedKey("mech", "chain_reserve");
    private static final NamespacedKey EXTENSION_KEY = new NamespacedKey("mech", "hoist_extension");

    // 1.21.9 renamed CHAIN → IRON_CHAIN; resolve by name so it compiles/runs on either API.
    private static final Material CHAIN_MATERIAL = resolveChain();

    private static Material resolveChain() {
        Material m = Material.matchMaterial("IRON_CHAIN");
        return m != null ? m : Material.matchMaterial("CHAIN");
    }

    private static final int TRIGGER_PERIOD = 4;         // ticks between idle-hoist scans
    private static final float MIN_STEP = 0.08f;         // blocks/tick floor (always progresses)
    private static final float SPEED_K = 0.5f;
    private static final int SETTLE_TICKS = 3;           // hold at target before disassembly (client lerp)
    private static final Vector3f AXIS_Y = new Vector3f(0f, 1f, 0f);

    private final JavaPlugin plugin;
    private final CustomBlockRegistry registry;
    private final RotationNetwork network;
    private final MechanismRegistry mechRegistry;
    private final GlueManager glueManager;
    private final RotationConfig config;

    private final Set<CustomBlockRegistry.LocationKey> hoists = new HashSet<>();
    private final Map<CustomBlockRegistry.LocationKey, ActiveMove> active = new HashMap<>();
    private final Map<CustomBlockRegistry.LocationKey, BlockDisplay> ropes = new HashMap<>();
    private final Set<CustomBlockRegistry.LocationKey> warned = new HashSet<>();
    private int tickCounter = 0;

    ChainHoistManager(JavaPlugin plugin, CustomBlockRegistry registry, RotationNetwork network,
                      MechanismRegistry mechRegistry, GlueManager glueManager, RotationConfig config) {
        this.plugin = plugin;
        this.registry = registry;
        this.network = network;
        this.mechRegistry = mechRegistry;
        this.glueManager = glueManager;
        this.config = config;
    }

    void register() {
        CustomHeadBlock type = registry.getType(HOIST_ID);
        if (type == null) {
            plugin.getLogger().warning("ChainHoist: block '" + HOIST_ID + "' not found — skipping");
            return;
        }
        registry.register(type.toBuilder()
            .drillable(false)
            .cancelPistons(true)
            .reactsToNeighbors(true)
            .onNeighborChange((b, face) -> recalcIfNode(b))
            .onInteract(this::handleInteract)
            .onChunkLoad((b, state) -> {
                // Omni consumer on Y: draws power from the first aligned shaft on any face (like the piston core).
                network.addNode(b, HOIST_ID, RotationNetwork.Axis.Y, RotationNetwork.NodeRole.CONSUMER,
                    config.getPower("chain_hoist", 1), false, true, null);
                hoists.add(CustomBlockRegistry.LocationKey.of(b));
                setSpin(b, false);   // normalise to idle (a crash mid-move could leave 'spinning' persisted)
                if (readExtension(b) > 0) rebuildRope(b, readExtension(b));   // re-render the resting rope
            })
            .onChunkUnload(this::forget)
            .onBlockRemoved((b, state) -> onRemoved(b))
            .onBlockPlaced((b, state) -> snapFloorRotation(b))
            .build());

        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** Snap the floor head's 16-way skull yaw to grid-aligned NORTH (display entities are world-framed). */
    private static void snapFloorRotation(Block b) {
        if (b.getType() == Material.PLAYER_HEAD && b.getBlockData() instanceof Rotatable r) {
            r.setRotation(BlockFace.NORTH);
            b.setBlockData(r, false);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Chain-reserve interaction (deposit / withdraw)
    // ──────────────────────────────────────────────────────────────────────

    private boolean handleInteract(Block b, PlayerInteractEvent event) {
        var player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (RotationBlocks.isWrench(hand)) {
            event.setCancelled(true);
            RotationBlocks.debugInteract(b, event, network, registry);
            player.sendMessage(Component.text("Chain reserve: " + readReserve(b) + "/" + config.chainHoistMaxReserve
                + " | extension " + readExtension(b), NamedTextColor.GRAY));
            return true;
        }
        // Sneak-right → withdraw the whole reserve to the player.
        if (player.isSneaking()) {
            int reserve = readReserve(b);
            if (reserve <= 0) return false;   // nothing to take — let other handlers run
            event.setCancelled(true);
            writeReserve(b, 0);
            if (player.getGameMode() != GameMode.CREATIVE) {
                player.getInventory().addItem(new ItemStack(CHAIN_MATERIAL, reserve));
            }
            player.sendActionBar(Component.text("Withdrew " + reserve + " chains", NamedTextColor.YELLOW));
            return true;
        }
        // Right-click with chains → deposit up to the cap.
        if (hand.getType() != CHAIN_MATERIAL) return false;   // not depositing — let placement proceed
        event.setCancelled(true);
        int reserve = readReserve(b);
        int space = config.chainHoistMaxReserve - reserve;
        if (space <= 0) {
            player.sendActionBar(Component.text("Reserve full (" + reserve + ")", NamedTextColor.RED));
            return true;
        }
        int add = Math.min(space, hand.getAmount());
        writeReserve(b, reserve + add);
        if (player.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - add);
        player.sendActionBar(Component.text("Chain reserve: " + (reserve + add) + "/" + config.chainHoistMaxReserve,
            NamedTextColor.AQUA));
        return true;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Tick: advance active moves, then periodically scan idle hoists
    // ──────────────────────────────────────────────────────────────────────

    private void tick() {
        if (!active.isEmpty()) {
            var it = active.values().iterator();
            while (it.hasNext()) {
                ActiveMove m = it.next();
                try {
                    if (advance(m)) { it.remove(); warned.remove(m.hoistKey); }
                } catch (Throwable t) {
                    warnOnce(m.hoistKey, "advance", t);
                    safeDisassemble(m.mech, m.hoistKey);
                    it.remove();
                    warned.remove(m.hoistKey);
                }
            }
        }
        if (++tickCounter % TRIGGER_PERIOD == 0 && !hoists.isEmpty()) {
            for (CustomBlockRegistry.LocationKey k : new ArrayList<>(hoists)) {
                if (active.containsKey(k)) continue;
                try {
                    maybeTrigger(k);
                } catch (Throwable t) {
                    warnOnce(k, "trigger", t);
                }
            }
        }
    }

    private void maybeTrigger(CustomBlockRegistry.LocationKey hoistKey) {
        if (!network.isPowered(hoistKey)) return;
        RotationNetwork.SpinDirection dir = network.getDirection(hoistKey);
        if (dir == null) return;
        Block hoist = blockOf(hoistKey);
        if (hoist == null) return;
        boolean descend = dir == RotationNetwork.SpinDirection.CW;   // CW lowers, CCW raises
        int d = readExtension(hoist);
        int chainsAvail = descend ? readReserve(hoist) : d;          // rising is capped by the current depth
        if (chainsAvail <= 0) return;

        // Platform top = the block directly under the hoist at the current depth. Its casing blob (+ any
        // authored glue) rides as one unit.
        Block seed = hoist.getRelative(0, -1 - d, 0);
        if (!MovableBlocks.isMovable(seed, registry)) return;
        List<Block> group = resolveGroup(seed);

        BlockFace moveFace = descend ? BlockFace.DOWN : BlockFace.UP;
        Set<Long> footprint = footprintKeys(group);
        int steps = clearForAll(group, footprint, moveFace, chainsAvail);
        if (steps <= 0) return;
        startMove(hoistKey, hoist, seed, group, moveFace, steps, descend, dir, d);
    }

    /** Platform = the seed's authored glue (rare — normally none) or the seed itself, expanded through casings. */
    private List<Block> resolveGroup(Block seed) {
        List<Block> base;
        List<Block> glued = glueManager.resolveStructure(new BlockAnchor(seed, () -> true));
        base = (glued != null && !glued.isEmpty()) ? glued : List.of(seed);
        return CasingExpansion.withDerived(base, registry, glueManager.maxSize());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Movement
    // ──────────────────────────────────────────────────────────────────────

    private void startMove(CustomBlockRegistry.LocationKey hoistKey, Block hoist, Block seed, List<Block> group,
                           BlockFace moveFace, int steps, boolean descend, RotationNetwork.SpinDirection spinDir,
                           int startDepth) {
        Anchor anchor = new BlockAnchor(seed, () -> !active.containsKey(hoistKey));
        final int[] glueOffsets = anchor.readOffsets();   // capture before air-out (null for a plain platform)
        final int seedDx = 0, seedDz = 0;                 // pivot is on the seed column

        Mechanism mech = mechRegistry.assembleMechanism(MECH_TYPE, group,
            seed.getLocation().add(0.5, 0, 0.5), AXIS_Y, null);
        if (mech == null) return;

        try {
            setSpin(hoist, true);
            Vector3f dir = new Vector3f(0f, descend ? -1f : 1f, 0f);
            mech.setOnDisassembled(placed -> {
                // Re-resolve any landed resolver-block displays (list order has no neighbour guarantee).
                for (Block b : placed) {
                    CustomHeadBlock t = registry.getTypeFromBlock(b);
                    if (t != null && t.displayTransformResolver() != null) {
                        registry.resolveDisplayTransforms(b, t, registry.getState(b));
                    }
                }
                // Rebind authored glue to the landed anchor (no-op when the platform had none / is non-skull).
                if (glueOffsets != null) {
                    Location p = mech.pivot();
                    new BlockAnchor(p.getWorld().getBlockAt(p.getBlockX() + seedDx, p.getBlockY(),
                        p.getBlockZ() + seedDz), () -> true).writeOffsets(glueOffsets);
                }
                // Commit the depth + reserve change and re-render the static resting rope.
                int newDepth = descend ? startDepth + steps : startDepth - steps;
                writeExtension(hoist, newDepth);
                adjustReserve(hoist, descend ? -steps : steps);
                setSpin(hoist, false);
                rebuildRope(hoist, newDepth);
            });
            Location start = mech.pivot();
            Location target = start.clone().add(0, dir.y * steps, 0);
            active.put(hoistKey, new ActiveMove(hoistKey, hoist, mech, start, target, dir, group.size(), spinDir));
        } catch (Throwable t) {
            safeDisassemble(mech, hoistKey);
            throw t;
        }
    }

    /** @return true when the move finished (disassembled) and should be dropped from {@code active}. */
    private boolean advance(ActiveMove m) {
        if (m.warmup > 0) { m.warmup--; return false; }
        if (m.settle >= 0) {
            if (m.settle-- == 0) { m.mech.disassemble(); return true; }
            return false;
        }
        Location cur = m.mech.pivot();
        // Stop at the next whole cell if power is cut or the spin flips mid-travel.
        if (!network.isPowered(m.hoistKey) || network.getDirection(m.hoistKey) != m.spinDir) {
            double progress = (cur.getY() - m.start.getY()) * m.dir.y;
            int stopped = Math.max(0, (int) Math.ceil(progress - 1e-6));
            m.target = m.start.clone().add(0, m.dir.y * stopped, 0);
        }
        double remaining = (m.target.getY() - cur.getY()) * m.dir.y;
        int[] stats = network.getNetworkStats(m.hoistKey);
        int base = config.getPower("chain_hoist", 1);
        int power = (stats != null ? stats[0] - stats[1] : 0) + base;
        float step = clamp(SPEED_K * power / Math.max(1, m.mass), MIN_STEP, (float) config.pistonMaxStep);

        if (remaining <= step + 1e-3) {
            m.mech.move(m.target, 0f);
            liveRope(m.hoist, m.target.getY());
            m.settle = SETTLE_TICKS;
            return false;
        }
        Location next = cur.clone().add(0, m.dir.y * step, 0);
        m.mech.move(next, 0f);
        liveRope(m.hoist, next.getY());
        return false;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Rope render (a stretched IRON_CHAIN block display from hoist → platform)
    // ──────────────────────────────────────────────────────────────────────

    /** Rebuild the static resting rope for depth {@code d} (removes it when {@code d <= 0}). Used on a
     *  completed move and on chunk load — clears any persisted/stale copy first, then sets the length. */
    private void rebuildRope(Block hoist, int d) {
        removeRope(hoist);
        if (d > 0) setRope(hoist, d);
    }

    /** Update the rope live to span from the hoist down to the moving platform pivot (block-centre Y).
     *  Updates the existing display's matrix in place — no per-tick respawn (which would flicker). */
    private void liveRope(Block hoist, double platformCentreY) {
        double len = (hoist.getY() + 0.5) - platformCentreY;   // hoist centre → platform centre
        if (len > 0.05) setRope(hoist, len); else removeRope(hoist);
    }

    /** Set the rope to a given length, updating the tracked display in place or spawning it if absent. */
    private void setRope(Block hoist, double length) {
        Quaternionf orient = new Quaternionf().rotationTo(0f, 1f, 0f, 0f, -1f, 0f);
        Vector3f translation = new Vector3f(0f, (float) (-length / 2.0), 0f);
        Vector3f scale = new Vector3f(1f, (float) length, 1f);
        // T·R·S·T(-0.5): the trailing -0.5 centres the BlockDisplay unit cube (renders from its MIN corner).
        Matrix4f matrix = new Matrix4f()
            .translate(translation).rotate(orient).scale(scale).translate(-0.5f, -0.5f, -0.5f);
        CustomBlockRegistry.LocationKey key = CustomBlockRegistry.LocationKey.of(hoist);
        BlockDisplay rope = ropes.get(key);
        if (rope != null && rope.isValid()) {
            rope.setTransformationMatrix(matrix);
            return;
        }
        // Anchor at the hoist block; DisplayUtil.spawnBlock adds +0.5 to reach the block centre.
        ropes.put(key, DisplayUtil.spawnBlock(hoist.getLocation(), CHAIN_MATERIAL.createBlockData(),
            matrix, ropeTag(hoist)));
    }

    private void removeRope(Block hoist) {
        CustomBlockRegistry.LocationKey key = CustomBlockRegistry.LocationKey.of(hoist);
        ropes.remove(key);
        DisplayUtil.removeByTag(hoist.getLocation(), ropeTag(hoist), 2.0);   // also clears a persisted copy
    }

    private static String ropeTag(Block hoist) {
        return DisplayUtil.blockTag("mech", ROPE_TYPE, hoist.getLocation(), null);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Obstruction / footprint (copied from ExtendablePistonManager — single-axis, local region)
    // ──────────────────────────────────────────────────────────────────────

    private int clearForAll(List<Block> moving, Set<Long> footprint, BlockFace face, int max) {
        int best = max;
        for (Block b : moving) {
            int n = 0;
            Block c = b;
            for (int i = 0; i < max; i++) {
                c = c.getRelative(face);
                if (footprint.contains(cellKey(c)) || isClear(c)) n++; else break;
            }
            if (n < best) best = n;
        }
        return best;
    }

    private static Set<Long> footprintKeys(List<Block> moving) {
        Set<Long> keys = new HashSet<>(moving.size());
        for (Block b : moving) keys.add(cellKey(b));
        return keys;
    }

    private static long cellKey(Block b) {
        return ((long) (b.getX() & 0x3FFFFFF) << 38) | ((long) (b.getZ() & 0x3FFFFFF) << 12)
             | ((long) ((b.getY() + 2048) & 0xFFF));
    }

    private static boolean isClear(Block b) {
        Material m = b.getType();
        return m.isAir() || m == Material.WATER || m == Material.LAVA;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Lifecycle / helpers
    // ──────────────────────────────────────────────────────────────────────

    private void forget(Block b) {
        CustomBlockRegistry.LocationKey k = CustomBlockRegistry.LocationKey.of(b);
        network.removeNode(k);
        hoists.remove(k);
        warned.remove(k);
        ActiveMove m = active.remove(k);
        if (m != null) safeDisassemble(m.mech, k);   // land the platform instead of orphaning it
    }

    /** Block removed (break / explosion / piston): drop the reserve, land any active platform, clear the rope. */
    private void onRemoved(Block b) {
        int reserve = readReserve(b);
        if (reserve > 0) {
            b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), new ItemStack(CHAIN_MATERIAL, reserve));
        }
        removeRope(b);
        forget(b);
    }

    private void setSpin(Block hoist, boolean spinning) {
        CustomHeadBlock t = registry.getType(HOIST_ID);
        if (t == null) return;
        String state = spinning ? "spinning" : "idle";
        registry.setState(hoist, state);
        registry.applyConfig(hoist, t, state, 0);
    }

    private void recalcIfNode(Block b) {
        CustomBlockRegistry.LocationKey k = CustomBlockRegistry.LocationKey.of(b);
        if (network.getNode(k) != null) network.recalculate(k);
    }

    // ── Skull-PDC reserve / extension counters ──

    private int readReserve(Block b) { return readInt(b, RESERVE_KEY); }
    private int readExtension(Block b) { return readInt(b, EXTENSION_KEY); }
    private void writeReserve(Block b, int v) { writeInt(b, RESERVE_KEY, v); }
    private void writeExtension(Block b, int v) { writeInt(b, EXTENSION_KEY, v); }
    private void adjustReserve(Block b, int delta) { writeReserve(b, Math.max(0, readReserve(b) + delta)); }

    private static int readInt(Block b, NamespacedKey key) {
        if (!(b.getState() instanceof Skull skull)) return 0;
        Integer v = skull.getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    private static void writeInt(Block b, NamespacedKey key, int v) {
        if (!(b.getState() instanceof Skull skull)) return;
        skull.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, v);
        skull.update();
    }

    private static @Nullable Block blockOf(CustomBlockRegistry.LocationKey k) {
        World w = Bukkit.getWorld(k.worldId());
        return w == null ? null : w.getBlockAt(k.x(), k.y(), k.z());
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    private void safeDisassemble(Mechanism mech, CustomBlockRegistry.LocationKey key) {
        try { mech.disassemble(); }
        catch (Throwable t) { warnOnce(key, "disassemble", t); }
    }

    private void warnOnce(CustomBlockRegistry.LocationKey key, String phase, Throwable t) {
        if (warned.add(key)) {
            plugin.getLogger().log(Level.WARNING, "ChainHoist: " + phase + " threw at " + key + "; recovering", t);
        }
    }

    private static final class ActiveMove {
        final CustomBlockRegistry.LocationKey hoistKey;
        final Block hoist;
        final Mechanism mech;
        final Location start;
        Location target;
        final Vector3f dir;
        final int mass;
        final RotationNetwork.SpinDirection spinDir;
        int warmup = 2;
        int settle = -1;
        ActiveMove(CustomBlockRegistry.LocationKey hoistKey, Block hoist, Mechanism mech, Location start,
                   Location target, Vector3f dir, int mass, RotationNetwork.SpinDirection spinDir) {
            this.hoistKey = hoistKey;
            this.hoist = hoist;
            this.mech = mech;
            this.start = start;
            this.target = target;
            this.dir = dir;
            this.mass = mass;
            this.spinDir = spinDir;
        }
    }
}
