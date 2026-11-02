package anon.def9a2a4.corelib;

import anon.def9a2a4.corelib.BlockRotation.Turn;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.Switch;
import org.bukkit.block.data.type.TrapDoor;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.bukkit.block.BlockFace.*;
import static org.junit.jupiter.api.Assertions.*;

/** The drawbridge (X/Z-axis) landing orientation: {@link Turn} and the Turn-based rotations. */
class BlockRotationTurnTest {

    private static final BlockFace[] SIX = {NORTH, EAST, SOUTH, WEST, UP, DOWN};
    private static final Set<BlockFace> HORIZONTAL = EnumSet.of(NORTH, EAST, SOUTH, WEST);
    private static final Set<BlockFace> ALL = EnumSet.of(NORTH, EAST, SOUTH, WEST, UP, DOWN);

    /** Exactly BasicMechanism.landingRotation(): rotate(-yaw) about the mechanism's axis. */
    private static Turn landing(float x, float y, float z, float deg) {
        return Turn.of(new Matrix4f().rotate((float) Math.toRadians(-deg), x, y, z));
    }

    private static Turn x(float deg) { return landing(1, 0, 0, deg); }
    private static Turn z(float deg) { return landing(0, 0, 1, deg); }

    private static void assertMap(Turn t, BlockFace... pairs) {
        for (int i = 0; i < pairs.length; i += 2) {
            assertEquals(pairs[i + 1], t.apply(pairs[i]), t + ": " + pairs[i]);
        }
    }

    // ── Turn ────────────────────────────────────────────────────────────────

    @Test
    void turnOfMatchesTheHandTableForXAndZ() {
        assertMap(x(90), UP, NORTH, NORTH, DOWN, DOWN, SOUTH, SOUTH, UP, EAST, EAST, WEST, WEST);
        assertMap(x(180), UP, DOWN, DOWN, UP, NORTH, SOUTH, SOUTH, NORTH, EAST, EAST, WEST, WEST);
        assertMap(x(270), UP, SOUTH, SOUTH, DOWN, DOWN, NORTH, NORTH, UP, EAST, EAST, WEST, WEST);
        assertMap(z(90), UP, EAST, EAST, DOWN, DOWN, WEST, WEST, UP, NORTH, NORTH, SOUTH, SOUTH);
        assertMap(z(180), UP, DOWN, DOWN, UP, EAST, WEST, WEST, EAST, NORTH, NORTH, SOUTH, SOUTH);
        assertMap(z(270), UP, WEST, WEST, DOWN, DOWN, EAST, EAST, UP, NORTH, NORTH, SOUTH, SOUTH);
        // Rotators spin with negative yaw: -90 is the 270 turn.
        assertEquals(x(270), x(-90));
        assertEquals(Turn.IDENTITY, x(0));
        assertEquals(Turn.IDENTITY, z(360));
        assertFalse(x(90).isYawOnly());
        assertFalse(x(90).upright());
        assertTrue(x(0).upright());
    }

    @Test
    void yAxisLandingIsExactlyTheOldYaw() {
        // The regression lock: a Y-axis landing matrix must give the face map rotateBlockFace always gave,
        // so every ship/door/cart/hoist/piston orients bit-identically.
        for (float deg = -360; deg <= 720; deg += 90) {
            Turn fromMatrix = landing(0, 1, 0, deg);
            Turn yaw = Turn.yaw(deg);
            assertEquals(yaw, fromMatrix, "yaw " + deg);
            assertTrue(fromMatrix.isYawOnly());
            for (BlockFace f : SIX) assertEquals(BlockRotation.rotateBlockFace(f, deg), yaw.apply(f), f + "@" + deg);
            assertEquals(Math.floorMod((int) deg, 360), (int) yaw.yawDegrees());
            for (RotationNetwork.Axis a : RotationNetwork.Axis.values()) {
                for (String tok : new String[] {"cw", "ccw", "idle"}) {
                    assertEquals(BlockRotation.rotateSpinDir(a, deg, tok), BlockRotation.rotateSpinDir(a, yaw, tok));
                }
            }
        }
    }

    @Test
    void turnRejectsANonQuarterTurn() {
        assertThrows(IllegalArgumentException.class, () -> landing(1, 0, 0, 45));
        assertThrows(IllegalArgumentException.class, () -> x(90).apply(NORTH_EAST));
        assertThrows(IllegalStateException.class, () -> x(90).yawDegrees());
    }

