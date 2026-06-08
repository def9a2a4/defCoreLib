package anon.def9a2a4.corelib;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
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
 *   <li>Along-axis: every node connects to its 2 neighbors along its axis if they share the same axis.</li>
 *   <li>Perpendicular (gear-like nodes only): connects to 4 neighbors in the perpendicular plane
 *       if the neighbor is also gear-like and has a different axis.</li>
 *   <li>Locked nodes (state starts with "locked_") are excluded from all connections.</li>
 * </ul>
 */
public class RotationNetwork {

    public enum Axis { X, Y, Z }
    public enum NodeRole { SOURCE, TRANSMITTER, CONSUMER }

    record RotationNode(CustomBlockRegistry.LocationKey key, String blockTypeId, Axis axis,
                        NodeRole role, int powerUnits, boolean gearLike) {}

    record NetworkState(int supply, int demand) {
        boolean powered() { return supply >= demand; }
    }

    private final CustomBlockRegistry registry;
    private final JavaPlugin plugin;
    private final Logger logger;

    // Graph state
    private final Map<CustomBlockRegistry.LocationKey, RotationNode> nodes = new HashMap<>();
    private final Map<CustomBlockRegistry.LocationKey, Integer> nodeNetworkId = new HashMap<>();
    private final Map<Integer, Set<CustomBlockRegistry.LocationKey>> networkMembers = new HashMap<>();
    private final Map<Integer, NetworkState> networks = new HashMap<>();
    private int nextNetworkId = 0;

    // Re-entrancy guard
    private boolean recalculating = false;
    private final Set<CustomBlockRegistry.LocationKey> pendingRecalcs = new HashSet<>();

