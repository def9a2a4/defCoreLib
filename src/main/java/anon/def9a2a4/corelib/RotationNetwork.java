package anon.def9a2a4.corelib;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.logging.Logger;

/**
 * Manages a graph of rotation-powered blocks. Each block is a node with an axis,
 * role (source/transmitter/consumer), and power units. Connected components form
 * networks; a network is powered when total supply &ge; total demand.
 *
 * <p>Connection rules:
 * <ul>
 *   <li>Along-axis: every node connects to its 2 neighbors along its axis if they share the same
 *       axis. A powered reverser flips the edge on its output side (UP for a floor head, its
 *       facing for a wall head); see the XOR rule in {@code getConnections}.</li>
 *   <li>Gear-to-gear (gear-like nodes only): connects to all 6 adjacent gear-like neighbors
 *       (same-axis gear mesh reverses; bevel reversal is face-dependent via
 *       {@code bevelReverses()}).</li>
 *   <li>Locked nodes (state starts with "locked_") are excluded from all connections.</li>
 * </ul>
 */
public class RotationNetwork {

    public enum Axis { X, Y, Z }
    public enum NodeRole { SOURCE, TRANSMITTER, CONSUMER }
    public enum SpinDirection {
        CW, CCW;
        public SpinDirection reversed() { return this == CW ? CCW : CW; }
    }

    record RotationNode(CustomBlockRegistry.LocationKey key, String blockTypeId, Axis axis,
                        NodeRole role, int powerUnits, boolean gearLike, boolean gearbox,
                        int reverserOutSign, boolean omni, @Nullable BlockFace omniExcludedFace) {}

    record Connection(CustomBlockRegistry.LocationKey neighbor, boolean reverses) {}

    record NetworkState(int supply, int demand, boolean jammed) {
        boolean powered() { return !jammed && supply >= demand && supply > 0; }
    }

    private final CustomBlockRegistry registry;
    private final JavaPlugin plugin;
    private final Logger logger;

    // Passive sources: YAML-only blocks detected at network boundary (e.g. mech:windmill)
    private final Map<String, Integer> passiveSourceTypes = new HashMap<>();

    // Graph state
    private final Map<CustomBlockRegistry.LocationKey, RotationNode> nodes = new HashMap<>();
    private final Map<CustomBlockRegistry.LocationKey, Integer> nodeNetworkId = new HashMap<>();
    private final Map<Integer, Set<CustomBlockRegistry.LocationKey>> networkMembers = new HashMap<>();
    private final Map<Integer, Set<CustomBlockRegistry.LocationKey>> networkPassiveSources = new HashMap<>();
    private final Map<Integer, NetworkState> networks = new HashMap<>();
    private final Map<CustomBlockRegistry.LocationKey, SpinDirection> nodeDirection = new HashMap<>();
    // Transient demand: extra demand from nodes that consume only while active (a Rotator while
    // swinging). Folded into getNetworkStats's demand WITHOUT a recalculation, so contending
    // rotators see each other's load without network churn.
    private final Map<CustomBlockRegistry.LocationKey, Integer> transientDemand = new HashMap<>();
    // Chain-pulley links: each pulley has ONE outgoing partner (a directed functional graph). A pulley
    // only transmits power along its link when it sits on a CLOSED loop (see onClosedLoop) — an open
    // chain renders but stays dead. Injected as a distance edge in getConnections.
    private final Map<CustomBlockRegistry.LocationKey, CustomBlockRegistry.LocationKey> chainOut = new HashMap<>();
    private int nextNetworkId = 0;

    // Re-entrancy guard
    private boolean recalculating = false;
    private final Set<CustomBlockRegistry.LocationKey> pendingRecalcs = new HashSet<>();

    // Reactive recalcs requested during the registry's reactive-flush dispatch loop are collected here and
    // drained once per network at the end of that flush (via a post-hook). Collapses K reactive pokes on
    // one connected network from K full O(component) rebuilds to a single rebuild that reads final state.
    private final Set<CustomBlockRegistry.LocationKey> pendingReactiveRecalc = new HashSet<>();

    // Config
    private int maxNetworkSize = 256;

    static final NamespacedKey SPIN_DIR_KEY = new NamespacedKey("mech", "spin_dir");

    private static final String REVERSER_ID = "mech:reverser";
    private static final String RATCHET_ID = "mech:ratchet";

    // Ratchets that are freewheeling (fully severed) for the current solve. Non-empty ONLY during
    // doRecalculate's apply pass; reset to an empty set in a finally. Consulted by isSevered.
    private Set<CustomBlockRegistry.LocationKey> freewheelCut = Set.of();

    // Per-doRecalculate memo of the STATE string (skull deserialize) for the READ-ONLY predicates
    // isLocked/readRatchetAllowed, which today re-read every edge endpoint of every node (~6-7×/node).
    // Non-null ONLY during one doRecalculate (set/restored in the wrapper). MUST NOT be used by the write
    // path (updateBlockState), and MUST NOT span the pendingRecalcs drain — a fresh map per doRecalculate,
    // because the values it caches (locked_ prefix, cw/ccw token) are invariant WITHIN one recalc but a
    // re-entrant redstone change between drained recalcs can change them.
    private @Nullable Map<CustomBlockRegistry.LocationKey, String> recalcStateCache = null;

    /** {@code registry.getState}, memoized for the duration of one doRecalculate (read-only predicates
     *  only). Falls through to a live read outside a recalc. Caches nulls too (containsKey guard). */
    private @Nullable String cachedState(CustomBlockRegistry.LocationKey key, Block b) {
        Map<CustomBlockRegistry.LocationKey, String> cache = recalcStateCache;
        if (cache == null) return registry.getState(b);
        String s = cache.get(key);
        if (s == null && !cache.containsKey(key)) {
            s = registry.getState(b);
            cache.put(key, s);
        }
        return s;
    }

