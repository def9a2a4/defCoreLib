package anon.def9a2a4.corelib;

import org.bukkit.block.BlockFace;

/** Shared block-face constants. */
final class Faces {

    /** The six axis-aligned block faces: north, south, east, west, up, down. */
    static final BlockFace[] CARDINAL = {
        BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST,
        BlockFace.UP, BlockFace.DOWN
    };

    private Faces() {}
}
