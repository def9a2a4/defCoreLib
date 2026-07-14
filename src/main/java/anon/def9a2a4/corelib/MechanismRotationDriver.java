package anon.def9a2a4.corelib;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.Inventory;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Makes rotation-power parts FUNCTION while assembled into a moving mechanism (minecart, door,
 * drawbridge). Assembly turns the real blocks to AIR, so the world-keyed {@link RotationNetwork}
 * can't see them — instead, each mechanism carries its own network over mechanism-LOCAL cells
 * (invariant while assembled), solved by the pure {@link RotationSolver} and driven from
 * {@link MechanismRegistry#tickMechanisms}:
 *
 * <ul>
 *   <li><b>Engines</b> burn fuel from their travelling {@code MechanismBlockData.storage}
 *       (the world fuel counter dies at assembly — {@code onBlockRemoved} clears it) and toggle
 *       {@code running_/idle_}.</li>
 *   <li><b>Power + direction</b> re-solve only when a source flips (topology is frozen while
 *       assembled; world redstone can't reach a riding block). Direction lands on
 *       {@code MechanismBlockData.spinReversed}, which {@code updateAnimatedDisplays} reads every
 *       tick — so meshed gears visually counter-rotate and conflicting sources jam.</li>
 *   <li><b>Consumers</b> actuate at their LIVE world position ({@code BasicMechanism.liveCell}/
 *       {@code liveFacing}) at their own configured tick interval: the drill mines the block it
 *       faces (drops land world-side — an on-board suction hopper collects them), the placer
 *       places from its travelling storage, the suction hopper vacuums into it. World-mutating
 *       consumers are gated on near-cardinal orientation (mid-turn cells are ambiguous).</li>
 * </ul>
 *
 * Which blocks participate, and how, is data-driven via the {@code mechanism:} section of
 * rotation-config.yml ({@link RotationConfig#mechMeta}); power comes from the same {@code power:}
 * map the static network uses. Custom kinds beyond drill/placer/suction_hopper still transmit,
 * supply, or add demand + spin visuals — only those three ids have world effects here.
 */
final class MechanismRotationDriver {

    /** Engine fuel burns in engine-ticks: 1 per 20 game ticks, matching the static overlay. */
    private static final int ENGINE_INTERVAL = 20;

    /** State prefixes this driver may rewrite. Anything else (e.g. a dynamo level readout) is a
     *  non-rotation visual state — leave it alone; the node still participates in the graph. */
    private static final Set<String> SWAPPABLE_PREFIXES = Set.of("idle", "spinning", "running", "locked");

    private static final String PLACER = "mech:placer";

    private final CustomBlockRegistry registry;
    private final EngineFuelManager fuelManager;
    private final RotationConfig config;

    private final Map<UUID, MechState> states = new HashMap<>();

    MechanismRotationDriver(CustomBlockRegistry registry, EngineFuelManager fuelManager,
                            RotationConfig config) {
        this.registry = registry;
        this.fuelManager = fuelManager;
        this.config = config;
    }

    /** One rotation part of a mechanism — immutable while assembled. */
    private record NodeSpec(int blockIndex, String typeId, RotationConfig.MechRotationMeta meta,
                            int cellX, int cellY, int cellZ,
                            RotationNetwork.Axis axis, boolean omni, @Nullable BlockFace omniExcludedFace,
                            RotationNetwork.SpinDirection dirPref,
                            int power, int actuateInterval) {}

    /** Staged-break progress + last target cell of one riding drill (the target moves with the
     *  mechanism, so the world-keyed static map can't serve it). */
    private static final class DrillTrack {
        RotationBlocks.@Nullable DrillState state;
        @Nullable Location lastTarget;
    }

    private static final class MechState {
        final List<NodeSpec> specs;
        RotationSolver.@Nullable Result result;
        boolean dirty = true;
        final Map<Integer, Integer> engineFuel = new HashMap<>();     // blockIndex → engine-ticks left
        final Map<Integer, Boolean> engineRunning = new HashMap<>();  // blockIndex → supplying?
        final Map<Integer, DrillTrack> drills = new HashMap<>();      // blockIndex → break progress

        MechState(List<NodeSpec> specs) { this.specs = specs; }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Lifecycle (called by MechanismRegistry)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Build the mechanism's rotation network from its captured blocks. Indices ≥
     * {@code realBlockCount} are ghost blocks (appearance-only copies that may overlap real
     * cells) and never participate.
     */
    void onAssembled(BasicMechanism mech, int realBlockCount) {
        List<NodeSpec> specs = new ArrayList<>();
        for (int i = 0; i < realBlockCount; i++) {
            MechanismBlockData mb = mech.getBlock(i);
            if (mb.customTypeId == null) continue;
            RotationConfig.MechRotationMeta meta = config.mechMeta(mb.customTypeId);
            if (meta == null) continue;

            Vector3f t = mb.localTransform.getTranslation(new Vector3f());
            int cx = Math.round(t.x), cy = Math.round(t.y), cz = Math.round(t.z);

            // Local facing: same convention as the drill's facing PDC (wall head → its facing,
            // floor head → DOWN). Used for FROM_FACING axes and the omni mount face.
            BlockFace localFacing = mb.blockData instanceof org.bukkit.block.data.Directional d
                ? d.getFacing() : BlockFace.DOWN;

            RotationNetwork.Axis axis = switch (meta.axisRule()) {
                case FROM_STATE -> RotationNetwork.axisFromState(
                    mb.customState() != null ? mb.customState() : "idle_y");
                case FIXED_Y -> RotationNetwork.Axis.Y;
                case FROM_FACING -> RotationNetwork.axisFromFace(localFacing);
                case OMNI -> RotationNetwork.Axis.Y; // nominal — omni edges ignore the axis
            };
            // Placer is facing-conditional (mirrors RotationBlocks.overlayPlacer via the shared
            // helper): a ceiling placer (localFacing DOWN) is omni excluding UP; a wall placer stays
            // single-axis Y. The typeId guard is REQUIRED, not cosmetic — a floor drill also has
            // localFacing == DOWN (FROM_STATE), and without the guard it would wrongly become omni.
            BlockFace placerExcluded = PLACER.equals(mb.customTypeId)
                ? RotationBlocks.placerOmniExcludedFace(localFacing) : null;
            boolean omni = placerExcluded != null || meta.axisRule() == RotationConfig.MechAxisRule.OMNI;
            BlockFace omniExcluded = placerExcluded != null ? placerExcluded
                : meta.axisRule() == RotationConfig.MechAxisRule.OMNI
                    ? (localFacing == BlockFace.DOWN ? BlockFace.DOWN : localFacing.getOppositeFace())
                    : null;

            specs.add(new NodeSpec(i, mb.customTypeId, meta, cx, cy, cz, axis, omni, omniExcluded,
                mb.spinReversed ? RotationNetwork.SpinDirection.CCW : RotationNetwork.SpinDirection.CW,
                power(mb.customTypeId), actuateInterval(mb.customTypeId)));
        }
        if (specs.isEmpty()) return;

        MechState st = new MechState(specs);
        for (NodeSpec s : specs) {
            if (s.meta().kind() == RotationConfig.MechKind.ENGINE) {
                String cur = mech.getBlock(s.blockIndex()).customState();
                st.engineRunning.put(s.blockIndex(), cur != null && cur.startsWith("running_"));
                st.engineFuel.put(s.blockIndex(), 0); // world counter died at assembly; refuel from storage
            }
        }
        states.put(mech.id(), st);
        solveAndApply(mech, st);
    }

    /** Per-tick drive; {@code tickAge} = ticks since assembly (same clock as display animation). */
    void tick(BasicMechanism mech, long tickAge) {
        MechState st = states.get(mech.id());
        if (st == null) return;

        if (tickAge % ENGINE_INTERVAL == 0) tickEngines(mech, st);
        if (st.dirty) solveAndApply(mech, st);
        actuateConsumers(mech, st, tickAge);
    }

    void onRemoved(BasicMechanism mech) {
        MechState st = states.remove(mech.id());
        if (st == null) return;
        // Clear any live crack animation — the blocks are being restored/dropped.
        st.drills.forEach((idx, track) -> {
            if (track.state != null && track.lastTarget != null) {
                RotationBlocks.clearBreakAnimationAt(track.lastTarget, sourceId(mech, idx));
            }
        });
    }

    // ──────────────────────────────────────────────────────────────────────
    // Engines
    // ──────────────────────────────────────────────────────────────────────

    /** Mirrors the static engine overlay's cycle: refill from storage when the counter is empty,
     *  auto-start when fueled, burn one engine-tick while running, stop at zero. */
    private void tickEngines(BasicMechanism mech, MechState st) {
        for (NodeSpec s : st.specs) {
            if (s.meta().kind() != RotationConfig.MechKind.ENGINE) continue;
            int idx = s.blockIndex();
            boolean running = st.engineRunning.getOrDefault(idx, false);
            int fuel = st.engineFuel.getOrDefault(idx, 0);

            if (fuel <= 0) {
                Inventory inv = mech.getStorage(idx);
                if (inv != null) fuel += RotationBlocks.consumeOneFuelItem(inv, fuelManager);
            }
            if (!running && fuel > 0) {
                st.engineRunning.put(idx, true);
                st.dirty = true;
            } else if (running) {
                fuel--;
                if (fuel <= 0) {
                    st.engineRunning.put(idx, false);
                    st.dirty = true;
                }
            }
            st.engineFuel.put(idx, Math.max(fuel, 0));
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Solve + visuals
    // ──────────────────────────────────────────────────────────────────────

    private void solveAndApply(BasicMechanism mech, MechState st) {
        List<RotationSolver.Node> nodes = new ArrayList<>(st.specs.size());
        for (NodeSpec s : st.specs) {
            boolean supplies = switch (s.meta().kind()) {
                case ENGINE -> st.engineRunning.getOrDefault(s.blockIndex(), false);
                case CONSTANT_SOURCE -> true;
                default -> false;
            };
            int demand = s.meta().kind() == RotationConfig.MechKind.CONSUMER ? s.power() : 0;
            nodes.add(new RotationSolver.Node(s.cellX(), s.cellY(), s.cellZ(), s.axis(),
                supplies ? s.power() : 0, demand, s.meta().gearLike(),
                s.omni(), s.omniExcludedFace(),
                supplies ? s.dirPref() : null));
        }
        RotationSolver.Result result = RotationSolver.solve(nodes);
        st.result = result;
        st.dirty = false;

        for (int k = 0; k < st.specs.size(); k++) {
            NodeSpec s = st.specs.get(k);
            MechanismBlockData mb = mech.getBlock(s.blockIndex());

            mb.spinReversed = result.direction()[k] == RotationNetwork.SpinDirection.CCW;

            String desiredPrefix = switch (s.meta().kind()) {
                case ENGINE -> st.engineRunning.getOrDefault(s.blockIndex(), false) ? "running" : "idle";
                case CONSTANT_SOURCE -> "spinning";
                case TRANSMITTER, CONSUMER -> result.powered()[k] ? "spinning" : "idle";
            };
            String cur = mb.customState();
            if (cur == null) continue;
            int us = cur.indexOf('_');
            String curPrefix = us >= 0 ? cur.substring(0, us) : cur;
            if (!SWAPPABLE_PREFIXES.contains(curPrefix)) continue;
            String desired = us >= 0 ? desiredPrefix + cur.substring(us) : desiredPrefix;
            if (!desired.equals(cur)) mech.setBlockState(s.blockIndex(), desired);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Consumer actuation at the live world position
    // ──────────────────────────────────────────────────────────────────────

    private void actuateConsumers(BasicMechanism mech, MechState st, long tickAge) {
        RotationSolver.Result result = st.result;
        if (result == null) return;
        World world = mech.pivot().getWorld();
        if (world == null) return;
        boolean cardinal = mech.isNearCardinal();

        for (int k = 0; k < st.specs.size(); k++) {
            NodeSpec s = st.specs.get(k);
            if (s.meta().kind() != RotationConfig.MechKind.CONSUMER) continue;
            if (tickAge % s.actuateInterval() != 0) continue;
            boolean powered = result.powered()[k];

            switch (s.typeId()) {
                case "mech:drill" -> tickDrill(mech, st, s, world, powered, cardinal);
                case "mech:suction_hopper" -> {
                    // No cardinal gate: the pull is positional and harmless mid-turn.
                    if (!powered) continue;
                    Inventory inv = mech.getStorage(s.blockIndex());
                    if (inv == null) continue;
                    Vector3i cell = mech.liveCell(s.blockIndex());
                    if (!world.isChunkLoaded(cell.x >> 4, cell.z >> 4)) continue;
                    RotationBlocks.suctionEffect(world,
                        new Location(world, cell.x + 0.5, cell.y + 0.5, cell.z + 0.5),
                        cell.x, cell.y, cell.z, inv,
                        config.suctionPullRange, config.suctionPullStrength);
                }
                case PLACER -> {
                    if (!powered || !cardinal) continue;
                    Inventory inv = mech.getStorage(s.blockIndex());
                    if (inv == null) continue;
                    BlockFace facing = mech.liveFacing(s.blockIndex());
                    if (facing == null) continue;
                    Vector3i cell = mech.liveCell(s.blockIndex());
                    int tx = cell.x + facing.getModX(), ty = cell.y + facing.getModY(),
                        tz = cell.z + facing.getModZ();
                    if (!world.isChunkLoaded(tx >> 4, tz >> 4)) continue;
                    RotationBlocks.placerEffect(world.getBlockAt(tx, ty, tz), inv);
                }
                default -> { /* demand + spin visuals only (millstone/press/fan and custom kinds) */ }
            }
        }
    }

    private void tickDrill(BasicMechanism mech, MechState st, NodeSpec s, World world,
                           boolean powered, boolean cardinal) {
        DrillTrack track = st.drills.computeIfAbsent(s.blockIndex(), i -> new DrillTrack());
        int sourceId = sourceId(mech, s.blockIndex());

        if (!powered) {   // mirror the static path: unpowered clears progress + crack animation
            if (track.state != null && track.lastTarget != null) {
                RotationBlocks.clearBreakAnimationAt(track.lastTarget, sourceId);
            }
            track.state = null;
            return;
        }
        if (!cardinal) return; // mid-turn: hold progress, resume on the next straight

        BlockFace facing = mech.liveFacing(s.blockIndex());
        if (facing == null) return;
        Vector3i cell = mech.liveCell(s.blockIndex());
        int tx = cell.x + facing.getModX(), ty = cell.y + facing.getModY(), tz = cell.z + facing.getModZ();
        if (!world.isChunkLoaded(tx >> 4, tz >> 4)) return;
        Block target = world.getBlockAt(tx, ty, tz);

        // The mechanism moved off the previous target: reset staged progress there.
        Location targetLoc = target.getLocation();
        if (track.lastTarget != null && !sameCell(track.lastTarget, targetLoc)) {
            if (track.state != null) RotationBlocks.clearBreakAnimationAt(track.lastTarget, sourceId);
            track.state = null;
        }
        track.lastTarget = targetLoc;

        RotationBlocks.DrillOutcome out = RotationBlocks.drillEffect(registry, target, facing,
            track.state, sourceId, config.drillBreakStages, config.drillBlacklist);
        track.state = out.next();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    private int power(String typeId) {
        int i = typeId.indexOf(':');
        return config.getPower(i >= 0 ? typeId.substring(i + 1) : typeId, 0);
    }

    private int actuateInterval(String typeId) {
        return switch (typeId) {
            case "mech:drill" -> config.drillTickInterval;
            case "mech:suction_hopper" -> config.suctionTickInterval;
            case PLACER -> config.placerTickInterval;
            default -> 20;
        };
    }

    private static int sourceId(BasicMechanism mech, int blockIndex) {
        return Objects.hash(mech.id(), blockIndex);
    }

    private static boolean sameCell(Location a, Location b) {
        return a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY()
            && a.getBlockZ() == b.getBlockZ() && a.getWorld() == b.getWorld();
    }
}