    @Test
    void turnMapsAxes() {
        assertEquals(Axis.Z, x(90).apply(Axis.Y));
        assertEquals(Axis.Y, x(90).apply(Axis.Z));
        assertEquals(Axis.X, x(90).apply(Axis.X));
        assertEquals(Axis.X, z(270).apply(Axis.Y));
    }

    // ── spin ────────────────────────────────────────────────────────────────

    @Test
    void spinFlipsIffTheTurnReversesTheAxle() {
        // X 90: +Y → NORTH (reversed), +Z → UP (kept), +X fixed.
        assertEquals("ccw", BlockRotation.rotateSpinDir(RotationNetwork.Axis.Y, x(90), "cw"));
        assertEquals("cw", BlockRotation.rotateSpinDir(RotationNetwork.Axis.Z, x(90), "cw"));
        assertEquals("cw", BlockRotation.rotateSpinDir(RotationNetwork.Axis.X, x(90), "cw"));
        // X 180: +Y → DOWN and +Z → NORTH both reversed — the old yaw code could never flip a Y axle.
        assertEquals("ccw", BlockRotation.rotateSpinDir(RotationNetwork.Axis.Y, x(180), "cw"));
        assertEquals("ccw", BlockRotation.rotateSpinDir(RotationNetwork.Axis.Z, x(180), "cw"));
        // Z 90: +X → DOWN (reversed), +Y → EAST (kept).
        assertEquals("ccw", BlockRotation.rotateSpinDir(RotationNetwork.Axis.X, z(90), "cw"));
        assertEquals("cw", BlockRotation.rotateSpinDir(RotationNetwork.Axis.Y, z(90), "cw"));
    }

    @Test
    void preserveSpinTokenUsesTheTurn() {
        // A CCW Z ratchet tipped onto the floor: the map rebuilds idle_cw_y; +Z → UP keeps ccw.
        assertEquals("idle_ccw_y", BlockRotation.preserveSpinToken("idle_ccw_z", "idle_cw_y", x(90)));
        // A CW Y ratchet at 180° about X: +Y → DOWN reverses it.
        assertEquals("idle_ccw_y", BlockRotation.preserveSpinToken("idle_cw_y", "idle_cw_y", x(180)));
        // The float overload (ShowcaseBuilder calls it with 0) is unchanged.
        assertEquals("idle_ccw_z", BlockRotation.preserveSpinToken("idle_ccw_z", "idle_cw_z", 0));
        assertNull(BlockRotation.preserveSpinToken(null, null, x(90)));
    }

    // ── custom state choice ─────────────────────────────────────────────────

    private static final Map<BlockFace, String> SHAFT = Map.of(
        DOWN, "idle_y", NORTH, "idle_z", SOUTH, "idle_z", EAST, "idle_x", WEST, "idle_x");
    private static final Set<String> SHAFT_STATES = Set.of(
        "idle_x", "idle_y", "idle_z", "spinning_x", "spinning_y", "spinning_z");
    private static final Map<BlockFace, String> GEAR = Map.of(
        DOWN, "idle_y", NORTH, "idle_z", SOUTH, "idle_south_z", EAST, "idle_east_x", WEST, "idle_x");
    private static final Set<String> GEAR_STATES = Set.of(
        "idle_y", "idle_z", "idle_south_z", "idle_east_x", "idle_x",
        "spinning_y", "spinning_z", "spinning_south_z", "spinning_east_x", "spinning_x");
    private static final Map<BlockFace, String> ROTATOR = Map.of(
        DOWN, "idle_y", UP, "idle_ceiling", NORTH, "idle_z", SOUTH, "idle_z", EAST, "idle_x", WEST, "idle_x");
    private static final Set<String> ROTATOR_STATES = Set.of(
        "idle_y", "idle_ceiling", "idle_z", "idle_x", "spinning_y", "spinning_ceiling", "spinning_z", "spinning_x");
    private static final Map<BlockFace, String> RATCHET = Map.of(
        DOWN, "idle_cw_y", NORTH, "idle_cw_z", SOUTH, "idle_cw_z", EAST, "idle_cw_x", WEST, "idle_cw_x");
    private static final Set<String> RATCHET_STATES = Set.of(
        "idle_cw_y", "idle_ccw_y", "idle_cw_z", "idle_ccw_z", "idle_cw_x", "idle_ccw_x");

