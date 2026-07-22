package anon.def9a2a4.corelib;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Rail;
import org.bukkit.util.Vector;

/**
 * Arc-length rail walker — the heart of drive-model B (see the betterminecarts plan). Given a
 * {@link RailState} (which rail block a cart is on, which face it entered from, and how far along that
 * rail's path it sits), {@link #advance} walks the connected rail network by arc length, block to
 * block, sampling each rail's centre path (straight line / ascending ramp / quarter-arc curve). Motion
 * is sub-stepped internally so a fast cart never skips a rail block or clips a corner.
 *
 * <p>This lets a cart be <b>position-driven</b>: instead of feeding velocity into vanilla physics and
 * being clamped to 0.4 b/t, we compute the exact point on the track a given arc-distance ahead and
 * place the cart there. Junction branch selection is intentionally minimal here (continue straight
 * where possible); the powered-junction logic layers on later.
 */
final class RailPathWalker {

    /** Approx. cart rest height above a flat rail block's base (for teleport placement). */
    static final double RAIL_Y = 0.35d;

    private static final Material[] RAIL_TYPES = {
        Material.RAIL, Material.POWERED_RAIL, Material.DETECTOR_RAIL, Material.ACTIVATOR_RAIL
    };

    /** Where a cart is on the rail network and which way it's heading. */
    static final class RailState {
        Block rail;          // the rail block currently under the cart
        BlockFace fromFace;  // the connected face the cart entered through; travel is fromFace → toFace
        double t;            // arc parameter along this rail's path, 0 (at fromFace edge) .. 1 (toFace edge)

        RailState(Block rail, BlockFace fromFace, double t) {
            this.rail = rail;
            this.fromFace = fromFace;
            this.t = t;
        }

        RailState copy() { return new RailState(rail, fromFace, t); }
    }

    /** Result of a walk: the (possibly new) state, the world point to place the cart, and the unit
     *  travel heading there. {@code blocked} is true when the walk ran off the end of the track. */
    static final class Step {
        final RailState state;
        final Vector position;
        final Vector heading;
        final boolean blocked;

        Step(RailState state, Vector position, Vector heading, boolean blocked) {
            this.state = state;
            this.position = position;
            this.heading = heading;
            this.blocked = blocked;
        }
    }

    private RailPathWalker() {}

    /**
     * Reverse a state's travel direction in place-of-value (same physical point, opposite heading):
     * the new fromFace is the old toFace and t → 1−t. Used to place a follower <em>behind</em> the
     * leader — flip the leader's state, {@link #advance} forward by the arc gap, then negate the
     * resulting heading so the follower still faces the train's travel direction.
     */
    static RailState flip(RailState s) {
        Rail data = railData(s.rail);
        // Missing rail (block edit / chunk edge): still reverse t so the parameter is consistent; the
        // next advance() off a null rail returns blocked anyway, so fromFace can't be resolved here.
        if (data == null) return new RailState(s.rail, s.fromFace, 1.0 - s.t);
        BlockFace to = toFace(data.getShape(), s.fromFace);
        return new RailState(s.rail, to, 1.0 - s.t);
    }

    static boolean isRail(Material m) {
        for (Material r : RAIL_TYPES) if (m == r) return true;
        return false;
    }

    static Rail railData(Block b) {
        return b != null && b.getBlockData() instanceof Rail rail ? rail : null;
    }

    /**
     * Build the initial state for a cart sitting on {@code rail} and travelling toward {@code heading}
     * (a horizontal-ish direction). Picks the connected face nearest to "behind" the heading as the
     * fromFace and places t at the block centre (0.5). Returns null if the block isn't a rail.
     */
    static RailState initOn(Block rail, Vector heading) {
        Rail data = railData(rail);
        if (data == null) return null;
        BlockFace[] faces = connectedFaces(data.getShape());
        // Choose fromFace = the connected face most opposite the heading (we came from behind).
        Vector h = heading.clone(); h.setY(0);
        if (h.lengthSquared() < 1e-6) h = new Vector(faceDir(faces[0]).getX(), 0, faceDir(faces[0]).getZ());
        h.normalize();
        BlockFace from = faces[0];
        double best = Double.POSITIVE_INFINITY;
        for (BlockFace f : faces) {
            Vector d = faceDir(f); // points outward from the block through face f
            double dot = d.getX() * h.getX() + d.getZ() * h.getZ(); // large positive = heading exits here
            if (dot < best) { best = dot; from = f; }
        }
        return new RailState(rail, from, 0.5);
    }

