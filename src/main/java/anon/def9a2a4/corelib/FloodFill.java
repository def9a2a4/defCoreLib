package anon.def9a2a4.corelib;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Shared 6-neighbour BFS flood-fill used by the mechanism block-selection consumers
 * (doors, rotators, mechanism minecarts). Extracted from three near-identical loops so
 * there is one canonical traversal. Cardinal faces live in {@link Faces#CARDINAL}.
 */
final class FloodFill {

    private FloodFill() {}

    /**
     * Collects the connected component of blocks reachable from {@code seed} through cardinal
     * neighbours that satisfy {@code accept}, capped at {@code max} blocks.
     *
     * @param seed        the origin block.
     * @param includeSeed if {@code true} the seed itself is a candidate (tested by {@code accept}
     *                    and included if it passes); if {@code false} the seed is excluded and only
     *                    its neighbours are seeded (the "anchor block is not part of the structure"
     *                    case used by skull hinges).
     * @param accept      membership test for a block (material / allow-list / custom-block exclusion).
     * @param max         hard cap on the number of collected blocks.
     * @param onCap       run once if the cap was hit while candidates remained (truncation feedback);
     *                    may be {@code null}.
     */
    static List<Block> component(Block seed, boolean includeSeed, Predicate<Block> accept,
                                 int max, Runnable onCap) {
        Set<Block> visited = new HashSet<>();
        Queue<Block> queue = new ArrayDeque<>();
        if (includeSeed) {
            queue.add(seed);
        } else {
            visited.add(seed); // exclude the anchor block itself
            for (BlockFace face : Faces.CARDINAL) queue.add(seed.getRelative(face));
        }
        List<Block> result = new ArrayList<>();
        while (!queue.isEmpty() && result.size() < max) {
            Block b = queue.poll();
            if (!visited.add(b)) continue;
            if (!accept.test(b)) continue;
            result.add(b);
            for (BlockFace face : Faces.CARDINAL) queue.add(b.getRelative(face));
        }
        if (result.size() >= max && !queue.isEmpty() && onCap != null) onCap.run();
        return result;
    }
}
