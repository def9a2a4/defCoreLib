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
 * Derived auto-glue for the {@code mech:casing_<wood>} family. Casings behave like vanilla slime
 * blocks: any casing touching the structure (or its anchor) joins, the bond spreads
 * casing-to-casing (6-dir, transitive; wood variants mix freely), and every payload casing drags
 * its movable cardinal neighbours along — non-casings join as <b>leaves</b> that don't propagate
 * further (vanilla slime/honey semantics). Immovable neighbours are simply not pulled
 * ({@link MovableBlocks#isMovable}); the mover's own obstruction check stops travel if one sits
 * in the path.
 *
 * <p>The closure is <b>derived at resolution time, never stored</b>: {@link GlueManager} appends it
 * in {@code resolveStructure} and unions it into the authoring outline, and the mover fallback
 * seeds run through {@link #withDerived}. Derived glue therefore costs nothing, cannot be
 * un-brushed, and self-heals — place or break a casing while unassembled and the structure follows.
 * Rebind-on-disassembly re-writes only the pre-move authored offsets
 * ({@link GlueManager#rebindTransformed}): a rigid move preserves adjacency, so every casing and
 * leaf that came along re-derives at its landed position on the next resolve.
 */
final class CasingExpansion {

    // Any id starting "mech:casing_" joins the glue family by design of this gate — the
    // casing_ prefix is reserved for glue-spreading blocks.
    static final String CASING_ID_PREFIX = "mech:casing_";

    private CasingExpansion() {}

    static boolean isCasing(Block b, CustomBlockRegistry registry) {
        CustomHeadBlock t = registry.getTypeFromBlock(b);
        return t != null && t.fullId().startsWith(CASING_ID_PREFIX);
    }

    /**
     * The derived drag for a structure: casings 6-adjacent to {@code anchorCell} or to any
     * structure cell join, the bond spreads casing-to-casing (BFS), and every payload casing
     * (structure or derived) pulls its movable non-casing cardinal neighbours in as leaves —
     * minus the cells already in {@code structure}. Total (structure + derived) is capped at
     * {@code cap}.
     */
    static List<Block> derivedCasings(Collection<Block> structure, @Nullable Block anchorCell,
                                      CustomBlockRegistry registry, int cap) {
        Set<CustomBlockRegistry.LocationKey> present = new HashSet<>();
        for (Block b : structure) present.add(CustomBlockRegistry.LocationKey.of(b));

        // Frontier: the anchor cell plus every structure cell. Any casing touching one joins (the
        // structure attracts casings); payload casings then drag their neighbours below. Only
        // casings attract here — a non-casing structure cell's neighbours are the brush's job.
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
        // Casings already in the structure (authored or seed) drag too, not just derived ones.
        for (Block b : structure) if (isCasing(b, registry)) queue.add(b);

        // Drag: every payload casing pulls its movable cardinal neighbours — casings propagate
        // (slime-through-slime), anything else joins as a non-propagating leaf.
        while (!queue.isEmpty()) {
            Block b = queue.poll();
            for (BlockFace f : Faces.CARDINAL) {
                Block n = b.getRelative(f);
                boolean casing = isCasing(n, registry);
                if (!casing && !MovableBlocks.isMovable(n, registry)) continue;   // not pulled
                if (!seen.add(CustomBlockRegistry.LocationKey.of(n))) continue;
                derived.add(n);
                if (casing) queue.add(n);
                if (present.size() + derived.size() >= cap) return derived;
            }
        }
        return derived;
    }

    /** {@code seed} plus its derived drag (casings + leaves) — for the movers' no-glue fallback seeds. */
    static List<Block> withDerived(List<Block> seed, CustomBlockRegistry registry, int cap) {
        if (seed.isEmpty()) return seed;
        List<Block> out = new ArrayList<>(seed);
        out.addAll(derivedCasings(seed, null, registry, cap));
        return out;
    }
}