    private static String land(Map<BlockFace, String> map, Set<String> states, String state, BlockFace attach, Turn t) {
        return BlockRotation.landedHeadState(map, states, state, t.apply(attach));
    }

    @Test
    void shaftStateFollowsTheTurnedAxle() {
        // Z shaft on a north wall, drawbridge tips it 90° about X: mounted on DOWN → a Y shaft, still spinning.
        assertEquals("spinning_y", land(SHAFT, SHAFT_STATES, "spinning_z", NORTH, x(90)));
        // Y shaft on the floor flipped onto a ceiling: no UP entry → its opposite (still a Y shaft).
        assertEquals("idle_y", land(SHAFT, SHAFT_STATES, "idle_y", DOWN, x(180)));
        // An X shaft rolled about X keeps its axis; about Z it stands up.
        assertEquals("idle_x", land(SHAFT, SHAFT_STATES, "idle_x", EAST, x(90)));
        assertEquals("idle_y", land(SHAFT, SHAFT_STATES, "idle_x", EAST, z(90)));
        // Floor Y shaft tipped onto a wall.
        assertEquals("idle_z", land(SHAFT, SHAFT_STATES, "idle_y", DOWN, x(90)));
    }

    @Test
    void gearPicksTheWallVariantOfItsNewFace() {
        assertEquals("idle_south_z", land(GEAR, GEAR_STATES, "idle_y", DOWN, x(90)));   // DOWN → SOUTH
        assertEquals("spinning_z", land(GEAR, GEAR_STATES, "spinning_y", DOWN, x(270)));  // DOWN → NORTH
        assertEquals("idle_east_x", land(GEAR, GEAR_STATES, "idle_y", DOWN, z(270)));   // DOWN → EAST
        assertEquals("idle_y", land(GEAR, GEAR_STATES, "idle_south_z", SOUTH, x(270)));  // SOUTH → DOWN
    }

    @Test
    void rotatorCeilingIsToldApartFromFloorByState() {
        BlockData floatingHead = fake(Material.PLAYER_HEAD, Map.of("rotation", NORTH), HORIZONTAL, Rotatable.class);
        assertEquals(UP, BlockRotation.capturedHeadAttach(ROTATOR, floatingHead, "spinning_ceiling"));
        assertEquals(DOWN, BlockRotation.capturedHeadAttach(ROTATOR, floatingHead, "idle_y"));
        assertEquals(NORTH, BlockRotation.capturedHeadAttach(ROTATOR,
            fake(Material.PLAYER_WALL_HEAD, Map.of("facing", SOUTH), HORIZONTAL, Directional.class), "idle_z"));
        // Ceiling rotator tipped onto a wall; floor rotator flipped onto the ceiling.
        assertEquals("spinning_z", land(ROTATOR, ROTATOR_STATES, "spinning_ceiling", UP, x(90)));
        assertEquals("idle_ceiling", land(ROTATOR, ROTATOR_STATES, "idle_y", DOWN, z(180)));
    }

    @Test
    void ratchetKeepsItsDirectionThroughTheMap() {
        String s = land(RATCHET, RATCHET_STATES, "idle_ccw_z", SOUTH, x(90));   // SOUTH → UP → map[DOWN]
        assertEquals("idle_cw_y", s);   // the map hard-codes cw…
        assertEquals("idle_ccw_y", BlockRotation.preserveSpinToken("idle_ccw_z", s, x(90)));   // …the token wins
        // Ratchet floor state still reads as a floor mount despite its ccw token.
        BlockData floatingHead = fake(Material.PLAYER_HEAD, Map.of("rotation", NORTH), HORIZONTAL, Rotatable.class);
        assertEquals(DOWN, BlockRotation.capturedHeadAttach(RATCHET, floatingHead, "idle_ccw_y"));
    }

    @Test
    void noStateForTheNewFaceMeansDrop() {
        Map<BlockFace, String> wallOnly = Map.of(NORTH, "idle_z", SOUTH, "idle_z", EAST, "idle_x", WEST, "idle_x");
        assertNull(land(wallOnly, Set.of("idle_z", "idle_x"), "idle_z", NORTH, x(90)));
    }

    // ── vanilla block data ──────────────────────────────────────────────────

