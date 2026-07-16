package anon.def9a2a4.corelib;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Rotatable;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
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
 * Chain Hoist (Create's "Rope Pulley"): a floor-placed vertical winch on a horizontal X/Z axle, paying its
 * chain <b>out</b> (CW) or reeling it back <b>in</b> (CCW), spending one chain from its storage GUI per
 * block of travel.
 *
 * <p><b>It is {@link ExtendablePistonManager} stood on end.</b> The piston's rod slides <i>through</i> its
 * core; the hoist's chain slides <i>through</i> the hoist. Every stroke captures the real chain column
 * <b>and</b> whatever hangs beneath it into one {@link Mechanism}, so links, load, and carried machinery
 * are one rigid body — welded together by construction, with no second thing to keep in sync. Descending, a
 * <b>ghost</b> chain at the hoist's own cell is the emerging link: ghosts are not aired out, so it starts
 * inside the hoist and slides out, exactly as the piston ghosts a pole inside its core, and
 * {@link #layLinks} appends one more per block travelled. Rising, the hoist cell is <b>protected</b>, so
 * that link lands there and is consumed — that is what reeling in <i>is</i>.
 *
 * <p>Two corollaries, because both are what a rewrite would be tempted to undo. <b>Nothing here draws the
 * chain</b> — displays, interpolation and client-side smoothing are the mechanism's job; a separately-drawn
 * rope cannot track a vehicle-carried load without predicting the client's entity lerp. And <b>nothing here
 * writes a block into the world mid-stroke</b>: the body is drawn where the client has lerped it, not where
 * the server has it, so a block placed at the server's position materialises ahead of the chain it belongs
 * to, and a stroke cut short lands the body on top of what it already wrote. Both were tried; both are why
 * links are appended to the mechanism instead.
 *
 * <p><b>Invariant</b> — at extension {@code D} the hoist at {@code y0} owns real chains filling
 * {@code y0-1 … y0-D}, and the load's top block, if any, sits at {@code y0-D-1}. <b>{@code D} is never
 * stored</b>: it is counted off the real chains on every trigger ({@link #scanDepth}), so the chain in the
 * world is the single source of truth and cannot desync from itself. A cut feed, a crash, or a player
 * mining the chain mid-span is simply what the next scan reads — the same reason the piston re-derives its
 * rod geometry per trigger instead of trusting stored state. At rest only real world blocks persist.
 */
final class ChainHoistManager {

    static final String HOIST_ID = "mech:chain_hoist";
    private static final String MECH_TYPE = "mech:chain_hoist";

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
    /** Blocks per stroke. A stroke is committed at assembly and re-checks nothing but power while it runs,
     *  so this bounds how stale its obstruction scan can get; the trigger just re-fires for a longer drop. */
    private static final int MAX_STROKE = 8;
    private static final Vector3f AXIS_Y = new Vector3f(0f, 1f, 0f);

    private final JavaPlugin plugin;
    private final CustomBlockRegistry registry;
    private final RotationNetwork network;
    private final MechanismRegistry mechRegistry;
    private final GlueManager glueManager;
    private final RotationConfig config;

    private final Set<CustomBlockRegistry.LocationKey> hoists = new HashSet<>();
    private final Map<CustomBlockRegistry.LocationKey, ActiveMove> active = new HashMap<>();
    private final Set<CustomBlockRegistry.LocationKey> warned = new HashSet<>();
    /** Hoists being torn down right now — see {@link #onRemoved}. Their storage is already dropped, so the
     *  disassembly callback must not bill against it, and must not re-apply state to a dying block. */
    private final Set<CustomBlockRegistry.LocationKey> dying = new HashSet<>();
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
            "Chains: " + countChains(registry.getOrCreateStorage(b)) + " | extension " + scanDepth(b),
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
        int d = scanDepth(hoist);
        if (dir == RotationNetwork.SpinDirection.CW) payOut(hoistKey, hoist, d, dir);
        else reelIn(hoistKey, hoist, d, dir);
    }

    /**
     * The extension, counted off the real rope rather than remembered: contiguous chains of ours straight
     * down from the hoist. Nothing is cached, so there is no second copy of the truth to desync — a link a
     * player mined out, or a stroke that stopped short, is just what the next scan reads.
     *
     * <p>Costs {@code depth} palette reads per trigger, and only when powered ({@link #maybeTrigger} bails
     * on the power check first). {@link #isOwnRope} short-circuits on the material, and excludes a chain
     * <i>shaft</i> — a bare block — so shaft builds under a hoist are not mistaken for rope. Plain chains a
     * player stacked under the hoist by hand ARE adopted, and reeling in banks them in its storage.
     */
    private int scanDepth(Block hoist) {
        int min = hoist.getWorld().getMinHeight();
        int d = 0;
        while (hoist.getY() - 1 - d >= min && isOwnRope(hoist.getRelative(0, -1 - d, 0))) d++;
        return d;
    }

    /** CW: lower — pay chain out of the hoist, sliding it (and whatever hangs on it) down. */
    private void payOut(CustomBlockRegistry.LocationKey hoistKey, Block hoist, int d,
                        RotationNetwork.SpinDirection spinDir) {
        int stock = countChains(registry.getOrCreateStorage(hoist));
        if (stock <= 0) return;   // nothing left to pay out
        // Capped, not `stock`: five slots of chain is 320, and a stroke is committed at assembly — nothing
        // re-checks obstruction while it travels, and it cannot be interrupted except by cutting power. The
        // trigger re-fires every TRIGGER_PERIOD, so a long drop is just several strokes, each re-scanning.
        startMove(hoistKey, hoist, d, true, spinDir, Math.min(stock, MAX_STROKE));
    }

    /** CCW: raise — slide the chain up, swallowing its top links back into the hoist. */
    private void reelIn(CustomBlockRegistry.LocationKey hoistKey, Block hoist, int d,
                        RotationNetwork.SpinDirection spinDir) {
        if (d <= 0) return;   // nothing paid out; there are no links to swallow
        startMove(hoistKey, hoist, d, false, spinDir, Math.min(d, MAX_STROKE));
    }

    /** The real chain we own, top-down: {@code y0-1 … y0-d}. {@link #scanDepth} guarantees all d are ours. */
    private List<Block> chainColumn(Block hoist, int d) {
        List<Block> out = new ArrayList<>(d);
        for (int i = 1; i <= d; i++) out.add(hoist.getRelative(0, -i, 0));
        return out;
    }

    /**
     * Platform = the seed's authored glue (rare — normally none) or the seed itself, expanded through
     * casings. @return {@code null} to reject the move outright (a glued cell is immovable).
     *
     * <p>{@code GlueManager.resolveStructure} skips air but NOT water/lava, and never consults the
     * immovable set — and {@code MechanismRegistry} filters nothing, trusting its caller entirely. So the
     * guards below are the only thing between stale glue and a mechanism assembled around a drowned or
     * bedrock cell. Skipping empties (rather than rejecting) treats them as what they are — glue pointing
     * at a block that is gone; rejecting on an immovable mirrors {@code ExtendablePistonManager
     * .addHeadPayload}, which aborts rather than shear a structure around one.
     */
    /*
     * No "bond refused" particle (the null callbacks below). That cue is for a DELIBERATE mistake — gluing
     * a mover's own hardware. But a hoist's load hangs on the chain by design, so it is always cardinally
     * against a chain link, and at depth 0 against the hoist head itself: the refusal is inherent and
     * universal here, not a mistake. Since the gather re-runs every trigger, emitting it puffs forever at
     * the bottom of the chain — loudest when the hoist is fully extended and can no longer move.
     */
    private @Nullable List<Block> resolveGroup(Block seed, Set<CustomBlockRegistry.LocationKey> excluded) {
        List<Block> glued = glueManager.resolveStructure(new BlockAnchor(seed, () -> true),
            excluded, null);
        List<Block> base = new ArrayList<>();
        if (glued != null) {
            for (Block b : glued) {
                if (isClear(b)) continue;                                 // stale glue: gone or drowned
                if (!MovableBlocks.isMovable(b, registry)) return null;   // immovable — shear guard
                base.add(b);
            }
        }
        if (base.isEmpty()) base = List.of(seed);   // seed is checked movable by both callers
        return StickySpread.withDerived(base, registry, glueManager.maxSize(),
            excluded, null);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Movement
    // ──────────────────────────────────────────────────────────────────────

    /**
     * One stroke, chain and load together, built like {@link ExtendablePistonManager}'s rod: the chain
     * slides <b>through</b> the hoist the way the rod slides through the piston core.
     *
     * <ul>
     *   <li><b>Blocks</b>: the chain column that survives the stroke, plus the platform. Captured, they
     *       become displays riding one vehicle — every link stays welded to its neighbours and to the load
     *       for free. That is the whole point: a separately-drawn rope cannot track a vehicle-carried load
     *       without predicting the client's interpolation.
     *   <li><b>Ghost</b>: exactly ONE chain, the link currently <i>emerging</i>. It only ever has to cover
     *       the sub-block gap — whole blocks behind it are laid as real chain by {@link #layLinks}, which
     *       is what makes the stroke length unbounded. Ghosts are not aired out
     *       ({@code MechanismRegistry.assembleCore}), so the one that starts in the hoist's own cell leaves
     *       the hoist block untouched and simply slides out of it. Data-only: at depth 0 there is no chain
     *       in the world to template from.
     *   <li><b>Protected cell</b>: the hoist. Rising, the ghost lands exactly there and is discarded —
     *       that IS the link being swallowed. Descending, it is also the abort guard: a stroke that lands
     *       at zero offset would otherwise drop the ghost onto the hoist itself.
     * </ul>
     *
     * <p>Neither direction ever renders above the hoist: the ghost lives in {@code [y0-N, y0+1]} both ways.
     * The pivot is the hoist's own cell, so the loaded and bare-chain cases share one frame.
     */
    private void startMove(CustomBlockRegistry.LocationKey hoistKey, Block hoist, int startDepth,
                           boolean descend, RotationNetwork.SpinDirection spinDir, int want) {
        List<Block> column = chainColumn(hoist, startDepth);
        Set<Long> cells = new HashSet<>();

        // Our "self" cells — never captured into the platform: the hoist block and the WHOLE chain
        // column (kept and swallowed links alike). A sticky block directly under the hoist otherwise
        // drags the hoist skull into the platform, which our own protected cell then consumes on
        // landing; one beside a mid-rope link would tear a chain out of the column.
        List<Block> hardware = new ArrayList<>(column);
        hardware.add(hoist);
        Set<CustomBlockRegistry.LocationKey> excluded = MoverExclusion.exclusionFor(hardware);

        // Whatever hangs under the chain rides along. Absent (a bare chain paying into air) the stroke is
        // the same, just chain-only — which is why there is no separate bare-rope path any more.
        Block seed = hoist.getRelative(0, -1 - startDepth, 0);
        List<Block> load = List.of();
        int[] glue = null;
        if (MovableBlocks.isMovable(seed, registry)) {
            List<Block> platform = resolveGroup(seed, excluded);
            if (platform == null) return;   // glued to something immovable — refuse rather than shear
            glue = new BlockAnchor(seed, () -> !active.containsKey(hoistKey)).readOffsets();  // pre air-out
            load = platform;
            for (Block b : platform) cells.add(cellKey(b));
        }

        // Our own chain never obstructs our own body — that is what the footprint is FOR, and it is
        // independent of what we capture. The whole column goes in, swallowed links included: rising, they
        // are still real blocks standing in the body's path until layLinks clears them.
        Set<Long> footprint = new HashSet<>(cells);
        for (Block b : column) footprint.add(cellKey(b));
        footprint.add(cellKey(hoist));   // the chain slides through the hoist, as the rod does the core

        // Ray origins. The load, plus — descending — the hoist's own cell, because the emerging link starts
        // there and is not in any list (the piston probes its core's ray for exactly this reason). Rising,
        // the column must NOT be probed: every cell a link crosses is a column cell and so already in the
        // footprint, but its ray would run on past the hoist into whatever sits above it, and a floor there
        // would clamp every reel-in to one block.
        List<Block> probe = new ArrayList<>(load);
        if (descend) probe.add(hoist);
        BlockFace face = descend ? BlockFace.DOWN : BlockFace.UP;
        int steps = clearForAll(probe, footprint, face, want);
        if (steps < 1) return;

        // Capture. Descending, the whole column. Rising, all but the top `steps`: those are the ones being
        // swallowed, and layLinks deletes them from the world as the body clears each — capturing them
        // would ride them up past the hoist in plain view. `steps`, not `want`: a stroke clamped short that
        // still dropped `want` links would leave the uncaptured remainder standing in its own path.
        List<Block> group = new ArrayList<>(column.subList(descend ? 0 : steps, column.size()));
        group.addAll(load);

        // The emerging link. Descending it starts inside the hoist and layLinks appends one more per block;
        // rising it starts above the body's top link and lands back inside the hoist to be discarded.
        Block ghostCell = descend ? hoist : hoist.getRelative(0, -steps, 0);
        Mechanism mech = mechRegistry.assembleMechanism(MECH_TYPE, group,
            List.of(new MechanismRegistry.GhostBlock(ghostCell.getLocation(), chainData())),
            hoist.getLocation().add(0.5, 0, 0.5), AXIS_Y, null);

        final int[] glueOffsets = glue;
        final int seedDy = -1 - startDepth;   // seed's offset from the pivot (= the hoist) at t=0
        try {
            ((BasicMechanism) mech).setProtectedCells(Set.of(hoistKey));
            setSpin(hoist, true);

            mech.setOnDisassembled(placed -> {
                // Re-resolve any landed resolver-block displays (list order has no neighbour guarantee).
                for (Block b : placed) {
                    CustomHeadBlock t = registry.getTypeFromBlock(b);
                    if (t != null && t.displayTransformResolver() != null) {
                        registry.resolveDisplayTransforms(b, t, registry.getState(b));
                    }
                }
                // Rebind authored glue onto the landed anchor (no-op when the platform had none).
                if (glueOffsets != null) {
                    Location p = mech.pivot();
                    new BlockAnchor(p.getWorld().getBlockAt(p.getBlockX(),
                        p.getBlockY() + seedDy, p.getBlockZ()), () -> true).writeOffsets(glueOffsets);
                }
                // Broken mid-stroke: forget() lands the body from inside onBlockRemoved, which runs its
                // consumer FIRST — the skull and its PDC are still there, so getTypeFromBlock would say the
                // hoist is alive and we would bill an inventory dropStorage has already emptied (free
                // chains) and re-apply state to a block one statement from AIR (orphaned displays). `dying`
                // is the only signal available at that instant.
                if (dying.contains(hoistKey)) return;
                // Bill from what actually landed, not from what we asked for: re-scan and pay the delta.
                int delta = scanDepth(hoist) - startDepth;
                Inventory inv = registry.getOrCreateStorage(hoist);
                if (delta > 0) consumeChains(inv, delta);
                else if (delta < 0) refundChains(inv, hoist, -delta);
                setSpin(hoist, false);
            });
            Location start = mech.pivot();
            active.put(hoistKey, new ActiveMove(hoistKey, hoist, mech,
                start, start.clone().add(0, descend ? -steps : steps, 0),
                descend ? -1f : 1f, group.size() + 1, spinDir, descend, steps));
        } catch (Throwable t) {
            safeDisassemble(mech, hoistKey);
            throw t;
        }
    }

    /**
     * Pay a new link out of the hoist for every whole block travelled, by <b>adding it to the moving body</b>
     * — never by writing a block into the world. Called every tick; {@code linksDone} makes it idempotent.
     *
     * <p>The link is appended at local offset {@code +k}, which is the hoist's own cell at the instant it is
     * appended ({@code pivot = y0-k}), so it appears inside the hoist and slides out with everything else.
     * Every link is therefore a moving display, and the whole column lands together at disassembly — the
     * piston's rule, and the reason nothing here has to reason about where the client is drawing the body.
     *
     * <p>Landing falls out for free, including a stroke cut short at {@code M < steps}: the links appended
     * so far sit at local {@code 0 … M}, the pivot lands at {@code y0-M}, so they land at {@code y0-M … y0}
     * — and the one at {@code y0} is the protected hoist cell, discarded. Exactly M links, no collision, no
     * item drop.
     *
     * <p>Rising is the mirror and needs no appends: the body simply carries {@code steps} fewer links, and
     * the doomed ones are deleted from the world here as the body clears each cell.
     */
    private void layLinks(ActiveMove m) {
        int done = (int) Math.floor(Math.abs(m.mech.pivot().getY() - m.start.getY()) + 1e-6);
        int limit = m.descend ? done : Math.min(done + 1, m.steps);
        while (m.linksDone < limit) {
            int k = ++m.linksDone;
            if (m.descend) {
                ((BasicMechanism) m.mech).appendGhost(new Vector3f(0f, k, 0f), chainData());
            } else {
                Block c = m.hoist.getRelative(0, -m.steps + k - 1, 0);
                if (isOwnRope(c)) c.setType(Material.AIR, false);
            }
        }
    }

    /** BlockData for a link: a Y-axis chain, matching what disassembly will land in the column. */
    private static BlockData chainData() {
        BlockData d = CHAIN_MATERIAL.createBlockData();
        if (d instanceof Orientable o) o.setAxis(Axis.Y);
        return d;
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
        // Rising: carry any entities standing on the platform up by this tick's delta BEFORE the colliders
        // teleport up, so the shulker boxes don't clip through a rider (who would otherwise fall through).
        double dy = next.getY() - cur.getY();
        if (m.dirY > 0 && dy > 0) m.mech.carryRidersUp(dy);
        m.mech.move(next, 0f);
        layLinks(m);   // fill in behind the emerging ghost, one real link per whole block travelled
        if (arrived) m.settle = SETTLE_TICKS;
        return false;
    }

    /** A plain chain we may take: a chain <i>shaft</i> is also CHAIN, but it is in the bare-block index. */
    private boolean isOwnRope(Block b) {
        return b.getType() == CHAIN_MATERIAL && !registry.isBareBlock(b);
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

    private static long cellKey(Block b) {
        return ((long) (b.getX() & 0x3FFFFFF) << 38) | ((long) (b.getZ() & 0x3FFFFFF) << 12)
             | ((long) ((b.getY() + 2048) & 0xFFF));
    }

    private static boolean isClear(Block b) {
        // The void is not clear. Below the world floor every cell reads as air, so without this a stroke
        // finds nothing to stop it and pays out past y=-64 forever — chain AND load. Bounding it here
        // rather than in payOut means clearForAll bounds the whole body, not just the column.
        if (b.getY() < b.getWorld().getMinHeight()) return false;
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
        if (m != null) safeDisassemble(m.mech, k);   // land the body instead of orphaning it
    }

    /** Block removed (break / explosion / piston): land any active stroke. The chain stays as real
     *  harvestable blocks; stock drops via the registry's storage handling.
     *
     *  <p>Flagged while forget() lands the body, so the disassembly callback knows the hoist is dying: at
     *  this point the skull still reads as a live hoist, but its storage has already been dropped. */
    private void onRemoved(Block b) {
        CustomBlockRegistry.LocationKey k = CustomBlockRegistry.LocationKey.of(b);
        dying.add(k);
        try {
            forget(b);
        } finally {
            dying.remove(k);
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
        final boolean descend;
        /** Blocks this stroke was planned for — the ghost's landing cell is {@code steps} below/above. */
        final int steps;
        /** Links appended (descending) or taken up (rising) so far. Makes {@link #layLinks} idempotent. */
        int linksDone = 0;
        int warmup = 2;
        int settle = -1;

        ActiveMove(CustomBlockRegistry.LocationKey hoistKey, Block hoist, Mechanism mech, Location start,
                   Location target, float dirY, int mass, RotationNetwork.SpinDirection spinDir,
                   boolean descend, int steps) {
            this.hoistKey = hoistKey;
            this.hoist = hoist;
            this.mech = mech;
            this.start = start;
            this.target = target;
            this.dirY = dirY;
            this.mass = mass;
            this.spinDir = spinDir;
            this.descend = descend;
            this.steps = steps;
        }
    }
}
