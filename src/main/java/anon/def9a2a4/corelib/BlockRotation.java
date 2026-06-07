package anon.def9a2a4.corelib;

import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;

import java.util.HashSet;
import java.util.Set;

/**
 * Rotates BlockData properties by a yaw angle (must be a multiple of 90°).
 * Handles Directional, Orientable, Rotatable, and MultipleFacing block data.
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

    private static BlockFace rotateBlockFace(BlockFace face, float yawDegrees) {
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

    private static int rotationToStep(BlockFace face) {
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
}