    @Test
    void stairTurnsItsHalfAndFacing() {
        BlockData stair = fake(Material.OAK_STAIRS, Map.of("facing", NORTH, "half", Bisected.Half.BOTTOM,
            "shape", Stairs.Shape.OUTER_LEFT), HORIZONTAL, Stairs.class);
        // Bottom slab → south half, top-north quarter → bottom-north: a bottom stair facing south.
        Stairs a = (Stairs) BlockRotation.rotateBlockData(stair, x(90));
        assertEquals(Bisected.Half.BOTTOM, a.getHalf());
        assertEquals(SOUTH, a.getFacing());
        assertEquals(Stairs.Shape.STRAIGHT, a.getShape());
        // 180 about X: upside-down, facing south.
        Stairs b = (Stairs) BlockRotation.rotateBlockData(stair, x(180));
        assertEquals(Bisected.Half.TOP, b.getHalf());
        assertEquals(SOUTH, b.getFacing());
        // The original is untouched.
        assertEquals(NORTH, ((Stairs) stair).getFacing());
    }

    @Test
    void slabFlipsOnlyWhenTurnedUpsideDown() {
        BlockData top = fake(Material.OAK_SLAB, Map.of("type", Slab.Type.TOP), HORIZONTAL, Slab.class);
        assertEquals(Slab.Type.BOTTOM, ((Slab) BlockRotation.rotateBlockData(top, x(180))).getType());
        assertEquals(Slab.Type.TOP, ((Slab) BlockRotation.rotateBlockData(top, z(90))).getType());
        BlockData dbl = fake(Material.OAK_SLAB, Map.of("type", Slab.Type.DOUBLE), HORIZONTAL, Slab.class);
        assertEquals(Slab.Type.DOUBLE, ((Slab) BlockRotation.rotateBlockData(dbl, x(180))).getType());
    }

    @Test
    void logAxisFollowsTheTurn() {
        BlockData log = fake(Material.OAK_LOG, Map.of("axis", Axis.Y), HORIZONTAL, Orientable.class);
        assertEquals(Axis.Z, ((Orientable) BlockRotation.rotateBlockData(log, x(90))).getAxis());
        assertEquals(Axis.X, ((Orientable) BlockRotation.rotateBlockData(log, z(90))).getAxis());
        assertEquals(Axis.Y, ((Orientable) BlockRotation.rotateBlockData(log, x(180))).getAxis());
        BlockData xLog = fake(Material.OAK_LOG, Map.of("axis", Axis.X), HORIZONTAL, Orientable.class);
        assertEquals(Axis.X, ((Orientable) BlockRotation.rotateBlockData(xLog, x(90))).getAxis());
    }

    @Test
    void observerTurnsToAnyFace() {
        BlockData obs = fake(Material.OBSERVER, Map.of("facing", UP), ALL, Directional.class);
        assertEquals(NORTH, ((Directional) BlockRotation.rotateBlockData(obs, x(90))).getFacing());
        assertEquals(DOWN, ((Directional) BlockRotation.rotateBlockData(obs, z(180))).getFacing());
    }

    @Test
    void horizontalOnlyFullBlockKeepsAValidFacing() {
        BlockData chest = fake(Material.CHEST, Map.of("facing", NORTH), HORIZONTAL, Directional.class);
        assertEquals(NORTH, ((Directional) BlockRotation.rotateBlockData(chest, x(90))).getFacing());
        BlockData chestE = fake(Material.CHEST, Map.of("facing", EAST), HORIZONTAL, Directional.class);
        assertEquals(EAST, ((Directional) BlockRotation.rotateBlockData(chestE, x(90))).getFacing());
    }

    @Test
    void closedTrapdoorTippedOntoAWallOpens() {
        BlockData td = fake(Material.OAK_TRAPDOOR, Map.of("facing", NORTH, "half", Bisected.Half.BOTTOM,
            "open", false), HORIZONTAL, TrapDoor.class);
        // Panel on the bottom → on the south face: open, facing north; hinge (bottom-south) → top-south.
        TrapDoor a = (TrapDoor) BlockRotation.rotateBlockData(td, x(90));
        assertTrue(a.isOpen());
        assertEquals(NORTH, a.getFacing());
        assertEquals(Bisected.Half.TOP, a.getHalf());
        // And back: the open one turned the other way closes again on the bottom.
        TrapDoor back = (TrapDoor) BlockRotation.rotateBlockData(a, x(270));
        assertFalse(back.isOpen());
        assertEquals(NORTH, back.getFacing());
        assertEquals(Bisected.Half.BOTTOM, back.getHalf());
    }

