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
    private final MachineRecipes millRecipes;
    private final MachineRecipes pressRecipes;
    private final MachineRecipes sieveRecipes;

    private final Map<UUID, MechState> states = new HashMap<>();

    MechanismRotationDriver(CustomBlockRegistry registry, EngineFuelManager fuelManager,
                            RotationConfig config, MachineRecipes millRecipes,
                            MachineRecipes pressRecipes, MachineRecipes sieveRecipes) {
        this.registry = registry;
        this.fuelManager = fuelManager;
        this.config = config;
        this.millRecipes = millRecipes;
        this.pressRecipes = pressRecipes;
        this.sieveRecipes = sieveRecipes;
    }

    /** One rotation part of a mechanism — immutable while assembled. {@code localFacing} follows
     *  the drill-PDC convention (wall head → its facing, floor head → DOWN); machines derive their
     *  input side (behind = opposite) and the hopper its mount from it, in the LOCAL frame. */
    private record NodeSpec(int blockIndex, String typeId, RotationConfig.MechRotationMeta meta,
                            int cellX, int cellY, int cellZ,
                            RotationNetwork.Axis axis, boolean omni, @Nullable BlockFace omniExcludedFace,
                            BlockFace localFacing,
                            RotationNetwork.SpinDirection dirPref,
                            int power, int actuateInterval) {}

    /** Staged-break progress + last target cell of one riding drill (the target moves with the
     *  mechanism, so the world-keyed static map can't serve it). */
    private static final class DrillTrack {
        RotationBlocks.@Nullable DrillState state;
        @Nullable Location lastTarget;
    }

    /** One conduit (pipe) riding the mechanism — a pure directed edge in the LOCAL frame. */
    private record ConduitSpec(int blockIndex, int cellX, int cellY, int cellZ,
                               BlockFace facing, MechanismConduits.Conduit props) {}

    private static final class MechState {
        final List<NodeSpec> specs;
        // Local cell (RotationSolver.pack) → block index, over ALL real blocks (chests included,
        // ghosts excluded) — machines find the travelling inventory of their neighbor through it.
        // Local-frame adjacency is orientation-invariant: neighbor = cell + localDirection.
        final Map<Long, Integer> indexByLocalCell = new HashMap<>();
        final Map<Integer, NodeSpec> specByIndex = new HashMap<>();   // machine acceptance lookups
        final List<ConduitSpec> conduits = new ArrayList<>();
        final Map<Long, ConduitSpec> conduitByCell = new HashMap<>();
        RotationSolver.@Nullable Result result;
        boolean dirty = true;
        final Map<Integer, Integer> engineFuel = new HashMap<>();     // blockIndex → engine-ticks left
        final Map<Integer, Boolean> engineRunning = new HashMap<>();  // blockIndex → supplying?
        final Map<Integer, DrillTrack> drills = new HashMap<>();      // blockIndex → break progress

        MechState(List<NodeSpec> specs) { this.specs = specs; }
    }

    /** Travelling inventory of the mechanism block at a LOCAL cell, or null (no block / no storage). */
    private static @Nullable Inventory storageAtLocal(BasicMechanism mech, MechState st,
                                                      int x, int y, int z) {
        Integer idx = st.indexByLocalCell.get(RotationSolver.pack(x, y, z));
        return idx == null ? null : mech.getStorage(idx);
    }

    /** Insert ALL outputs into {@code dest} atomically (snapshot/rollback — mirror of the container
     *  branch of {@code RotationBlocks.ejectOutputs}). @return false when they didn't all fit. */
    private static boolean insertAllAtomic(Inventory dest, List<org.bukkit.inventory.ItemStack> outputs) {
        org.bukkit.inventory.ItemStack[] snapshot = java.util.Arrays.stream(dest.getContents())
            .map(s -> s == null ? null : s.clone()).toArray(org.bukkit.inventory.ItemStack[]::new);
        for (org.bukkit.inventory.ItemStack out : outputs) {
            if (!dest.addItem(out).isEmpty()) {
                dest.setContents(snapshot);
                return false;
            }
        }
        return true;
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
                localFacing,
                mb.spinReversed ? RotationNetwork.SpinDirection.CCW : RotationNetwork.SpinDirection.CW,
                power(mb.customTypeId), actuateInterval(mb.customTypeId)));
        }
        MechState st = new MechState(specs);
        // Cell map over ALL real blocks (not just rotation nodes): machines resolve neighboring
        // travelling inventories (chests, other machines) through it. Ghosts excluded by the bound.
        // Conduits (pipes) are collected here too — they route items between those inventories.
        for (int i = 0; i < realBlockCount; i++) {
            MechanismBlockData bd = mech.getBlock(i);
            Vector3f t = bd.localTransform.getTranslation(new Vector3f());
            int bx = Math.round(t.x), by = Math.round(t.y), bz = Math.round(t.z);
            st.indexByLocalCell.put(RotationSolver.pack(bx, by, bz), i);
            MechanismConduits.Conduit conduit = MechanismConduits.get(bd.customTypeId);
            if (conduit != null) {
                BlockFace cf = conduit.facingFromState().apply(bd.customState());
                if (cf != null) {
                    ConduitSpec cs = new ConduitSpec(i, bx, by, bz, cf, conduit);
                    st.conduits.add(cs);
                    st.conduitByCell.put(RotationSolver.pack(bx, by, bz), cs);
                }
            }
        }
        // Neither rotation parts nor conduits aboard → nothing for the driver to do here.
        // (Conduits run without rotation power, exactly like ground pipes.)
        if (specs.isEmpty() && st.conduits.isEmpty()) return;

        for (NodeSpec s : specs) st.specByIndex.put(s.blockIndex(), s);
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
        tickConduits(mech, st, tickAge);
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
                    // Burn counter hit zero — pull the next fuel item in the SAME tick so the engine
                    // doesn't stop for a full interval at every fuel-item boundary. Only stop when
                    // storage is dry. On a successful refill we keep running and leave st.dirty alone
                    // (the engine never changed running state, so no re-solve is needed).
                    Inventory inv = mech.getStorage(idx);
                    if (inv != null) fuel += RotationBlocks.consumeOneFuelItem(inv, fuelManager);
                    if (fuel <= 0) {
                        st.engineRunning.put(idx, false);
                        st.dirty = true;
                    }
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
                    // Feed ONE collected item into the travelling inventory this hopper is mounted
                    // on (never drops — mirror of pushToMount, against the on-board container).
                    BlockFace mount = s.localFacing() == BlockFace.DOWN
                        ? BlockFace.DOWN : s.localFacing().getOppositeFace();
                    Inventory mountInv = storageAtLocal(mech, st,
                        s.cellX() + mount.getModX(), s.cellY() + mount.getModY(),
                        s.cellZ() + mount.getModZ());
                    if (mountInv != null) RotationBlocks.pullOne(inv, mountInv);
                }
                case PLACER -> {
                    if (!powered || !cardinal) continue;
                    Inventory inv = mech.getStorage(s.blockIndex());
                    if (inv == null) continue;
                    // Refill from the on-board container behind the placer (the borer's rail feed):
                    // behind = UP for a ceiling placer (its mount/cap face, consistent with
                    // placerOmniExcludedFace), the facing's opposite for a wall placer.
                    BlockFace behind = s.localFacing() == BlockFace.DOWN
                        ? BlockFace.UP : s.localFacing().getOppositeFace();
                    Inventory feed = storageAtLocal(mech, st,
                        s.cellX() + behind.getModX(), s.cellY() + behind.getModY(),
                        s.cellZ() + behind.getModZ());
                    if (feed != null) RotationBlocks.pullOne(feed, inv);
                    BlockFace facing = mech.liveFacing(s.blockIndex());
                    if (facing == null) continue;
                    Vector3i cell = mech.liveCell(s.blockIndex());
                    int tx = cell.x + facing.getModX(), ty = cell.y + facing.getModY(),
                        tz = cell.z + facing.getModZ();
                    if (!world.isChunkLoaded(tx >> 4, tz >> 4)) continue;
                    RotationBlocks.placerEffect(world.getBlockAt(tx, ty, tz), inv);
                }
                case "mech:millstone" -> tickProcessing(mech, st, s, k, world, powered, cardinal,
                    millRecipes, org.bukkit.Sound.BLOCK_GRINDSTONE_USE, config.millstoneMaxBatch);
                case "mech:press" -> tickProcessing(mech, st, s, k, world, powered, cardinal,
                    pressRecipes, org.bukkit.Sound.BLOCK_ANVIL_USE, config.pressMaxBatch);
                case "mech:sieve" -> tickProcessing(mech, st, s, k, world, powered, cardinal,
                    sieveRecipes, org.bukkit.Sound.ITEM_BRUSH_BRUSHING_GRAVEL, config.sieveMaxBatch);
                case "mech:fan" -> {
                    if (!powered || !cardinal) continue;
                    // Blow direction mirrors the static blowDirection: floor fan blows UP, wall
                    // fan blows its facing — rotated into the world by the live transform.
                    BlockFace blowLocal = s.localFacing() == BlockFace.DOWN
                        ? BlockFace.UP : s.localFacing();
                    BlockFace blow = mech.liveDirection(blowLocal);
                    if (blow == null) continue;
                    Vector3i cell = mech.liveCell(s.blockIndex());
                    if (!world.isChunkLoaded(cell.x >> 4, cell.z >> 4)) continue;
                    RotationBlocks.fanEffect(world,
                        new Location(world, cell.x + 0.5, cell.y + 0.5, cell.z + 0.5),
                        blow, result.surplus()[k],
                        config.fanRange, config.fanMinPush, config.fanMaxPush, config.fanPushPerPower);
                }
                default -> { /* demand + spin visuals only (custom kinds) */ }
            }
        }
    }

    /**
     * Millstone/press aboard: pull one input from the on-board container behind, run the shared
     * recipe loop against the machine's travelling storage, and eject into the on-board container
     * below — else drop at the live world cell below (hopper-catchable; needs cardinal + loaded).
     * Batch mirrors the static path exactly: {@code min(maxBatch, max(1, supply − demand))} — the
     * solver's per-node surplus IS that same self-inclusive headroom, clamped to at least one
     * cycle while powered.
     */
    private void tickProcessing(BasicMechanism mech, MechState st, NodeSpec s, int k, World world,
                                boolean powered, boolean cardinal, MachineRecipes recipes,
                                org.bukkit.Sound sound, int maxBatch) {
        if (!powered) return;
        Inventory in = mech.getStorage(s.blockIndex());
        if (in == null) return;

        BlockFace behind = s.localFacing() == BlockFace.DOWN
            ? BlockFace.UP : s.localFacing().getOppositeFace();
        Inventory feed = storageAtLocal(mech, st,
            s.cellX() + behind.getModX(), s.cellY() + behind.getModY(), s.cellZ() + behind.getModZ());
        if (feed != null) RotationBlocks.pullOne(feed, in);

        RotationSolver.Result result = st.result;
        if (result == null) return;
        int batch = Math.min(maxBatch, Math.max(1, result.surplus()[k]));

        Inventory below = storageAtLocal(mech, st, s.cellX(), s.cellY() - 1, s.cellZ());
        ConduitSpec pipeBelow = st.conduitByCell.get(
            RotationSolver.pack(s.cellX(), s.cellY() - 1, s.cellZ()));
        java.util.function.Function<List<org.bukkit.inventory.ItemStack>, Boolean> eject;
        if (below != null) {
            eject = outputs -> insertAllAtomic(below, outputs);
        } else if (pipeBelow != null && pipeBelow.facing() == BlockFace.DOWN) {
            // Machine → down-pipe (deliverFromAbove analogue): push outputs atomically down the
            // chain into its destination; anything less than a full fit stalls the machine.
            ChainEnd end = walkChain(st, pipeBelow);
            Integer destIdx = end.destCell() == null ? null : st.indexByLocalCell.get(end.destCell());
            Inventory dest = destIdx == null ? null : mech.getStorage(destIdx);
            if (dest == null || !acceptsInto(st, destIdx, end.entryDir())) return; // dead chain: stall
            eject = outputs -> insertAllAtomic(dest, outputs);
        } else if (cardinal) {
            Vector3i cell = mech.liveCell(s.blockIndex());
            if (!world.isChunkLoaded(cell.x >> 4, cell.z >> 4)) return;
            Location dropAt = new Location(world, cell.x + 0.5, cell.y, cell.z + 0.5);
            eject = outputs -> {
                for (org.bukkit.inventory.ItemStack out : outputs) world.dropItem(dropAt, out);
                return true;
            };
        } else {
            return; // no on-board sink and mid-turn: hold until the next straight
        }

        if (RotationBlocks.processingEffect(in, batch, recipes, registry, eject)) {
            Vector3i cell = mech.liveCell(s.blockIndex());
            if (world.isChunkLoaded(cell.x >> 4, cell.z >> 4)) {
                world.playSound(new Location(world, cell.x + 0.5, cell.y + 0.5, cell.z + 0.5),
                    sound, 0.6f, 1f);
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
    // Conduits (pipes) aboard: directed facing-chains between travelling inventories,
    // mirroring the Pipes ground model (corner bends, head-on terminates, min throughput).
    // ──────────────────────────────────────────────────────────────────────

    /** Where a facing-chain ends: the local cell just past the last conduit ({@code null} for a
     *  loop / head-on dead end), the last conduit walked, the entry direction into the end cell,
     *  and the chain's throughput (min itemsPerTransfer along it). */
    private record ChainEnd(@Nullable Long destCell, ConduitSpec last, BlockFace entryDir, int minItems) {}

    private static ChainEnd walkChain(MechState st, ConduitSpec start) {
        BlockFace dir = start.facing();
        ConduitSpec cur = start;
        int minItems = start.props().itemsPerTransfer();
        Set<Long> visited = new java.util.HashSet<>();
        visited.add(RotationSolver.pack(start.cellX(), start.cellY(), start.cellZ()));
        while (true) {
            long next = RotationSolver.pack(cur.cellX() + dir.getModX(),
                cur.cellY() + dir.getModY(), cur.cellZ() + dir.getModZ());
            ConduitSpec nc = st.conduitByCell.get(next);
            if (nc == null) return new ChainEnd(next, cur, dir, minItems);      // chain ends here
            if (!visited.add(next)) return new ChainEnd(null, cur, dir, minItems);          // loop
            if (nc.facing() == dir.getOppositeFace()) return new ChainEnd(null, nc, dir, minItems); // head-on
            minItems = Math.min(minItems, nc.props().itemsPerTransfer());
            dir = nc.facing();
            cur = nc;
        }
    }

    /** Destination acceptance, mirroring {@code RotationMachineAdapter.canReceiveFrom}: engines
     *  accept from any side; other rotation machines only through their back (the chain must enter
     *  along the machine's facing); plain storage blocks (chests) always accept. */
    private boolean acceptsInto(MechState st, int destIndex, BlockFace entryDir) {
        NodeSpec ms = st.specByIndex.get(destIndex);
        if (ms == null) return true;                                     // plain container
        if (ms.meta().kind() == RotationConfig.MechKind.ENGINE) return true;
        return entryDir == ms.localFacing();
    }

    private void tickConduits(BasicMechanism mech, MechState st, long tickAge) {
        if (st.conduits.isEmpty()) return;
        World world = mech.pivot().getWorld();
        if (world == null) return;

        for (ConduitSpec c : st.conduits) {
            if (c.props().corner()) continue;                            // corners never pull
            long cellKey = RotationSolver.pack(c.cellX(), c.cellY(), c.cellZ());
            // Phase-offset per conduit (mirror of PipeManager.isTransferDue) to spread work.
            if (Math.floorMod(tickAge, c.props().intervalTicks())
                    != Math.floorMod(Long.hashCode(cellKey), c.props().intervalTicks())) continue;

            // Source = the travelling inventory behind the conduit. Rotation machines are NOT
            // sources (ground parity: RotationMachineAdapter is insert-only — pipes can feed a
            // machine but never drain one; machine output leaves via its own eject).
            BlockFace back = c.facing().getOppositeFace();
            Integer srcIdx = st.indexByLocalCell.get(RotationSolver.pack(
                c.cellX() + back.getModX(), c.cellY() + back.getModY(), c.cellZ() + back.getModZ()));
            if (srcIdx == null || st.specByIndex.containsKey(srcIdx)) continue;
            Inventory source = mech.getStorage(srcIdx);
            if (source == null) continue;

            ChainEnd end = walkChain(st, c);
            if (end.destCell() == null) continue;                        // loop/head-on: dead chain

            Integer destIdx = st.indexByLocalCell.get(end.destCell());
            if (destIdx != null) {
                // A mechanism block ends the chain: transfer only into real storage through an
                // accepted face — a storage-less or wrong-side block means NO transfer (ground
                // parity: a pipe pointing at a non-container simply idles).
                Inventory dest = mech.getStorage(destIdx);
                if (dest == null || !acceptsInto(st, destIdx, end.entryDir())) continue;
                transferChain(source, dest, end.minItems());
            } else {
                // No on-board destination: drop out the end of the chain at its live world cell,
                // like a ground dead-end pipe (hopper-catchable). Needs a stable orientation.
                if (!mech.isNearCardinal()) continue;
                Vector3i lc = mech.liveCell(end.last().blockIndex());
                BlockFace out = mech.liveDirection(end.entryDir());
                if (out == null) continue;
                int dx = lc.x + out.getModX(), dy = lc.y + out.getModY(), dz = lc.z + out.getModZ();
                if (!world.isChunkLoaded(dx >> 4, dz >> 4)) continue;
                org.bukkit.inventory.ItemStack taken = takeUpTo(source, end.minItems());
                if (taken != null) {
                    world.dropItem(new Location(world, dx + 0.5, dy + 0.5, dz + 0.5), taken);
                }
            }
        }
    }

    /** Move up to {@code max} items of the first available stack from {@code source} into
     *  {@code dest}; partial insert commits only what fit (mirror of the Pipes peek/commit). */
    private static void transferChain(Inventory source, Inventory dest, int max) {
        for (int i = 0; i < source.getSize(); i++) {
            org.bukkit.inventory.ItemStack it = source.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            int n = Math.min(it.getAmount(), max);
            org.bukkit.inventory.ItemStack moving = it.clone();
            moving.setAmount(n);
            var leftover = dest.addItem(moving);
            int inserted = n - leftover.values().stream()
                .mapToInt(org.bukkit.inventory.ItemStack::getAmount).sum();
            if (inserted <= 0) return;                                   // destination full
            it.setAmount(it.getAmount() - inserted);
            source.setItem(i, it.getAmount() <= 0 ? null : it);
            return;                                                      // one stack type per cycle
        }
    }

    /** Remove and return up to {@code max} items of the first available stack, or null when empty. */
    private static org.bukkit.inventory.@Nullable ItemStack takeUpTo(Inventory source, int max) {
        for (int i = 0; i < source.getSize(); i++) {
            org.bukkit.inventory.ItemStack it = source.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            int n = Math.min(it.getAmount(), max);
            org.bukkit.inventory.ItemStack taken = it.clone();
            taken.setAmount(n);
            it.setAmount(it.getAmount() - n);
            source.setItem(i, it.getAmount() <= 0 ? null : it);
            return taken;
        }
        return null;
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
            case "mech:millstone" -> config.millstoneTickInterval;
            case "mech:press" -> config.pressTickInterval;
            case "mech:sieve" -> config.sieveTickInterval;
            case "mech:fan" -> config.fanTickInterval;
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
