package anon.def9a2a4.corelib;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
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

    private static final int CORE_POWER = 2;
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
                network.addNode(b, CORE_ID, RotationNetwork.Axis.Y,
                    RotationNetwork.NodeRole.CONSUMER, CORE_POWER, false, true, null);
                cores.add(k);
            })
            .onChunkUnload(b -> forget(b))
            .onBlockRemoved((b, state) -> forget(b))
            .build());

        overlayStructural(POLE_ID);
        overlayHead();

        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** Pole is an inert structural block (no network behaviour). */
    private void overlayStructural(String id) {
        CustomHeadBlock t = registry.getType(id);
        if (t == null) {
            plugin.getLogger().warning("ExtendablePiston: block '" + id + "' not found — skipping");
            return;
        }
        registry.register(t.toBuilder().drillable(false).cancelPistons(true).build());
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
            .displayTransformResolver((block, state, cfg, idx) -> headTransform(block, state, idx))
            .build());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Head display: pole-stub (idx 0) + cap (idx 1) pointing away from the pole
    // ──────────────────────────────────────────────────────────────────────

    private static final AxisAngle4f R_IDENTITY = new AxisAngle4f(0f, 0f, 0f, 1f);
    private static final float POLE_W = 1.002f;      // stub width  (0.501 block, matches piston_pole)
    private static final float STUB_LEN = 1.8f;      // stub length (0.9 block "on the outward side")
    private static final float CAP_W = 2.0f;         // cap width   (2x pole ⇒ ~full block)
    private static final float CAP_THICK = 0.5f;     // cap depth along the axis (thin disc)
    private static final float STUB_T = 0.5f;        // stub outer end at the block face
    private static final float CAP_T = 0.75f;        // cap centre 0.25 past the block face (0.5 + 0.25)

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
        if (idx == 0) {
            // Toothed stub: long axis lies along the head's axis, outer end at the block face.
            return new Transformation(o.mul(STUB_T, new Vector3f()),
                stubRotation(outward), new Vector3f(POLE_W, STUB_LEN, POLE_W), R_IDENTITY);
        }
        // Cap: thin disc perpendicular to the axis, pushed 0.25 past the face.
        return new Transformation(o.mul(CAP_T, new Vector3f()),
            capRotation(outward), new Vector3f(CAP_W, CAP_THICK, CAP_W), R_IDENTITY);
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
     * Orient the pole-stub mesh (local long-axis = Y, extends toward local −Y) so its outer end points at
     * {@code outward}. Mirrors the piston_pole YAML rotations, with a 180° flip for the negative direction.
     */
    private static AxisAngle4f stubRotation(BlockFace outward) {
        float h = (float) (Math.PI / 2), p = (float) Math.PI;
        return switch (outward) {
            case DOWN  -> R_IDENTITY;                     // mesh already extends −Y
            case UP    -> new AxisAngle4f(p, 1f, 0f, 0f); // flip to +Y
            case WEST  -> new AxisAngle4f(h, 0f, 0f, 1f); // +90 about Z
            case EAST  -> new AxisAngle4f(-h, 0f, 0f, 1f);
            case NORTH -> new AxisAngle4f(-h, 1f, 0f, 0f); // −90 about X
            case SOUTH -> new AxisAngle4f(h, 1f, 0f, 0f);
            default    -> R_IDENTITY;
        };
    }

    /** Orient the flat cap disc perpendicular to the axis (sign-independent — the disc is symmetric). */
    private static AxisAngle4f capRotation(BlockFace outward) {
        float h = (float) (Math.PI / 2);
        return switch (outward) {
            case UP, DOWN       -> R_IDENTITY;
            case EAST, WEST     -> new AxisAngle4f(h, 0f, 0f, 1f);
            case NORTH, SOUTH   -> new AxisAngle4f(-h, 1f, 0f, 0f);
            default             -> R_IDENTITY;
        };
    }

    private void recalcIfNode(Block b) {
        CustomBlockRegistry.LocationKey k = CustomBlockRegistry.LocationKey.of(b);
        if (network.getNode(k) != null) network.recalculate(k);
    }

    private void forget(Block b) {
        CustomBlockRegistry.LocationKey k = CustomBlockRegistry.LocationKey.of(b);
        network.removeNode(k);
        cores.remove(k);
        active.remove(k);
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
        RotationNetwork.SpinDirection dir = network.getDirection(coreKey);
        if (dir == null) return;
        Block core = blockOf(coreKey);
        if (core == null) return;
        PistonLine line = detectLine(core);
        if (line == null) return;

        List<Block> payload = resolvePayload(line);
        if (dir == RotationNetwork.SpinDirection.CW) {              // extend
            Block edge = leadingEdge(line, payload);
            int r = Math.min(line.backPoles(), clearAhead(edge, line.frontFace(), line.backPoles()));
            if (r > 0) startMove(coreKey, line, payload, line.frontFace(), r);
        } else {                                                    // CCW → retract
            int r = Math.min(line.extension(), clearBehind(line, line.extension()));
            if (r > 0) startMove(coreKey, line, payload, line.frontFace().getOppositeFace(), r);
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

    private int clearAhead(Block edge, BlockFace forward, int max) {
        Block b = edge;
        int n = 0;
        for (int i = 0; i < max; i++) {
            b = b.getRelative(forward);
            if (isClear(b)) n++; else break;
        }
        return n;
    }

    private int clearBehind(PistonLine line, int max) {
        BlockFace back = line.frontFace().getOppositeFace();
        Block b = line.core();
        int n = 0;
        for (int i = 0; i < max; i++) {
            b = b.getRelative(back);
            if (isClear(b)) n++; else break;
        }
        return n;
    }

    /** The payload the head carries: the glue-authored structure, else the single block directly ahead. */
    private List<Block> resolvePayload(PistonLine line) {
        List<Block> glued = glueManager.resolveStructure(new BlockAnchor(line.head(), () -> true));
        if (glued != null && !glued.isEmpty()) return glued;
        Block front = line.head().getRelative(line.frontFace());
        return front.getType().isAir() ? List.of() : List.of(front);
    }

    /** Furthest-forward cell of the moving assembly (head or a payload block) — for the obstruction check. */
    private static Block leadingEdge(PistonLine line, List<Block> payload) {
        Block edge = line.head();
        int best = forwardCoord(edge, line.frontFace());
        for (Block b : payload) {
            int c = forwardCoord(b, line.frontFace());
            if (c > best) { best = c; edge = b; }
        }
        return edge;
    }

    private static int forwardCoord(Block b, BlockFace f) {
        return b.getX() * f.getModX() + b.getY() * f.getModY() + b.getZ() * f.getModZ();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Movement: assemble the rod, slide R cells, disassemble (core protected)
    // ──────────────────────────────────────────────────────────────────────

    private void startMove(CustomBlockRegistry.LocationKey coreKey, PistonLine line, List<Block> payload,
                           BlockFace moveFace, int r) {
        // The moving assembly: the rod (poles + head) + the head's payload (glued blocks or the block ahead).
        List<Block> assembleBlocks = new ArrayList<>(line.rodBlocks());
        assembleBlocks.addAll(payload);
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

        Location start = mech.pivot(); // block-centered after assembly
        Vector3f dir = new Vector3f(moveFace.getModX(), moveFace.getModY(), moveFace.getModZ());
        Location target = start.clone().add(dir.x * r, dir.y * r, dir.z * r);
        // Glue rebind: head + payload translate together, so the same offsets apply to the moved head.
        int[] glueOffsets = new BlockAnchor(line.head(), () -> true).readOffsets();
        Location newHeadLoc = line.head().getLocation().add(dir.x * r, dir.y * r, dir.z * r);
        active.put(coreKey, new ActiveMove(coreKey, mech, target, dir,
            assembleBlocks.size() + ghosts.size(), glueOffsets, newHeadLoc));
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
            m.mech.move(m.target, 0f);   // snap to the block-centered target cell
            m.mech.disassemble();        // lands on-grid; core cell skipped
            if (m.glueOffsets != null && m.newHeadLoc.getWorld() != null) {
                // Head + payload moved together → identical offsets, new origin. Rebind glue on the landed head.
                new BlockAnchor(m.newHeadLoc.getBlock(), () -> true).writeOffsets(m.glueOffsets);
            }
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
        final int @Nullable [] glueOffsets;   // non-null if the head had authored glue to rebind on land
        final Location newHeadLoc;
        int warmup = 2;                        // ticks to wait before the first move (mount + rotate(0) first)
        ActiveMove(CustomBlockRegistry.LocationKey coreKey, Mechanism mech, Location target,
                   Vector3f dir, int mass, int @Nullable [] glueOffsets, Location newHeadLoc) {
            this.coreKey = coreKey;
            this.mech = mech;
            this.target = target;
            this.dir = dir;
            this.mass = mass;
            this.glueOffsets = glueOffsets;
            this.newHeadLoc = newHeadLoc;
        }
    }
}