    @Test
    void doorsAndBedsHaveNoSidewaysForm() {
        BlockData door = fake(Material.OAK_DOOR, Map.of("facing", NORTH, "half", Bisected.Half.BOTTOM),
            HORIZONTAL, Door.class);
        assertNull(BlockRotation.rotateBlockData(door, x(90)));
        assertNull(BlockRotation.rotateBlockData(door, z(180)));
        BlockData bed = fake(Material.RED_BED, Map.of("facing", NORTH), HORIZONTAL, Bed.class);
        assertNull(BlockRotation.rotateBlockData(bed, x(90)));
        // …but a yaw still turns a door exactly as before.
        assertEquals(WEST, ((Directional) BlockRotation.rotateBlockData(door, Turn.yaw(270))).getFacing());
    }

    @Test
    void leverOnAWallTippedOntoTheFloor() {
        BlockData lever = fake(Material.LEVER, Map.of("facing", SOUTH,
            "attachedFace", FaceAttachable.AttachedFace.WALL), HORIZONTAL, Switch.class);
        Switch s = (Switch) BlockRotation.rotateBlockData(lever, x(90));   // mounted NORTH → DOWN
        assertEquals(FaceAttachable.AttachedFace.FLOOR, s.getAttachedFace());
        assertEquals(NORTH, s.getFacing());   // its old "up" now points north
        Switch c = (Switch) BlockRotation.rotateBlockData(lever, x(270));   // mounted NORTH → UP
        assertEquals(FaceAttachable.AttachedFace.CEILING, c.getAttachedFace());
    }

    @Test
    void standingSkullMirrorsWhenFlippedAndWallTorchSwaps() {
        BlockData skull = fake(Material.SKELETON_SKULL, Map.of("rotation", SOUTH_SOUTH_EAST), HORIZONTAL, Rotatable.class);
        assertEquals(NORTH_NORTH_EAST, ((Rotatable) BlockRotation.rotateBlockData(skull, x(180))).getRotation());
        assertEquals(SOUTH_SOUTH_WEST, ((Rotatable) BlockRotation.rotateBlockData(skull, z(180))).getRotation());
        // On its side a standing skull needs the wall variant.
        assertNull(BlockRotation.rotateBlockData(skull, x(90)));
        BlockRotation.Swap ws = BlockRotation.floorWallSwap(skull, x(90));   // DOWN → SOUTH
        assertEquals(new BlockRotation.Swap(Material.SKELETON_WALL_SKULL, NORTH, null), ws);

        BlockData wallTorch = fake(Material.WALL_TORCH, Map.of("facing", SOUTH), HORIZONTAL, Directional.class);
        // Mounted NORTH, tipped 90° about X onto the floor: a standing torch.
        BlockData landed = BlockRotation.landBlockData(wallTorch, x(90),
            m -> fake(m, Map.of(), HORIZONTAL, BlockData.class));
        assertNotNull(landed);
        assertEquals(Material.TORCH, landed.getMaterial());
        // Onto the ceiling instead: a torch can't hang — drop.
        assertNull(BlockRotation.landBlockData(wallTorch, x(270), m -> fake(m, Map.of(), HORIZONTAL, BlockData.class)));

        BlockData torch = fake(Material.TORCH, Map.of(), HORIZONTAL, BlockData.class);
        BlockData onWall = BlockRotation.landBlockData(torch, z(90),
            m -> fake(m, Map.of("facing", NORTH), HORIZONTAL, Directional.class));
        assertEquals(Material.WALL_TORCH, onWall.getMaterial());
        assertEquals(EAST, ((Directional) onWall).getFacing());   // support DOWN → WEST, so it faces east
        assertNull(BlockRotation.landBlockData(torch, x(180), m -> fake(m, Map.of(), HORIZONTAL, BlockData.class)));
    }

