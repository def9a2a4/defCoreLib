package anon.def9a2a4.corelib;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Skull;
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
                        NodeRole role, int powerUnits, boolean gearLike, int reverserOutSign,
                        boolean omni, @Nullable BlockFace omniExcludedFace) {}

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

    // Config
    private int maxNetworkSize = 256;

    static final NamespacedKey SPIN_DIR_KEY = new NamespacedKey("mech", "spin_dir");

    private static final String REVERSER_ID = "mech:reverser";

    RotationNetwork(JavaPlugin plugin, CustomBlockRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        this.logger = plugin.getLogger();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────

    public void addNode(Block block, String blockTypeId, Axis axis,
                        NodeRole role, int powerUnits, boolean gearLike) {
        addNode(block, blockTypeId, axis, role, powerUnits, gearLike, false, null);
    }

    /**
     * Register a node, optionally as an <b>omni consumer</b>: a leaf sink that draws power from the
     * first aligned shaft on any of its faces (in {@link Faces#CARDINAL} order, skipping
     * {@code omniExcludedFace}), chosen live during flood-fill so it self-corrects each recalc.
     * Omni nodes store a nominal {@code axis} — their connectivity comes from {@link #omniAttachKey}.
     */
    public void addNode(Block block, String blockTypeId, Axis axis,
                        NodeRole role, int powerUnits, boolean gearLike,
                        boolean omni, @Nullable BlockFace omniExcludedFace) {
        CustomBlockRegistry.LocationKey key = CustomBlockRegistry.LocationKey.of(block);
        int reverserOutSign = reverserOutSign(block, blockTypeId, axis);
        nodes.put(key, new RotationNode(key, blockTypeId, axis, role, powerUnits, gearLike,
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

    private void doRecalculate(CustomBlockRegistry.LocationKey changed) {
        // Snapshot which nodes are currently in jammed networks (for transition detection).
        // Scan ALL jammed networks, not just the changed one — neighbor networks also get torn down
        // and rebuilt below, and missing them replays the jam smoke/sound on a merge that stays jammed.
        Set<CustomBlockRegistry.LocationKey> previouslyJammed = new HashSet<>();
        for (Map.Entry<Integer, NetworkState> e : networks.entrySet()) {
            if (e.getValue().jammed()) {
                Set<CustomBlockRegistry.LocationKey> m = networkMembers.get(e.getKey());
                if (m != null) previouslyJammed.addAll(m);
            }
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

        // 2. BFS from each unassigned dirty node → new components with direction tracking
        for (CustomBlockRegistry.LocationKey start : dirty) {
            if (nodeNetworkId.containsKey(start)) continue;

            int netId = nextNetworkId++;
            Set<CustomBlockRegistry.LocationKey> members = new HashSet<>();
            int supply = 0, demand = 0;
            boolean jammed = false;

            // BFS with direction propagation (tentative root = CW)
            Map<CustomBlockRegistry.LocationKey, SpinDirection> dirMap = new HashMap<>();
            Queue<CustomBlockRegistry.LocationKey> queue = new ArrayDeque<>();
            queue.add(start);
            dirMap.put(start, SpinDirection.CW);

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
                    }
                }

                SpinDirection myDir = dirMap.get(loc);
                for (Connection conn : getConnections(node)) {
                    SpinDirection neighborDir = conn.reverses() ? myDir.reversed() : myDir;
                    CustomBlockRegistry.LocationKey nk = conn.neighbor();
                    if (nodeNetworkId.containsKey(nk)) {
                        // Cycle detection: direction contradiction → jammed
                        SpinDirection existing = dirMap.get(nk);
                        if (existing != null && existing != neighborDir) {
                            jammed = true;
                        }
                    } else if (!dirMap.containsKey(nk)) {
                        dirMap.put(nk, neighborDir);
                        queue.add(nk);
                    }
                }
            }

            // Scan boundary for passive sources + derive their directions
            Set<CustomBlockRegistry.LocationKey> passiveSources = new HashSet<>();
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
                                    // Along-axis edge = preserves direction
                                    SpinDirection adjDir = dirMap.get(loc);
                                    if (adjDir != null) {
                                        passiveSources.add(neighbor);
                                        dirMap.put(neighbor, adjDir);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Post-pass: pin the absolute spin frame to a source so it's deterministic and stable
            // across a reverser toggle / reload — otherwise the arbitrary CW-seeded BFS root decides
            // which side "stays", and toggling a reverser can flip the source instead of the target.
            // EVERY source anchors (desired = its stored PDC direction, else CW); a source with an
            // explicit stored direction is preferred as the anchor. Conflict/jam detection stays over
            // stored-direction sources only, so two flexible (unwrenched) sources on opposite sides of
            // a reverser don't false-jam.
            List<Map.Entry<CustomBlockRegistry.LocationKey, SpinDirection>> allSources = new ArrayList<>();
            List<Map.Entry<CustomBlockRegistry.LocationKey, SpinDirection>> storedSources = new ArrayList<>();
            for (CustomBlockRegistry.LocationKey loc : members) {
                RotationNode node = nodes.get(loc);
                if (node == null || node.role() != NodeRole.SOURCE) continue;
                SpinDirection stored = readStoredDirection(loc);
                allSources.add(Map.entry(loc, stored != null ? stored : SpinDirection.CW));
                if (stored != null) storedSources.add(Map.entry(loc, stored));
            }
            for (CustomBlockRegistry.LocationKey ps : passiveSources) {
                SpinDirection stored = readStoredDirection(ps);
                allSources.add(Map.entry(ps, stored != null ? stored : SpinDirection.CW));
                if (stored != null) storedSources.add(Map.entry(ps, stored));
            }

            if (!allSources.isEmpty()) {
                // Deterministic anchor: lowest LocationKey, preferring a stored-direction source.
                Comparator<Map.Entry<CustomBlockRegistry.LocationKey, SpinDirection>> byKey = Comparator
                        .comparing((Map.Entry<CustomBlockRegistry.LocationKey, SpinDirection> e) -> e.getKey().worldId())
                        .thenComparingInt(e -> e.getKey().x())
                        .thenComparingInt(e -> e.getKey().y())
                        .thenComparingInt(e -> e.getKey().z());
                var anchor = (storedSources.isEmpty() ? allSources : storedSources)
                        .stream().min(byKey).orElseThrow();
                SpinDirection bfsDir = dirMap.getOrDefault(anchor.getKey(), SpinDirection.CW);
                if (bfsDir != anchor.getValue()) {
                    dirMap.replaceAll((k, v) -> v.reversed());
                }

                // Jam only when two EXPLICITLY-directed sources disagree after anchoring.
                for (var entry : storedSources) {
                    SpinDirection computed = dirMap.getOrDefault(entry.getKey(), SpinDirection.CW);
                    if (computed != entry.getValue()) {
                        jammed = true;
                        break;
                    }
                }
            }

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
        if (!(block.getState() instanceof Skull skull)) return null;
        String val = skull.getPersistentDataContainer().get(SPIN_DIR_KEY, PersistentDataType.STRING);
        if (val == null) return null;
        return switch (val) {
            case "cw" -> SpinDirection.CW;
            case "ccw" -> SpinDirection.CCW;
            default -> null;
        };
    }

    // ──────────────────────────────────────────────────────────────────────
    // Batch chunk operations
    // ──────────────────────────────────────────────────────────────────────

    void removeNodesInChunk(UUID worldId, int chunkX, int chunkZ) {
        Set<CustomBlockRegistry.LocationKey> toRemove = new HashSet<>();
        Set<Integer> affectedNetworks = new HashSet<>();

        for (var entry : nodes.entrySet()) {
            CustomBlockRegistry.LocationKey k = entry.getKey();
            if (k.worldId().equals(worldId) && (k.x() >> 4) == chunkX && (k.z() >> 4) == chunkZ) {
                toRemove.add(k);
                Integer nid = nodeNetworkId.get(k);
                if (nid != null) affectedNetworks.add(nid);
            }
        }
        if (toRemove.isEmpty()) return;

        // Remove nodes + direction entries
        for (CustomBlockRegistry.LocationKey k : toRemove) {
            nodes.remove(k);
            nodeNetworkId.remove(k);
            nodeDirection.remove(k);
            transientDemand.remove(k);
        }

        // Collect remaining dirty members of affected networks
        Set<CustomBlockRegistry.LocationKey> dirty = new HashSet<>();
        for (int nid : affectedNetworks) {
            Set<CustomBlockRegistry.LocationKey> members = networkMembers.remove(nid);
            if (members != null) {
                members.removeAll(toRemove);
                dirty.addAll(members);
                for (CustomBlockRegistry.LocationKey dk : members) {
                    nodeNetworkId.remove(dk);
                    nodeDirection.remove(dk);
                }
            }
            resetPassiveSources(nid);
            networks.remove(nid);
        }

        // Rebuild affected components
        for (CustomBlockRegistry.LocationKey start : dirty) {
            if (nodeNetworkId.containsKey(start)) continue;
            doRecalculate(start);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Connection logic
    // ──────────────────────────────────────────────────────────────────────

    List<Connection> getConnections(RotationNode node) {
        if (isLocked(node)) return List.of();

        // Omni consumer: a single leaf edge to the first aligned shaft (see omniAttachKey). No along-axis,
        // gear, or chain edges — a sink draws from one shaft and passes nothing on, so it never bridges
        // networks or takes part in cross-axis spin bookkeeping.
        if (node.omni()) {
            CustomBlockRegistry.LocationKey attach = omniAttachKey(node);
            return attach == null ? List.of() : List.of(new Connection(attach, false));
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
                if (other != null && other.gearLike() && !isLocked(other)) {
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
        if (chainPartner != null && nodes.containsKey(chainPartner) && !isLocked(nodes.get(chainPartner))
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
        if (other == null || isLocked(other)) return;
        if (other.omni()) {
            // Mutual single edge: connect back only if this omni neighbour actually chose us, so a
            // non-chosen adjacent shaft never pulls it into that shaft's network. reverses=false — a
            // leaf sink imposes no spin direction, keeping both half-edges symmetric (no spurious jam).
            if (callerKey.equals(omniAttachKey(other))) result.add(new Connection(neighborKey, false));
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
            if (other != null && !other.omni() && !isLocked(other) && other.axis() == axisFromFace(face)) {
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
        String state = registry.getState(b);
        return state != null && state.startsWith("locked_");
    }

    // ──────────────────────────────────────────────────────────────────────
    // State propagation
    // ──────────────────────────────────────────────────────────────────────

    private void updateBlockState(CustomBlockRegistry.LocationKey loc, boolean powered) {
        RotationNode node = nodes.get(loc);
        if (node == null || node.role() == NodeRole.SOURCE) return;

        Block block = toBlock(loc);
        if (block == null) return;
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

    private static boolean bevelReverses(Axis a, Axis b, BlockFace face) {
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
