package anon.def9a2a4.corelib;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Derived auto-glue for {@code mech:casing} blocks. Casings glue themselves: any casing touching
 * the structure (or its anchor) joins, and the bond spreads casing-to-casing (6-dir, transitive).
 * Non-casing neighbours are never dragged — the casing graph defines the free structure; everything
 * else is authored with the brush.
 *
 * <p>The closure is <b>derived at resolution time, never stored</b>: {@link GlueManager} appends it
 * in {@code resolveStructure} and unions it into the authoring outline, and the mover fallback
 * seeds run through {@link #withDerived}. Derived glue therefore costs nothing, cannot be
 * un-brushed, and self-heals — place or break a casing while unassembled and the structure follows.
 * Rebind-on-disassembly stores only non-casing blocks: a rigid transform preserves adjacency, so
 * every casing that came along re-derives at its landed position on the next resolve.
 */
final class CasingExpansion {

    static final String CASING_ID = "mech:casing";

    private CasingExpansion() {}

    static boolean isCasing(Block b, CustomBlockRegistry registry) {
        CustomHeadBlock t = registry.getTypeFromBlock(b);
        return t != null && CASING_ID.equals(t.fullId());
    }

    /**
     * The derived casings for a structure: casings 6-adjacent to {@code anchorCell} or to any
     * structure cell, expanded casing-to-casing (BFS) — minus the cells already in
     * {@code structure}. Total (structure + derived) is capped at {@code cap}.
     */
    static List<Block> derivedCasings(Collection<Block> structure, @Nullable Block anchorCell,
                                      CustomBlockRegistry registry, int cap) {
        Set<CustomBlockRegistry.LocationKey> present = new HashSet<>();
        for (Block b : structure) present.add(CustomBlockRegistry.LocationKey.of(b));

        // Frontier: the anchor cell plus every structure cell. Any casing touching one joins (the
        // structure attracts casings); joined casings then spread the bond casing-to-casing below.
        // Non-casing neighbours of a casing never join — only the brush authors those.
        Set<CustomBlockRegistry.LocationKey> seen = new HashSet<>(present);
        Deque<Block> queue = new ArrayDeque<>();
        List<Block> derived = new ArrayList<>();
        Set<Block> frontier = new LinkedHashSet<>();
        if (anchorCell != null) frontier.add(anchorCell);
        frontier.addAll(structure);
        for (Block b : frontier) {
            for (BlockFace f : Faces.CARDINAL) {
                Block n = b.getRelative(f);
                if (!isCasing(n, registry)) continue;
                if (!seen.add(CustomBlockRegistry.LocationKey.of(n))) continue;
                derived.add(n);
                queue.add(n);
                if (present.size() + derived.size() >= cap) return derived;
            }
        }

        // Spread casing-to-casing.
        while (!queue.isEmpty()) {
            Block b = queue.poll();
            for (BlockFace f : Faces.CARDINAL) {
                Block n = b.getRelative(f);
                if (!isCasing(n, registry)) continue;
                if (!seen.add(CustomBlockRegistry.LocationKey.of(n))) continue;
                derived.add(n);
                queue.add(n);
                if (present.size() + derived.size() >= cap) return derived;
            }
        }
        return derived;
    }

    /** {@code seed} plus its derived casings — for the movers' no-glue fallback seeds. */
    static List<Block> withDerived(List<Block> seed, CustomBlockRegistry registry, int cap) {
        if (seed.isEmpty()) return seed;
        List<Block> out = new ArrayList<>(seed);
        out.addAll(derivedCasings(seed, null, registry, cap));
        return out;
    }
}
