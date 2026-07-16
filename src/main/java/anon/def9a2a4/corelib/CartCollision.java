package anon.def9a2a4.corelib;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.data.Rail;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import org.jspecify.annotations.Nullable;

/**
 * Terrain collision for assembled mechanism minecarts (Part B).
 *
 * <p>The lever (verified against the decompiled server): NMS clamps a minecart's per-tick horizontal
 * displacement to ±maxSpeed per axis at the move call, and our scheduled tick runs before entity
 * physics — so a fractional maxSpeed written each tick governs THIS tick's move exactly. B1 sets
 * {@code maxSpeed = gap − EPS} where {@code gap} is the continuous distance from the structure's
 * leading faces to the nearest wall; the brake IS the pin. B2 is a guarded re-snap backstop for the
 * unsigned-clamp ratchet / curve overshoot / griefed block-place.
 *
 * <p>Cross-mechanism collision (cart↔cart, piston↔mech) is a SEPARATE later round (B3) — this file is
 * pure terrain: the structure's own blocks are AIR while assembled, so a solid-world scan sees them
 * as clear (no self-whitelist needed).
 */
final class CartCollision {

    private CartCollision() {}

    static final double DEFAULT_MAX_SPEED   = 0.4;
    static final double EPS                 = 0.02;  // standoff between leading face and wall
    static final double FLUSH_SNAP          = 0.04;  // gap ≤ this → true hard stop + arm autoStopped
    static final double AT_REST_CAP         = 0.05;  // at-rest cap, only when a wall is near
    static final double AT_REST_WALL_NEAR   = 1.0;   // "near" threshold for the at-rest cap
    static final double RESUME_KICK         = 0.10;  // one-shot velocity when a docked path reopens
    static final double RESUME_GAP_OPEN     = 0.30;  // gap must reopen past this before kicking
    static final double RESUME_CLEAR_DIST   = 1.0;   // travel this far before disarming autoStopped
    static final double AT_REST_V           = 0.02;  // |horizontal v| below this = "at rest"
    static final int    LOOKAHEAD_CELLS     = 2;     // cells scanned ahead of each leading face
    static final double PENETRATION_TOL     = 0.02;  // B2 fires only past this overlap

    // ──────────────────────────────────────────────────────────────────────
    // B1 — the clamp
    // ──────────────────────────────────────────────────────────────────────

    /** Resolve the travel axis/sign from velocity (moving) or the stored heading (at rest). */
    static boolean resolveHeading(MechanismMinecartManager.MinecartState st) {
        Vector v = st.minecart.getVelocity();
        double vx = v.getX(), vz = v.getZ();
        boolean atRest = Math.hypot(vx, vz) < AT_REST_V;
        if (!atRest) {
            if (Math.abs(vx) >= Math.abs(vz)) { st.headingAxis = 0; st.headingSign = vx >= 0 ? 1 : -1; }
            else                              { st.headingAxis = 2; st.headingSign = vz >= 0 ? 1 : -1; }
        }
        if (st.headingSign == 0) st.headingSign = 1;   // never leave the leading face degenerate
        return atRest;
    }

    /**
     * The clamped maxSpeed for this tick. Stores {@code st.lastGap}; sets {@code st.autoStopped} on a
     * flush stop. Assumes {@link #resolveHeading} already ran this tick.
     */
    static double clampedMaxSpeed(BasicMechanism m, MechanismMinecartManager.MinecartState st, boolean atRest) {
        Minecart cart = st.minecart;
        int axis = st.headingAxis, s = st.headingSign;

        Rail.Shape shape = railShape(cart.getLocation().getBlock());
        boolean curve = !m.isNearCardinal() || isCurveShape(shape);
        boolean ascending = isAscending(shape, axis, s);

        double gap;
        if (!curve) {
            gap = axisGap(m, cart, axis, s, m.currentTransform(), ascending);
        } else {
            // On a curve both horizontal axes move; derive the transform from the current yaw directly
            // (currentTransform can be a tick stale across the split tick tasks). Scan both axes on the
            // real cells (no inflation in v1 — B2 catches corner overshoot), take the min.
            Matrix4f yawT = new Matrix4f().rotate((float) Math.toRadians(-m.getCurrentYaw()), 0, 1, 0);
            Vector v = cart.getVelocity();
            int sx = v.getX() >= 0 ? 1 : -1, sz = v.getZ() >= 0 ? 1 : -1;
            double gx = axisGap(m, cart, 0, sx, yawT, isAscending(shape, 0, sx));
            double gz = axisGap(m, cart, 2, sz, yawT, isAscending(shape, 2, sz));
            gap = Math.min(gx, gz);
        }

        st.lastGap = gap;
        double raw = gap - EPS;

        if (gap <= FLUSH_SNAP) {          // true hard stop (no sub-cm creep tail)
            st.autoStopped = true;
            return 0.0;
        }
        if (atRest && gap <= AT_REST_WALL_NEAR) {
            return clamp(Math.min(AT_REST_CAP, raw));
        }
        return clamp(raw);
    }

