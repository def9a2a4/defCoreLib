package anon.def9a2a4.corelib;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.plugin.java.JavaPlugin;
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

    private final Set<CustomBlockRegistry.LocationKey> cores = new HashSet<>();
    private final Map<CustomBlockRegistry.LocationKey, ActiveMove> active = new HashMap<>();
    private int tickCounter = 0;

    ExtendablePistonManager(JavaPlugin plugin, CustomBlockRegistry registry,
                            RotationNetwork network, MechanismRegistry mechRegistry) {
        this.plugin = plugin;
        this.registry = registry;
        this.network = network;
        this.mechRegistry = mechRegistry;
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
        overlayStructural(HEAD_ID);

        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** Pole/head are inert structural blocks (no network behaviour). */
    private void overlayStructural(String id) {
        CustomHeadBlock t = registry.getType(id);
        if (t == null) {
            plugin.getLogger().warning("ExtendablePiston: block '" + id + "' not found — skipping");
            return;
        }
        registry.register(t.toBuilder().drillable(false).cancelPistons(true).build());
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

        if (dir == RotationNetwork.SpinDirection.CW) {              // extend
            int r = Math.min(line.backPoles(), clearAhead(line, line.backPoles()));
            if (r > 0) startMove(coreKey, line, line.frontFace(), r);
        } else {                                                    // CCW → retract
            int r = Math.min(line.extension(), clearBehind(line, line.extension()));
            if (r > 0) startMove(coreKey, line, line.frontFace().getOppositeFace(), r);
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

    private int clearAhead(PistonLine line, int max) {
        Block b = line.head();
        int n = 0;
        for (int i = 0; i < max; i++) {
            b = b.getRelative(line.frontFace());
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

    // ──────────────────────────────────────────────────────────────────────
    // Movement: assemble the rod, slide R cells, disassemble (core protected)
    // ──────────────────────────────────────────────────────────────────────

    private void startMove(CustomBlockRegistry.LocationKey coreKey, PistonLine line,
                           BlockFace moveFace, int r) {
        // Ghost "fake shaft inside the core": a pole at the core cell, so the sliding rod is contiguous
        // (fills what would otherwise be a gap). Copies an existing pole's appearance/orientation.
        Block template = firstPole(line.rodBlocks());
        List<MechanismRegistry.GhostBlock> ghosts = template == null ? List.of()
            : List.of(new MechanismRegistry.GhostBlock(line.core().getLocation(), template));

        Mechanism mech = mechRegistry.assembleMechanism(MECH_TYPE, line.rodBlocks(), ghosts,
            line.head().getLocation(), AXIS_Y, null);
        if (mech == null) return;
        // Whichever rod block lands on the core cell is skipped (consumed); the ghost fills the gap.
        ((BasicMechanism) mech).setProtectedCells(Set.of(coreKey));

        Location start = mech.pivot(); // block-centered after assembly
        Vector3f dir = new Vector3f(moveFace.getModX(), moveFace.getModY(), moveFace.getModZ());
        Location target = start.clone().add(dir.x * r, dir.y * r, dir.z * r);
        active.put(coreKey, new ActiveMove(coreKey, mech, target, dir,
            line.rodBlocks().size() + ghosts.size()));
    }

    private @Nullable Block firstPole(List<Block> rod) {
        for (Block b : rod) if (isType(b, POLE_ID)) return b;
        return null;
    }

    /** @return true when the move finished (disassembled) and should be dropped from {@code active}. */
    private boolean advance(ActiveMove m) {
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