    // Config
    private int maxNetworkSize = 256;

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
        recalculate(key);
    }

    public void removeNode(CustomBlockRegistry.LocationKey key) {
        nodes.remove(key);
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

    public void setMaxNetworkSize(int max) {
        this.maxNetworkSize = max;
    }

    /** Returns [supply, demand, blockCount] for the network containing this node, or null. */
    public int @Nullable [] getNetworkStats(CustomBlockRegistry.LocationKey key) {
        Integer netId = nodeNetworkId.get(key);
        if (netId == null) return null;
        NetworkState state = networks.get(netId);
        Set<CustomBlockRegistry.LocationKey> members = networkMembers.get(netId);
        if (state == null || members == null) return null;
        return new int[]{ state.supply(), state.demand(), members.size() };
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
                    doRecalculate(pending);
                }
            }
        } finally {
            recalculating = false;
        }
    }

    private void doRecalculate(CustomBlockRegistry.LocationKey changed) {
        // 1. Determine dirty set
        Set<CustomBlockRegistry.LocationKey> dirty = new HashSet<>();
        Integer oldNetId = nodeNetworkId.get(changed);
        if (oldNetId != null) {
            // Node was in an existing network — dirty = all members
            Set<CustomBlockRegistry.LocationKey> members = networkMembers.remove(oldNetId);
            if (members != null) {
                dirty.addAll(members);
                for (CustomBlockRegistry.LocationKey dk : members) nodeNetworkId.remove(dk);
            }
            networks.remove(oldNetId);
        } else {
            // Freshly added or already removed — dirty = this + adjacent networks
            dirty.add(changed);
            RotationNode node = nodes.get(changed);
            if (node != null) {
                for (CustomBlockRegistry.LocationKey neighbor : getConnections(node)) {
                    Integer nid = nodeNetworkId.get(neighbor);
                    if (nid != null && !dirty.containsAll(networkMembers.getOrDefault(nid, Set.of()))) {
                        Set<CustomBlockRegistry.LocationKey> nMembers = networkMembers.remove(nid);
                        if (nMembers != null) {
                            dirty.addAll(nMembers);
                            for (CustomBlockRegistry.LocationKey dk : nMembers) nodeNetworkId.remove(dk);
                        }
                        networks.remove(nid);
                    }
                }
            }
        }

        // Remove nodes that no longer exist
        dirty.removeIf(k -> !nodes.containsKey(k));

        // 2. BFS from each unassigned dirty node → new components
        for (CustomBlockRegistry.LocationKey start : dirty) {
            if (nodeNetworkId.containsKey(start)) continue;

            int netId = nextNetworkId++;
            Set<CustomBlockRegistry.LocationKey> members = new HashSet<>();
            int supply = 0, demand = 0;
            Queue<CustomBlockRegistry.LocationKey> queue = new ArrayDeque<>();
            queue.add(start);

            while (!queue.isEmpty() && members.size() < maxNetworkSize) {
                CustomBlockRegistry.LocationKey loc = queue.poll();
                if (nodeNetworkId.containsKey(loc)) continue;
                RotationNode node = nodes.get(loc);
                if (node == null) continue;

                nodeNetworkId.put(loc, netId);
                members.add(loc);

                if (node.role() == NodeRole.SOURCE) supply += node.powerUnits();
                if (node.role() == NodeRole.CONSUMER) demand += node.powerUnits();

                for (CustomBlockRegistry.LocationKey neighbor : getConnections(node)) {
                    if (!nodeNetworkId.containsKey(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }

            networkMembers.put(netId, members);
            NetworkState netState = new NetworkState(supply, demand);
            networks.put(netId, netState);

            // 3. Update block states for nodes whose powered state changed
            boolean powered = netState.powered();
            for (CustomBlockRegistry.LocationKey loc : members) {
                updateBlockState(loc, powered);
            }
        }
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

        // Remove nodes
        for (CustomBlockRegistry.LocationKey k : toRemove) {
            nodes.remove(k);
            nodeNetworkId.remove(k);
        }

        // Collect remaining dirty members of affected networks
        Set<CustomBlockRegistry.LocationKey> dirty = new HashSet<>();
        for (int nid : affectedNetworks) {
            Set<CustomBlockRegistry.LocationKey> members = networkMembers.remove(nid);
            if (members != null) {
                members.removeAll(toRemove);
                dirty.addAll(members);
                for (CustomBlockRegistry.LocationKey dk : members) nodeNetworkId.remove(dk);
            }
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

    List<CustomBlockRegistry.LocationKey> getConnections(RotationNode node) {
        if (isLocked(node)) return List.of();

        List<CustomBlockRegistry.LocationKey> result = new ArrayList<>(6);
        CustomBlockRegistry.LocationKey k = node.key();

        // Along-axis: 2 neighbors
        CustomBlockRegistry.LocationKey pos = axisNeighbor(k, node.axis(), +1);
        CustomBlockRegistry.LocationKey neg = axisNeighbor(k, node.axis(), -1);
        checkAxisNeighbor(pos, node.axis(), result);
        checkAxisNeighbor(neg, node.axis(), result);

        // Perpendicular: gear-like nodes connect to gear-like neighbors on different axes
        if (node.gearLike()) {
            for (CustomBlockRegistry.LocationKey perpKey : perpendicularNeighbors(k, node.axis())) {
                RotationNode other = nodes.get(perpKey);
                if (other != null && other.gearLike() && other.axis() != node.axis() && !isLocked(other)) {
                    result.add(perpKey);
                }
            }
        }

        return result;
    }

    private void checkAxisNeighbor(CustomBlockRegistry.LocationKey neighborKey, Axis requiredAxis, List<CustomBlockRegistry.LocationKey> result) {
        RotationNode other = nodes.get(neighborKey);
        if (other != null && other.axis() == requiredAxis && !isLocked(other)) {
            result.add(neighborKey);
        }
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

        // Extract axis from state name (e.g. "idle_x" → "x")
        int lastUnderscore = current.lastIndexOf('_');
        if (lastUnderscore < 0) return; // axis-less state (e.g. grindstone "idle") — handle separately
        String axis = current.substring(lastUnderscore + 1);

        String target = (powered ? "spinning_" : "idle_") + axis;
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

    private static List<CustomBlockRegistry.LocationKey> perpendicularNeighbors(CustomBlockRegistry.LocationKey k, Axis axis) {
        return switch (axis) {
            case X -> List.of(
                new CustomBlockRegistry.LocationKey(k.worldId(), k.x(), k.y() + 1, k.z()),
                new CustomBlockRegistry.LocationKey(k.worldId(), k.x(), k.y() - 1, k.z()),
                new CustomBlockRegistry.LocationKey(k.worldId(), k.x(), k.y(), k.z() + 1),
                new CustomBlockRegistry.LocationKey(k.worldId(), k.x(), k.y(), k.z() - 1));
            case Y -> List.of(
                new CustomBlockRegistry.LocationKey(k.worldId(), k.x() + 1, k.y(), k.z()),
                new CustomBlockRegistry.LocationKey(k.worldId(), k.x() - 1, k.y(), k.z()),
                new CustomBlockRegistry.LocationKey(k.worldId(), k.x(), k.y(), k.z() + 1),
                new CustomBlockRegistry.LocationKey(k.worldId(), k.x(), k.y(), k.z() - 1));
            case Z -> List.of(
                new CustomBlockRegistry.LocationKey(k.worldId(), k.x() + 1, k.y(), k.z()),
                new CustomBlockRegistry.LocationKey(k.worldId(), k.x() - 1, k.y(), k.z()),
                new CustomBlockRegistry.LocationKey(k.worldId(), k.x(), k.y() + 1, k.z()),
                new CustomBlockRegistry.LocationKey(k.worldId(), k.x(), k.y() - 1, k.z()));
        };
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
        if (i < 0) return Axis.Y;
        String suffix = state.substring(i + 1);
        return switch (suffix) {
            case "x" -> Axis.X;
            case "z" -> Axis.Z;
            default -> Axis.Y;
        };
    }
}
