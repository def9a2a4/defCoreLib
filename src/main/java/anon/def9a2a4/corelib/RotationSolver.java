package anon.def9a2a4.corelib;

import org.bukkit.block.BlockFace;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure rotation-power solver over an abstract integer-cell grid — no world, no PDC, no side
 * effects. Used by {@link MechanismRotationDriver} to power rotation parts riding an assembled
 * mechanism (where the real blocks are AIR and the world-keyed {@link RotationNetwork} can't see
 * them). Cells are mechanism-LOCAL, so the graph is invariant under the mechanism's motion.
 *
 * <p>Edge rules mirror {@link RotationNetwork#getConnections} minus its world-coupled parts
 * (reverser redstone, clutch locking, chain edges — all inert on a mechanism, where no world
 * redstone can reach a hologram cell):
 * <ul>
 *   <li>Along-axis: the ±1 neighbors along a node's axis, when they share that axis. Preserves
 *       spin direction.</li>
 *   <li>Gear mesh: a gearLike node connects to any adjacent gearLike node on all 6 faces
 *       (skipping pairs already connected along-axis — load-bearing for direction). Same-axis
 *       mesh reverses; cross-axis (bevel) reverses per {@link RotationNetwork#bevelReverses}.</li>
 *   <li>Omni consumer: a single leaf edge to the first aligned non-omni neighbor in
 *       {@link Faces#CARDINAL} order, skipping its excluded (mounted) face. Never reverses.</li>
 * </ul>
 *
 * <p>Direction: BFS seeds each component CW, flips across reversing edges, and jams the component
 * on a contradiction (odd gear loop). A post-pass re-anchors the component to its sources'
 * direction preferences (lowest-cell source wins, deterministically); a second source whose
 * preference still disagrees also jams. Jammed ⇒ unpowered.
 *
 * <p>TODO(later): migrate the static {@link RotationNetwork#doRecalculate} onto this solver so the
 * direction/jam logic lives in one place. Not done in the same change that introduced this class —
 * the static network is shipped and battle-tested.
 */
final class RotationSolver {

    private RotationSolver() {}

    /** A node on the abstract grid. {@code dirPref} is non-null only for sources with a captured
     *  spin preference; {@code supply}/{@code demand} are the node's power contribution. */
    record Node(int x, int y, int z, RotationNetwork.Axis axis,
                int supply, int demand, boolean gearLike, boolean gearbox,
                boolean omni, @Nullable BlockFace omniExcludedFace,
                RotationNetwork.@Nullable SpinDirection dirPref) {

        long cellKey() { return pack(x, y, z); }
    }

    /** Per-node solve output, parallel to the input list. {@code surplus} is the node's component
     *  surplus, {@code max(0, supply − demand)} — machines scale work with it (processing batch
     *  size, fan push), mirroring the static network's {@code getNetworkStats} semantics. */
    record Result(int[] component, boolean[] powered,
                  RotationNetwork.SpinDirection[] direction, boolean[] jammed, int[] surplus) {}

    // Package-visible: MechanismRotationDriver keys its local-cell → block-index map the same way.
    static long pack(int x, int y, int z) {
        // 21 bits per coordinate, offset-shifted — mechanism-local offsets are tiny (|v| ≤ ~256).
        return ((x & 0x1FFFFFL) << 42) | ((y & 0x1FFFFFL) << 21) | (z & 0x1FFFFFL);
    }

    static Result solve(List<Node> nodes) {
        int n = nodes.size();
        int[] component = new int[n];
        boolean[] powered = new boolean[n];
        var direction = new RotationNetwork.SpinDirection[n];
        boolean[] jammed = new boolean[n];
        int[] surplus = new int[n];
        java.util.Arrays.fill(component, -1);

        Map<Long, Integer> byCell = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) byCell.put(nodes.get(i).cellKey(), i);

        int nextComponent = 0;
        for (int root = 0; root < n; root++) {
            if (component[root] != -1) continue;
            int comp = nextComponent++;

            // Pass A: membership across ALL edges (gearboxes included) — pools supply/demand.
            List<Integer> members = new ArrayList<>();
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            component[root] = comp;
            queue.add(root);
            while (!queue.isEmpty()) {
                int cur = queue.poll();
                members.add(cur);
                for (Edge e : edges(nodes, byCell, cur)) {
                    if (component[e.to()] == -1) {
                        component[e.to()] = comp;
                        queue.add(e.to());
                    }
                }
            }

            // Pass B: spin direction per gearbox-free DOMAIN. A gearbox is a direction firewall — it
            // carries power but couples no CW/CCW across itself, so two sources on perpendicular axes
            // feeding one gearbox never false-jam. Each domain is seeded from its lowest-cell member so
            // a powered-but-sourceless domain (fed only through a gearbox) resolves to a stable direction.
            boolean compJammed = false;
            List<Integer> sorted = new ArrayList<>(members);
            sorted.sort(Comparator.comparingInt((Integer i) -> nodes.get(i).x())
                .thenComparingInt(i -> nodes.get(i).y())
                .thenComparingInt(i -> nodes.get(i).z()));
            for (int domainRoot : sorted) {
                if (direction[domainRoot] != null) continue;

                List<Integer> domain = new ArrayList<>();
                boolean domainJam = false;
                ArrayDeque<Integer> dq = new ArrayDeque<>();
                direction[domainRoot] = RotationNetwork.SpinDirection.CW;
                dq.add(domainRoot);
                domain.add(domainRoot);
                while (!dq.isEmpty()) {
                    int cur = dq.poll();
                    boolean curGear = nodes.get(cur).gearbox();
                    for (Edge e : edges(nodes, byCell, cur)) {
                        int other = e.to();
                        if (component[other] != comp) continue;
                        if (curGear || nodes.get(other).gearbox()) continue;   // firewall
                        RotationNetwork.SpinDirection want =
                            e.reverses() ? direction[cur].reversed() : direction[cur];
                        if (direction[other] == null) {
                            direction[other] = want;
                            dq.add(other);
                            domain.add(other);
                        } else if (direction[other] != want) {
                            domainJam = true;   // in-domain contradiction (odd reversal cycle)
                        }
                    }
                }

                // Anchor this domain to its source preferences: lowest-cell preferring source wins; any
                // other preferring source in the SAME domain that still disagrees jams the component.
                List<Integer> prefSources = domain.stream()
                    .filter(i -> nodes.get(i).dirPref() != null)
                    .sorted(Comparator.comparingInt((Integer i) -> nodes.get(i).x())
                        .thenComparingInt(i -> nodes.get(i).y())
                        .thenComparingInt(i -> nodes.get(i).z()))
                    .toList();
                if (!prefSources.isEmpty()) {
                    int anchor = prefSources.get(0);
                    if (direction[anchor] != nodes.get(anchor).dirPref()) {
                        for (int i : domain) direction[i] = direction[i].reversed();
                    }
                    for (int j = 1; j < prefSources.size(); j++) {
                        int s = prefSources.get(j);
                        if (direction[s] != nodes.get(s).dirPref()) domainJam = true;
                    }
                }
                compJammed |= domainJam;
            }

            int supply = 0, demand = 0;
            for (int i : members) {
                supply += nodes.get(i).supply();
                demand += nodes.get(i).demand();
            }
            boolean compPowered = !compJammed && supply > 0 && supply >= demand;
            int compSurplus = Math.max(0, supply - demand);
            for (int i : members) {
                powered[i] = compPowered;
                jammed[i] = compJammed;
                surplus[i] = compSurplus;
            }
        }
        return new Result(component, powered, direction, jammed, surplus);
    }

    private record Edge(int to, boolean reverses) {}

    private static List<Edge> edges(List<Node> nodes, Map<Long, Integer> byCell, int idx) {
        Node node = nodes.get(idx);
        List<Edge> result = new ArrayList<>(6);

        // Omni consumer: one leaf edge to its chosen aligned neighbor; nothing else.
        if (node.omni()) {
            Integer attach = omniAttach(nodes, byCell, node);
            if (attach != null) result.add(new Edge(attach, false));
            return result;
        }

        // Gearbox: omnidirectional hub — mirrors RotationNetwork.getConnections. Couples (reverses=false)
        // on each face to another gearbox or a node aligned with that face; emits an omni back-edge only
        // if that omni chose us. Early-return before along-axis/gearLike (nominal axis is meaningless).
        if (node.gearbox()) {
            for (BlockFace face : Faces.CARDINAL) {
                Integer oi = neighborAt(byCell, node, face);
                if (oi == null) continue;
                Node other = nodes.get(oi);
                if (other.omni()) {
                    Integer chosen = omniAttach(nodes, byCell, other);
                    if (chosen != null && chosen == idx) result.add(new Edge(oi, false));
                } else if (other.gearbox() || other.axis() == RotationNetwork.axisFromFace(face)) {
                    result.add(new Edge(oi, false));
                }
            }
            return result;
        }

        // Along-axis (checked first — load-bearing so same-axis gears along their shared axis
        // connect shaft-like, not as a reversing mesh). An omni neighbor can only ever choose a
        // node whose axis runs along their connecting face — i.e. it always sits on that node's
        // ±axis — so the mutual omni back-edge is fully handled here.
        for (int sign : new int[]{+1, -1}) {
            Integer oi = neighborAlong(byCell, node, node.axis(), sign);
            if (oi == null) continue;
            Node other = nodes.get(oi);
            if (other.omni()) {
                // Mutual single edge: connect back only if the omni node actually chose us.
                Integer chosen = omniAttach(nodes, byCell, other);
                if (chosen != null && chosen == idx) result.add(new Edge(oi, false));
            } else if (other.gearbox()) {
                // A gearbox on our axis couples back (its own face loop emits the matching edge).
                result.add(new Edge(oi, false));
            } else if (other.axis() == node.axis()) {
                result.add(new Edge(oi, false));
            }
        }

        // Gear-to-gear on all 6 faces (skip already-connected along-axis pairs).
        if (node.gearLike()) {
            for (BlockFace face : Faces.CARDINAL) {
                Integer oi = neighborAt(byCell, node, face);
                if (oi == null) continue;
                final int oiv = oi;
                if (result.stream().anyMatch(e -> e.to() == oiv)) continue;
                Node other = nodes.get(oi);
                if (!other.omni() && other.gearLike()) {
                    boolean sameAxis = other.axis() == node.axis();
                    boolean reverses = sameAxis
                        || RotationNetwork.bevelReverses(node.axis(), other.axis(), face);
                    result.add(new Edge(oi, reverses));
                }
            }
        }
        return result;
    }

    /** The single node an omni consumer draws from: first aligned non-omni neighbor in
     *  {@link Faces#CARDINAL} order, skipping the excluded (mounted) face. Mirrors
     *  {@code RotationNetwork.omniAttachKey}. */
    private static @Nullable Integer omniAttach(List<Node> nodes, Map<Long, Integer> byCell, Node node) {
        for (BlockFace face : Faces.CARDINAL) {
            if (face == node.omniExcludedFace()) continue;
            Integer oi = neighborAt(byCell, node, face);
            if (oi == null) continue;
            Node other = nodes.get(oi);
            // A gearbox attaches on any face (nominal axis is always Y); mirror RotationNetwork.omniAttachKey.
            if (!other.omni() && (other.gearbox() || other.axis() == RotationNetwork.axisFromFace(face))) return oi;
        }
        return null;
    }

    private static @Nullable Integer neighborAlong(Map<Long, Integer> byCell, Node node,
                                                   RotationNetwork.Axis axis, int sign) {
        return switch (axis) {
            case X -> byCell.get(pack(node.x() + sign, node.y(), node.z()));
            case Y -> byCell.get(pack(node.x(), node.y() + sign, node.z()));
            case Z -> byCell.get(pack(node.x(), node.y(), node.z() + sign));
        };
    }

    private static @Nullable Integer neighborAt(Map<Long, Integer> byCell, Node node, BlockFace face) {
        return byCell.get(pack(node.x() + face.getModX(), node.y() + face.getModY(),
            node.z() + face.getModZ()));
    }
}
