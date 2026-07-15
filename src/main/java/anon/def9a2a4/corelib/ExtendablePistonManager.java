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

    private static final int TRIGGER_PERIOD = 4;         // ticks between idle-core scans
    private static final float MIN_STEP = 0.08f;         // blocks/tick (floor so it always progresses)
    private static final float SPEED_K = 0.5f;
    private static final int SETTLE_TICKS = 3;           // hold at the target before disassembly (client lerp)
    private static final Vector3f AXIS_Y = new Vector3f(0f, 1f, 0f);

    private final JavaPlugin plugin;
    private final CustomBlockRegistry registry;
    private final RotationNetwork network;
    private final MechanismRegistry mechRegistry;
    private final GlueManager glueManager;
    private final RotationConfig config;

    private final Set<CustomBlockRegistry.LocationKey> cores = new HashSet<>();
    private final Map<CustomBlockRegistry.LocationKey, ActiveMove> active = new HashMap<>();
    private final Set<CustomBlockRegistry.LocationKey> warnedCores = new HashSet<>();  // rate-limit tick warnings
    private int tickCounter = 0;

    ExtendablePistonManager(JavaPlugin plugin, CustomBlockRegistry registry,
                            RotationNetwork network, MechanismRegistry mechRegistry,
                            GlueManager glueManager, RotationConfig config) {
        this.plugin = plugin;
        this.registry = registry;
        this.network = network;
        this.mechRegistry = mechRegistry;
        this.glueManager = glueManager;
        this.config = config;
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
            .stateResolver(ExtendablePistonManager::coreForwardState)
            .onNeighborChange((b, face) -> recalcIfNode(b))
            .onChunkLoad((b, state) -> {
                CustomBlockRegistry.LocationKey k = CustomBlockRegistry.LocationKey.of(b);
                // Omni consumer: draws power from the first aligned shaft/gear on ANY face. Static base demand
                // (default 1, like drill/fan/hopper) so the piston participates in the power economy — it slows
                // co-resident consumers by their fair share, and its own speed scales with supply minus other
                // consumers' demand (its own base is added back in advance()).
                network.addNode(b, CORE_ID, RotationNetwork.Axis.Y,
                    RotationNetwork.NodeRole.CONSUMER, config.getPower("piston_core", 1), false, true, null);
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
        if (isRodSide(head.getRelative(BlockFace.UP)) || isRodSide(head.getRelative(BlockFace.DOWN)))
            return "idle_y";
        if (isRodSide(head.getRelative(BlockFace.EAST)) || isRodSide(head.getRelative(BlockFace.WEST)))
            return "idle_x";
        if (isRodSide(head.getRelative(BlockFace.NORTH)) || isRodSide(head.getRelative(BlockFace.SOUTH)))
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
    private static final float CAP_T = 0.75f;        // vertical cap anchor 0.25 past the face (plate u∈[0.25,0.75])
    private static final float WALL_CAP_OUT = 0.25f; // wall cap outer face flush with the block face (u∈[0,0.5])

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
        // Cap idx 1 — a 0.5-thick plate. Vertical and wall heads use DIFFERENT head skins whose decorated art
        // sits on different faces, so they need different rotation/scale:
        if (outward == BlockFace.UP || outward == BlockFace.DOWN) {
            // Vertical: @head_up art on ±Y, thin-in-Y, +Y→outward. Nudged 0.25 down; down-cap lifted 0.49
            // (0.5 − 0.01, the extra 0.01 pushing it clear of the block face below to avoid z-fighting).
            Vector3f capT = new Vector3f(o).mul(CAP_T).add(0f, -0.25f, 0f);
            if (outward == BlockFace.DOWN) capT.add(0f, 0.49f, 0f);
            return new Transformation(capT, r, new Vector3f(CAP_W, CAP_THICK, CAP_W), R_IDENTITY);
        }
        // Wall: @head_fwd art on ±Z, thin-in-Z, front(+Z)→outward, upright & vertically centred on the block.
        Vector3f capT = new Vector3f(o).mul(WALL_CAP_OUT).add(0f, 0.501f, 0f);
        return new Transformation(capT, faceRotation(outward), new Vector3f(CAP_W, CAP_W, CAP_THICK), R_IDENTITY);
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

    /** The axis side without a rod block (outward). Defaults to {@code +axis} if ambiguous. */
    private BlockFace outwardFace(Block head, BlockFace axisPos) {
        boolean posRod = isRodSide(head.getRelative(axisPos));
        boolean negRod = isRodSide(head.getRelative(axisPos.getOppositeFace()));
        if (posRod && !negRod) return axisPos.getOppositeFace();
        if (negRod && !posRod) return axisPos;
        return axisPos;
    }

    /** A head's rod side: an adjacent pole OR the core (a fully-retracted head caps directly onto the core). */
    private boolean isRodSide(Block b) {
        return isType(b, POLE_ID) || isType(b, CORE_ID);
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

    /**
     * Rotation that maps the model's +Z (front) face onto {@code outward}, for the wall cap's @head_fwd skin.
     * All rotations are about +Y, so the head stays upright. Only the four horizontal faces occur for walls.
     */
    private static AxisAngle4f faceRotation(BlockFace outward) {
        float h = (float) (Math.PI / 2), p = (float) Math.PI;
        return switch (outward) {
            case SOUTH -> R_IDENTITY;                     // +Z → +Z
            case NORTH -> new AxisAngle4f(p, 0f, 1f, 0f);  // +Z → -Z
            case EAST  -> new AxisAngle4f(h, 0f, 1f, 0f);  // +Z → +X
            case WEST  -> new AxisAngle4f(-h, 0f, 1f, 0f); // +Z → -X
            default    -> R_IDENTITY;
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
        warnedCores.remove(k);
        ActiveMove m = active.remove(k);
        // Land the rod (fires the glue hook) instead of orphaning displays. Guarded: forget() runs inside
        // unguarded chunk-unload / block-removed dispatchers, so a throw here would abort the rest of THEIR
        // cleanup and leak other blocks' state.
        if (m != null) safeDisassemble(m.mech, k);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Tick: advance active moves, then periodically scan idle cores for triggers
    // ──────────────────────────────────────────────────────────────────────

    private void tick() {
        if (!active.isEmpty()) {
            var it = active.values().iterator();
            while (it.hasNext()) {
                ActiveMove m = it.next();
                try {
                    if (advance(m)) { it.remove(); warnedCores.remove(m.coreKey); }
                } catch (Throwable t) {
                    // A faulted move must not freeze the shared ticker for every other piston. Drop it via the
                    // iterator (NOT active.remove → CME), land the rod best-effort (idempotent), warn once.
                    warnOnce(m.coreKey, "advance", t);
                    safeDisassemble(m.mech, m.coreKey);
                    it.remove();
                    warnedCores.remove(m.coreKey);   // re-arm: a fresh fault after this recovery re-logs once
                }
            }
        }
        if (++tickCounter % TRIGGER_PERIOD == 0 && !cores.isEmpty()) {
            for (CustomBlockRegistry.LocationKey k : new ArrayList<>(cores)) {
                if (active.containsKey(k)) continue;
                try {
                    maybeTrigger(k);
                } catch (Throwable t) {
                    // Leave k in `cores` (re-evaluated next scan); don't touch `active` (k isn't in it here).
                    warnOnce(k, "trigger", t);
                }
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

        // CW slides forward, CCW backward. Reach = the OPPOSITE side's pole reserve. Both heads are sticky
        // (each carries its adjacent block), so the leading head pushes and the trailing head pulls.
        boolean cw = dir == RotationNetwork.SpinDirection.CW;
        BlockFace moveFace = cw ? line.frontFace() : line.frontFace().getOppositeFace();
        int reserve = cw ? line.backPoles() : line.frontPoles();

        List<Block> payload = collectPayload(line);
        if (payload == null) return;                                // a glued structure holds an immovable — abort
        // Full moving assembly (rod + both heads' payloads); the core cell is passable (ghost-filled).
        List<Block> moving = new ArrayList<>(line.rodBlocks());
        moving.addAll(payload);
        Set<Long> footprint = footprintKeys(moving, line.core());
        // The ghost pole slides OUT of the core cell too — scan its ray as well, or a block that survived
        // capture right beside the core (an excluded-barrier casing) silently eats the ghost's landing.
        moving.add(line.core());
        int r = clearForAll(moving, footprint, moveFace, reserve);
        if (r > 0) startMove(coreKey, line, payload, moveFace, r, dir);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Detection: find the pole/head line through the core
    // ──────────────────────────────────────────────────────────────────────

    private @Nullable PistonLine detectLine(Block core) {
        // Forward is derived from the actual pole GEOMETRY (the faces holding a head-terminated
        // run), not trusted from the stored fwd_* state: that state is resolved once from the
        // placement click-face (coreForwardState) and is wrong for many natural build orders —
        // a floor-placed core with a horizontal rod, or a core capping a pre-built rod (forward
        // would point away from it). The stored state only breaks ties (a double-ended rod keeps
        // the player's CW-slides-forward orientation) and is self-healed below on mismatch so the
        // core's visuals and future scans agree with the geometry.
        BlockFace stored = parseForward(registry.getState(core));
        BlockFace forward = null;
        for (BlockFace f : Faces.CARDINAL) {
            if (!endsWithHead(walk(core, f))) continue;
            if (forward == null) forward = f;      // first head-terminated face (deterministic order)
            if (f == stored) { forward = f; break; } // stored orientation wins when geometry-valid
        }
        if (forward == null) return null;            // no pole run ends in a head on any face
        healForwardState(core, forward, stored);

        List<Block> frontRun = walk(core, forward);                 // poles… then head (forward side)
        List<Block> backRun = walk(core, forward.getOppositeFace()); // poles… then optionally a back head
        Block frontHead = frontRun.get(frontRun.size() - 1);
        // A back head makes the piston double-ended (it leads the CCW/backward stroke). Whether or not the back
        // run ends in a head, the POLE reserve excludes it — so the reach never consumes a head at the core.
        Block backHead = endsWithHead(backRun) ? backRun.get(backRun.size() - 1) : null;
        int frontPoles = frontRun.size() - 1;
        int backPoles = backHead != null ? backRun.size() - 1 : backRun.size();
        List<Block> rod = new ArrayList<>(frontRun.size() + backRun.size());
        rod.addAll(frontRun);
        rod.addAll(backRun);
        return new PistonLine(forward, core, backPoles, frontHead, backHead, frontPoles, rod);
    }

    /** One-time self-heal: re-point the stored {@code fwd_*} state (and with it the core's display
     *  orientation) at the geometry-derived forward when they disagree — placement only guessed
     *  from the click-face; the pole line is authoritative. Idempotent: once healed, the next
     *  scan's stored state matches and this is a no-op. */
    private void healForwardState(Block core, BlockFace forward, @Nullable BlockFace stored) {
        if (forward == stored) return;
        String target = "fwd_" + forward.name().toLowerCase(java.util.Locale.ROOT);
        registry.setState(core, target);
        CustomHeadBlock type = registry.getTypeFromBlock(core);
        if (type != null) registry.applyConfig(core, type, target, 0);
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
     * The full moving payload: BOTH heads' payloads, so the piston is sticky on both ends (leading head pushes,
     * trailing head pulls — the rigid rod carries both blocks). Each head contributes its authored glue
     * structure, else the single movable block off its outward face. Returns {@code null} to <em>reject the
     * whole move</em> if any glued block is immovable (shear guard); an empty list means "nothing to carry".
     * Duplicates are fine — {@code startMove} dedups by cell.
     *
     * <p>Every capture path is filtered through the piston's {@link MoverExclusion} set (core + rod +
     * drive train): a casing chain running core→head must dead-end at the core, never pull it into the
     * payload — a captured core is aired out and then silently consumed by its own protected cell.
     */
    private @Nullable List<Block> collectPayload(PistonLine line) {
        List<Block> hardware = new ArrayList<>(line.rodBlocks());
        hardware.add(line.core());
        Set<CustomBlockRegistry.LocationKey> excluded = MoverExclusion.exclusionFor(
            network, CustomBlockRegistry.LocationKey.of(line.core()), hardware);
        List<Block> out = new ArrayList<>();
        if (!addHeadPayload(out, line.frontHead(), line.frontFace(), excluded)) return null;
        if (line.backHead() != null
                && !addHeadPayload(out, line.backHead(), line.frontFace().getOppositeFace(), excluded)) return null;
        // Slime-style casing spread: a casing in the payload drags its neighbours (transitively).
        return CasingExpansion.withDerived(out, registry, glueManager.maxSize(),
            excluded, MoverExclusion::blockedParticle);
    }

    /**
     * Add {@code head}'s payload to {@code out}: its glued structure, else the single movable block off its
     * {@code outFace} (outward, away from the rod). @return {@code false} to reject the move (glued immovable).
     */
    private boolean addHeadPayload(List<Block> out, Block head, BlockFace outFace,
                                   Set<CustomBlockRegistry.LocationKey> excluded) {
        List<Block> glued = glueManager.resolveStructure(new BlockAnchor(head, () -> true),
            excluded, MoverExclusion::blockedParticle);
        if (glued != null && !glued.isEmpty()) {
            for (Block b : glued) if (!MovableBlocks.isMovable(b, registry)) return false;
            out.addAll(glued);
            return true;
        }
        Block grab = head.getRelative(outFace);
        if (excluded.contains(CustomBlockRegistry.LocationKey.of(grab))) {   // own hardware/drive train
            MoverExclusion.blockedParticle(head, grab);
            return true;
        }
        if (MovableBlocks.isMovable(grab, registry)) out.add(grab);
        return true;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Movement: assemble the rod, slide R cells, disassemble (core protected)
    // ──────────────────────────────────────────────────────────────────────

    private void startMove(CustomBlockRegistry.LocationKey coreKey, PistonLine line, List<Block> payload,
                           BlockFace moveFace, int r, RotationNetwork.SpinDirection spinDir) {
        // Capture BOTH heads' authored-glue offsets BEFORE assembly airs the heads out to AIR (else readOffsets
        // reads a non-skull → null → glue is lost on land). The back head's landed cell is the front head's
        // landing (= pivot) plus the rigid front→back offset (yaw 0 → the integer offset is preserved).
        final int[] frontGlue = new BlockAnchor(line.frontHead(), () -> true).readOffsets();
        final int[] backGlue = line.backHead() != null
            ? new BlockAnchor(line.backHead(), () -> true).readOffsets() : null;
        final int bdx = line.backHead() != null ? line.backHead().getX() - line.frontHead().getX() : 0;
        final int bdy = line.backHead() != null ? line.backHead().getY() - line.frontHead().getY() : 0;
        final int bdz = line.backHead() != null ? line.backHead().getZ() - line.frontHead().getZ() : 0;

        // The moving assembly: the rod (poles + head(s)) + the payload, deduped so a payload cell can't
        // double a rod cell (double air-out/placement).
        List<Block> assembleBlocks = new ArrayList<>(line.rodBlocks());
        Set<Long> cells = new HashSet<>();
        cells.add(cellKey(line.core()));   // the core must never assemble — gather excludes it; this backstops
        for (Block b : line.rodBlocks()) cells.add(cellKey(b));
        for (Block b : payload) if (cells.add(cellKey(b))) assembleBlocks.add(b);
        // Ghost "fake shaft inside the core": a pole at the core cell, so the sliding rod is contiguous
        // (fills what would otherwise be a gap). Copies an existing pole's appearance/orientation.
        Block template = firstPole(line.rodBlocks());
        List<MechanismRegistry.GhostBlock> ghosts = template == null ? List.of()
            : List.of(new MechanismRegistry.GhostBlock(line.core().getLocation(), template));

        // Centered pivot (mirror the rotator): the engine re-centers only Y of the vehicle spawn, so a corner
        // pivot makes the displays spawn 0.5 off in X/Z until the first move(). Everything downstream re-snaps
        // X/Z to floor+0.5, so this is a pure display fix (identical block math).
        // Pivot on the FRONT head (mech.pivot() must track it — the back-head rebind offset is relative to it).
        Mechanism mech = mechRegistry.assembleMechanism(MECH_TYPE, assembleBlocks, ghosts,
            line.frontHead().getLocation().add(0.5, 0, 0.5), AXIS_Y, null);
        if (mech == null) return;

        // Guard the post-assembly setup: a throw before active.put would leave an assembled-but-untracked mech
        // (aired-out rod = a permanent hole). Protect the core FIRST so a guard-triggered disassemble is safe.
        try {
            ((BasicMechanism) mech).setProtectedCells(Set.of(coreKey));
            Vector3f dir = new Vector3f(moveFace.getModX(), moveFace.getModY(), moveFace.getModZ());
            // Runs on ANY disassembly (completion, power-cut/reverse stop, forget, engine teardown), AFTER the
            // whole rod is placed. Two jobs:
            //   1. Re-resolve the display of EVERY landed resolver-block — blocks land in list order with no
            //      neighbour guarantee, so a head placed before its pole resolves against air and defaults to
            //      +axis (a down head flips up). Covers the rod head AND any pushed payload head; by hook time
            //      all cells are placed and PDC-stamped, so the resolver sees real neighbours.
            //   2. Rebind authored glue onto BOTH landed heads: the front head at the final pivot, the back
            //      head at pivot + the rigid front→back offset.
            mech.setOnDisassembled(placed -> {
                for (Block b : placed) {
                    CustomHeadBlock t = registry.getTypeFromBlock(b);
                    if (t != null && t.displayTransformResolver() != null) {
                        registry.resolveDisplayTransforms(b, t, registry.getState(b));
                    }
                }
                Location p = mech.pivot();
                if (frontGlue != null) {
                    new BlockAnchor(p.getWorld().getBlockAt(p.getBlockX(), p.getBlockY(), p.getBlockZ()),
                        () -> true).writeOffsets(frontGlue);
                }
                if (backGlue != null) {
                    new BlockAnchor(p.getWorld().getBlockAt(p.getBlockX() + bdx, p.getBlockY() + bdy,
                        p.getBlockZ() + bdz), () -> true).writeOffsets(backGlue);
                }
            });
            Location start = mech.pivot(); // block-centered after assembly
            Location target = start.clone().add(dir.x * r, dir.y * r, dir.z * r);
            active.put(coreKey, new ActiveMove(coreKey, mech, start, target, dir,
                assembleBlocks.size() + ghosts.size(), spinDir));
        } catch (Throwable t) {
            safeDisassemble(mech, coreKey);   // restore the aired-out rod instead of leaking the mech
            throw t;
        }
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
        // Settling: parked at the block-centered target, waiting out the client's ~3-tick entity interpolation
        // so the rendered displays converge onto the target BEFORE the display→block swap — else the rendered
        // rod trails the server by ~2-3×step and the landing reads as a forward teleport of the final gap.
        if (m.settle >= 0) {
            if (m.settle-- == 0) {
                m.mech.disassemble();   // lands on-grid; core cell skipped; fires the glue/orientation hook
                return true;
            }
            return false;
        }
        Location cur = m.mech.pivot();
        // Stop-then-reverse: if power is cut OR the spin direction flips mid-slide, stop at the NEXT whole block
        // (don't run to the full planned target). Retarget to start + dir·ceil(progress); `start`/`target` are
        // block-centered so the arrival branch lands on-grid, and the next trigger scan drives the other way.
        if (!network.isPowered(m.coreKey) || network.getDirection(m.coreKey) != m.spinDir) {
            double progress = (cur.getX() - m.start.getX()) * m.dir.x
                            + (cur.getY() - m.start.getY()) * m.dir.y
                            + (cur.getZ() - m.start.getZ()) * m.dir.z;
            int steps = Math.max(0, (int) Math.ceil(progress - 1e-6));
            m.target = m.start.clone().add(m.dir.x * steps, m.dir.y * steps, m.dir.z * steps);
        }
        double remaining = (m.target.getX() - cur.getX()) * m.dir.x
                         + (m.target.getY() - cur.getY()) * m.dir.y
                         + (m.target.getZ() - cur.getZ()) * m.dir.z;
        // Speed ∝ power/mass, capped by the configurable piston max-step, floored by MIN_STEP. `power` is
        // supply minus OTHER consumers' demand: add back the piston's OWN base demand (which is folded into
        // stats[1]) so it doesn't throttle its own speed — the rotator excludes its own demand the same way.
        int[] stats = network.getNetworkStats(m.coreKey);
        int base = config.getPower("piston_core", 1);
        int power = (stats != null ? stats[0] - stats[1] : 0) + base;
        float step = clamp(SPEED_K * power / Math.max(1, m.mass), MIN_STEP, (float) config.pistonMaxStep);

        if (remaining <= step + 1e-3) {
            // Rising: carry riders up by the final delta BEFORE the colliders teleport up (see below).
            if (m.dir.y > 0) carryUp(m, cur, m.target);
            m.mech.move(m.target, 0f);   // snap to the block-centered target cell
            m.settle = SETTLE_TICKS;     // hold here while the client catches up, then disassemble (above)
            return false;
        }
        Location next = cur.clone().add(m.dir.x * step, m.dir.y * step, m.dir.z * step);
        // Rising: carry any entities standing on the platform up by this tick's delta BEFORE the colliders
        // teleport up, so the shulker boxes don't clip through a rider (who would otherwise fall through).
        if (m.dir.y > 0) carryUp(m, cur, next);
        m.mech.move(next, 0f);
        return false;
    }

    /** Lift riders standing on the mechanism by the vertical gap between {@code cur} and {@code next}. */
    private static void carryUp(ActiveMove m, Location cur, Location next) {
        double dy = next.getY() - cur.getY();
        if (dy > 0) m.mech.carryRidersUp(dy);
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

    /**
     * Core forward state from the placed-against face. {@code placement_state_map} never receives UP for a
     * player head (a ceiling click folds to DOWN), so ceilings can't reach {@code fwd_down} via YAML — a
     * resolver can, by reading the real support→block face: forward = {@code getBlockAgainst().getFace(block)}.
     * Falls back to {@code fwd_up} when either the support or the face is unavailable (non-adjacent placement).
     */
    private static String coreForwardState(org.bukkit.event.block.BlockPlaceEvent event) {
        Block against = event.getBlockAgainst();
        BlockFace f = against == null ? null : against.getFace(event.getBlockPlaced());
        return f == null ? "fwd_up" : "fwd_" + f.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Disassemble a mechanism without letting a throw escape (disassemble() is idempotent). Warns once. */
    private void safeDisassemble(Mechanism mech, CustomBlockRegistry.LocationKey coreKey) {
        try {
            mech.disassemble();
        } catch (Throwable t) {
            warnOnce(coreKey, "disassemble", t);
        }
    }

    /** Log a piston fault at WARNING, but only the first time per core, so a stuck mech can't spam 20×/s. */
    private void warnOnce(CustomBlockRegistry.LocationKey coreKey, String phase, Throwable t) {
        if (warnedCores.add(coreKey)) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                "ExtendablePiston: " + phase + " threw at " + coreKey + "; recovering", t);
        }
    }

    /** A detected piston line. {@code frontHead} always exists (the {@code fwd_*} side); {@code backHead} is
     *  present only for a double-ended piston. {@code extension} = front-pole count = cells past the core. */
    private record PistonLine(BlockFace frontFace, Block core, int backPoles, Block frontHead,
                              @Nullable Block backHead, int frontPoles, List<Block> rodBlocks) {
        int extension() { return frontPoles; }
    }

    private static final class ActiveMove {
        final CustomBlockRegistry.LocationKey coreKey;
        final Mechanism mech;
        final Location start;                  // block-centered assembly pivot (base for the stop retarget)
        Location target;                       // mutable: retargeted to the next whole block on power-cut/reverse
        final Vector3f dir;
        final int mass;
        final RotationNetwork.SpinDirection spinDir;  // the spin that started this move; a flip stops it
        int warmup = 2;                        // ticks to wait before the first move (mount + rotate(0) first)
        int settle = -1;                       // ≥0: parked at target, counting down to disassembly (client lerp)
        ActiveMove(CustomBlockRegistry.LocationKey coreKey, Mechanism mech, Location start, Location target,
                   Vector3f dir, int mass, RotationNetwork.SpinDirection spinDir) {
            this.coreKey = coreKey;
            this.mech = mech;
            this.start = start;
            this.target = target;
            this.dir = dir;
            this.mass = mass;
            this.spinDir = spinDir;
        }
    }
}