    RotationNetwork(JavaPlugin plugin, CustomBlockRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        this.logger = plugin.getLogger();
        registry.addReactiveFlushPostHook(this::drainReactiveRecalcs);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────

    public void addNode(Block block, String blockTypeId, Axis axis,
                        NodeRole role, int powerUnits, boolean gearLike) {
        addNode(block, blockTypeId, axis, role, powerUnits, gearLike, false, false, null);
    }

    /** As {@link #addNode(Block, String, Axis, NodeRole, int, boolean)} but flagged as a
     *  <b>gearbox</b> — an omnidirectional transmitter that couples to any aligned shaft/gear (or
     *  another gearbox) on all six faces, passing power but not spin direction (a direction firewall
     *  — each side resolves its own rotation; see {@link #getConnections}). */
    public void addNode(Block block, String blockTypeId, Axis axis,
                        NodeRole role, int powerUnits, boolean gearLike, boolean gearbox) {
        addNode(block, blockTypeId, axis, role, powerUnits, gearLike, gearbox, false, null);
    }

    /**
     * Register a node, optionally as an <b>omni consumer</b>: a leaf sink that draws power from the
     * first aligned shaft on any of its faces (in {@link Faces#CARDINAL} order, skipping
     * {@code omniExcludedFace}), chosen live during flood-fill so it self-corrects each recalc.
     * Omni nodes store a nominal {@code axis} — their connectivity comes from {@link #omniAttachKey}.
     */
    public void addNode(Block block, String blockTypeId, Axis axis,
                        NodeRole role, int powerUnits, boolean gearLike, boolean gearbox,
                        boolean omni, @Nullable BlockFace omniExcludedFace) {
        CustomBlockRegistry.LocationKey key = CustomBlockRegistry.LocationKey.of(block);
        int reverserOutSign = reverserOutSign(block, blockTypeId, axis);
        nodes.put(key, new RotationNode(key, blockTypeId, axis, role, powerUnits, gearLike, gearbox,
                reverserOutSign, omni, omniExcludedFace));
        recalculate(key);
    }

    public void removeNode(CustomBlockRegistry.LocationKey key) {
        nodes.remove(key);
        transientDemand.remove(key);
        recalculate(key);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Chain-pulley links (distance edges gated on a closed loop)
    // ──────────────────────────────────────────────────────────────────────

    /** Set {@code a}'s single outgoing chain link to {@code b} and recompute both endpoints' networks. */
    public void linkChain(CustomBlockRegistry.LocationKey a, CustomBlockRegistry.LocationKey b) {
        chainOut.put(a, b);
        recalculate(a);
        if (nodes.containsKey(b)) recalculate(b);
    }

    /** Remove {@code a}'s outgoing chain link and recompute {@code a} and its former partner. */
    public void unlinkChain(CustomBlockRegistry.LocationKey a) {
        CustomBlockRegistry.LocationKey old = chainOut.remove(a);
        recalculate(a);
        if (old != null && nodes.containsKey(old)) recalculate(old);
    }

    /** This pulley's current outgoing chain partner, or null. */
    public CustomBlockRegistry.@Nullable LocationKey chainOutOf(CustomBlockRegistry.LocationKey a) {
        return chainOut.get(a);
    }

    /** All pulleys whose outgoing link targets {@code b} (for cleanup when {@code b} breaks). */
    public List<CustomBlockRegistry.LocationKey> chainIntoOf(CustomBlockRegistry.LocationKey b) {
        List<CustomBlockRegistry.LocationKey> in = new ArrayList<>();
        for (Map.Entry<CustomBlockRegistry.LocationKey, CustomBlockRegistry.LocationKey> e : chainOut.entrySet()) {
            if (e.getValue().equals(b)) in.add(e.getKey());
        }
        return in;
    }

    /**
     * True iff following the outgoing chain links from {@code start} returns to {@code start} — i.e.
     * {@code start} lies on a closed loop. A hop into a missing node (unloaded/broken) is a dead end.
     */
    public boolean onClosedLoop(CustomBlockRegistry.LocationKey start) {
        CustomBlockRegistry.LocationKey cur = chainOut.get(start);
        int hops = 0;
        while (cur != null && hops <= maxNetworkSize) {
            if (!nodes.containsKey(cur)) return false;
            if (cur.equals(start)) return true;
            cur = chainOut.get(cur);
            hops++;
        }
        return false;
    }

    public boolean isPowered(CustomBlockRegistry.LocationKey key) {
        Integer netId = nodeNetworkId.get(key);
        if (netId == null) return false;
        NetworkState state = networks.get(netId);
        return state != null && state.powered();
    }

    public @Nullable RotationNode getNode(CustomBlockRegistry.LocationKey key) {
        return nodes.get(key);
    }

    public @Nullable SpinDirection getDirection(CustomBlockRegistry.LocationKey key) {
        return nodeDirection.get(key);
    }

    public void recalculateAdjacentNetworks(CustomBlockRegistry.LocationKey key) {
        // Recalc every distinct adjacent network (a passive source can border more than one).
        // Dedup by the pre-rebuild member snapshot: recalculate() reassigns network ids, so caching
        // the id would go stale — but the member LocationKeys are stable, so skip any face whose
        // neighbor we've already covered via an earlier network rebuild.
        Set<CustomBlockRegistry.LocationKey> recalced = new HashSet<>();
        for (BlockFace face : Faces.CARDINAL) {
            CustomBlockRegistry.LocationKey neighbor = faceNeighbor(key, face);
            if (!nodes.containsKey(neighbor) || recalced.contains(neighbor)) continue;
            Set<CustomBlockRegistry.LocationKey> members = getNetworkMembers(neighbor);
            recalculate(neighbor);
            if (members != null) recalced.addAll(members); else recalced.add(neighbor);
        }
    }

    public void setMaxNetworkSize(int max) {
        this.maxNetworkSize = max;
    }

    /** Register a YAML-only block type as a passive rotation source.
     *  These blocks provide power when adjacent to a network node, without needing Java callbacks. */
    public void registerPassiveSource(String blockTypeId, int powerUnits) {
        passiveSourceTypes.put(blockTypeId, powerUnits);
    }

    record NetworkDebugInfo(int supply, int demand, int blockCount, boolean jammed,
                            int cwSources, int ccwSources) {}

    public @Nullable NetworkDebugInfo getNetworkDebugInfo(CustomBlockRegistry.LocationKey key) {
        Integer netId = nodeNetworkId.get(key);
        if (netId == null) return null;
        NetworkState state = networks.get(netId);
        Set<CustomBlockRegistry.LocationKey> members = networkMembers.get(netId);
        if (state == null || members == null) return null;
        int cw = 0, ccw = 0;
        for (CustomBlockRegistry.LocationKey loc : members) {
            RotationNode node = nodes.get(loc);
            if (node != null && node.role() == NodeRole.SOURCE) {
                SpinDirection dir = nodeDirection.get(loc);
                if (dir == SpinDirection.CW) cw++;
                else if (dir == SpinDirection.CCW) ccw++;
            }
        }
        Set<CustomBlockRegistry.LocationKey> passives = networkPassiveSources.get(netId);
        if (passives != null) {
            for (CustomBlockRegistry.LocationKey ps : passives) {
                SpinDirection stored = readStoredDirection(ps);
                if (stored == SpinDirection.CW) cw++;
                else if (stored == SpinDirection.CCW) ccw++;
            }
        }
        return new NetworkDebugInfo(state.supply(), state.demand(), members.size(),
                state.jammed(), cw, ccw);
    }

    /** Returns [supply, demand, blockCount] for the network containing this node, or null.
     *  {@code demand} includes any transient demand (e.g. swinging Rotators) on the network. */
    public int @Nullable [] getNetworkStats(CustomBlockRegistry.LocationKey key) {
        Integer netId = nodeNetworkId.get(key);
        if (netId == null) return null;
        NetworkState state = networks.get(netId);
        Set<CustomBlockRegistry.LocationKey> members = networkMembers.get(netId);
        if (state == null || members == null) return null;
        int transientSum = 0;
        if (!transientDemand.isEmpty()) {
            for (CustomBlockRegistry.LocationKey m : members) {
                Integer t = transientDemand.get(m);
                if (t != null) transientSum += t;
            }
        }
        return new int[]{ state.supply(), state.demand() + transientSum, members.size() };
    }

    /** Register extra demand for a node that consumes only while active (a Rotator while
     *  swinging). Folded into {@link #getNetworkStats}'s demand with no recalculation. Read your
     *  surplus BEFORE calling this so the reading excludes your own load. */
    public void addTransientDemand(CustomBlockRegistry.LocationKey key, int amount) {
        transientDemand.put(key, amount);
    }

    public void clearTransientDemand(CustomBlockRegistry.LocationKey key) {
        transientDemand.remove(key);
    }

    /** Returns all nodes in the same network as this key, or null. */
    public @Nullable Set<CustomBlockRegistry.LocationKey> getNetworkMembers(CustomBlockRegistry.LocationKey key) {
        Integer netId = nodeNetworkId.get(key);
        if (netId == null) return null;
        return networkMembers.get(netId);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Recalculation
    // ──────────────────────────────────────────────────────────────────────

    public void recalculate(CustomBlockRegistry.LocationKey changed) {
        // LOAD-BEARING re-entrancy guard: doRecalculate can synchronously fire block events /
        // RotationNetworkPoweredEvent whose listeners call back into add/removeNode/recalculate. This flag
        // routes re-entrant calls into pendingRecalcs instead of recursing into doRecalculate, which is what
        // keeps the nodes/networks maps from being mutated mid-iteration. Preserve it if doRecalculate ever
        // migrates onto RotationSolver.
        if (recalculating) {
            pendingRecalcs.add(changed);
            return;
        }
        recalculating = true;
        try {
            doRecalculate(changed);
            while (!pendingRecalcs.isEmpty()) {
                Set<CustomBlockRegistry.LocationKey> batch = new HashSet<>(pendingRecalcs);
                pendingRecalcs.clear();
                for (CustomBlockRegistry.LocationKey pending : batch) {
                    if (nodeNetworkId.containsKey(pending)) continue;
                    doRecalculate(pending);
                }
            }
        } finally {
            recalculating = false;
        }
    }

    /**
     * Recalc entry point for REACTIVE (neighbor-change) triggers. During the registry's reactive-flush
     * dispatch loop, collects the key and lets {@link #drainReactiveRecalcs} rebuild each affected network
     * exactly once at flush end (after all sibling setStates are applied). Outside the flush (wrench,
     * chunk load, tick), rebuilds immediately — identical to {@link #recalculate}.
     */
    public void recalcReactive(CustomBlockRegistry.LocationKey changed) {
        if (registry.isFlushingReactive()) pendingReactiveRecalc.add(changed);
        else recalculate(changed);
    }

    /** Post-flush hook: rebuild each collected network once, deduping keys already covered by a prior
     *  rebuild's member set (a lock-split may leave a key uncovered → it rebuilds too; redundant but
     *  idempotent). Runs with isFlushingReactive()==false, so recalculate here executes immediately. */
    private void drainReactiveRecalcs() {
        if (pendingReactiveRecalc.isEmpty()) return;
        Set<CustomBlockRegistry.LocationKey> batch = new HashSet<>(pendingReactiveRecalc);
        pendingReactiveRecalc.clear();
        Set<CustomBlockRegistry.LocationKey> covered = new HashSet<>();
        for (CustomBlockRegistry.LocationKey key : batch) {
            if (covered.contains(key) || !nodes.containsKey(key)) continue;
            recalculate(key);
            Set<CustomBlockRegistry.LocationKey> members = getNetworkMembers(key);
            if (members != null) covered.addAll(members); else covered.add(key);
        }
    }

    private void doRecalculate(CustomBlockRegistry.LocationKey changed) {
        // Scope the read-only STATE cache to exactly this doRecalculate (a re-entrant drain call gets a
        // fresh map; reads outside a recalc stay live). `prev` keeps it null-nesting-safe though today
        // doRecalculate is never nested.
        Map<CustomBlockRegistry.LocationKey, String> prev = recalcStateCache;
        recalcStateCache = new HashMap<>();
        try {
            doRecalculate0(changed);
        } finally {
            recalcStateCache = prev;
        }
    }

    private void doRecalculate0(CustomBlockRegistry.LocationKey changed) {
        // Snapshot which nodes are currently in jammed networks (for transition detection).
        // Scan ALL jammed networks, not just the changed one — neighbor networks also get torn down
        // and rebuilt below, and missing them replays the jam smoke/sound on a merge that stays jammed.
        Set<CustomBlockRegistry.LocationKey> previouslyJammed = new HashSet<>();
        // Also snapshot members of already-powered networks, so the powered event below fires only on a
        // false→true rising edge (a member not in this set gaining power), never on every recalc.
        Set<CustomBlockRegistry.LocationKey> previouslyPowered = new HashSet<>();
        for (Map.Entry<Integer, NetworkState> e : networks.entrySet()) {
            Set<CustomBlockRegistry.LocationKey> m = networkMembers.get(e.getKey());
            if (m == null) continue;
            if (e.getValue().jammed()) previouslyJammed.addAll(m);
            if (e.getValue().powered()) previouslyPowered.addAll(m);
        }

        // 1. Determine dirty set — clear old network indexes + directions
        Set<CustomBlockRegistry.LocationKey> dirty = new HashSet<>();
        Integer oldNetId = nodeNetworkId.get(changed);
        if (oldNetId != null) {
            Set<CustomBlockRegistry.LocationKey> members = networkMembers.remove(oldNetId);
            if (members != null) {
                dirty.addAll(members);
                for (CustomBlockRegistry.LocationKey dk : members) {
                    nodeNetworkId.remove(dk);
                    nodeDirection.remove(dk);
                }
            }
            resetPassiveSources(oldNetId);
            networks.remove(oldNetId);
        } else {
            dirty.add(changed);
        }

        // Always dirty neighbor networks (handles clutch unlock, new node, etc.) — TRANSITIVELY over
        // getConnections, which includes chain-pulley distance edges. One hop isn't enough: closing a
        // 3+ ring A→B→C→A must tear down EVERY ring member's network, or a member keeps a stale network
        // id and the BFS skip-guard below drops it, fragmenting the merged component. Walk the whole
        // reachable graph, tearing down each network as we reach it.
        Deque<CustomBlockRegistry.LocationKey> frontier = new ArrayDeque<>(dirty);
        while (!frontier.isEmpty()) {
            RotationNode node = nodes.get(frontier.poll());
            if (node == null) continue;
            for (Connection conn : getConnections(node)) {
                CustomBlockRegistry.LocationKey nb = conn.neighbor();
                if (!nodes.containsKey(nb)) continue;
                Integer nid = nodeNetworkId.get(nb);
                if (nid != null) {
                    Set<CustomBlockRegistry.LocationKey> nMembers = networkMembers.remove(nid);
                    resetPassiveSources(nid);
                    networks.remove(nid);
                    if (nMembers != null) {
                        for (CustomBlockRegistry.LocationKey dk : nMembers) {
                            nodeNetworkId.remove(dk);
                            nodeDirection.remove(dk);
                            if (dirty.add(dk)) frontier.add(dk);
                        }
                    }
                } else if (dirty.add(nb)) {
                    frontier.add(nb);
                }
            }
        }

        dirty.removeIf(k -> !nodes.containsKey(k));

        // 2. Decompose the dirty set into networks. A ratchet freewheels (fully severs) when the
        //    shaft turns against its allowed direction — but that direction is only known AFTER a
        //    component is flooded and anchored. So when any ratchet is dirty we solve TWICE: a
        //    measure pass (fully engaged, no side effects) resolves each ratchet's direction and
        //    picks the cut set, then an apply pass re-solves with those ratchets severed and commits.
        //    Ratchet-free recalcs keep the original single pass (no cost regression).
        boolean hasRatchet = false;
        for (CustomBlockRegistry.LocationKey k : dirty) {
            RotationNode n = nodes.get(k);
            if (n != null && RATCHET_ID.equals(n.blockTypeId())) { hasRatchet = true; break; }
        }
        if (!hasRatchet) {
            runDecomposition(dirty, previouslyJammed, previouslyPowered, true, null);
            return;
        }

        // Measure pass: fully engaged, no commit — record each powered ratchet's resolved direction.
        Map<CustomBlockRegistry.LocationKey, SpinDirection> ratchetDirs = new HashMap<>();
        runDecomposition(dirty, previouslyJammed, previouslyPowered, false, ratchetDirs);
        Set<CustomBlockRegistry.LocationKey> cut = new HashSet<>();
        for (Map.Entry<CustomBlockRegistry.LocationKey, SpinDirection> e : ratchetDirs.entrySet()) {
            SpinDirection allowed = readRatchetAllowed(e.getKey());
            if (allowed != null && e.getValue() != allowed) cut.add(e.getKey());
        }

        // Rewind the measure pass: its ONLY instance-map write is nodeNetworkId (dirMap/supply/
        // anchoring are component-local; every other map + all side effects are gated on commit).
        // Every measure-assigned key is in `dirty` (BFS with an empty cut can't reach past the
        // teardown frontier), so clearing dirty's nodeNetworkId fully restores the post-teardown state.
        for (CustomBlockRegistry.LocationKey k : dirty) nodeNetworkId.remove(k);

        // Apply pass: severed ratchets in force, committing side effects. Reset the cut in finally.
        freewheelCut = cut;
        try {
            runDecomposition(dirty, previouslyJammed, previouslyPowered, true, null);
        } finally {
            freewheelCut = Set.of();
        }
    }

    /**
     * Flood {@code dirty} into connected components, resolving spin direction, supply/demand and jams.
     * When {@code commit} is true, stores node directions/network state and fires the block-state,
     * jam-smoke, and powered-edge side effects. When false (the ratchet measure pass) it stops right
     * after anchoring and — if {@code ratchetDirsOut} is non-null — records each powered, non-jammed
     * {@code mech:ratchet} member's resolved direction there. The only instance-map write shared by
     * both modes is {@code nodeNetworkId} (see the rewind note in {@link #doRecalculate}).
     */
    private void runDecomposition(Set<CustomBlockRegistry.LocationKey> dirty,
                                  Set<CustomBlockRegistry.LocationKey> previouslyJammed,
                                  Set<CustomBlockRegistry.LocationKey> previouslyPowered,
                                  boolean commit,
                                  @Nullable Map<CustomBlockRegistry.LocationKey, SpinDirection> ratchetDirsOut) {
        // BFS from each unassigned dirty node → new components with direction tracking
        for (CustomBlockRegistry.LocationKey start : dirty) {
            if (nodeNetworkId.containsKey(start)) continue;

            int netId = nextNetworkId++;
            Set<CustomBlockRegistry.LocationKey> members = new HashSet<>();
            int supply = 0, demand = 0;
            boolean jammed = false;
            boolean chainLoop = false;  // set when a member pulley transmits on a closed loop

            // ── Pass A: membership flood across ALL edges (gearboxes included) so supply/demand pool
            // across the whole component. Spin direction is resolved separately in pass B, because a
            // gearbox is a DIRECTION FIREWALL — it carries power but couples no CW/CCW across itself.
            Queue<CustomBlockRegistry.LocationKey> queue = new ArrayDeque<>();
            Set<CustomBlockRegistry.LocationKey> enqueued = new HashSet<>();
            queue.add(start);
            enqueued.add(start);

            while (!queue.isEmpty() && members.size() < maxNetworkSize) {
                CustomBlockRegistry.LocationKey loc = queue.poll();
                if (nodeNetworkId.containsKey(loc)) continue;
                RotationNode node = nodes.get(loc);
                if (node == null) continue;

                nodeNetworkId.put(loc, netId);
                members.add(loc);

                if (node.role() == NodeRole.SOURCE) supply += node.powerUnits();
                if (node.role() == NodeRole.CONSUMER) demand += node.powerUnits();
                // Chain pulley on a live loop draws ceil(span/10) power on its outgoing link, so a long
                // chain isn't a free power-teleporter (balance vs gears). Open/dead chains cost nothing.
                if (ChainPulley.PULLEY_ID.equals(node.blockTypeId())) {
                    CustomBlockRegistry.LocationKey partner = chainOut.get(loc);
                    if (partner != null && onClosedLoop(loc)) {
                        double dx = loc.x() - partner.x(), dy = loc.y() - partner.y(), dz = loc.z() - partner.z();
                        demand += (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) / 10.0);
                        chainLoop = true;
                    }
                }

                for (Connection conn : getConnections(node)) {
                    CustomBlockRegistry.LocationKey nk = conn.neighbor();
                    if (!nodeNetworkId.containsKey(nk) && enqueued.add(nk)) queue.add(nk);
                }
            }

            // Scan boundary for passive sources (windmills) + record each one's attach member. Their
            // spin direction is assigned per direction-domain in pass B (a windmill rides its attach).
            Set<CustomBlockRegistry.LocationKey> passiveSources = new HashSet<>();
            Map<CustomBlockRegistry.LocationKey, CustomBlockRegistry.LocationKey> passiveAttach = new HashMap<>();
            if (!passiveSourceTypes.isEmpty()) {
                Set<CustomBlockRegistry.LocationKey> countedSources = new HashSet<>();
                for (CustomBlockRegistry.LocationKey loc : members) {
                    RotationNode memberNode = nodes.get(loc);
                    if (memberNode == null) continue;
                    if (memberNode.omni()) continue;   // omni sink has a nominal axis; not a windmill attach point
                    Axis nodeAxis = memberNode.axis();
                    for (BlockFace face : Faces.CARDINAL) {
                        if (axisFromFace(face) != nodeAxis) continue;
                        CustomBlockRegistry.LocationKey neighbor = faceNeighbor(loc, face);
                        if (nodes.containsKey(neighbor)) continue;
                        Block nb = toBlock(neighbor);
                        if (nb == null) continue;
                        CustomHeadBlock type = registry.getTypeFromBlock(nb);
                        if (type != null) {
                            Integer passivePower = passiveSourceTypes.get(type.fullId());
                            if (passivePower != null) {
                                String passiveState = registry.getState(nb);
                                if (passiveState != null && axisFromState(passiveState) == nodeAxis) {
                                    // Dedup AFTER axis validation: a wrong-axis probe must not block a
                                    // valid one. Counts each windmill's power exactly once.
                                    if (!countedSources.add(neighbor)) continue;
                                    supply += passivePower;
                                    passiveSources.add(neighbor);
                                    passiveAttach.put(neighbor, loc);
                                }
                            }
                        }
                    }
                }
            }

            // ── Pass B: resolve spin direction per gearbox-free DOMAIN. Membership (hence power) already
            // spans gearboxes; here each maximal gearbox-free region floods on its own, gets its own
            // source anchor, and its own jam verdict — so two sources on perpendicular axes feeding one
            // gearbox never compare frame-less CW/CCW tokens across it (the false-jam this fixes). Each
            // domain is seeded from its lowest LocationKey member, so a powered-but-sourceless domain
            // (fed only through a gearbox) still resolves to a STABLE direction across recalcs.
            Map<CustomBlockRegistry.LocationKey, SpinDirection> dirMap = new HashMap<>();
            Comparator<Map.Entry<CustomBlockRegistry.LocationKey, SpinDirection>> byKey = Comparator
                    .comparing((Map.Entry<CustomBlockRegistry.LocationKey, SpinDirection> e) -> e.getKey().worldId())
                    .thenComparingInt(e -> e.getKey().x())
                    .thenComparingInt(e -> e.getKey().y())
                    .thenComparingInt(e -> e.getKey().z());
            List<CustomBlockRegistry.LocationKey> sortedMembers = new ArrayList<>(members);
            sortedMembers.sort(Comparator
                    .comparing(CustomBlockRegistry.LocationKey::worldId)
                    .thenComparingInt(CustomBlockRegistry.LocationKey::x)
                    .thenComparingInt(CustomBlockRegistry.LocationKey::y)
                    .thenComparingInt(CustomBlockRegistry.LocationKey::z));

            for (CustomBlockRegistry.LocationKey root : sortedMembers) {
                if (dirMap.containsKey(root)) continue;

                // Flood one domain over NON-gearbox edges only (the firewall boundary).
                List<CustomBlockRegistry.LocationKey> domain = new ArrayList<>();
                boolean domainJam = false;
                Queue<CustomBlockRegistry.LocationKey> dq = new ArrayDeque<>();
                dirMap.put(root, SpinDirection.CW);
                dq.add(root);
                domain.add(root);
                while (!dq.isEmpty()) {
                    CustomBlockRegistry.LocationKey loc = dq.poll();
                    RotationNode node = nodes.get(loc);
                    if (node == null) continue;
                    SpinDirection myDir = dirMap.get(loc);
                    for (Connection conn : getConnections(node)) {
                        CustomBlockRegistry.LocationKey nk = conn.neighbor();
                        if (!members.contains(nk)) continue;
                        RotationNode other = nodes.get(nk);
                        if (node.gearbox() || (other != null && other.gearbox())) continue;  // firewall
                        SpinDirection neighborDir = conn.reverses() ? myDir.reversed() : myDir;
                        SpinDirection existing = dirMap.get(nk);
                        if (existing != null) {
                            if (existing != neighborDir) domainJam = true;   // in-domain cycle contradiction
                        } else {
                            dirMap.put(nk, neighborDir);
                            dq.add(nk);
                            domain.add(nk);
                        }
                    }
                }

                // Windmills ride their attach's direction (along-axis, same domain).
                for (var e : passiveAttach.entrySet()) {
                    if (domain.contains(e.getValue())) {
                        dirMap.put(e.getKey(), dirMap.get(e.getValue()));
                        domain.add(e.getKey());
                    }
                }

                // Per-domain anchor: pin the frame to the lowest-key source (a stored PDC direction is
                // preferred) so display/ratchet direction is deterministic and stable across a reverser
                // toggle / reload; a SECOND stored-direction source in the SAME domain that still
                // disagrees is a real jam. (Cross-gearbox source pairs are in different domains and are
                // never compared — that is the point.)
                List<Map.Entry<CustomBlockRegistry.LocationKey, SpinDirection>> allSources = new ArrayList<>();
                List<Map.Entry<CustomBlockRegistry.LocationKey, SpinDirection>> storedSources = new ArrayList<>();
                for (CustomBlockRegistry.LocationKey loc : domain) {
                    if (!passiveAttach.containsKey(loc)) {
                        RotationNode node = nodes.get(loc);
                        if (node == null || node.role() != NodeRole.SOURCE) continue;
                    }
                    SpinDirection stored = readStoredDirection(loc);
                    allSources.add(Map.entry(loc, stored != null ? stored : SpinDirection.CW));
                    if (stored != null) storedSources.add(Map.entry(loc, stored));
                }

                if (!allSources.isEmpty()) {
                    var anchor = (storedSources.isEmpty() ? allSources : storedSources)
                            .stream().min(byKey).orElseThrow();
                    SpinDirection bfsDir = dirMap.getOrDefault(anchor.getKey(), SpinDirection.CW);
                    if (bfsDir != anchor.getValue()) {
                        for (CustomBlockRegistry.LocationKey loc : domain) {
                            dirMap.computeIfPresent(loc, (k, v) -> v.reversed());
                        }
                    }
                    for (var entry : storedSources) {
                        SpinDirection computed = dirMap.getOrDefault(entry.getKey(), SpinDirection.CW);
                        if (computed != entry.getValue()) {
                            domainJam = true;
                            break;
                        }
                    }
                }

                jammed |= domainJam;
            }

            // Measure pass: record each powered, non-jammed ratchet's resolved (post-anchor)
            // direction, then stop before any side effect. A ratchet in a jammed/unpowered component
            // has no well-defined direction, so it is left out of the cut set (never freewheels there).
            if (ratchetDirsOut != null && supply > 0 && !jammed) {
                for (CustomBlockRegistry.LocationKey loc : members) {
                    RotationNode rn = nodes.get(loc);
                    if (rn != null && RATCHET_ID.equals(rn.blockTypeId())) {
                        ratchetDirsOut.put(loc, dirMap.getOrDefault(loc, SpinDirection.CW));
                    }
                }
            }
            if (!commit) continue;

            // Store node directions + set animation directions BEFORE state updates
            for (CustomBlockRegistry.LocationKey loc : members) {
                SpinDirection dir = dirMap.getOrDefault(loc, SpinDirection.CW);
                nodeDirection.put(loc, dir);
                // A powered reverser's dirMap value groups with its mount/input side (it flips the
                // output edge). Its rod should instead spin with its OUTPUT side, so flip the DISPLAY
                // only — nodeDirection (propagation/debug) stays the network's real direction.
                RotationNode n = nodes.get(loc);
                SpinDirection animDir =
                    (n != null && n.reverserOutSign() != 0 && isPoweredReverser(n)) ? dir.reversed() : dir;
                registry.setAnimationDirection(loc, animDir);
            }
            for (CustomBlockRegistry.LocationKey ps : passiveSources) {
                SpinDirection dir = dirMap.getOrDefault(ps, SpinDirection.CW);
                registry.setAnimationDirection(ps, dir);
            }

            networkMembers.put(netId, members);
            if (!passiveSources.isEmpty()) networkPassiveSources.put(netId, passiveSources);
            NetworkState netState = new NetworkState(supply, demand, jammed);
            networks.put(netId, netState);

            // verbose: fires per network on every recalculation — uncomment to trace network state
            // logger.info("[Rotation] Network #" + netId + ": " + members.size() + " blocks, "
            //     + supply + "/" + demand + " Power"
            //     + (jammed ? ", JAMMED" : "")
            //     + ", " + (netState.powered() ? "POWERED" : "unpowered"));

            // Smoke + sound on transition to jammed
            if (jammed && !members.stream().allMatch(previouslyJammed::contains)) {
                boolean playedSound = false;
                for (CustomBlockRegistry.LocationKey loc : members) {
                    Block b = toBlock(loc);
                    if (b == null) continue;
                    World w = b.getWorld();
                    Location center = b.getLocation().add(0.5, 0.5, 0.5);
                    w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, center, 3, 0.2, 0.2, 0.2, 0.01);
                    if (!playedSound) {
                        w.playSound(center, Sound.BLOCK_ANVIL_LAND, 0.5f, 0.5f);
                        playedSound = true;
                    }
                }
            }

            boolean powered = netState.powered();
            for (CustomBlockRegistry.LocationKey loc : members) {
                updateBlockState(loc, powered);
            }

            // Rising edge only: fire once for a network that just became powered (some member was not
            // already in a powered network). Idempotent grants + the companion's short-circuit absorb
            // the minor over-fire when a powered network merely grows.
            if (powered && !members.stream().allMatch(previouslyPowered::contains)) {
                // Collect the network's source block-types (active SOURCE nodes + passive sources like
                // windmills) so listeners can reward "power it with a windmill/water wheel/engine".
                Set<String> sourceTypeIds = new HashSet<>();
                for (CustomBlockRegistry.LocationKey loc : members) {
                    RotationNode n = nodes.get(loc);
                    if (n != null && n.role() == NodeRole.SOURCE) sourceTypeIds.add(n.blockTypeId());
                }
                for (CustomBlockRegistry.LocationKey ps : passiveSources) {
                    Block pb = toBlock(ps);
                    if (pb == null) continue;
                    CustomHeadBlock t = registry.getTypeFromBlock(pb);
                    if (t != null) sourceTypeIds.add(t.fullId());
                }
                List<String> sourceTypes = List.copyOf(sourceTypeIds);
                for (CustomBlockRegistry.LocationKey loc : members) {
                    Block b = toBlock(loc);
                    if (b == null) continue;
                    Location center = b.getLocation().add(0.5, 0.5, 0.5);
                    Bukkit.getPluginManager().callEvent(new RotationNetworkPoweredEvent(
                        center, netState.supply(), netState.demand(), members.size(), sourceTypes, chainLoop));
                    break;
                }
            }
        }
    }

    private void resetPassiveSources(int netId) {
        Set<CustomBlockRegistry.LocationKey> oldPassives = networkPassiveSources.remove(netId);
        if (oldPassives != null) {
            for (CustomBlockRegistry.LocationKey ps : oldPassives) {
                registry.setAnimationDirection(ps, SpinDirection.CW);
            }
        }
    }

    @Nullable SpinDirection readStoredDirection(CustomBlockRegistry.LocationKey key) {
        Block block = toBlock(key);
        if (block == null) return null;
        if (!(block.getState() instanceof org.bukkit.block.TileState tile)) return null;
        String val = tile.getPersistentDataContainer().get(SPIN_DIR_KEY, PersistentDataType.STRING);
        if (val == null) return null;
        return switch (val) {
            case "cw" -> SpinDirection.CW;
            case "ccw" -> SpinDirection.CCW;
            default -> null;
        };
    }

    /** A ratchet's allowed spin direction, parsed from its state string ({@code idle_cw_x} → CW,
     *  {@code spinning_ccw_z} → CCW). Kept in the STATE (not PDC) so it survives mechanism assembly
     *  and rotation. Null when the state carries no direction token (freshly placed, not yet
     *  defaulted) — such a ratchet is treated as "pass-through" and never freewheels. */
    @Nullable SpinDirection readRatchetAllowed(CustomBlockRegistry.LocationKey key) {
        Block block = toBlock(key);
        if (block == null) return null;
        String state = cachedState(key, block); // read-only predicate — safe to memoize per recalc
        if (state == null) return null;
        for (String tok : state.split("_")) {
            if (tok.equals("ccw")) return SpinDirection.CCW;
            if (tok.equals("cw")) return SpinDirection.CW;
        }
        return null;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Connection logic
    // ──────────────────────────────────────────────────────────────────────

    List<Connection> getConnections(RotationNode node) {
        if (isSevered(node)) return List.of();

        // Omni consumer: a single leaf edge to the first aligned shaft (see omniAttachKey). No along-axis,
        // gear, or chain edges — a sink draws from one shaft and passes nothing on, so it never bridges
        // networks or takes part in cross-axis spin bookkeeping.
        if (node.omni()) {
            CustomBlockRegistry.LocationKey attach = omniAttachKey(node);
            return attach == null ? List.of() : List.of(new Connection(attach, false));
        }

        // Gearbox: an omnidirectional hub. On each face it couples (spin-preserving) to the neighbor
        // there iff that neighbor is another gearbox OR a node whose axis runs along that face (a shaft
        // or gear aligned with the face). Every edge is reverses=false, so the hub and its aligned
        // shafts share one spin label (a tree — no false jams). Symmetric from the neighbor side: an
        // aligned shaft's along-axis probe lands on the gearbox and checkAxisNeighbor connects back.
        // Early-return before along-axis/gearLike/chain — the gearbox's nominal axis is meaningless.
        if (node.gearbox()) {
            List<Connection> res = new ArrayList<>(6);
            CustomBlockRegistry.LocationKey gk = node.key();
            for (BlockFace face : Faces.CARDINAL) {
                CustomBlockRegistry.LocationKey nk = faceNeighbor(gk, face);
                RotationNode other = nodes.get(nk);
                if (other == null || isSevered(other)) continue;
                if (other.omni()) {
                    // Emit the back-edge only if this omni consumer actually chose us as its attach —
                    // mirrors checkAxisNeighbor, so a non-chosen omni isn't pulled in (and one that DID
                    // choose us isn't orphaned).
                    if (gk.equals(omniAttachKey(other))) res.add(new Connection(nk, false));
                } else if (other.gearbox() || other.axis() == axisFromFace(face)) {
                    res.add(new Connection(nk, false));
                }
            }
            return res;
        }

        List<Connection> result = new ArrayList<>(6);
        CustomBlockRegistry.LocationKey k = node.key();

        // Along-axis: 2 neighbors (checked first — load-bearing for reverses classification).
        // An along-axis edge reverses iff EXACTLY ONE of its endpoints is a powered reverser whose
        // output face points along that edge, outward (UP for a floor head, its facing for a wall
        // head — see reverserOutSign). XOR keeps both half-edges' flags identical from either side
        // (symmetry the direction-contradiction jam relies on) and makes two reversers pointed at
        // each other cancel. Reduces to "the one reverser facing this edge" for an ordinary shaft,
        // and correctly drives the reverser's flip onto the shaft side even when its mount side is
        // a dead wall (previously a −axis-facing wall reverser was inert).
        Axis axis = node.axis();
        int selfSign = poweredReverserSign(node, axis);
        CustomBlockRegistry.LocationKey posKey = axisNeighbor(k, axis, +1);
        CustomBlockRegistry.LocationKey negKey = axisNeighbor(k, axis, -1);
        boolean posReverses = (selfSign > 0) ^ (poweredReverserSign(nodes.get(posKey), axis) < 0);
        boolean negReverses = (selfSign < 0) ^ (poweredReverserSign(nodes.get(negKey), axis) > 0);
        checkAxisNeighbor(k, posKey, axis, posReverses, result);
        checkAxisNeighbor(k, negKey, axis, negReverses, result);

        // Gear-to-gear: connects to ANY adjacent gear (all 6 faces, any axis)
        if (node.gearLike()) {
            for (BlockFace face : Faces.CARDINAL) {
                CustomBlockRegistry.LocationKey neighbor = faceNeighbor(k, face);
                if (result.stream().anyMatch(c -> c.neighbor().equals(neighbor))) continue;
                RotationNode other = nodes.get(neighbor);
                if (other != null && other.gearLike() && !isSevered(other)) {
                    boolean sameAxis = other.axis() == node.axis();
                    boolean reverses = sameAxis || bevelReverses(node.axis(), other.axis(), face);
                    result.add(new Connection(neighbor, reverses));
                }
            }
        }

        // Chain-pulley distance edge: inject the outgoing link ONLY when this pulley sits on a closed
        // loop (an open chain carries no power). reverses=false — a chain drive keeps spin direction.
        // Connection.neighbor() is unconstrained, so BFS in doRecalculate hops across the gap and every
        // ring member's out-edge merges the whole loop into one network.
        CustomBlockRegistry.LocationKey chainPartner = chainOut.get(k);
        if (chainPartner != null && nodes.containsKey(chainPartner) && !isSevered(nodes.get(chainPartner))
                && onClosedLoop(k)
                && result.stream().noneMatch(c -> c.neighbor().equals(chainPartner))) {
            result.add(new Connection(chainPartner, false));
        }

        return result;
    }

    private void checkAxisNeighbor(CustomBlockRegistry.LocationKey callerKey,
                                   CustomBlockRegistry.LocationKey neighborKey, Axis requiredAxis,
                                   boolean reverses, List<Connection> result) {
        RotationNode other = nodes.get(neighborKey);
        if (other == null || isSevered(other)) return;
        if (other.omni()) {
            // Mutual single edge: connect back only if this omni neighbour actually chose us, so a
            // non-chosen adjacent shaft never pulls it into that shaft's network. reverses=false — a
            // leaf sink imposes no spin direction, keeping both half-edges symmetric (no spurious jam).
            if (callerKey.equals(omniAttachKey(other))) result.add(new Connection(neighborKey, false));
            return;
        }
        if (other.gearbox()) {
            // A gearbox couples to any aligned neighbour on all six faces; since this probe runs along
            // the caller's axis, the gearbox lies on the caller's ±axis and its face loop emits the
            // matching reverses=false edge back — symmetric. (Intentionally drops a reverser flip into
            // the hub: matching a true on only this side would create a false jam. See gearbox docs.)
            result.add(new Connection(neighborKey, false));
            return;
        }
        if (other.axis() == requiredAxis) {
            result.add(new Connection(neighborKey, reverses));
        }
    }

    /** The single shaft an omni node draws from: the first aligned shaft in fixed {@link Faces#CARDINAL}
     *  order (skipping its mounted face), or null. Evaluated live so it self-corrects every recalc —
     *  a shaft that registers after the block is picked up as soon as its addNode triggers recalculation. */
    private CustomBlockRegistry.@Nullable LocationKey omniAttachKey(RotationNode node) {
        for (BlockFace face : Faces.CARDINAL) {
            if (face == node.omniExcludedFace()) continue;
            CustomBlockRegistry.LocationKey nk = faceNeighbor(node.key(), face);
            RotationNode other = nodes.get(nk);
            // A gearbox is a valid attach on ANY face (its hub couples on all six); its nominal axis is
            // always Y, so without the gearbox() term an omni consumer could only tap it through UP/DOWN.
            if (other != null && !other.omni() && !isSevered(other)
                    && (other.gearbox() || other.axis() == axisFromFace(face))) {
                return nk;
            }
        }
        return null;
    }

    /** A reverser is an along-axis-only transmitter that flips spin direction once across
     *  itself while redstone-powered. Power is read live (it changes via redstone, which fires
     *  the reverser's onNeighborChange → recalculate, so getConnections sees the fresh value). */
    private boolean isPoweredReverser(@Nullable RotationNode node) {
        if (node == null || !REVERSER_ID.equals(node.blockTypeId())) return false;
        Block b = toBlock(node.key());
        return b != null && b.getBlockPower() > 0;
    }

    /** Output-face sign (+1/−1) of a reverser along {@code axis}: UP for a floor head, its facing
     *  for a wall head. 0 for non-reversers. Captured once at {@link #addNode} so the hot path
     *  never reads live facing — facing only changes via rotation/glue, which re-add the node
     *  (and the reverser cancels pistons), whereas redstone power changes with no re-add. */
    private static int reverserOutSign(Block block, String blockTypeId, Axis axis) {
        if (!REVERSER_ID.equals(blockTypeId)) return 0;
        if (block.getBlockData() instanceof org.bukkit.block.data.Directional dir) {
            BlockFace f = dir.getFacing();                 // wall head → points out of the wall
            return axisComponent(axis, f.getModX(), f.getModY(), f.getModZ());
        }
        return axis == Axis.Y ? 1 : 0;                     // floor head → UP
    }

    /** Signed output of {@code node} along {@code axis} when it's a live-powered reverser on that
     *  axis, else 0. Drives the XOR edge-reversal rule in {@link #getConnections}. */
    private int poweredReverserSign(@Nullable RotationNode node, Axis axis) {
        if (node == null || node.reverserOutSign() == 0 || node.axis() != axis) return 0;
        return isPoweredReverser(node) ? node.reverserOutSign() : 0;
    }

    private boolean isLocked(RotationNode node) {
        Block b = toBlock(node.key());
        if (b == null) return false;
        String state = cachedState(node.key(), b); // read-only predicate — safe to memoize per recalc
        return state != null && state.startsWith("locked_");
    }

    /** A node emits NO connections when it is locked (redstone clutch) OR freewheeling (a ratchet
     *  cut this solve because the shaft is turning against its allowed direction). Must be consulted
     *  on BOTH endpoints of every edge (all six edge sites in getConnections/checkAxisNeighbor/
     *  omniAttachKey) — an asymmetric half-edge yields a non-deterministic partition. */
    private boolean isSevered(RotationNode node) {
        return isLocked(node) || freewheelCut.contains(node.key());
    }

    // ──────────────────────────────────────────────────────────────────────
    // State propagation
    // ──────────────────────────────────────────────────────────────────────

    private void updateBlockState(CustomBlockRegistry.LocationKey loc, boolean powered) {
        RotationNode node = nodes.get(loc);
        if (node == null || node.role() == NodeRole.SOURCE) return;

        Block block = toBlock(loc);
        if (block == null) return;
        // Bare (chain) shafts have no PDC state — drive their rod spin directly from network power,
        // guarded so the rod is only re-applied on an actual idle↔spinning transition.
        if (registry.driveChainShaftSpinIfChain(block, powered)) return;
        String current = registry.getState(block);
        if (current == null) return;

        // Extract suffix (e.g. "east_x" from "idle_east_x") and rebuild with idle/spinning prefix.
        String suffix;
        if (current.startsWith("idle_")) {
            suffix = current.substring(5);
        } else if (current.startsWith("spinning_")) {
            suffix = current.substring(9);
        } else if (current.equals("idle") || current.equals("spinning")) {
            suffix = null;
        } else {
            return;
        }
        String target = suffix != null
                ? (powered ? "spinning_" : "idle_") + suffix
                : (powered ? "spinning" : "idle");
        if (target.equals(current)) return;

        CustomHeadBlock type = registry.getTypeFromBlock(block);
        if (type == null) return;

        // Verify the target state exists
        if (!type.states().containsKey(target)) return;

        registry.setState(block, target);
        registry.applyConfig(block, type, target, 0);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    private static CustomBlockRegistry.LocationKey faceNeighbor(CustomBlockRegistry.LocationKey k, BlockFace face) {
        return new CustomBlockRegistry.LocationKey(k.worldId(),
            k.x() + face.getModX(), k.y() + face.getModY(), k.z() + face.getModZ());
    }

    private @Nullable Block toBlock(CustomBlockRegistry.LocationKey key) {
        World world = Bukkit.getWorld(key.worldId());
        if (world == null) return null;
        // Don't sync-load an unloaded chunk. Graph nodes are always in loaded chunks (added on chunk
        // load, removed on unload), so this only affects the passive-source boundary probe reaching
        // into an unloaded neighbor chunk — which should be skipped, not force-loaded.
        if (!world.isChunkLoaded(key.x() >> 4, key.z() >> 4)) return null;
        return world.getBlockAt(key.x(), key.y(), key.z());
    }

    private static CustomBlockRegistry.LocationKey axisNeighbor(CustomBlockRegistry.LocationKey k, Axis axis, int offset) {
        return switch (axis) {
            case X -> new CustomBlockRegistry.LocationKey(k.worldId(), k.x() + offset, k.y(), k.z());
            case Y -> new CustomBlockRegistry.LocationKey(k.worldId(), k.x(), k.y() + offset, k.z());
            case Z -> new CustomBlockRegistry.LocationKey(k.worldId(), k.x(), k.y(), k.z() + offset);
        };
    }

    private static int axisComponent(Axis axis, int dx, int dy, int dz) {
        return switch (axis) { case X -> dx; case Y -> dy; case Z -> dz; };
    }

    // Package-visible: RotationSolver mirrors these edge rules for mechanism-mounted rotation parts.
    static boolean bevelReverses(Axis a, Axis b, BlockFace face) {
        int dx = face.getModX(), dy = face.getModY(), dz = face.getModZ();
        int dA = axisComponent(a, dx, dy, dz);
        int dB = axisComponent(b, dx, dy, dz);
        int remOrd = 3 - a.ordinal() - b.ordinal();
        int dRem = axisComponent(Axis.values()[remOrd], dx, dy, dz);
        int cross = (b.ordinal() == (a.ordinal() + 1) % 3) ? dRem : -dRem;
        return (dA - dB + cross) > 0;
    }

    public static Axis axisFromFace(BlockFace face) {
        return switch (face) {
            case DOWN, UP -> Axis.Y;
            case NORTH, SOUTH -> Axis.Z;
            case EAST, WEST -> Axis.X;
            default -> Axis.Y;
        };
    }

    public static Axis axisFromState(String state) {
        int i = state.lastIndexOf('_');
        // Axis-less states (e.g. millstone "idle") default to Y — correct for floor-only blocks
        if (i < 0) return Axis.Y;
        String suffix = state.substring(i + 1);
        // Wall-mounted windmills carry a wall-direction suffix instead of an axis letter; map it to the
        // axis their blades actually spin on (n/s → Z, e/w → X) so a wall windmill supplies power along
        // its visual spin axis, not always Y.
        return switch (suffix) {
            case "x", "e", "w" -> Axis.X;
            case "z", "n", "s" -> Axis.Z;
            default -> Axis.Y;
        };
    }
}
