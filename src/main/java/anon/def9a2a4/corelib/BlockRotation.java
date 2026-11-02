package anon.def9a2a4.corelib;

import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.Hangable;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Bell;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.PointedDripstone;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.TrapDoor;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Rotates BlockData properties for a landing mechanism.
 *
 * <p>Two entry points. {@link #rotateBlockData(BlockData, float)} is the yaw-only rotation every Y-axis mover
 * (ships, doors, carts, hoists, pistons, floor/ceiling rotators) uses — Directional, Orientable, Rotatable and
 * MultipleFacing. {@link #rotateBlockData(BlockData, Turn)} takes the TRUE landing rotation as a {@link Turn}
 * (a signed permutation of the six faces), so an X/Z drawbridge that lands on its side re-orients its blocks
 * the same way it moved their positions. A yaw-only Turn delegates to the yaw code unchanged.
 */
public final class BlockRotation {

    private BlockRotation() {}

    public static BlockData rotateBlockData(BlockData originalData, float yawDegrees) {
        BlockData rotated = originalData.clone();

        int rotationSteps = Math.round(yawDegrees / 90.0f) % 4;
        if (rotationSteps < 0) rotationSteps += 4;
        if (rotationSteps == 0) return rotated;

        if (rotated instanceof org.bukkit.block.data.Directional directional) {
            BlockFace newFacing = rotateBlockFace(directional.getFacing(), yawDegrees);
            if (directional.getFaces().contains(newFacing)) {
                directional.setFacing(newFacing);
            }
        }

        if (rotated instanceof org.bukkit.block.data.Orientable orientable) {
            org.bukkit.Axis currentAxis = orientable.getAxis();
            if (currentAxis != org.bukkit.Axis.Y && rotationSteps % 2 == 1) {
                orientable.setAxis(currentAxis == org.bukkit.Axis.X ? org.bukkit.Axis.Z : org.bukkit.Axis.X);
            }
        }

        if (rotated instanceof org.bukkit.block.data.Rotatable rotatable) {
            int currentStep = rotationToStep(rotatable.getRotation());
            int newStep = (currentStep + (rotationSteps * 4)) % 16;
            rotatable.setRotation(stepToRotation(newStep));
        }

        if (rotated instanceof org.bukkit.block.data.MultipleFacing mf) {
            Set<BlockFace> originalFaces = new HashSet<>(mf.getFaces());
            for (BlockFace face : originalFaces) mf.setFace(face, false);
            for (BlockFace face : originalFaces) {
                BlockFace newFace = rotateBlockFace(face, yawDegrees);
                if (mf.getAllowedFaces().contains(newFace)) mf.setFace(newFace, true);
            }
        }

        return rotated;
    }

    /** State-name dynamic prefixes that precede an orientation token (e.g. {@code spinning_x}). */
    private static final Set<String> DYNAMIC_PREFIXES = Set.of("idle", "spinning", "running", "locked");

    /**
     * Derive the custom-block state a block should have after its vanilla data was rotated to {@code landed}.
     * Custom state encodes orientation (axis suffix, wall variant); on disassembly the vanilla data is
     * rotated but the captured state is not, producing impossible states. This reverses the placement
     * mapping: the landed attachment face → {@code placementStateMap} entry, with the captured dynamic
     * prefix (idle/spinning/running/locked) grafted back on. Returns {@code state} unchanged when no
     * orientation mapping applies (no map, custom resolver, or unmapped face).
     */
    static String rotateCustomState(CustomHeadBlock type, String state, BlockData landed) {
        if (state == null || type.placementStateMap() == null || type.stateResolver() != null) return state;
        // Non-Directional heads (floor/ceiling PLAYER_HEAD) carry no facing in their BlockData, so
        // attachmentFace can't tell UP from DOWN and would collapse a ceiling drill (idle_ceiling, mines
        // down) to its floor state (idle_y, mines up). The captured vertical state is already correct
        // under any move — Y-rotation and translation never flip up/down — so keep it as-is.
        if (!(landed instanceof org.bukkit.block.data.Directional)) return state;
        BlockFace key = attachmentFace(landed);
        String mapped = type.placementStateMap().get(key);
        if (mapped == null) return state;

        String[] cap = splitDynamicState(state);
        String[] map = splitDynamicState(mapped);
        String newState = cap[0] != null ? cap[0] + "_" + map[1] : mapped;

        if (type.states().containsKey(newState)) return newState;
        if (type.states().containsKey(mapped)) return mapped;
        return state;
    }

    /**
     * Rotate a rotation-power spin token ({@code "cw"}/{@code "ccw"}) so it names the SAME physical spin
     * after the block's axle was yaw-rotated on landing. The token means "cw = positive rotation about the
     * block's unsigned spin {@code axis}"; a landing yaw that maps that axis's positive cardinal onto a
     * NEGATIVE cardinal (−X/−Z) inverts the sense, so the token must flip. A Y axle (its positive cardinal
     * is UP, which yaw never moves) and a 0° yaw never flip. The axis image is derived from the SAME
     * {@link #rotateBlockFace} convention {@link #rotateBlockData}/{@link #rotateCustomState} use — so a 90°
     * vs 270° landing can't silently invert (a 180°-only check would miss it). Returns {@code token}
     * unchanged when it is not cw/ccw.
     */
    static String rotateSpinDir(RotationNetwork.Axis axis, float yawDegrees, String token) {
        return rotateSpinDir(axis, Turn.yaw(yawDegrees), token);
    }

    /** {@link #rotateSpinDir(RotationNetwork.Axis, float, String)} for any landing {@link Turn}: the token flips
     *  iff the turn maps the axle's positive cardinal onto a negative one (−X/−Y/−Z). Under a drawbridge turn a
     *  Y axle CAN be negated (UP → DOWN at 180°), and an X/Z axle can become vertical. */
    static String rotateSpinDir(RotationNetwork.Axis axis, Turn turn, String token) {
        if (!turn.negates(axis)) return token;
        return switch (token) {
            case "cw" -> "ccw";
            case "ccw" -> "cw";
            default -> token;
        };
    }

    /**
     * Preserve a direction-carrying custom state's cw/ccw token across landing. {@link #rotateCustomState}
     * rebuilds the orientation from {@code placementStateMap}, whose values hard-code one direction — so a
     * wall ratchet's cw/ccw setting is lost (a CCW ratchet lands CW) and is never axis-flipped. Re-inject the
     * CAPTURED direction into {@code landedState}, flipped iff the landing yaw negated the captured spin axis
     * (same rule as {@link #rotateSpinDir} for a source's PDC spin_dir). No-op when either state carries no
     * cw/ccw token (i.e. every block but the ratchet), so it is safe to call for any landed custom state.
     */
    static String preserveSpinToken(@Nullable String capturedState, @Nullable String landedState,
                                    float yawDegrees) {
        return preserveSpinToken(capturedState, landedState, Turn.yaw(yawDegrees));
    }

    /** {@link #preserveSpinToken(String, String, float)} for any landing {@link Turn}. */
    static String preserveSpinToken(@Nullable String capturedState, @Nullable String landedState, Turn turn) {
        // A stateless custom head captures customState == null → rotateCustomState returns null → both
        // states null here. Mirror rotateCustomState's null tolerance rather than throwing mid-landing.
        if (capturedState == null || landedState == null) return landedState;
        String capDir = spinToken(capturedState);
        String landedDir = spinToken(landedState);
        if (capDir == null || landedDir == null) return landedState;
        String want = rotateSpinDir(RotationNetwork.axisFromState(capturedState), turn, capDir);
        if (want.equals(landedDir)) return landedState;
        String[] parts = landedState.split("_");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals(landedDir)) { parts[i] = want; break; }
        }
        return String.join("_", parts);
    }

    /** The cw/ccw token in a custom state ({@code idle_cw_x} → "cw"), or null if it carries none. */
    private static @Nullable String spinToken(@Nullable String state) {
        if (state == null) return null;
        for (String tok : state.split("_")) {
            if (tok.equals("cw") || tok.equals("ccw")) return tok;
        }
        return null;
    }

    /** The face a placed head is mounted on — mirrors CoreLibPlugin.getAttachmentFace. */
    private static BlockFace attachmentFace(BlockData data) {
        if (data instanceof org.bukkit.block.data.Directional dir) return dir.getFacing().getOppositeFace();
        return BlockFace.DOWN; // floor head sits on the block below
    }

    /** Split a state into [dynamicPrefix, orientationToken]; dynamicPrefix is null when there is none. */
    private static String[] splitDynamicState(String state) {
        int i = state.indexOf('_');
        if (i > 0 && DYNAMIC_PREFIXES.contains(state.substring(0, i))) {
            return new String[]{state.substring(0, i), state.substring(i + 1)};
        }
        return new String[]{null, state};
    }

    /** Package-private: also used by {@link BannerManager#placeLandedBanners} to rotate a landed
     *  wall-banner face. UP/DOWN pass through unchanged; negative yaw self-normalizes. */
    static BlockFace rotateBlockFace(BlockFace face, float yawDegrees) {
        if (face == BlockFace.UP || face == BlockFace.DOWN) return face;
        float baseYaw = blockFaceToYaw(face);
        float newYaw = (baseYaw + yawDegrees) % 360;
        if (newYaw < 0) newYaw += 360;
        return yawToBlockFace(newYaw);
    }

    private static float blockFaceToYaw(BlockFace face) {
        return switch (face) {
            case SOUTH -> 0.0f;
            case WEST -> 90.0f;
            case NORTH -> 180.0f;
            case EAST -> 270.0f;
            default -> 0.0f;
        };
    }

    private static BlockFace yawToBlockFace(float yaw) {
        int rounded = Math.round(yaw / 90.0f) * 90 % 360;
        return switch (rounded) {
            case 0 -> BlockFace.SOUTH;
            case 90 -> BlockFace.WEST;
            case 180 -> BlockFace.NORTH;
            default -> BlockFace.EAST;
        };
    }

    // Package-visible: also drives the standing-banner and floor-head yaw tables in MechanismRegistry.
    static int rotationToStep(BlockFace face) {
        return switch (face) {
            case SOUTH -> 0; case SOUTH_SOUTH_WEST -> 1; case SOUTH_WEST -> 2; case WEST_SOUTH_WEST -> 3;
            case WEST -> 4; case WEST_NORTH_WEST -> 5; case NORTH_WEST -> 6; case NORTH_NORTH_WEST -> 7;
            case NORTH -> 8; case NORTH_NORTH_EAST -> 9; case NORTH_EAST -> 10; case EAST_NORTH_EAST -> 11;
            case EAST -> 12; case EAST_SOUTH_EAST -> 13; case SOUTH_EAST -> 14; case SOUTH_SOUTH_EAST -> 15;
            default -> 0;
        };
    }

    private static BlockFace stepToRotation(int step) {
        step = ((step % 16) + 16) % 16;
        return switch (step) {
            case 0 -> BlockFace.SOUTH; case 1 -> BlockFace.SOUTH_SOUTH_WEST;
            case 2 -> BlockFace.SOUTH_WEST; case 3 -> BlockFace.WEST_SOUTH_WEST;
            case 4 -> BlockFace.WEST; case 5 -> BlockFace.WEST_NORTH_WEST;
            case 6 -> BlockFace.NORTH_WEST; case 7 -> BlockFace.NORTH_NORTH_WEST;
            case 8 -> BlockFace.NORTH; case 9 -> BlockFace.NORTH_NORTH_EAST;
            case 10 -> BlockFace.NORTH_EAST; case 11 -> BlockFace.EAST_NORTH_EAST;
            case 12 -> BlockFace.EAST; case 13 -> BlockFace.EAST_SOUTH_EAST;
            case 14 -> BlockFace.SOUTH_EAST; case 15 -> BlockFace.SOUTH_SOUTH_EAST;
            default -> BlockFace.SOUTH;
        };
    }

    // ──────────────────────────────────────────────────────────────────────
    // Off-yaw landing: the true rotation of an X/Z drawbridge (a Turn)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * A 90°-multiple landing rotation as a signed permutation of the six cardinal faces — the same rotation
     * {@link BasicMechanism#landingRotation()} applies to block positions, so orientation and position can't
     * disagree. A Y-axis landing is a pure yaw ({@link #isYawOnly()}); an X/Z drawbridge at 90/180/270° moves UP.
     * Immutable value type.
     */
    public static final class Turn {
        private static final BlockFace[] FACES = {
            BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN};
        private static final float EPS = 1e-3f;

        public static final Turn IDENTITY = new Turn(FACES.clone());

        /** Image of FACES[i]. */
        private final BlockFace[] images;

        private Turn(BlockFace[] images) { this.images = images; }

        private static int index(BlockFace f) {
            return switch (f) {
                case NORTH -> 0; case EAST -> 1; case SOUTH -> 2; case WEST -> 3; case UP -> 4; case DOWN -> 5;
                default -> -1;
            };
        }

        /** The face a rotation matrix maps each cardinal onto. Throws if {@code m} is not a quarter-turn
         *  rotation (a signed permutation of the axes, within trig epsilon). */
        public static Turn of(Matrix4fc m) {
            BlockFace[] img = new BlockFace[6];
            boolean[] used = new boolean[6];
            for (int i = 0; i < 6; i++) {
                BlockFace f = FACES[i];
                Vector3f v = m.transformDirection(new Vector3f(f.getModX(), f.getModY(), f.getModZ()), new Vector3f());
                int x = Math.round(v.x), y = Math.round(v.y), z = Math.round(v.z);
                if (Math.abs(v.x - x) > EPS || Math.abs(v.y - y) > EPS || Math.abs(v.z - z) > EPS
                        || Math.abs(x) + Math.abs(y) + Math.abs(z) != 1) {
                    throw new IllegalArgumentException("not a quarter-turn rotation: " + f + " -> " + v);
                }
                int gi = -1;
                for (int j = 0; j < 6; j++) {
                    BlockFace g = FACES[j];
                    if (g.getModX() == x && g.getModY() == y && g.getModZ() == z) { gi = j; break; }
                }
                if (used[gi]) throw new IllegalArgumentException("not a permutation: two faces map to " + FACES[gi]);
                used[gi] = true;
                img[i] = FACES[gi];
            }
            return new Turn(img);
        }

        /** The yaw-only turn {@link #rotateBlockFace} describes (UP/DOWN fixed). */
        public static Turn yaw(float yawDegrees) {
            BlockFace[] img = new BlockFace[6];
            for (int i = 0; i < 6; i++) img[i] = rotateBlockFace(FACES[i], yawDegrees);
            return new Turn(img);
        }

        /** The image of a cardinal face; SELF maps to itself. Non-cardinal faces throw. */
        public BlockFace apply(BlockFace face) {
            int i = index(face);
            if (i < 0) {
                if (face == BlockFace.SELF) return face;
                throw new IllegalArgumentException("not a cardinal face: " + face);
            }
            return images[i];
        }

        public org.bukkit.Axis apply(org.bukkit.Axis axis) {
            BlockFace pos = switch (axis) {
                case X -> BlockFace.EAST;
                case Y -> BlockFace.UP;
                case Z -> BlockFace.SOUTH;
            };
            return axisOf(apply(pos));
        }

        /** The face the positive end of a rotation-network axle points at after the turn. */
        public BlockFace image(RotationNetwork.Axis axis) {
            return apply(switch (axis) {
                case X -> BlockFace.EAST;
                case Y -> BlockFace.UP;
                case Z -> BlockFace.SOUTH;
            });
        }

        /** Whether the turn points the axle's positive end at a negative cardinal (reverses its spin sense). */
        public boolean negates(RotationNetwork.Axis axis) {
            BlockFace img = image(axis);
            return img == BlockFace.WEST || img == BlockFace.NORTH || img == BlockFace.DOWN;
        }

        /** UP stays UP: a pure yaw about Y (the identity included). */
        public boolean isYawOnly() { return images[4] == BlockFace.UP; }

        /** A block's up-vector stays +Y — the banner/painting/hoist "upright" landing gate. Same as yaw-only. */
        public boolean upright() { return isYawOnly(); }

        /** The yaw (0/90/180/270) of a yaw-only turn, in {@link #rotateBlockFace}'s convention. */
        public float yawDegrees() {
            if (!isYawOnly()) throw new IllegalStateException("not a yaw-only turn: " + this);
            return switch (apply(BlockFace.SOUTH)) {
                case WEST -> 90f;
                case NORTH -> 180f;
                case EAST -> 270f;
                default -> 0f;
            };
        }

        @Override public boolean equals(Object o) { return o instanceof Turn t && Arrays.equals(images, t.images); }
        @Override public int hashCode() { return Arrays.hashCode(images); }
        @Override public String toString() {
            StringBuilder sb = new StringBuilder("Turn[");
            for (int i = 0; i < 6; i++) {
                if (i > 0) sb.append(' ');
                sb.append(FACES[i].name().charAt(0)).append("->").append(images[i].name().charAt(0));
            }
            return sb.append(']').toString();
        }
    }

    private static boolean vertical(BlockFace f) { return f == BlockFace.UP || f == BlockFace.DOWN; }

    private static org.bukkit.Axis axisOf(BlockFace f) {
        return switch (f) {
            case EAST, WEST -> org.bukkit.Axis.X;
            case UP, DOWN -> org.bukkit.Axis.Y;
            default -> org.bukkit.Axis.Z;
        };
    }

    /**
     * Rotate block data by a landing {@link Turn}. Returns null when the turned block has no representation
     * (a door/bed on its side, a floor-only plant on a wall, a sign on a ceiling): the caller drops it as an
     * item. A yaw-only turn is exactly {@link #rotateBlockData(BlockData, float)} (never null).
     *
     * <p>Floor↔wall material swaps (torch ↔ wall torch, sign, banner, skull, coral fan) need a new material, so
     * they live in {@link #landBlockData}; called directly, this returns null for those.
     */
    public static @Nullable BlockData rotateBlockData(BlockData originalData, Turn turn) {
        if (turn.isYawOnly()) return rotateBlockData(originalData, turn.yawDegrees());
        BlockData d = originalData.clone();
        Material mat = d.getMaterial();
        boolean bound = supportBound(mat);

        // Two-cell / multi-state blocks with no sideways form.
        if (d instanceof Door || d instanceof Bed || d instanceof Bell) return null;

        if (d instanceof FaceAttachable fa) {   // lever, button, grindstone
            BlockFace facing = d instanceof Directional dir ? dir.getFacing() : BlockFace.NORTH;
            BlockFace attach = switch (fa.getAttachedFace()) {
                case FLOOR -> BlockFace.DOWN;
                case CEILING -> BlockFace.UP;
                case WALL -> facing.getOppositeFace();
            };
            BlockFace a2 = turn.apply(attach);
            BlockFace f2;
            if (vertical(a2)) {
                fa.setAttachedFace(a2 == BlockFace.DOWN ? FaceAttachable.AttachedFace.FLOOR
                                                        : FaceAttachable.AttachedFace.CEILING);
                // floor/ceiling → keep the image of its horizontal facing; wall → its "up" now lies horizontal
                f2 = vertical(attach) ? turn.apply(facing) : turn.apply(BlockFace.UP);
            } else {
                fa.setAttachedFace(FaceAttachable.AttachedFace.WALL);
                f2 = a2.getOppositeFace();
            }
            if (d instanceof Directional dir && dir.getFaces().contains(f2)) dir.setFacing(f2);
            return d;
        }

        if (d instanceof Stairs st) {
            // A stair is its half-slab (half face) plus the quarter on its facing side. Both images: the
            // vertical one is the new half, the horizontal one the new facing.
            BlockFace h2 = turn.apply(st.getHalf() == Bisected.Half.TOP ? BlockFace.UP : BlockFace.DOWN);
            BlockFace f2 = turn.apply(st.getFacing());
            if (vertical(h2) != vertical(f2)) {
                BlockFace v = vertical(h2) ? h2 : f2;
                BlockFace h = vertical(h2) ? f2 : h2;
                st.setHalf(v == BlockFace.UP ? Bisected.Half.TOP : Bisected.Half.BOTTOM);
                st.setFacing(h);
            } else {
                // Both images horizontal: the step's inner edge now runs vertically — no stair has that.
                // Approximate: keep the half, take the facing image.
                st.setFacing(f2);
            }
            st.setShape(Stairs.Shape.STRAIGHT);
            return d;
        }

        if (d instanceof TrapDoor td) {
            // The hinge edge is (half face) ∩ (facing.opposite); the panel lies on the half face when closed
            // and on facing.opposite when open.
            BlockFace hf = td.getHalf() == Bisected.Half.TOP ? BlockFace.UP : BlockFace.DOWN;
            BlockFace back = td.getFacing().getOppositeFace();
            BlockFace panel = td.isOpen() ? back : hf;
            BlockFace h2 = turn.apply(hf), b2 = turn.apply(back), p2 = turn.apply(panel);
            if (vertical(h2) != vertical(b2)) {
                BlockFace v = vertical(h2) ? h2 : b2;
                BlockFace h = vertical(h2) ? b2 : h2;
                td.setHalf(v == BlockFace.UP ? Bisected.Half.TOP : Bisected.Half.BOTTOM);
                td.setFacing(h.getOppositeFace());
                td.setOpen(p2 == h);
            } else {
                // Vertical hinge edge: no trapdoor has it. Keep the panel where it is — an open trapdoor.
                td.setFacing(p2.getOppositeFace());
                td.setOpen(true);
            }
            return d;
        }

        if (d instanceof Bisected) return null;   // tall plants & co: a two-high column on its side

        if (d instanceof Hangable hg) {   // lantern, mangrove propagule
            BlockFace s2 = turn.apply(hg.isHanging() ? BlockFace.UP : BlockFace.DOWN);
            if (!vertical(s2)) return null;
            hg.setHanging(s2 == BlockFace.UP);
            return d;
        }

        if (d instanceof PointedDripstone pd) {
            BlockFace tip = turn.apply(pd.getVerticalDirection());
            if (!vertical(tip)) return null;
            pd.setVerticalDirection(tip);
            return d;
        }

        boolean supportHandled = false;
        if (d instanceof Directional dir) {
            boolean wallMounted = wallMountedDirectional(dir, mat);
            if (bound && !wallMounted) return null;   // repeater, comparator, petals…: floor-only
            BlockFace f2 = turn.apply(dir.getFacing());
            if (dir.getFaces().contains(f2)) {
                dir.setFacing(f2);
            } else if (bound) {
                return null;   // wall torch/sign/ladder onto a floor or ceiling (swaps are landBlockData's)
            }
            // else: a full block with a horizontal-only facing (chest, furnace) turned up/down — keep a valid one
            supportHandled = wallMounted;
        }

        if (d instanceof Orientable o) {
            org.bukkit.Axis a2 = turn.apply(o.getAxis());
            if (o.getAxes().contains(a2)) o.setAxis(a2);
        }

        if (d instanceof Rotatable rot) {
            BlockFace up2 = turn.apply(BlockFace.UP);
            if (!vertical(up2)) return null;   // a standing skull/sign/banner onto a wall: landBlockData swaps
            rot.setRotation(mapRotation(rot.getRotation(), turn));
        }

        if (d instanceof MultipleFacing mf) {
            Set<BlockFace> original = new HashSet<>(mf.getFaces());
            for (BlockFace face : original) mf.setFace(face, false);
            int kept = 0;
            for (BlockFace face : original) {
                BlockFace nf = turn.apply(face);
                if (mf.getAllowedFaces().contains(nf)) { mf.setFace(nf, true); kept++; }
            }
            if (bound && kept == 0 && !original.isEmpty()) return null;   // a vine with nothing to cling to
            supportHandled = true;
        }

        if (d instanceof Slab slab && slab.getType() != Slab.Type.DOUBLE
                && turn.apply(BlockFace.UP) == BlockFace.DOWN) {
            slab.setType(slab.getType() == Slab.Type.TOP ? Slab.Type.BOTTOM : Slab.Type.TOP);
        }

        // Anything else that needs a floor under it (carpet, rail, wire, candle, flower, crop, sign…) has none
        // after a drawbridge turn.
        if (bound && !supportHandled) return null;
        return d;
    }

    /** A block that needs support and pops without it. Leaves are on the fragile list but are full blocks. */
    private static boolean supportBound(Material m) {
        return FragileBlocks.isAttachable(m)
            || (FragileBlocks.isFragile(m) && !m.name().endsWith("_LEAVES"));
    }

    /** A Directional whose support follows its facing (wall torch, ladder, cocoa, amethyst, rods), vs one
     *  that always stands on the floor whatever its facing (repeater, comparator, petals). */
    private static boolean wallMountedDirectional(Directional dir, Material m) {
        String n = m.name();
        return dir.getFaces().size() == 6 || n.contains("WALL_")
            || m == Material.LADDER || m == Material.COCOA || m == Material.TRIPWIRE_HOOK;
    }

    /** Map a 16-step Rotatable heading through a turn that keeps the vertical axis (UP → ±UP). */
    private static BlockFace mapRotation(BlockFace rotation, Turn turn) {
        int s = rotationToStep(rotation);
        int off = rotationToStep(turn.apply(BlockFace.SOUTH));
        boolean mirrored = rotationToStep(turn.apply(BlockFace.WEST)) != (off + 4) % 16;
        return stepToRotation(mirrored ? off - s : off + s);
    }

    /** A floor↔wall material swap for a landing turn: the new material plus its facing (wall variant) or
     *  rotation (standing variant). */
    record Swap(Material material, @Nullable BlockFace facing, @Nullable BlockFace rotation) {}

    private static final String[] SWAP_SUFFIXES = {"TORCH", "SIGN", "BANNER", "SKULL", "HEAD", "FAN"};

    private static boolean swapFamily(String name) {
        if (name.contains("HANGING") || name.equals("TORCHFLOWER")) return false;
        if (name.endsWith("FAN") && !name.contains("CORAL")) return false;
        if (name.equals("PISTON_HEAD")) return false;
        for (String suf : SWAP_SUFFIXES) if (name.endsWith(suf)) return true;
        return false;
    }

    /** OAK_SIGN → OAK_WALL_SIGN, TORCH → WALL_TORCH; null when the name isn't in a swap family. */
    static @Nullable String wallVariantName(String floor) {
        if (!swapFamily(floor) || floor.contains("WALL_")) return null;
        int i = floor.lastIndexOf('_');
        return i < 0 ? "WALL_" + floor : floor.substring(0, i + 1) + "WALL_" + floor.substring(i + 1);
    }

    /** OAK_WALL_SIGN → OAK_SIGN, WALL_TORCH → TORCH; null when not a swappable wall variant. */
    static @Nullable String floorVariantName(String wall) {
        if (!swapFamily(wall)) return null;
        if (wall.startsWith("WALL_")) return wall.substring(5);
        if (!wall.contains("_WALL_")) return null;
        return wall.replace("_WALL_", "_");
    }

    /**
     * The floor↔wall swap a turn forces, or null when the block keeps its material (no swap family, the
     * support stays on a wall / on the floor, or the target placement is impossible — e.g. a sign on a
     * ceiling, which {@link #rotateBlockData(BlockData, Turn)} then turns into a drop). Skulls/heads float,
     * so they also go onto a ceiling (as their standing variant).
     */
    static @Nullable Swap floorWallSwap(BlockData data, Turn turn) {
        if (turn.isYawOnly()) return null;
        String name = data.getMaterial().name();
        boolean head = name.endsWith("SKULL") || name.endsWith("HEAD");
        String floor = floorVariantName(name);
        if (floor != null && data instanceof Directional dir) {   // wall variant
            BlockFace a2 = turn.apply(dir.getFacing().getOppositeFace());
            if (!vertical(a2)) return null;
            if (a2 == BlockFace.UP && !head) return null;
            Material m = Material.getMaterial(floor);
            // Its old "down" now lies horizontal: stand it facing there.
            return m == null ? null : new Swap(m, null, turn.apply(BlockFace.DOWN));
        }
        String wall = wallVariantName(name);
        if (wall != null) {   // standing variant: its support is below it
            BlockFace a2 = turn.apply(BlockFace.DOWN);
            if (vertical(a2)) return null;
            Material m = Material.getMaterial(wall);
            return m == null ? null : new Swap(m, a2.getOppositeFace(), null);
        }
        return null;
    }

    /**
     * Full landed data for a vanilla block under a turn: a floor↔wall swap where one applies (built with
     * {@code create}, i.e. {@code Material::createBlockData} at runtime), else {@link #rotateBlockData(BlockData,
     * Turn)}. Null → no representation, drop as an item.
     */
    static @Nullable BlockData landBlockData(BlockData data, Turn turn, Function<Material, BlockData> create) {
        if (turn.isYawOnly()) return rotateBlockData(data, turn.yawDegrees());
        Swap swap = floorWallSwap(data, turn);
        if (swap != null) return buildSwap(swap, data, create);
        return rotateBlockData(data, turn);
    }

    private static @Nullable BlockData buildSwap(Swap swap, BlockData from, Function<Material, BlockData> create) {
        BlockData nd = create.apply(swap.material());
        if (swap.facing() != null) {
            if (!(nd instanceof Directional dir) || !dir.getFaces().contains(swap.facing())) return null;
            dir.setFacing(swap.facing());
        }
        if (swap.rotation() != null && nd instanceof Rotatable rot) rot.setRotation(swap.rotation());
        if (from instanceof Waterlogged w1 && nd instanceof Waterlogged w2) w2.setWaterlogged(w1.isWaterlogged());
        if (from instanceof Lightable l1 && nd instanceof Lightable l2) l2.setLit(l1.isLit());
        return nd;
    }

    /** A custom head's landed vanilla data + custom state. */
    record HeadLanding(BlockData data, @Nullable String state) {}

    /**
     * Land a custom block (skull-backed or physical_material; NOT bare-first) under an off-yaw turn: map its
     * attachment face, pick the state for the new face from {@code placement_state_map}
     * ({@code map[a'] ?? map[a'.opposite]} — a shaft's map has no UP, a Y shaft on a ceiling is still idle_y),
     * keep the captured dynamic prefix, and choose PLAYER_HEAD vs PLAYER_WALL_HEAD. Null → the block can't be
     * represented (a type with a stateResolver, or no state for its new face): drop the custom item.
     * Yaw-only turns never reach this (the caller keeps {@link #rotateCustomState}).
     */
    static @Nullable HeadLanding landCustomHead(CustomHeadBlock type, BlockData captured, @Nullable String state,
                                                Turn turn, Function<Material, BlockData> create) {
        if (type.stateResolver() != null) return null;
        Map<BlockFace, String> map = type.placementStateMap();
        Material cm = captured.getMaterial();
        boolean skull = cm == Material.PLAYER_HEAD || cm == Material.PLAYER_WALL_HEAD;
        if (!skull) {
            BlockData data = rotateBlockData(captured, turn);
            if (data == null) return null;
            if (map == null || state == null) return new HeadLanding(data, state);
            BlockFace a2 = data instanceof Directional dir
                ? dir.getFacing().getOppositeFace()
                : turn.apply(capturedHeadAttach(map, captured, state));
            String s = landedHeadState(map, type.states().keySet(), state, a2);
            return s == null ? null : new HeadLanding(data, s);
        }
        BlockFace a2 = turn.apply(capturedHeadAttach(map, captured, state));
        String s = state;
        if (map != null && state != null) {
            s = landedHeadState(map, type.states().keySet(), state, a2);
            if (s == null) return null;
        }
        BlockData data;
        if (!vertical(a2) && (s == null || !type.playerHeadStates().contains(s))) {
            data = create.apply(Material.PLAYER_WALL_HEAD);
            if (!(data instanceof Directional dir)) return null;
            dir.setFacing(a2.getOppositeFace());
        } else {
            data = create.apply(Material.PLAYER_HEAD);
            if (data instanceof Rotatable rot) rot.setRotation(floatingHeadRotation(captured, turn));
        }
        return new HeadLanding(data, s);
    }

    /** A landed floating PLAYER_HEAD's 16-way rotation: the captured one mapped when the turn keeps the vertical
     *  (floor ↔ ceiling), else the image of the captured heading when horizontal, else NORTH (CoreLibPlugin's
     *  choice for a forced floor head). */
    private static BlockFace floatingHeadRotation(BlockData captured, Turn turn) {
        if (captured instanceof Rotatable rot && vertical(turn.apply(BlockFace.UP))) {
            return mapRotation(rot.getRotation(), turn);
        }
        BlockFace heading = captured instanceof Directional dir ? dir.getFacing()
            : captured instanceof Rotatable rot ? stepToRotation(Math.round(rotationToStep(rot.getRotation()) / 4f) * 4)
            : BlockFace.NORTH;
        BlockFace img = turn.apply(heading);
        return vertical(img) ? BlockFace.NORTH : img;
    }

    /**
     * The face a captured custom block is mounted on: a Directional (wall head, physical block) → facing's
     * opposite; a floating PLAYER_HEAD → DOWN (floor) or UP (ceiling), told apart by its state matching
     * {@code map[DOWN]} / {@code map[UP]} (the head's data can't say). Ambiguous or unmatched → DOWN.
     */
    static BlockFace capturedHeadAttach(@Nullable Map<BlockFace, String> map, BlockData captured,
                                        @Nullable String state) {
        if (captured instanceof Directional dir) return dir.getFacing().getOppositeFace();
        if (map == null || state == null) return BlockFace.DOWN;
        String o = orientationKey(state);
        String down = map.get(BlockFace.DOWN);
        if (down != null && orientationKey(down).equals(o)) return BlockFace.DOWN;
        String up = map.get(BlockFace.UP);
        if (up != null && orientationKey(up).equals(o)) return BlockFace.UP;
        return BlockFace.DOWN;
    }

    /**
     * The custom state for a block now mounted on {@code landedAttach}: {@code map[a'] ?? map[a'.opposite]},
     * with the captured dynamic prefix (idle/spinning/running/locked) grafted on when that state exists. Null
     * when neither face has an entry (→ drop). Pure; the spin token is restored afterwards by
     * {@link #preserveSpinToken(String, String, Turn)}.
     */
    static @Nullable String landedHeadState(Map<BlockFace, String> map, Set<String> states, String capturedState,
                                            BlockFace landedAttach) {
        String mapped = map.get(landedAttach);
        if (mapped == null) mapped = map.get(landedAttach.getOppositeFace());
        if (mapped == null) return null;
        String[] cap = splitDynamicState(capturedState);
        String[] m = splitDynamicState(mapped);
        String grafted = cap[0] != null ? cap[0] + "_" + m[1] : mapped;
        return states.contains(grafted) ? grafted : mapped;
    }

    /** A state's orientation part, without dynamic prefix or cw/ccw token: {@code spinning_ccw_y} → "y". */
    private static String orientationKey(String state) {
        StringBuilder sb = new StringBuilder();
        for (String tok : splitDynamicState(state)[1].split("_")) {
            if (tok.equals("cw") || tok.equals("ccw")) continue;
            if (sb.length() > 0) sb.append('_');
            sb.append(tok);
        }
        return sb.toString();
    }
}