    @Test
    void floorWallNamesResolveToRealMaterials() {
        String[][] pairs = {
            {"TORCH", "WALL_TORCH"}, {"SOUL_TORCH", "SOUL_WALL_TORCH"}, {"REDSTONE_TORCH", "REDSTONE_WALL_TORCH"},
            {"OAK_SIGN", "OAK_WALL_SIGN"}, {"WHITE_BANNER", "WHITE_WALL_BANNER"},
            {"SKELETON_SKULL", "SKELETON_WALL_SKULL"}, {"PLAYER_HEAD", "PLAYER_WALL_HEAD"},
            {"DRAGON_HEAD", "DRAGON_WALL_HEAD"}, {"TUBE_CORAL_FAN", "TUBE_CORAL_WALL_FAN"},
            {"DEAD_BRAIN_CORAL_FAN", "DEAD_BRAIN_CORAL_WALL_FAN"}};
        for (String[] p : pairs) {
            assertEquals(p[1], BlockRotation.wallVariantName(p[0]));
            assertEquals(p[0], BlockRotation.floorVariantName(p[1]));
            assertNotNull(Material.getMaterial(p[0]), p[0]);
            assertNotNull(Material.getMaterial(p[1]), p[1]);
        }
        assertNull(BlockRotation.wallVariantName("OAK_HANGING_SIGN"));
        assertNull(BlockRotation.floorVariantName("OAK_WALL_HANGING_SIGN"));
        assertNull(BlockRotation.wallVariantName("TORCHFLOWER"));
        assertNull(BlockRotation.wallVariantName("STONE"));
    }

    @Test
    void fenceKeepsOnlyFacesThatStayHorizontal() {
        Map<String, Object> st = new HashMap<>();
        st.put("faces", EnumSet.of(NORTH, EAST));
        BlockData fence = fake(Material.OAK_FENCE, st, HORIZONTAL, MultipleFacing.class);
        MultipleFacing mf = (MultipleFacing) BlockRotation.rotateBlockData(fence, x(90));
        assertEquals(EnumSet.of(EAST), mf.getFaces());   // NORTH → DOWN is not a fence face
    }

    @Test
    void yawOnlyTurnMatchesTheFloatPath() {
        for (float deg : new float[] {0, 90, 180, 270, -90}) {
            BlockData stair = fake(Material.OAK_STAIRS, Map.of("facing", NORTH, "half", Bisected.Half.TOP,
                "shape", Stairs.Shape.INNER_LEFT), HORIZONTAL, Stairs.class);
            assertEquals(BlockRotation.rotateBlockData(stair, deg).toString(),
                BlockRotation.rotateBlockData(stair, Turn.yaw(deg)).toString());
        }
    }

    // ── fakes ───────────────────────────────────────────────────────────────

    /** A minimal BlockData via a dynamic proxy: getters/setters read a property map; Directional.getFaces
     *  is the allowed set; MultipleFacing faces live under "faces". */
    @SuppressWarnings("unchecked")
    static BlockData fake(Material mat, Map<String, Object> init, Set<BlockFace> allowed, Class<?> iface) {
        Map<String, Object> st = new HashMap<>(init);
        if (iface == MultipleFacing.class) st.put("faces", EnumSet.copyOf((Set<BlockFace>) st.getOrDefault("faces",
            EnumSet.noneOf(BlockFace.class))));
        return (BlockData) Proxy.newProxyInstance(BlockRotationTurnTest.class.getClassLoader(),
            new Class<?>[] {iface}, (p, m, a) -> {
                String n = m.getName();
                switch (n) {
                    case "clone": {
                        Map<String, Object> copy = new HashMap<>(st);
                        if (copy.get("faces") instanceof Set<?> s) copy.put("faces", new HashSet<>(s));
                        return fake(mat, copy, allowed, iface);
                    }
                    case "getMaterial": return mat;
                    case "toString": return mat + new java.util.TreeMap<>(st).toString();
                    case "hashCode": return System.identityHashCode(p);
                    case "equals": return p == a[0];
                    case "getFaces":
                        return iface == MultipleFacing.class ? Set.copyOf((Set<BlockFace>) st.get("faces")) : allowed;
                    case "getAllowedFaces": return allowed;
                    case "setFace": {
                        Set<BlockFace> f = (Set<BlockFace>) st.get("faces");
                        if ((Boolean) a[1]) f.add((BlockFace) a[0]); else f.remove(a[0]);
                        return null;
                    }
                    case "hasFace": return ((Set<BlockFace>) st.get("faces")).contains(a[0]);
                    case "getAxes": return EnumSet.allOf(Axis.class);
                    case "isWaterlogged": return false;
                    default:
                }
                String prop = n.startsWith("get") || n.startsWith("set") ? n.substring(3)
                    : n.startsWith("is") ? n.substring(2) : null;
                if (prop == null) throw new UnsupportedOperationException(n);
                prop = Character.toLowerCase(prop.charAt(0)) + prop.substring(1);
                if (n.startsWith("set")) { st.put(prop, a[0]); return null; }
                if (!st.containsKey(prop)) throw new UnsupportedOperationException(n + " unset");
                return st.get(prop);
            });
    }
}