    /**
     * Walk {@code distance} blocks of arc from {@code start} (mutating a copy). Positive distance moves
     * fromFace → toFace. Sub-stepped at {@code subStep} granularity so curves/junctions aren't skipped.
     */
    static Step advance(RailState start, double distance, double subStep) {
        RailState s = start.copy();
        double remaining = distance;
        double step = Math.max(0.05, subStep);

        while (remaining > 1e-6) {
            Rail data = railData(s.rail);
            if (data == null) return finish(s, true);
            double segLen = segmentLength(data.getShape());
            double move = Math.min(step, remaining);
            double dt = move / segLen;

            if (s.t + dt < 1.0) {
                s.t += dt;
                remaining -= move;
                continue;
            }
            // Consume the rest of this rail's arc, then hop to the connected rail out of the exit face.
            remaining -= (1.0 - s.t) * segLen;
            BlockFace exit = toFace(data.getShape(), s.fromFace);
            RailState next = hop(s, exit);
            if (next == null) {
                s.t = 1.0;                 // park exactly at the exit edge
                return finish(s, true);
            }
            s = next;                      // enters the new rail at t = 0
        }
        return finish(s, false);
    }

    /** Package a step: world position at the current arc parameter + the local path tangent as heading. */
    private static Step finish(RailState s, boolean blocked) {
        return new Step(s, pathPoint(s, s.t), tangentAt(s), blocked);
    }

    /** Move to the neighbouring rail out of {@code exit}, trying same-level, then up (climb a slope),
     *  then down (descend). Returns the new state entering that rail, or null if none. */
    private static RailState hop(RailState s, BlockFace exit) {
        for (int dy : new int[]{0, 1, -1}) {
            Block cand = s.rail.getRelative(exit.getModX(), dy, exit.getModZ());
            Rail nd = railData(cand);
            if (nd == null) continue;
            BlockFace enter = exit.getOppositeFace();
            BlockFace[] faces = connectedFaces(nd.getShape());
            if (faces[0] != enter && faces[1] != enter) continue; // that rail doesn't connect back to us
            return new RailState(cand, enter, 0.0);
        }
        return null;
    }

    // ── Geometry ────────────────────────────────────────────────────────────

