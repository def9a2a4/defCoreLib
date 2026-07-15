package anon.def9a2a4.corelib;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Skull;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Rotatable;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.joml.Matrix4f;
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
 * any adjacent shaft/gear (omni consumer) and, by spin direction, pays its rope <b>out</b> (CW) or reels
 * it back <b>in</b> (CCW). The rope is <b>real {@code CHAIN} blocks</b> in the hoist's own column, laid
 * one per block of travel out of the chains loaded into its 5-slot storage GUI — exactly like
 * {@link ExtendablePistonManager} landing real pole blocks each stroke, but on a fixed vertical axis.
 *
 * <p>Whatever sits at the bottom of the rope rides along: if the cell under the rope holds a movable
 * block, it plus its casing-connected blob (and any authored glue) travels as a {@link Mechanism}. If
 * that cell is empty the rope still extends, so a hoist over a pit pays chain into open air until it
 * reaches something. Carried machinery stays live in transit automatically via
 * {@code MechanismRotationDriver} (built at the assemble choke point).
 *
 * <p><b>Invariant</b> — at extension {@code D} the hoist at {@code y0} owns real chains filling
 * {@code y0-1 … y0-D}, and the platform's top block, if any, sits at {@code y0-D-1}. {@code D} is
 * re-derived from where the platform actually landed on every stroke and the column is re-synced to it,
 * so a cut power feed, a crash, or a player mining the rope mid-span self-heals instead of desyncing.
 * At rest nothing but {@code D} (in the hoist's skull PDC) and real world blocks persist — no live
 * mechanism is serialized. The stretched block-display rope exists only while a platform is in transit,
 * spanning the cells whose real chains have not been laid yet.
 */
final class ChainHoistManager {

    static final String HOIST_ID = "mech:chain_hoist";
    private static final String MECH_TYPE = "mech:chain_hoist";
    /** Non-hoist tag family for the in-transit rope so the block-display refresh (which wipes
     *  {@code corelib:mech:chain_hoist:*} on a state change) can't delete it — mirrors the pulley strand. */
    private static final String ROPE_TYPE = "chain_strand";

    private static final NamespacedKey EXTENSION_KEY = new NamespacedKey("mech", "hoist_extension");

    // 1.21.9 renamed CHAIN → IRON_CHAIN; resolve by name so it compiles/runs on either API.
    private static final Material CHAIN_MATERIAL = resolveChain();

    private static Material resolveChain() {
        Material m = Material.matchMaterial("IRON_CHAIN");
        return m != null ? m : Material.matchMaterial("CHAIN");
    }

    private static final int TRIGGER_PERIOD = 4;         // ticks between idle-hoist scans (= bare-rope cadence)
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
    /** In-transit rope, one display per chain link, bottom-up. Real CHAIN blocks take over at rest. */
    private final Map<CustomBlockRegistry.LocationKey, List<BlockDisplay>> ropes = new HashMap<>();
    /** Hoists spun up by the bare-rope path (which has no mechanism to bracket the animation), and the
     *  tick each should idle again — a state change is only pushed on the transitions, not per step. */
    private final Map<CustomBlockRegistry.LocationKey, Integer> spinUntil = new HashMap<>();
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
            .stateResolver(ChainHoistManager::resolvePlacementState)
            .onNeighborChange((b, face) -> recalcIfNode(b))
            .onInteract(this::handleInteract)
            .onChunkLoad((b, state) -> {
                // In-line consumer on its own horizontal axle: a shaft runs through it, so the network
                // links it to both its ±axis neighbours and power crosses it (see getConnections).
                String s = healAxisState(b, state);
                network.addNode(b, HOIST_ID, RotationNetwork.axisFromState(s),
                    RotationNetwork.NodeRole.CONSUMER, config.getPower("chain_hoist", 1), false);
                hoists.add(CustomBlockRegistry.LocationKey.of(b));
                setSpin(b, false);   // normalise to idle (a crash mid-move could leave 'spinning' persisted)
                removeRope(b);       // the resting rope is real blocks; any display here is a crash leftover
            })
            .onChunkUnload(this::forget)
            .onBlockRemoved((b, state) -> onRemoved(b))
            .onBlockPlaced(ChainHoistManager::snapFloorRotation)
            .build());

        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /**
     * The axle runs across the placer's view: facing north/south gives an X axle, east/west a Z one.
     * The clicked face is always DOWN on a floor block and so can't carry this, and {@code onBlockPlaced}
     * runs a tick later without the player — the resolver is the only hook that sees them. Never returns
     * null: that would cancel the placement.
     */
    private static String resolvePlacementState(org.bukkit.event.block.BlockPlaceEvent event) {
        float yaw = (event.getPlayer().getLocation().getYaw() % 360f + 360f) % 360f;
        boolean alongZ = yaw >= 315f || yaw < 45f || (yaw >= 135f && yaw < 225f);   // facing south / north
        return alongZ ? "idle_x" : "idle_z";
    }

    /** Snap the floor head's 16-way skull yaw to the cardinal its axle runs on, so the head art lines
     *  up with the rod (display entities are world-framed; the vanilla skull follows the placer's look). */
    private static void snapFloorRotation(Block b, String state) {
        if (b.getType() == Material.PLAYER_HEAD && b.getBlockData() instanceof Rotatable r) {
            r.setRotation(RotationNetwork.axisFromState(state) == RotationNetwork.Axis.X
                ? BlockFace.EAST : BlockFace.NORTH);
            b.setBlockData(r, false);
        }
    }

    /**
     * v2 hoists persisted an axis-less {@code "idle"}/{@code "spinning"}, which {@link
     * RotationNetwork#axisFromState} reads as the Y axis — leaving them unpowerable by any shaft and
     * feeding {@link #setSpin} a state with no suffix to preserve. Adopt the X axle so one keeps
     * working across the upgrade instead of going inert.
     */
    private String healAxisState(Block b, @Nullable String state) {
        if (state != null && state.indexOf('_') >= 0) return state;
        CustomHeadBlock t = registry.getType(HOIST_ID);
        if (t == null) return "idle_x";
        registry.setState(b, "idle_x");
        registry.applyConfig(b, t, "idle_x", 0);
        return "idle_x";
    }

    /**
     * Wrench inspects; anything else falls through to the storage GUI (opened by the plugin's interact
     * dispatch when this returns false), which is where the chains live.
     */
    private boolean handleInteract(Block b, PlayerInteractEvent event) {
        if (!RotationBlocks.isWrench(event.getPlayer().getInventory().getItemInMainHand())) return false;
        event.setCancelled(true);
        RotationBlocks.debugInteract(b, event, network, registry);
        event.getPlayer().sendMessage(Component.text(
            "Chains: " + countChains(registry.getOrCreateStorage(b)) + " | extension " + readExtension(b),
            NamedTextColor.GRAY));
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
        expireBareSpin();
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
        int d = readExtension(hoist);
        if (dir == RotationNetwork.SpinDirection.CW) payOut(hoistKey, hoist, d, dir);
        else reelIn(hoistKey, hoist, d, dir);
    }

    /** CW: lower. Slide the hanging platform down, or — with nothing hanging — pay bare rope into the air. */
    private void payOut(CustomBlockRegistry.LocationKey hoistKey, Block hoist, int d,
                        RotationNetwork.SpinDirection spinDir) {
        Inventory inv = registry.getOrCreateStorage(hoist);
        int avail = countChains(inv);
        if (avail <= 0) return;

        // The cell under the rope: the platform's top block if something hangs there, else the next
        // cell the bare rope grows into.
        Block seed = hoist.getRelative(0, -1 - d, 0);
        if (MovableBlocks.isMovable(seed, registry)) {
            List<Block> group = resolveGroup(seed);
            Set<Long> footprint = footprintKeys(group);
            int steps = clearForAll(group, footprint, BlockFace.DOWN, avail);
            if (steps <= 0) return;
            startMove(hoistKey, hoist, seed, group, steps, true, spinDir, d);
            return;
        }
        if (!isClear(seed) || seed.getY() < hoist.getWorld().getMinHeight()) return;   // bedrock etc.
        placeChain(seed);
        consumeChains(inv, 1);
        writeExtension(hoist, d + 1);
        markBareSpin(hoist, hoistKey);
    }

    /** CCW: raise. Slide the platform back up, or reel bare rope in a cell at a time. */
    private void reelIn(CustomBlockRegistry.LocationKey hoistKey, Block hoist, int d,
                        RotationNetwork.SpinDirection spinDir) {
        if (d <= 0) return;
        Block seed = hoist.getRelative(0, -1 - d, 0);
        if (MovableBlocks.isMovable(seed, registry)) {
            List<Block> group = resolveGroup(seed);
            Set<Long> footprint = footprintKeys(group);
            addRopeCells(hoist, d, footprint);   // our own rope is not an obstruction — we reel it in
            int steps = clearForAll(group, footprint, BlockFace.UP, d);
            if (steps <= 0) return;
            startMove(hoistKey, hoist, seed, group, steps, false, spinDir, d);
            return;
        }
        // Bare rope, nothing hanging. A mined-away link just shortens the rope with no refund.
        Block bottom = hoist.getRelative(0, -d, 0);
        if (isOwnRope(bottom)) {
            bottom.setType(Material.AIR, false);
            refundChains(registry.getOrCreateStorage(hoist), hoist, 1);
        }
        writeExtension(hoist, d - 1);
        markBareSpin(hoist, hoistKey);
    }

    /** Platform = the seed's authored glue (rare — normally none) or the seed itself, expanded through casings. */
    private List<Block> resolveGroup(Block seed) {
        List<Block> glued = glueManager.resolveStructure(new BlockAnchor(seed, () -> true));
        List<Block> base = (glued != null && !glued.isEmpty()) ? glued : List.of(seed);
        return CasingExpansion.withDerived(base, registry, glueManager.maxSize());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Movement
    // ──────────────────────────────────────────────────────────────────────

    private void startMove(CustomBlockRegistry.LocationKey hoistKey, Block hoist, Block seed, List<Block> group,
                           int steps, boolean descend, RotationNetwork.SpinDirection spinDir, int startDepth) {
        Anchor anchor = new BlockAnchor(seed, () -> !active.containsKey(hoistKey));
        final int[] glueOffsets = anchor.readOffsets();   // capture before air-out (null for a plain platform)
        final int seedDx = 0, seedDz = 0;                 // pivot is on the seed column

        Mechanism mech = mechRegistry.assembleMechanism(MECH_TYPE, group,
            seed.getLocation().add(0.5, 0, 0.5), AXIS_Y, null);
        if (mech == null) return;

        try {
            setSpin(hoist, true);
            // Rising: the rope the platform is about to travel through has to go now — disassembly would
            // overwrite those cells anyway. The transit display covers the gap; syncColumn below puts back
            // any link the platform didn't actually reach.
            if (!descend) stripRope(hoist, startDepth, steps);
            final int deepest = descend ? startDepth + steps : startDepth;

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
                // Broken mid-stroke (onRemoved lands the platform on its way out): the platform is down
                // safely, but there is no hoist left to own a rope or take a refund. Leave the world alone.
                if (registry.getTypeFromBlock(hoist) == null) return;
                // Depth comes from where the platform really landed, not from the steps we asked for: a
                // power cut mid-stroke stops it short, and disassembly can drop a block instead of placing it.
                int newDepth = depthFromLanding(hoist, placed, startDepth, deepest);
                syncColumn(hoist, newDepth, deepest);
                int delta = newDepth - startDepth;
                Inventory inv = registry.getOrCreateStorage(hoist);
                if (delta > 0) consumeChains(inv, delta);
                else if (delta < 0) refundChains(inv, hoist, -delta);
                writeExtension(hoist, newDepth);
                setSpin(hoist, false);
                removeRope(hoist);
            });
            Location start = mech.pivot();
            Location target = start.clone().add(0, descend ? -steps : steps, 0);
            active.put(hoistKey, new ActiveMove(hoistKey, hoist, mech, start, target,
                descend ? -1f : 1f, group.size(), spinDir, descend ? startDepth : startDepth - steps));
        } catch (Throwable t) {
            safeDisassemble(mech, hoistKey);
            throw t;
        }
    }

    /**
     * Re-derive the extension from the invariant "platform top block sits at {@code y0-D-1}", reading the
     * highest cell in the hoist's own column that the platform actually landed in. Falls back to
     * {@code startDepth} if the platform left the column entirely (every block dropped as an item).
     */
    private static int depthFromLanding(Block hoist, List<Block> placed, int startDepth, int deepest) {
        int topY = Integer.MIN_VALUE;
        for (Block b : placed) {
            if (b.getX() == hoist.getX() && b.getZ() == hoist.getZ()
                    && b.getY() < hoist.getY() && b.getY() > topY) {
                topY = b.getY();
            }
        }
        if (topY == Integer.MIN_VALUE) return startDepth;
        return Math.max(0, Math.min(deepest, hoist.getY() - topY - 1));
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
            double progress = (cur.getY() - m.start.getY()) * m.dirY;
            int stopped = Math.max(0, (int) Math.ceil(progress - 1e-6));
            m.target = m.start.clone().add(0, m.dirY * stopped, 0);
        }
        double remaining = (m.target.getY() - cur.getY()) * m.dirY;
        int[] stats = network.getNetworkStats(m.hoistKey);
        int base = config.getPower("chain_hoist", 1);
        int power = (stats != null ? stats[0] - stats[1] : 0) + base;
        float step = clamp(SPEED_K * power / Math.max(1, m.mass), MIN_STEP, (float) config.pistonMaxStep);

        boolean arrived = remaining <= step + 1e-3;
        Location next = arrived ? m.target : cur.clone().add(0, m.dirY * step, 0);
        m.mech.move(next, 0f);
        liveRope(m.hoist, m.ropeDepth, next.getY());
        if (arrived) m.settle = SETTLE_TICKS;
        return false;
    }

    // ──────────────────────────────────────────────────────────────────────
    // The rope: real CHAIN blocks at rest, a stretched block display in transit
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Make the real rope column match {@code depth}: chain filling {@code y0-1 … y0-depth}, none of ours
     * below that down to {@code deepest}. Idempotent, so it doubles as the repair for a rope a player
     * mined into or a stroke that stopped short.
     */
    private void syncColumn(Block hoist, int depth, int deepest) {
        for (int i = 1; i <= depth; i++) {
            Block c = hoist.getRelative(0, -i, 0);
            if (isClear(c)) placeChain(c);
        }
        for (int i = depth + 1; i <= deepest; i++) {
            Block c = hoist.getRelative(0, -i, 0);
            if (isOwnRope(c)) c.setType(Material.AIR, false);
        }
    }

    /** Strip {@code n} links off the bottom of a {@code startDepth}-deep rope ({@code y0-startDepth} up). */
    private void stripRope(Block hoist, int startDepth, int n) {
        for (int i = 0; i < n; i++) {
            Block c = hoist.getRelative(0, -(startDepth - i), 0);
            if (isOwnRope(c)) c.setType(Material.AIR, false);
        }
    }

    private static void placeChain(Block b) {
        b.setType(CHAIN_MATERIAL, false);
        if (b.getBlockData() instanceof Orientable o) {
            o.setAxis(Axis.Y);
            b.setBlockData(o, false);
        }
    }

    /** A plain chain we may take: a chain <i>shaft</i> is also CHAIN, but it is in the bare-block index. */
    private boolean isOwnRope(Block b) {
        return b.getType() == CHAIN_MATERIAL && !registry.isBareBlock(b);
    }

    /** Rope cells that are really ours — passable for a rising platform, which reels them in as it goes. */
    private void addRopeCells(Block hoist, int d, Set<Long> footprint) {
        for (int i = 1; i <= d; i++) {
            Block c = hoist.getRelative(0, -i, 0);
            if (isOwnRope(c)) footprint.add(cellKey(c));
        }
    }

    /**
     * Span the transit rope over the stretch that has no real chain in it yet: from the moving platform's
     * top face up to the bottom face of the lowest laid link ({@code y0-ropeDepth}). Zero-length at both
     * ends of a stroke, so the handoff to real blocks is seamless.
     */
    private void liveRope(Block hoist, int ropeDepth, double platformBottomY) {
        double platformTopY = platformBottomY + 1;
        setRope(hoist, platformTopY, (hoist.getY() - ropeDepth) - platformTopY);
    }

    /**
     * Render {@code length} blocks of rope upward from {@code bottomY} as one display per link, rather
     * than one display stretched over the span (which renders a single elongated link). Links sit flush
     * to the platform, so the only sub-height one is the newest, at the top: it grows 0→1 at the hoist
     * and is then promoted to a full link — which is what reads as a link being paid out of the block,
     * and swallowed by it on the way back up. Matrices are updated in place; a per-tick respawn flickers.
     */
    private void setRope(Block hoist, double bottomY, double length) {
        CustomBlockRegistry.LocationKey key = CustomBlockRegistry.LocationKey.of(hoist);
        int want = length > 0.05 ? (int) Math.ceil(length - 1e-6) : 0;
        List<BlockDisplay> links = ropes.get(key);
        if (want == 0) {
            if (links != null) removeRope(hoist);
            return;
        }
        if (links == null) ropes.put(key, links = new ArrayList<>(want));
        while (links.size() > want) {                      // reeling in: the top link is swallowed
            BlockDisplay d = links.remove(links.size() - 1);
            if (d.isValid()) d.remove();
        }
        for (int i = 0; i < want; i++) {
            float h = (float) Math.max(0.001, Math.min(1.0, length - i));   // 0 scale = degenerate matrix
            // Anchored at the hoist block (spawnBlock adds +0.5 to its centre), so place each link's
            // midpoint relative to that. T·S·T(-0.5): the trailing -0.5 centres the BlockDisplay unit
            // cube, which renders from its MIN corner. No rotation — a chain is symmetric about Y.
            Matrix4f m = new Matrix4f()
                .translate(0f, (float) ((bottomY + i + h / 2.0) - (hoist.getY() + 0.5)), 0f)
                .scale(1f, h, 1f)
                .translate(-0.5f, -0.5f, -0.5f);
            BlockDisplay d = i < links.size() ? links.get(i) : null;
            if (d != null && d.isValid()) d.setTransformationMatrix(m);
            else if (d != null) links.set(i, spawnLink(hoist, m, i));
            else links.add(spawnLink(hoist, m, i));
        }
    }

    private static BlockDisplay spawnLink(Block hoist, Matrix4f matrix, int index) {
        BlockDisplay d = DisplayUtil.spawnBlock(hoist.getLocation(), CHAIN_MATERIAL.createBlockData(),
            matrix, DisplayUtil.blockTag("mech", ROPE_TYPE, hoist.getLocation(), String.valueOf(index)));
        // Match the platform's own displays (MechanismRegistry): those interpolate each 1-tick hop over
        // 2 ticks, so a rope applied instantly would run ahead of them and visibly detach from the thing
        // it is carrying — up to a block of daylight at pistonMaxStep.
        d.setInterpolationDuration(2);
        d.setViewRange(64f);
        return d;
    }

    private void removeRope(Block hoist) {
        List<BlockDisplay> links = ropes.remove(CustomBlockRegistry.LocationKey.of(hoist));
        if (links != null) for (BlockDisplay d : links) if (d.isValid()) d.remove();
        // Sweep by prefix too: catches links persisted through a crash, whose entities we no longer hold.
        DisplayUtil.removeByTag(hoist.getLocation(), ropeTagPrefix(hoist), 2.0);
    }

    private static String ropeTagPrefix(Block hoist) {
        return DisplayUtil.blockTagPrefix("mech", ROPE_TYPE, hoist.getLocation());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Chain stock (the block's storage GUI is the reserve)
    // ──────────────────────────────────────────────────────────────────────

    private static int countChains(@Nullable Inventory inv) {
        if (inv == null) return 0;
        int n = 0;
        for (ItemStack s : inv.getContents()) {
            if (s != null && s.getType() == CHAIN_MATERIAL) n += s.getAmount();
        }
        return n;
    }

    /** Match {@link #countChains} exactly — by material, not by stack similarity, so a renamed chain that
     *  counts as stock is also spendable (otherwise it would read as an inexhaustible supply). */
    private static void consumeChains(@Nullable Inventory inv, int n) {
        if (inv == null) return;
        for (int i = 0; i < inv.getSize() && n > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s == null || s.getType() != CHAIN_MATERIAL) continue;
            int take = Math.min(n, s.getAmount());
            s.setAmount(s.getAmount() - take);
            inv.setItem(i, s.getAmount() <= 0 ? null : s);
            n -= take;
        }
    }

    /** Reeled-in chain goes back to stock; what won't fit (5 slots) spills out on top of the hoist. */
    private static void refundChains(@Nullable Inventory inv, Block hoist, int n) {
        if (n <= 0) return;
        ItemStack stack = new ItemStack(CHAIN_MATERIAL, n);
        Map<Integer, ItemStack> leftover = inv == null ? Map.of(0, stack) : inv.addItem(stack);
        for (ItemStack s : leftover.values()) {
            hoist.getWorld().dropItemNaturally(hoist.getLocation().add(0.5, 1.0, 0.5), s);
        }
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
        spinUntil.remove(k);
        ActiveMove m = active.remove(k);
        if (m != null) safeDisassemble(m.mech, k);   // land the platform instead of orphaning it
    }

    /** Block removed (break / explosion / piston): land any active platform, clear the transit display. The
     *  rope stays as real harvestable chain; stock drops via the registry's storage handling. */
    private void onRemoved(Block b) {
        removeRope(b);
        forget(b);
    }

    /** Spin the wheel through a bare-rope step. Only the transitions push a state change (which rebuilds
     *  displays); the steps in between just extend the deadline. */
    private void markBareSpin(Block hoist, CustomBlockRegistry.LocationKey key) {
        if (spinUntil.put(key, tickCounter + TRIGGER_PERIOD * 2) == null) setSpin(hoist, true);
    }

    private void expireBareSpin() {
        if (spinUntil.isEmpty()) return;
        var it = spinUntil.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (tickCounter < e.getValue()) continue;
            Block b = blockOf(e.getKey());
            // A platform stroke owns the spin state while it runs and clears it on landing.
            if (b != null && !active.containsKey(e.getKey())) setSpin(b, false);
            it.remove();
        }
    }

    /** Swap the idle/spinning prefix while keeping the axle suffix — the suffix IS the node's axis
     *  (axisFromState), so overwriting the whole state would unpower the hoist. Clutch-style. */
    private void setSpin(Block hoist, boolean spinning) {
        CustomHeadBlock t = registry.getType(HOIST_ID);
        if (t == null) return;
        String current = registry.getState(hoist);
        if (current == null) return;
        int i = current.lastIndexOf('_');
        String state = (spinning ? "spinning_" : "idle_") + (i < 0 ? "x" : current.substring(i + 1));
        if (state.equals(current)) return;   // applyConfig respawns every display; don't churn per tick
        registry.setState(hoist, state);
        registry.applyConfig(hoist, t, state, 0);
    }

    private void recalcIfNode(Block b) {
        CustomBlockRegistry.LocationKey k = CustomBlockRegistry.LocationKey.of(b);
        if (network.getNode(k) != null) network.recalculate(k);
    }

    private static int readExtension(Block b) {
        if (!(b.getState() instanceof Skull skull)) return 0;
        Integer v = skull.getPersistentDataContainer().get(EXTENSION_KEY, PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    private static void writeExtension(Block b, int v) {
        if (!(b.getState() instanceof Skull skull)) return;
        skull.getPersistentDataContainer().set(EXTENSION_KEY, PersistentDataType.INTEGER, Math.max(0, v));
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
        final float dirY;
        final int mass;
        final RotationNetwork.SpinDirection spinDir;
        /** Depth of the real chain already in the column for the whole stroke — where the transit rope starts. */
        final int ropeDepth;
        int warmup = 2;
        int settle = -1;
        ActiveMove(CustomBlockRegistry.LocationKey hoistKey, Block hoist, Mechanism mech, Location start,
                   Location target, float dirY, int mass, RotationNetwork.SpinDirection spinDir, int ropeDepth) {
            this.hoistKey = hoistKey;
            this.hoist = hoist;
            this.mech = mech;
            this.start = start;
            this.target = target;
            this.dirY = dirY;
            this.mass = mass;
            this.spinDir = spinDir;
            this.ropeDepth = ropeDepth;
        }
    }
}
