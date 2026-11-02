package anon.def9a2a4.corelib;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BlockRotationTest {

    @Test
    void rotateBlockFaceTurnsClockwiseSeenFromAbove() {
        assertEquals(BlockFace.WEST, BlockRotation.rotateBlockFace(BlockFace.SOUTH, 90));
        assertEquals(BlockFace.NORTH, BlockRotation.rotateBlockFace(BlockFace.SOUTH, 180));
        assertEquals(BlockFace.EAST, BlockRotation.rotateBlockFace(BlockFace.SOUTH, 270));
        assertEquals(BlockFace.SOUTH, BlockRotation.rotateBlockFace(BlockFace.SOUTH, 360));
        assertEquals(BlockFace.EAST, BlockRotation.rotateBlockFace(BlockFace.SOUTH, -90), "negative yaw normalizes");
        assertEquals(BlockFace.WEST, BlockRotation.rotateBlockFace(BlockFace.SOUTH, 89.9999f), "trig epsilon snaps");
    }

    @Test
    void rotateBlockFacePassesVerticalFacesThrough() {
        for (float yaw : new float[] {0, 90, 180, 270}) {
            assertEquals(BlockFace.UP, BlockRotation.rotateBlockFace(BlockFace.UP, yaw));
            assertEquals(BlockFace.DOWN, BlockRotation.rotateBlockFace(BlockFace.DOWN, yaw));
        }
    }

    @Test
    void fourQuarterTurnsAreIdentity() {
        for (BlockFace f : new BlockFace[] {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
            BlockFace r = f;
            for (int i = 0; i < 4; i++) r = BlockRotation.rotateBlockFace(r, 90);
            assertEquals(f, r);
        }
    }

    @Test
    void spinTokenFlipsExactlyWhenTheAxleIsReversed() {
        // X axle (+X = EAST): 90° → SOUTH (kept), 180° → WEST (reversed), 270° → NORTH (reversed).
        assertEquals("cw", BlockRotation.rotateSpinDir(RotationNetwork.Axis.X, 0, "cw"));
        assertEquals("cw", BlockRotation.rotateSpinDir(RotationNetwork.Axis.X, 90, "cw"));
        assertEquals("ccw", BlockRotation.rotateSpinDir(RotationNetwork.Axis.X, 180, "cw"));
        assertEquals("cw", BlockRotation.rotateSpinDir(RotationNetwork.Axis.X, 270, "ccw"));
        // Z axle (+Z = SOUTH): 90° → WEST (reversed), 180° → NORTH (reversed), 270° → EAST (kept).
        assertEquals("ccw", BlockRotation.rotateSpinDir(RotationNetwork.Axis.Z, 90, "cw"));
        assertEquals("ccw", BlockRotation.rotateSpinDir(RotationNetwork.Axis.Z, 180, "cw"));
        assertEquals("cw", BlockRotation.rotateSpinDir(RotationNetwork.Axis.Z, 270, "cw"));
        // A vertical axle never flips; a non-spin token is untouched.
        for (float yaw : new float[] {0, 90, 180, 270}) {
            assertEquals("cw", BlockRotation.rotateSpinDir(RotationNetwork.Axis.Y, yaw, "cw"));
        }
        assertEquals("idle", BlockRotation.rotateSpinDir(RotationNetwork.Axis.X, 180, "idle"));
    }

    @Test
    void preserveSpinTokenKeepsTheCapturedDirection() {
        // A CCW ratchet's landed state is rebuilt with a hard-coded direction; the captured one wins.
        assertEquals("idle_ccw_z", BlockRotation.preserveSpinToken("idle_ccw_z", "idle_cw_z", 0));
        // ...flipped when the landing reverses the axle (Z axle, 90°).
        assertEquals("idle_cw_x", BlockRotation.preserveSpinToken("idle_ccw_z", "idle_ccw_x", 90));
        // No token on either side, or null states: unchanged.
        assertEquals("idle_x", BlockRotation.preserveSpinToken("idle_z", "idle_x", 90));
        assertNull(BlockRotation.preserveSpinToken(null, null, 90));
        assertEquals("idle_x", BlockRotation.preserveSpinToken(null, "idle_x", 90));
    }

    @Test
    void rotationStepsCoverAllSixteenDirections() {
        assertEquals(0, BlockRotation.rotationToStep(BlockFace.SOUTH));
        assertEquals(4, BlockRotation.rotationToStep(BlockFace.WEST));
        assertEquals(8, BlockRotation.rotationToStep(BlockFace.NORTH));
        assertEquals(12, BlockRotation.rotationToStep(BlockFace.EAST));
        assertEquals(15, BlockRotation.rotationToStep(BlockFace.SOUTH_SOUTH_EAST));
    }

    @Test
    void axisFromStateReadsAxisAndWallSuffixes() {
        assertEquals(RotationNetwork.Axis.X, RotationNetwork.axisFromState("idle_cw_x"));
        assertEquals(RotationNetwork.Axis.Z, RotationNetwork.axisFromState("running_n"));
        assertEquals(RotationNetwork.Axis.X, RotationNetwork.axisFromState("running_e"));
        assertEquals(RotationNetwork.Axis.Y, RotationNetwork.axisFromState("idle"));
        assertEquals(RotationNetwork.Axis.Y, RotationNetwork.axisFromState("idle_y"));
    }
}
