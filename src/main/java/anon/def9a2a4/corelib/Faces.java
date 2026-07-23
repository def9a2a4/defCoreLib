package anon.def9a2a4.corelib;

import org.bukkit.block.BlockFace;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/** Shared block-face constants and orientation helpers. */
final class Faces {

    /** The six axis-aligned block faces: north, south, east, west, up, down. */
    static final BlockFace[] CARDINAL = {
        BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST,
        BlockFace.UP, BlockFace.DOWN
    };

    /** Identity rotation (fresh instance per call so callers can store it without aliasing). */
    static AxisAngle4f identity() { return new AxisAngle4f(0f, 0f, 0f, 1f); }

    /**
     * Rotation mapping a display model's +Z (front) face onto {@code facing}. Horizontal faces rotate
     * about +Y only, so the art stays upright and faces out of each wall distinctly (no 180° roll);
     * UP/DOWN tip the front skyward/floorward about X. Shared by every head/eye disguise that orients
     * to a block's facing (redstone dynamo, mechanical dispenser, …).
     */
    static AxisAngle4f rotationForFace(BlockFace facing) {
        float h = (float) (Math.PI / 2), p = (float) Math.PI;
        return switch (facing) {
            case SOUTH -> identity();                       // +Z → +Z
            case NORTH -> new AxisAngle4f(p, 0f, 1f, 0f);   // +Z → -Z
            case EAST  -> new AxisAngle4f(h, 0f, 1f, 0f);   // +Z → +X
            case WEST  -> new AxisAngle4f(-h, 0f, 1f, 0f);  // +Z → -X
            case UP    -> new AxisAngle4f(-h, 1f, 0f, 0f);  // +Z → +Y (front skyward)
            case DOWN  -> new AxisAngle4f(h, 1f, 0f, 0f);   // +Z → -Y (front floorward)
            default    -> identity();
        };
    }

    /** Rotate {@code v} by {@code r}, returning a new vector (does not mutate {@code v} or {@code r}). */
    static Vector3f rotate(AxisAngle4f r, Vector3f v) {
        return r.transform(new Vector3f(v));
    }

    private Faces() {}
}
