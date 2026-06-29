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
 *       axis. A powered reverser on the lower endpoint flips the edge's direction.</li>
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
                        NodeRole role, int powerUnits, boolean gearLike) {}

    record Connection(CustomBlockRegistry.LocationKey neighbor, boolean reverses) {}

    record NetworkState(int supply, int demand, boolean jammed) {
        boolean powered() { return !jammed && supply >= demand && supply > 0; }
    }

    private final CustomBlockRegistry registry;
    private final JavaPlugin plugin;
    private final Logger logger;

    // Passive sources: YAML-only blocks detected at network boundary (e.g. rotation:windmill)
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
    private int nextNetworkId = 0;

    // Re-entrancy guard
    private boolean recalculating = false;
    private final Set<CustomBlockRegistry.LocationKey> pendingRecalcs = new HashSet<>();

    // Config
    private int maxNetworkSize = 256;

    static final NamespacedKey SPIN_DIR_KEY = new NamespacedKey("rotation", "spin_dir");

    private static final String REVERSER_ID = "rotation:reverser";

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
        CustomBlockRegistry.LocationKey key = CustomBlockRegistry.LocationKey.of(block);
        nodes.put(key, new RotationNode(key, blockTypeId, axis, role, powerUnits, gearLike));
        logger.info("[Rotation] addNode: " + blockTypeId + " axis=" + axis
            + " at " + key.x() + "," + key.y() + "," + key.z()
            + " (total nodes: " + nodes.size() + ")");
        recalculate(key);
    }

    public void removeNode(CustomBlockRegistry.LocationKey key) {
        nodes.remove(key);
        transientDemand.remove(key);
        recalculate(key);
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
        for (BlockFace face : CARDINAL_FACES) {
            CustomBlockRegistry.LocationKey neighbor = faceNeighbor(key, face);
            if (nodes.containsKey(neighbor)) {
                recalculate(neighbor);
                return;
            }
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
        // Snapshot which nodes are currently in jammed networks (for transition detection)
        Set<CustomBlockRegistry.LocationKey> previouslyJammed = new HashSet<>();
        Integer changedNet = nodeNetworkId.get(changed);
        if (changedNet != null) {
            NetworkState oldState = networks.get(changedNet);
            if (oldState != null && oldState.jammed()) {
                Set<CustomBlockRegistry.LocationKey> m = networkMembers.get(changedNet);
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

        // Always dirty neighbor networks (handles clutch unlock, new node, etc.)
        RotationNode changedNode = nodes.get(changed);
        if (changedNode != null) {
            for (Connection conn : getConnections(changedNode)) {
                Integer nid = nodeNetworkId.get(conn.neighbor());
                if (nid != null && !dirty.containsAll(networkMembers.getOrDefault(nid, Set.of()))) {
                    Set<CustomBlockRegistry.LocationKey> nMembers = networkMembers.remove(nid);
                    if (nMembers != null) {
                        dirty.addAll(nMembers);
                        for (CustomBlockRegistry.LocationKey dk : nMembers) {
                            nodeNetworkId.remove(dk);
                            nodeDirection.remove(dk);
                        }
                    }
                    resetPassiveSources(nid);
                    networks.remove(nid);
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
                    Axis nodeAxis = memberNode.axis();
                    for (BlockFace face : CARDINAL_FACES) {
                        if (axisFromFace(face) != nodeAxis) continue;
                        CustomBlockRegistry.LocationKey neighbor = faceNeighbor(loc, face);
                        if (nodes.containsKey(neighbor)) continue;
                        if (!countedSources.add(neighbor)) continue;
                        Block nb = toBlock(neighbor);
                        if (nb == null) continue;
                        CustomHeadBlock type = registry.getTypeFromBlock(nb);
                        if (type != null) {
                            Integer passivePower = passiveSourceTypes.get(type.fullId());
                            if (passivePower != null) {
                                String passiveState = registry.getState(nb);
                                if (passiveState != null && axisFromState(passiveState) == nodeAxis) {
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

            // Post-pass: anchor to explicit source directions from PDC
            List<Map.Entry<CustomBlockRegistry.LocationKey, SpinDirection>> explicitSources = new ArrayList<>();
            for (CustomBlockRegistry.LocationKey loc : members) {
                RotationNode node = nodes.get(loc);
                if (node != null && node.role() == NodeRole.SOURCE) {
                    SpinDirection stored = readStoredDirection(loc);
                    if (stored != null) explicitSources.add(Map.entry(loc, stored));
                }
            }
            for (CustomBlockRegistry.LocationKey ps : passiveSources) {
                SpinDirection stored = readStoredDirection(ps);
                if (stored != null) explicitSources.add(Map.entry(ps, stored));
            }

            if (!explicitSources.isEmpty()) {
                // Deterministic anchor: lowest LocationKey
                explicitSources.sort(Comparator
                        .comparing((Map.Entry<CustomBlockRegistry.LocationKey, SpinDirection> e) -> e.getKey().worldId())
                        .thenComparingInt(e -> e.getKey().x())
                        .thenComparingInt(e -> e.getKey().y())
                        .thenComparingInt(e -> e.getKey().z()));

                var anchor = explicitSources.get(0);
                SpinDirection bfsDir = dirMap.getOrDefault(anchor.getKey(), SpinDirection.CW);
                if (bfsDir != anchor.getValue()) {
                    dirMap.replaceAll((k, v) -> v.reversed());
                }

                // Check remaining explicit sources for conflicts
                for (int i = 1; i < explicitSources.size(); i++) {
                    var entry = explicitSources.get(i);
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
                registry.setAnimationDirection(loc, dir);
            }
            for (CustomBlockRegistry.LocationKey ps : passiveSources) {
                SpinDirection dir = dirMap.getOrDefault(ps, SpinDirection.CW);
                registry.setAnimationDirection(ps, dir);
            }

            networkMembers.put(netId, members);
            if (!passiveSources.isEmpty()) networkPassiveSources.put(netId, passiveSources);
            NetworkState netState = new NetworkState(supply, demand, jammed);
            networks.put(netId, netState);

            logger.info("[Rotation] Network #" + netId + ": " + members.size() + " blocks, "
                + supply + "/" + demand + " SU"
                + (jammed ? ", JAMMED" : "")
                + ", " + (netState.powered() ? "POWERED" : "unpowered"));

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

        List<Connection> result = new ArrayList<>(6);
        CustomBlockRegistry.LocationKey k = node.key();

        // Along-axis: 2 neighbors (checked first — load-bearing for reverses classification).
        // An along-axis edge reverses iff its lower (−axis) endpoint is a powered reverser.
        // This yields exactly one direction flip across a reverser (so its two along-axis
        // neighbours spin oppositely) and is symmetric: both endpoints compute the same flag
        // because the lower endpoint of the shared edge is the same block from either side.
        //   +axis edge: this node is the lower endpoint.
        //   −axis edge: the neighbour is the lower endpoint.
        checkAxisNeighbor(axisNeighbor(k, node.axis(), +1), node.axis(), isPoweredReverser(node), result);
        CustomBlockRegistry.LocationKey negKey = axisNeighbor(k, node.axis(), -1);
        checkAxisNeighbor(negKey, node.axis(), isPoweredReverser(nodes.get(negKey)), result);

        // Gear-to-gear: connects to ANY adjacent gear (all 6 faces, any axis)
        if (node.gearLike()) {
            for (BlockFace face : CARDINAL_FACES) {
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

        return result;
    }

    private void checkAxisNeighbor(CustomBlockRegistry.LocationKey neighborKey, Axis requiredAxis,
                                   boolean reverses, List<Connection> result) {
        RotationNode other = nodes.get(neighborKey);
        if (other != null && other.axis() == requiredAxis && !isLocked(other)) {
            result.add(new Connection(neighborKey, reverses));
        }
    }

    /** A reverser is an along-axis-only transmitter that flips spin direction once across
     *  itself while redstone-powered. Power is read live (it changes via redstone, which fires
     *  the reverser's onNeighborChange → recalculate, so getConnections sees the fresh value). */
    private boolean isPoweredReverser(@Nullable RotationNode node) {
        if (node == null || !REVERSER_ID.equals(node.blockTypeId())) return false;
        Block b = toBlock(node.key());
        return b != null && b.getBlockPower() > 0;
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

    private static final BlockFace[] CARDINAL_FACES = {
        BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
    };

    private static CustomBlockRegistry.LocationKey faceNeighbor(CustomBlockRegistry.LocationKey k, BlockFace face) {
        return new CustomBlockRegistry.LocationKey(k.worldId(),
            k.x() + face.getModX(), k.y() + face.getModY(), k.z() + face.getModZ());
    }

    private @Nullable Block toBlock(CustomBlockRegistry.LocationKey key) {
        World world = Bukkit.getWorld(key.worldId());
        if (world == null) return null;
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
        // Axis-less states (e.g. grindstone "idle") default to Y — correct for floor-only blocks
        if (i < 0) return Axis.Y;
        String suffix = state.substring(i + 1);
        return switch (suffix) {
            case "x" -> Axis.X;
            case "z" -> Axis.Z;
            default -> Axis.Y;
        };
    }
}
