package anon.def9a2a4.corelib;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Slime-style propagation for {@code mech:casing} blocks. A casing that ends up in a moving payload
 * drags its cardinal neighbours along, transitively through other casings — exactly like a vanilla
 * slime block. Non-casing neighbours are pulled in but are <b>leaves</b> (they don't propagate
 * further), matching vanilla slime/honey semantics.
 *
 * <p>Every mechanism payload collector (piston, rotator, door, minecart, chain hoist) runs its
 * resolved glue set through {@link #expand} before assembling, so a casing blob moves as one unit
 * with no brush authoring. Immovable neighbours are simply not pulled ({@link MovableBlocks#isMovable}
 * gate); the mover's own obstruction check stops travel if one sits in the path.
 */
final class CasingExpansion {

    static final String CASING_ID = "mech:casing";

    private static final BlockFace[] CARDINALS = {
        BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private CasingExpansion() {}

    /**
     * Expand {@code seed} through casing blocks. Returns a new list containing the seed plus every
     * movable block dragged in by a casing (BFS, capped at {@code cap} total blocks). The seed blocks
     * come first and their order is preserved. A {@code null} or empty seed returns an empty list.
     */
    static List<Block> expand(Collection<Block> seed, CustomBlockRegistry registry, int cap) {
        List<Block> out = new ArrayList<>();
        if (seed == null || seed.isEmpty()) return out;
        Set<CustomBlockRegistry.LocationKey> seen = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        for (Block b : seed) {
            if (seen.add(CustomBlockRegistry.LocationKey.of(b))) { out.add(b); queue.add(b); }
        }
        while (!queue.isEmpty() && out.size() < cap) {
            Block b = queue.poll();
            if (!isCasing(b, registry)) continue;                 // only casings drag their neighbours
            for (BlockFace f : CARDINALS) {
                if (out.size() >= cap) break;
                Block n = b.getRelative(f);
                if (!seen.add(CustomBlockRegistry.LocationKey.of(n))) continue;
                if (!MovableBlocks.isMovable(n, registry)) continue;   // skip air/fluid/immovable
                out.add(n);
                queue.add(n);                                     // a pulled casing propagates further
            }
        }
        return out;
    }

    private static boolean isCasing(Block b, CustomBlockRegistry registry) {
        CustomHeadBlock t = registry.getTypeFromBlock(b);
        return t != null && CASING_ID.equals(t.fullId());
    }
}