    /** Min over collision blocks of the free distance from the block's leading face to the nearest wall. */
    private static double axisGap(BasicMechanism m, Minecart cart, int axis, int s,
                                  Matrix4f transform, boolean ascending) {
        Location loc = cart.getLocation();
        double best = DEFAULT_MAX_SPEED;
        for (int i = 0; i < m.blockCount(); i++) {
            if (!m.hasCollision(i)) continue;
            Vector3f off = blockOffset(m, i, transform);
            double cAxis = (axis == 0 ? loc.getX() : loc.getZ()) + (axis == 0 ? off.x : off.z);
            int perp = (int) Math.floor((axis == 0 ? loc.getZ() + off.z : loc.getX() + off.x));
            int y    = (int) Math.floor(loc.getY() + off.y);
            double gi = columnGap(loc.getWorld(), axis, s, cAxis, perp, y);
            if (ascending) gi = Math.min(gi, columnGap(loc.getWorld(), axis, s, cAxis, perp, y + 1));
            if (gi < best) best = gi;
        }
        return best;
    }

    /** Free distance ahead of {@code cAxis}'s leading face along {@code axis}, at fixed perp/y cells. */
    private static double columnGap(org.bukkit.World world, int axis, int s, double cAxis, int perp, int y) {
        double f = cAxis + s * 0.5;
        double nf0 = s > 0 ? Math.ceil(f - 1e-6) : Math.floor(f + 1e-6);
        for (int k = 0; k <= LOOKAHEAD_CELLS; k++) {
            double nf = nf0 + s * k;
            int cell = s > 0 ? (int) nf : (int) nf - 1;
            int cx = axis == 0 ? cell : perp;
            int cz = axis == 0 ? perp : cell;
            if (!world.isChunkLoaded(cx >> 4, cz >> 4)) return Math.abs(nf - f);   // unloaded = wall
            if (clearForCart(world.getBlockAt(cx, y, cz))) continue;               // air/water/plant/rail
            return Math.abs(nf - f);                                               // collidable = wall
        }
        return DEFAULT_MAX_SPEED;
    }