    /** The two horizontal faces a rail shape connects. */
    static BlockFace[] connectedFaces(Rail.Shape shape) {
        return switch (shape) {
            case NORTH_SOUTH, ASCENDING_NORTH, ASCENDING_SOUTH -> new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH};
            case EAST_WEST, ASCENDING_EAST, ASCENDING_WEST -> new BlockFace[]{BlockFace.EAST, BlockFace.WEST};
            case SOUTH_EAST -> new BlockFace[]{BlockFace.SOUTH, BlockFace.EAST};
            case SOUTH_WEST -> new BlockFace[]{BlockFace.SOUTH, BlockFace.WEST};
            case NORTH_WEST -> new BlockFace[]{BlockFace.NORTH, BlockFace.WEST};
            case NORTH_EAST -> new BlockFace[]{BlockFace.NORTH, BlockFace.EAST};
        };
    }

    static BlockFace toFace(Rail.Shape shape, BlockFace fromFace) {
        BlockFace[] f = connectedFaces(shape);
        return f[0] == fromFace ? f[1] : f[0];
    }

    static boolean isCurve(Rail.Shape shape) {
        return switch (shape) {
            case SOUTH_EAST, SOUTH_WEST, NORTH_WEST, NORTH_EAST -> true;
            default -> false;
        };
    }

    static boolean isAscending(Rail.Shape shape) {
        return switch (shape) {
            case ASCENDING_NORTH, ASCENDING_SOUTH, ASCENDING_EAST, ASCENDING_WEST -> true;
            default -> false;
        };
    }

    /** The face on the high (raised) end of an ascending rail, else null. */
    static BlockFace ascendingHighFace(Rail.Shape shape) {
        return switch (shape) {
            case ASCENDING_NORTH -> BlockFace.NORTH;
            case ASCENDING_SOUTH -> BlockFace.SOUTH;
            case ASCENDING_EAST -> BlockFace.EAST;
            case ASCENDING_WEST -> BlockFace.WEST;
            default -> null;
        };
    }

    /** Approximate arc length of a rail segment (edge to edge). */
    static double segmentLength(Rail.Shape shape) {
        if (isCurve(shape)) return Math.PI * 0.5 * 0.5;   // quarter arc, r=0.5 → ~0.785
        if (isAscending(shape)) return Math.sqrt(2.0);     // rises 1 over 1 → ~1.414
        return 1.0;                                        // straight
    }

    private static Vector faceDir(BlockFace f) {
        return new Vector(f.getModX(), f.getModY(), f.getModZ());
    }

    /** World Y of the rail surface at the given face (raised by 1 on an ascending rail's high face). */
    private static double railTopY(Block rail, Rail.Shape shape, BlockFace face) {
        double base = rail.getY() + RAIL_Y;
        if (isAscending(shape) && ascendingHighFace(shape) == face) return base + 1.0;
        return base;
    }

    /** World point at the midpoint of {@code face}'s edge on {@code rail}, at height {@code y}. */
    private static Vector edgePoint(Block rail, BlockFace face, double y) {
        double x = rail.getX() + 0.5 + face.getModX() * 0.5;
        double z = rail.getZ() + 0.5 + face.getModZ() * 0.5;
        return new Vector(x, y, z);
    }

    /** The world point on {@code state}'s rail path at parameter {@code t} (0 at fromFace, 1 at toFace). */
    private static Vector pathPoint(RailState state, double t) {
        Rail data = railData(state.rail);
        if (data == null) return new Vector(state.rail.getX() + 0.5, state.rail.getY() + RAIL_Y, state.rail.getZ() + 0.5);
        Rail.Shape shape = data.getShape();
        BlockFace from = state.fromFace;
        BlockFace to = toFace(shape, from);
        double yFrom = railTopY(state.rail, shape, from);
        double yTo = railTopY(state.rail, shape, to);
        double y = yFrom + (yTo - yFrom) * clamp01(t);

        if (isCurve(shape)) {
            return arcPoint(state.rail, from, to, clamp01(t), y);
        }
        // Straight / ascending: lerp between the two edge midpoints (through the block centre).
        Vector a = edgePoint(state.rail, from, y);
        Vector b = edgePoint(state.rail, to, y);
        return a.clone().multiply(1.0 - clamp01(t)).add(b.clone().multiply(clamp01(t)));
    }

    /** Quarter-arc point between two adjacent faces, centred on the shared block corner. */
    private static Vector arcPoint(Block rail, BlockFace from, BlockFace to, double t, double y) {
        // Corner shared by the two faces (in block-local 0..1 coords).
        double cx = (from.getModX() > 0 || to.getModX() > 0) ? 1.0 : 0.0;
        double cz = (from.getModZ() > 0 || to.getModZ() > 0) ? 1.0 : 0.0;
        Vector p0 = localEdge(from);
        Vector p1 = localEdge(to);
        double a0 = Math.atan2(p0.getZ() - cz, p0.getX() - cx);
        double a1 = Math.atan2(p1.getZ() - cz, p1.getX() - cx);
        // Take the short way (the two are 90° apart).
        double da = a1 - a0;
        while (da > Math.PI) da -= 2 * Math.PI;
        while (da < -Math.PI) da += 2 * Math.PI;
        double ang = a0 + da * t;
        double lx = cx + 0.5 * Math.cos(ang);
        double lz = cz + 0.5 * Math.sin(ang);
        return new Vector(rail.getX() + lx, y, rail.getZ() + lz);
    }

    /** Block-local edge midpoint (0..1) for a horizontal face. */
    private static Vector localEdge(BlockFace f) {
        return new Vector(0.5 + f.getModX() * 0.5, 0, 0.5 + f.getModZ() * 0.5);
    }

    /** Local unit travel tangent (horizontal) at the state's current arc parameter — the true heading
     *  along the rail, including the curving direction mid-arc (so a cart follows a curve smoothly
     *  rather than snapping to a cardinal face). Sampled by finite difference around t. */
    private static Vector tangentAt(RailState s) {
        Rail data = railData(s.rail);
        if (data == null) return new Vector(0, 0, 1);
        double e = 0.03;
        double t0 = Math.max(0.0, s.t - e);
        double t1 = Math.min(1.0, s.t + e);
        Vector d = pathPoint(s, t1).subtract(pathPoint(s, t0));
        d.setY(0);
        if (d.lengthSquared() < 1e-9) return faceDir(toFace(data.getShape(), s.fromFace)).clone();
        return d.normalize();
    }

    private static double clamp01(double v) { return v < 0 ? 0 : Math.min(1, v); }

    /** Convenience: the world Location for a step's position, facing its heading. */
    static Location toLocation(World world, Vector pos, Vector heading) {
        float yaw = (float) Math.toDegrees(Math.atan2(-heading.getX(), heading.getZ()));
        Location loc = new Location(world, pos.getX(), pos.getY(), pos.getZ());
        loc.setYaw(yaw);
        return loc;
    }
}
