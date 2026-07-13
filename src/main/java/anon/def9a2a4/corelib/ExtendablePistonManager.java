package anon.def9a2a4.corelib;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Rotatable;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extendable piston (arm-only MVP). A fixed {@code piston_core} draws rotational power from any
 * adjacent shaft/gear (omni consumer). Along its pole line — {@code [back poles][CORE][front poles]
 * [HEAD]} — the WHOLE rod (all poles + head) is assembled as one {@link Mechanism} and slid by the
 * spin direction: <b>CW extends</b> (slides the rod out by the back-pole count), <b>CCW retracts</b>
 * (until the head is adjacent to the core). Pure spin, no redstone.
 *
 * <p>The rod slides <em>through</em> the core's cell; on disassembly the block that lands on the core
 * is skipped (protected cell — see {@link BasicMechanism#setProtectedCells}) so the core is never
 * destroyed. Reuses {@link MechanismRegistry#assembleMechanism} + {@code move()} translation.
 */
final class ExtendablePistonManager {

    static final String CORE_ID = "mech:piston_core";
    static final String POLE_ID = "mech:piston_pole";
    static final String HEAD_ID = "mech:piston_head";
    private static final String MECH_TYPE = "mech:piston";

    private static final int MOVE_DEMAND = 2;            // transient network demand while sliding
    private static final int TRIGGER_PERIOD = 4;         // ticks between idle-core scans
    private static final float MIN_STEP = 0.08f;         // blocks/tick (floor so it always progresses)
    private static final float MAX_STEP = 0.5f;
    private static final float SPEED_K = 0.5f;
    private static final Vector3f AXIS_Y = new Vector3f(0f, 1f, 0f);

    private final JavaPlugin plugin;
    private final CustomBlockRegistry registry;
    private final RotationNetwork network;
    private final MechanismRegistry mechRegistry;
    private final GlueManager glueManager;

    private final Set<CustomBlockRegistry.LocationKey> cores = new HashSet<>();
    private final Map<CustomBlockRegistry.LocationKey, ActiveMove> active = new HashMap<>();
    private int tickCounter = 0;

    ExtendablePistonManager(JavaPlugin plugin, CustomBlockRegistry registry,
                            RotationNetwork network, MechanismRegistry mechRegistry,
                            GlueManager glueManager) {
        this.plugin = plugin;
        this.registry = registry;
        this.network = network;
        this.mechRegistry = mechRegistry;
        this.glueManager = glueManager;
    }

    void register() {
        CustomHeadBlock core = registry.getType(CORE_ID);
        if (core == null) {
            plugin.getLogger().warning("ExtendablePiston: block '" + CORE_ID + "' not found — skipping");
            return;
        }
        registry.register(core.toBuilder()
            .drillable(false)
            .cancelPistons(true)
            .reactsToNeighbors(true)
            .onNeighborChange((b, face) -> recalcIfNode(b))
            .onChunkLoad((b, state) -> {
                CustomBlockRegistry.LocationKey k = CustomBlockRegistry.LocationKey.of(b);
                // Omni consumer: draws power from the first aligned shaft/gear on ANY face.
                // Static demand 0 (like RotationRotator) so an idle piston never loads/stalls the network;
                // demand is added transiently only while sliding (see startMove).
                network.addNode(b, CORE_ID, RotationNetwork.Axis.Y,
                    RotationNetwork.NodeRole.CONSUMER, 0, false, true, null);
                cores.add(k);
            })
            .onChunkUnload(b -> forget(b))
            .onBlockRemoved((b, state) -> forget(b))
            .onBlockPlaced((b, state) -> snapFloorRotation(b))
            .build());

        overlayStructural(POLE_ID);
        overlayHead();

        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** Pole is an inert structural block (no network behaviour); floor placements snap to grid-aligned NORTH. */
    private void overlayStructural(String id) {
        CustomHeadBlock t = registry.getType(id);
        if (t == null) {
            plugin.getLogger().warning("ExtendablePiston: block '" + id + "' not found — skipping");
            return;
        }
        registry.register(t.toBuilder()
            .drillable(false)
            .cancelPistons(true)
            .onBlockPlaced((b, state) -> snapFloorRotation(b))
            .build());
    }

    /**
     * Snap a floor-placed head's 16-way skull yaw to grid-aligned NORTH (like {@code PipeBlockRegistrar}).
     * Display entities are fixed-world-frame, but the vanilla skull underneath follows the player's look;
     * snapping keeps it aligned with the grid. No-op for wall heads (which have no free yaw).
     */
    private static void snapFloorRotation(Block b) {
        if (b.getType() == Material.PLAYER_HEAD && b.getBlockData() instanceof Rotatable r) {
            r.setRotation(BlockFace.NORTH);
            b.setBlockData(r, false);
        }
    }

    /**
     * Head: structural like the pole, plus a neighbour-aware {@link CustomHeadBlock.DisplayTransformResolver}
     * (pipes-style) that points the toothed pole-stub (display 0) and the wide cap (display 1) <b>outward</b> —
     * the axis side that does <em>not</em> have an adjacent pole. Re-resolved automatically when a neighbour
     * changes (needs {@code reactsToNeighbors}). Static YAML transforms are the outward=+axis fallback.
     */
    private void overlayHead() {
        CustomHeadBlock t = registry.getType(HEAD_ID);
        if (t == null) {
            plugin.getLogger().warning("ExtendablePiston: block '" + HEAD_ID + "' not found — skipping");
            return;
        }
        registry.register(t.toBuilder()
            .drillable(false)
            .cancelPistons(true)
            .reactsToNeighbors(true)
            .stateResolver(event -> headAxisState(event.getBlock()))
            .onBlockPlaced((b, state) -> snapFloorRotation(b))
            .displayTransformResolver((block, state, cfg, idx) -> headTransform(block, state, idx))
            .build());
    }

    /**
     * Orient a head to the axis of an adjacent pole (so it caps the rod correctly instead of following the
     * clicked face — a mid-air cap otherwise becomes a floor {@code idle_y} showing the wrong entities). Falls
     * back to the placement-face axis (mirrors {@code placement_state_map}) when no pole is adjacent. Never
     * returns null (a null state would cancel placement).
     */
    private String headAxisState(Block head) {
        if (isType(head.getRelative(BlockFace.UP), POLE_ID) || isType(head.getRelative(BlockFace.DOWN), POLE_ID))
            return "idle_y";
        if (isType(head.getRelative(BlockFace.EAST), POLE_ID) || isType(head.getRelative(BlockFace.WEST), POLE_ID))
            return "idle_x";
        if (isType(head.getRelative(BlockFace.NORTH), POLE_ID) || isType(head.getRelative(BlockFace.SOUTH), POLE_ID))
            return "idle_z";
        // No adjacent pole: derive the axis from how the head was placed (wall head = horizontal).
        if (head.getBlockData() instanceof Directional d) {
            return switch (d.getFacing()) {
                case EAST, WEST -> "idle_x";
                case NORTH, SOUTH -> "idle_z";
                default -> "idle_y";
            };
        }
        return "idle_y";
    }

    // ──────────────────────────────────────────────────────────────────────
    // Head display: pole-stub (idx 0) + cap (idx 1) pointing away from the pole
    // ──────────────────────────────────────────────────────────────────────

    // A player-head ItemDisplay's intrinsic model box is p ∈ [-0.25,0.25]×[-0.5,0]×[-0.25,0.25] — a 0.5³
    // cube, X/Z-centred but TOP-ANCHORED in Y (origin at the top-face centre, body hangs toward -Y). World
    // point = C + T + R·(S⊙p), C = block centre. Because the box is asymmetric in Y, orientation must map
    // model +Y onto the outward direction per {@link #rotationFor} — a sign-independent rotation is wrong.
    private static final AxisAngle4f R_IDENTITY = new AxisAngle4f(0f, 0f, 0f, 1f);
    private static final float POLE_W = 1.002f;      // stub width  (0.501 block, matches piston_pole)
    private static final float STUB_LEN = 1.8f;      // stub length (0.9 block "on the outward side")
    private static final float CAP_W = 2.004f;       // cap width   (2x the 0.501 pole ⇒ 1.002 block)
    private static final float CAP_THICK = 1.0f;     // cap depth along the axis (0.5 block plate)
    private static final float STUB_T = 0.4f;        // stub inner end flush with the pole (u∈[-0.5,0.4])
    private static final float CAP_T = 0.75f;        // cap anchor 0.25 past the block face (plate u∈[0.25,0.75])

    /**
     * Resolve display {@code idx} (0 = toothed pole-stub, 1 = wide cap) for a placed head. Both point toward
     * {@code outward} = the axis face with no adjacent pole. Offsets are visual-tuning values (cf. pipes'
     * display.yml) — adjust against a running server.
     */
    private @Nullable Transformation headTransform(Block block, @Nullable String state, int idx) {
        BlockFace axisPos = axisPositive(state);
        if (axisPos == null) return null;
        BlockFace outward = outwardFace(block, axisPos);
        Vector3f o = new Vector3f(outward.getModX(), outward.getModY(), outward.getModZ());
        AxisAngle4f r = rotationFor(outward);
        if (idx == 0) {
            // Toothed stub: 0.9-long rod, inner end flush with the pole, outer end hidden under the cap.
            return new Transformation(o.mul(STUB_T, new Vector3f()),
                r, new Vector3f(POLE_W, STUB_LEN, POLE_W), R_IDENTITY);
        }
        // Cap: 0.5-thick full-width plate ⟂ the axis, nudged onto the block — floor caps 0.25 down (world),
        // wall caps 0.25 toward the pole (inward).
        Vector3f capT = new Vector3f(o).mul(CAP_T);
        if (outward == BlockFace.UP || outward == BlockFace.DOWN) {
            capT.add(0f, -0.25f, 0f);
        } else {
            capT.sub(o.x * 0.25f, o.y * 0.25f, o.z * 0.25f);
        }
        return new Transformation(capT, r, new Vector3f(CAP_W, CAP_THICK, CAP_W), R_IDENTITY);
    }

    /** +axis for the head's placement state ({@code idle_x/y/z}), or null if unknown. */
    private static @Nullable BlockFace axisPositive(@Nullable String state) {
        if (state == null) return null;
        return switch (state) {
            case "idle_y" -> BlockFace.UP;
            case "idle_x" -> BlockFace.EAST;
            case "idle_z" -> BlockFace.SOUTH;
            default -> null;
        };
    }

    /** The axis side without an adjacent pole (outward). Defaults to {@code +axis} if ambiguous. */
    private BlockFace outwardFace(Block head, BlockFace axisPos) {
        boolean posPole = isType(head.getRelative(axisPos), POLE_ID);
        boolean negPole = isType(head.getRelative(axisPos.getOppositeFace()), POLE_ID);
        if (posPole && !negPole) return axisPos.getOppositeFace();
        if (negPole && !posPole) return axisPos;
        return axisPos;
    }

    /**
     * Rotation that maps the model's +Y axis onto {@code outward}. The whole ±-asymmetry of the top-anchored
     * head box lives here, so with {@code T = k·outward} both the stub and the cap land correctly (inner/outer
     * ends flush) for every one of the six directions.
     */
    private static AxisAngle4f rotationFor(BlockFace outward) {
        float h = (float) (Math.PI / 2), p = (float) Math.PI;
        return switch (outward) {
            case UP    -> R_IDENTITY;                     // +Y → +Y
            case DOWN  -> new AxisAngle4f(p, 1f, 0f, 0f);  // +Y → -Y
            case EAST  -> new AxisAngle4f(-h, 0f, 0f, 1f); // +Y → +X
            case WEST  -> new AxisAngle4f(h, 0f, 0f, 1f);  // +Y → -X
            case SOUTH -> new AxisAngle4f(h, 1f, 0f, 0f);  // +Y → +Z
            case NORTH -> new AxisAngle4f(-h, 1f, 0f, 0f); // +Y → -Z
            default    -> R_IDENTITY;
        };
    }

    private void recalcIfNode(Block b) {
        CustomBlockRegistry.LocationKey k = CustomBlockRegistry.LocationKey.of(b);
        if (network.getNode(k) != null) network.recalculate(k);
    }

    private void forget(Block b) {
        CustomBlockRegistry.LocationKey k = CustomBlockRegistry.LocationKey.of(b);
        network.removeNode(k);   // also clears any transient demand for this node
        cores.remove(k);
        ActiveMove m = active.remove(k);
        if (m != null) m.mech.disassemble();   // land the rod (fires the glue hook) instead of orphaning displays
    }

    // ──────────────────────────────────────────────────────────────────────
    // Tick: advance active moves, then periodically scan idle cores for triggers
    // ──────────────────────────────────────────────────────────────────────

    private void tick() {
        if (!active.isEmpty()) {
            var it = active.values().iterator();
            while (it.hasNext()) {
                if (advance(it.next())) it.remove();
            }
        }
        if (++tickCounter % TRIGGER_PERIOD == 0 && !cores.isEmpty()) {
            for (CustomBlockRegistry.LocationKey k : new ArrayList<>(cores)) {
                if (!active.containsKey(k)) maybeTrigger(k);
            }
        }
    }

    private void maybeTrigger(CustomBlockRegistry.LocationKey coreKey) {
        if (!network.isPowered(coreKey)) return;
        // Yield under contention: only move when the network has spare surplus (read BEFORE we add our own
        // transient demand, so it excludes us — mirrors RotationRotator). Prevents starving co-resident consumers.
        int[] stats = network.getNetworkStats(coreKey);
        if (stats == null || stats[0] - stats[1] <= 0) return;
        RotationNetwork.SpinDirection dir = network.getDirection(coreKey);
        if (dir == null) return;
        Block core = blockOf(coreKey);
        if (core == null) return;
        PistonLine line = detectLine(core);
        if (line == null) return;

        List<Block> payload = resolvePayload(line);
        if (payload == null) return;                                // glued structure holds an immovable block — abort
        // Full moving assembly (rod + payload); the core cell is passable (ghost-filled, protected on land).
        List<Block> moving = new ArrayList<>(line.rodBlocks());
        moving.addAll(payload);
        Set<Long> footprint = footprintKeys(moving, line.core());

        if (dir == RotationNetwork.SpinDirection.CW) {              // extend
            int r = clearForAll(moving, footprint, line.frontFace(), line.backPoles());
            if (r > 0) startMove(coreKey, line, payload, line.frontFace(), r);
        } else {                                                    // CCW → retract
            BlockFace back = line.frontFace().getOppositeFace();
            int r = clearForAll(moving, footprint, back, line.extension());
            if (r > 0) startMove(coreKey, line, payload, back, r);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Detection: find the pole/head line through the core
    // ──────────────────────────────────────────────────────────────────────

    private @Nullable PistonLine detectLine(Block core) {
        BlockFace forward = parseForward(registry.getState(core));
        if (forward == null) return null;
        List<Block> frontRun = walk(core, forward);                 // poles… then head (forward side)
        if (!endsWithHead(frontRun)) return null;                   // must terminate in a head
        List<Block> backRun = walk(core, forward.getOppositeFace()); // poles only (reserve)
        Block head = frontRun.get(frontRun.size() - 1);
        int frontPoles = frontRun.size() - 1;
        int backPoles = backRun.size();
        List<Block> rod = new ArrayList<>(frontRun.size() + backRun.size());
        rod.addAll(frontRun);
        rod.addAll(backRun);
        return new PistonLine(forward, core, backPoles, head, frontPoles, rod);
    }

    /** Forward (extend) direction from the core's {@code fwd_*} state. */
    private static @Nullable BlockFace parseForward(@Nullable String state) {
        if (state == null) return null;
        return switch (state) {
            case "fwd_up" -> BlockFace.UP;
            case "fwd_down" -> BlockFace.DOWN;
            case "fwd_north" -> BlockFace.NORTH;
            case "fwd_south" -> BlockFace.SOUTH;
            case "fwd_east" -> BlockFace.EAST;
            case "fwd_west" -> BlockFace.WEST;
            default -> null;
        };
    }

    /** Contiguous poles from the core along {@code face}, terminating at (and including) a head. */
    private List<Block> walk(Block core, BlockFace face) {
        List<Block> run = new ArrayList<>();
        Block b = core.getRelative(face);
        while (true) {
            if (isType(b, POLE_ID)) { run.add(b); b = b.getRelative(face); }
            else if (isType(b, HEAD_ID)) { run.add(b); break; }
            else break;
        }
        return run;
    }

    private boolean endsWithHead(List<Block> run) {
        return !run.isEmpty() && isType(run.get(run.size() - 1), HEAD_ID);
    }

    /**
     * Max forward steps (≤ {@code max}) the whole rigid assembly can advance along {@code face}: the min, over
     * every moving block, of consecutive cells ahead that are clear <em>or</em> part of the assembly's own swept
     * footprint (cells that also vacate). Checking every block — not just the leading column — is what stops a
     * wide glued payload from shearing off and destroying blocks on landing.
     */
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

    /** Cells occupied by the moving assembly, plus the core cell (the rod slides through it — ghost-filled). */
    private static Set<Long> footprintKeys(List<Block> moving, Block core) {
        Set<Long> keys = new HashSet<>(moving.size() + 1);
        for (Block b : moving) keys.add(cellKey(b));
        keys.add(cellKey(core));
        return keys;
    }

    /** Pack a block's local coords into a long (piston lines are small + single-world). */
    private static long cellKey(Block b) {
        return ((long) (b.getX() & 0x3FFFFFF) << 38) | ((long) (b.getZ() & 0x3FFFFFF) << 12)
             | ((long) ((b.getY() + 2048) & 0xFFF));
    }

    /**
     * The payload the head carries: the glue-authored structure, else the single movable block directly ahead.
     * Returns an empty list for "nothing to carry" and {@code null} to <em>reject the whole move</em> (a glued
     * structure containing an immovable block — moving it would shear the structure).
     */
    private @Nullable List<Block> resolvePayload(PistonLine line) {
        List<Block> glued = glueManager.resolveStructure(new BlockAnchor(line.head(), () -> true));
        if (glued != null && !glued.isEmpty()) {
            for (Block b : glued) if (!MovableBlocks.isMovable(b, registry)) return null;
            return glued;
        }
        Block front = line.head().getRelative(line.frontFace());   // auto-grab the single block directly ahead
        return MovableBlocks.isMovable(front, registry) ? List.of(front) : List.of();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Movement: assemble the rod, slide R cells, disassemble (core protected)
    // ──────────────────────────────────────────────────────────────────────

    private void startMove(CustomBlockRegistry.LocationKey coreKey, PistonLine line, List<Block> payload,
                           BlockFace moveFace, int r) {
        // Capture the head's authored-glue offsets BEFORE assembly airs the head out to AIR (else readOffsets
        // reads a non-skull → null → glue is lost on land).
        int[] glueOffsets = new BlockAnchor(line.head(), () -> true).readOffsets();

        // The moving assembly: the rod (poles + head) + the head's payload, deduped so a payload cell can't
        // double a rod cell (double air-out/placement).
        List<Block> assembleBlocks = new ArrayList<>(line.rodBlocks());
        Set<Long> cells = new HashSet<>();
        for (Block b : line.rodBlocks()) cells.add(cellKey(b));
        for (Block b : payload) if (cells.add(cellKey(b))) assembleBlocks.add(b);
        // Ghost "fake shaft inside the core": a pole at the core cell, so the sliding rod is contiguous
        // (fills what would otherwise be a gap). Copies an existing pole's appearance/orientation.
        Block template = firstPole(line.rodBlocks());
        List<MechanismRegistry.GhostBlock> ghosts = template == null ? List.of()
            : List.of(new MechanismRegistry.GhostBlock(line.core().getLocation(), template));

        Mechanism mech = mechRegistry.assembleMechanism(MECH_TYPE, assembleBlocks, ghosts,
            line.head().getLocation(), AXIS_Y, null);
        if (mech == null) return;
        // Whichever rod block lands on the core cell is skipped (consumed); the ghost fills the gap.
        ((BasicMechanism) mech).setProtectedCells(Set.of(coreKey));

        Vector3f dir = new Vector3f(moveFace.getModX(), moveFace.getModY(), moveFace.getModZ());
        // Rebind glue on ANY disassembly (normal completion OR engine shutdown/vehicle death): head + payload
        // translate by the same R, so the pre-move offsets stay valid on the landed head.
        if (glueOffsets != null) {
            Location landedHead = line.head().getLocation().add(dir.x * r, dir.y * r, dir.z * r);
            mech.setOnDisassembled(placed ->
                new BlockAnchor(landedHead.getBlock(), () -> true).writeOffsets(glueOffsets));
        }

        // Load the network only while sliding (after the mech==null guard, so an assembly failure can't leak
        // phantom demand). Cleared on completion (advance) and on interruption (forget → removeNode).
        network.addTransientDemand(coreKey, MOVE_DEMAND);

        Location start = mech.pivot(); // block-centered after assembly
        Location target = start.clone().add(dir.x * r, dir.y * r, dir.z * r);
        active.put(coreKey, new ActiveMove(coreKey, mech, target, dir, assembleBlocks.size() + ghosts.size()));
    }

    private @Nullable Block firstPole(List<Block> rod) {
        for (Block b : rod) if (isType(b, POLE_ID)) return b;
        return null;
    }

    /** @return true when the move finished (disassembled) and should be dropped from {@code active}. */
    private boolean advance(ActiveMove m) {
        // Let the assembly's deferred mount + initial rotate(0) run before we drive the first move,
        // else the transform lands on still-unmounted displays (spawned pivot+2.5) → a ~0.5-block pop.
        // Mirrors RotationRotator's 2-tick startup delay.
        if (m.warmup > 0) { m.warmup--; return false; }
        Location cur = m.mech.pivot();
        double remaining = (m.target.getX() - cur.getX()) * m.dir.x
                         + (m.target.getY() - cur.getY()) * m.dir.y
                         + (m.target.getZ() - cur.getZ()) * m.dir.z;
        int[] stats = network.getNetworkStats(m.coreKey);
        float supply = stats != null ? Math.max(1, stats[0]) : 1;
        float step = clamp(SPEED_K * supply / Math.max(1, m.mass), MIN_STEP, MAX_STEP);

        if (remaining <= step + 1e-3) {
            m.mech.move(m.target, 0f);              // snap to the block-centered target cell
            m.mech.disassemble();                   // lands on-grid; core cell skipped; fires the glue-rebind hook
            network.clearTransientDemand(m.coreKey); // release the network load
            return true;
        }
        Location next = cur.clone().add(m.dir.x * step, m.dir.y * step, m.dir.z * step);
        m.mech.move(next, 0f);
        return false;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    private boolean isType(Block b, String id) {
        CustomHeadBlock t = registry.getTypeFromBlock(b);
        return t != null && id.equals(t.fullId());
    }

    private static boolean isClear(Block b) {
        Material m = b.getType();
        return m.isAir() || m == Material.WATER || m == Material.LAVA;
    }

    private static @Nullable Block blockOf(CustomBlockRegistry.LocationKey k) {
        World w = Bukkit.getWorld(k.worldId());
        return w == null ? null : w.getBlockAt(k.x(), k.y(), k.z());
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** A detected piston line. {@code extension} = front-pole count = cells the head sits past the core. */
    private record PistonLine(BlockFace frontFace, Block core, int backPoles, Block head, int frontPoles,
                              List<Block> rodBlocks) {
        int extension() { return frontPoles; }
    }

    private static final class ActiveMove {
        final CustomBlockRegistry.LocationKey coreKey;
        final Mechanism mech;
        final Location target;
        final Vector3f dir;
        final int mass;
        int warmup = 2;                        // ticks to wait before the first move (mount + rotate(0) first)
        ActiveMove(CustomBlockRegistry.LocationKey coreKey, Mechanism mech, Location target,
                   Vector3f dir, int mass) {
            this.coreKey = coreKey;
            this.mech = mech;
            this.target = target;
            this.dir = dir;
            this.mass = mass;
        }
    }
}