    /**
     * A cell is clear for a cart if it has no collision box ({@link Block#isPassable()} — covers air,
     * water, lava, passable plants, torches, rails). A collidable fragile (leaves/cactus/bamboo) is a
     * wall here; {@link #breakFragilesAhead} clears the ones it was ALLOWED to break before the scan,
     * so a surviving collidable fragile correctly stops the cart. (Divergence from the piston/hoist
     * {@code isClear} = air/water/lava is deliberate: a cart glides past a torch/rail, a piston doesn't.)
     */
    private static boolean clearForCart(Block b) {
        return b.isPassable();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Fragile break — gated through BlockBreakEvent + protection plugins
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Once per tick, before the gap scan: break collidable fragiles the leading faces will cross this
     * tick, but ONLY through a {@link BlockBreakEvent} attributed to a rider (so claim protection can
     * veto). No rider → don't break (the cart stops at the vegetation instead of griefing).
     */
    static void breakFragilesAhead(BasicMechanism m, MechanismMinecartManager.MinecartState st, double vmag) {
        Minecart cart = st.minecart;
        Player breaker = firstPlayerPassenger(cart);
        if (breaker == null) return;   // nothing to attribute the break to → leave fragiles as walls
        Location loc = cart.getLocation();
        org.bukkit.World world = loc.getWorld();
        int axis = st.headingAxis, s = st.headingSign;
        Matrix4f transform = m.currentTransform();
        double reach = vmag + 0.1;
        for (int i = 0; i < m.blockCount(); i++) {
            if (!m.hasCollision(i)) continue;
            Vector3f off = blockOffset(m, i, transform);
            double cAxis = (axis == 0 ? loc.getX() : loc.getZ()) + (axis == 0 ? off.x : off.z);
            int perp = (int) Math.floor((axis == 0 ? loc.getZ() + off.z : loc.getX() + off.x));
            int y    = (int) Math.floor(loc.getY() + off.y);
            double f = cAxis + s * 0.5;
            double nf0 = s > 0 ? Math.ceil(f - 1e-6) : Math.floor(f + 1e-6);
            for (int k = 0; k <= LOOKAHEAD_CELLS; k++) {
                double nf = nf0 + s * k;
                if (Math.abs(nf - f) > reach) break;   // beyond this tick's reach
                int cell = s > 0 ? (int) nf : (int) nf - 1;
                int cx = axis == 0 ? cell : perp;
                int cz = axis == 0 ? perp : cell;
                if (!world.isChunkLoaded(cx >> 4, cz >> 4)) break;
                Block b = world.getBlockAt(cx, y, cz);
                if (b.isPassable()) continue;                     // clear / passable plant → not a target
                if (!FragileBlocks.isFragile(b.getType())) break; // solid non-fragile wall
                if (!tryBreak(b, breaker)) break;                 // denied (protected) → it's a wall
                // broke it → keep scanning past
            }
        }
    }

    private static boolean tryBreak(Block b, Player breaker) {
        BlockBreakEvent ev = new BlockBreakEvent(b, breaker);
        Bukkit.getPluginManager().callEvent(ev);
        if (ev.isCancelled()) return false;
        b.breakNaturally();
        return true;
    }

    private static @Nullable Player firstPlayerPassenger(Minecart cart) {
        for (Entity e : cart.getPassengers()) if (e instanceof Player p) return p;
        return null;
    }

    // ──────────────────────────────────────────────────────────────────────
    // B2 — guarded re-snap backstop
    // ──────────────────────────────────────────────────────────────────────

    /**
     * If a collision block has penetrated a solid world cell past {@link #PENETRATION_TOL}, teleport
     * the cart back out along the heading axis (along the rail tangent on a slope, so it doesn't
     * derail). Vehicle-only teleport — {@code updateFromVehicle} absorbs it as one normal delta next
     * tick (minecarts never call {@code move()}, so no double-count). Never fires in routine operation.
     */
    static void resnapIfPenetrating(BasicMechanism m, MechanismMinecartManager.MinecartState st) {
        Minecart cart = st.minecart;
        int axis = st.headingAxis, s = st.headingSign;
        if (s == 0) return;
        Location loc = cart.getLocation();
        org.bukkit.World world = loc.getWorld();
        Matrix4f transform = m.currentTransform();
        double maxPen = 0;
        for (int i = 0; i < m.blockCount(); i++) {
            if (!m.hasCollision(i)) continue;
            Vector3f off = blockOffset(m, i, transform);
            double cAxis = (axis == 0 ? loc.getX() : loc.getZ()) + (axis == 0 ? off.x : off.z);
            int perp = (int) Math.floor((axis == 0 ? loc.getZ() + off.z : loc.getX() + off.x));
            int y    = (int) Math.floor(loc.getY() + off.y);
            double f = cAxis + s * 0.5;
            int leadCell = (int) Math.floor(f + (s > 0 ? -1e-6 : 1e-6));
            int cx = axis == 0 ? leadCell : perp;
            int cz = axis == 0 ? perp : leadCell;
            if (!world.isChunkLoaded(cx >> 4, cz >> 4)) continue;
            Block b = world.getBlockAt(cx, y, cz);
            if (clearForCart(b)) continue;
            double pen = s > 0 ? f - leadCell : leadCell + 1 - f;
            if (pen > maxPen) maxPen = pen;
        }
        if (maxPen <= PENETRATION_TOL) return;

        double back = Math.min(maxPen, DEFAULT_MAX_SPEED + EPS);
        Location tp = loc.clone();
        if (axis == 0) tp.setX(tp.getX() - s * back); else tp.setZ(tp.getZ() - s * back);
        if (isAscending(railShape(loc.getBlock()), axis, s)) tp.setY(tp.getY() - back);  // rail is 45° → 1:1
        cart.teleport(tp);
        Vector vel = cart.getVelocity();
        if (axis == 0) vel.setX(0); else vel.setZ(0);
        cart.setVelocity(vel);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Shared geometry / rail helpers
    // ──────────────────────────────────────────────────────────────────────

    /** Continuous world offset of block {@code i} from the anchor: {@code transform · localOffset}. */
    private static Vector3f blockOffset(BasicMechanism m, int i, Matrix4f transform) {
        Vector3f local = m.getBlock(i).localTransform.getTranslation(new Vector3f());
        return transform.transformPosition(local, new Vector3f());
    }

    private static Rail.@Nullable Shape railShape(Block b) {
        return b.getBlockData() instanceof Rail rail ? rail.getShape() : null;
    }

    private static boolean isCurveShape(Rail.@Nullable Shape shape) {
        return shape == Rail.Shape.NORTH_EAST || shape == Rail.Shape.NORTH_WEST
            || shape == Rail.Shape.SOUTH_EAST || shape == Rail.Shape.SOUTH_WEST;
    }

    /** Whether the rail here ascends in the travel direction (axis 0=X/2=Z, sign ±1). */
    private static boolean isAscending(Rail.@Nullable Shape shape, int axis, int s) {
        if (shape == null) return false;
        return switch (shape) {
            case ASCENDING_EAST  -> axis == 0 && s == 1;
            case ASCENDING_WEST  -> axis == 0 && s == -1;
            case ASCENDING_SOUTH -> axis == 2 && s == 1;
            case ASCENDING_NORTH -> axis == 2 && s == -1;
            default -> false;
        };
    }

    private static double clamp(double x) {
        return Math.max(0, Math.min(DEFAULT_MAX_SPEED, x));
    }
}
