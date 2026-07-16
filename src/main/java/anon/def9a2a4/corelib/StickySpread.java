package anon.def9a2a4.corelib;

import org.bukkit.Material;
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
import java.util.function.BiConsumer;

/**
 * Derived auto-glue for the sticky block families. Three families, each with its own reach:
 *
 * <ul>
 *   <li><b>Casings</b> ({@code mech:casing_<wood>}) — structural connectors: a casing bonds ONLY to
 *       other casings (wood variants mix freely). They frame a contraption but never grab
 *       machinery or world blocks — that is deliberate; attach anything else with slime, honey,
 *       or the glue brush.</li>
 *   <li><b>Vanilla slime</b> — grabs every movable neighbour (world blocks and custom blocks
 *       alike) EXCEPT honey, propagating slime-to-slime. Vanilla piston semantics.</li>
 *   <li><b>Vanilla honey</b> — the mirror: grabs everything movable except slime.</li>
 * </ul>
 *
 * <p>A grabbed block that is itself sticky spreads onward by ITS OWN family rule — slime grabbing
 * a casing brings that casing's whole casing-frame along. Immovable neighbours are simply not
 * pulled ({@link MovableBlocks#isMovable}); the mover's own obstruction check stops travel if one
 * sits in the path.
 *
 * <p>The closure is <b>derived at resolution time, never stored</b>: {@link GlueManager} appends it
 * in {@code resolveStructure} and unions it into the authoring outline, and the mover fallback
 * seeds run through {@link #withDerived}. Derived glue therefore costs nothing, cannot be
 * un-brushed, and self-heals — place or break a sticky block while unassembled and the structure
 * follows. Rebind-on-disassembly re-writes only the pre-move authored offsets
 * ({@link GlueManager#rebindTransformed}): a rigid move preserves adjacency, so every sticky block
 * and leaf that came along re-derives at its landed position on the next resolve.
 */
final class StickySpread {

    // Any id starting "mech:casing_" joins the casing family by design of this gate — the
    // casing_ prefix is reserved for frame-bonding blocks.
    static final String CASING_ID_PREFIX = "mech:casing_";

    /** The sticky families. Order is meaningless; {@code null} family = not sticky. */
    enum Family { CASING, SLIME, HONEY }

    private StickySpread() {}

    static boolean isCasing(Block b, CustomBlockRegistry registry) {
        CustomHeadBlock t = registry.getTypeFromBlock(b);
        return t != null && t.fullId().startsWith(CASING_ID_PREFIX);
    }

    /** {@code b}'s sticky family, or null for ordinary (non-sticky) blocks. */
    static @Nullable Family familyOf(Block b, CustomBlockRegistry registry) {
        Material m = b.getType();
        if (m == Material.SLIME_BLOCK) return Family.SLIME;
        if (m == Material.HONEY_BLOCK) return Family.HONEY;
        return isCasing(b, registry) ? Family.CASING : null;
    }

    /** Sticky blocks are never brush-glued or stored — their bond derives fresh at every resolve. */
    static boolean isSticky(Block b, CustomBlockRegistry registry) {
        return familyOf(b, registry) != null;
    }

    /** May a {@code grabber}-family block bond to a neighbour of family {@code target}?
     *  Casings bond only to casings; slime grabs everything but honey; honey everything but slime. */
    private static boolean sticksTo(Family grabber, @Nullable Family target) {
        return switch (grabber) {
            case CASING -> target == Family.CASING;
            case SLIME -> target != Family.HONEY;
            case HONEY -> target != Family.SLIME;
        };
    }

    /**
     * The derived drag for a structure: sticky blocks 6-adjacent to {@code anchorCell} or to any
     * structure cell join (the structure attracts them), then every sticky payload block bonds its
     * neighbours by its family rule (BFS) — sticky neighbours propagate onward, ordinary movable
     * neighbours join as non-propagating leaves — minus the cells already in {@code structure}.
     * Total (structure + derived) is capped at {@code cap}.
     */
    static List<Block> derived(Collection<Block> structure, @Nullable Block anchorCell,
                               CustomBlockRegistry registry, int cap) {
        return derived(structure, anchorCell, registry, cap, Set.of(), null);
    }

    /**
     * As {@link #derived(Collection, Block, CustomBlockRegistry, int)}, but cells in
     * {@code excluded} are barriers: never captured. Movers MUST pass their
     * {@link MoverExclusion} set here or the spread pulls along the mover's own structural
     * blocks (core/rod/hoist/head) — a captured core is aired out and then silently consumed by
     * its own protected cell. {@code onBlocked} fires once per refused bond,
     * (grabbing cell, excluded cell) — movers pass {@link MoverExclusion#blockedParticle};
     * repeat-render paths (authoring outline) pass null.
     */
    static List<Block> derived(Collection<Block> structure, @Nullable Block anchorCell,
                               CustomBlockRegistry registry, int cap,
                               Set<CustomBlockRegistry.LocationKey> excluded,
                               @Nullable BiConsumer<Block, Block> onBlocked) {
        Set<CustomBlockRegistry.LocationKey> present = new HashSet<>();
        for (Block b : structure) present.add(CustomBlockRegistry.LocationKey.of(b));

        // Frontier: the anchor cell plus every structure cell. Any sticky block touching one joins
        // (the structure attracts sticky blocks); payload sticky blocks then bond their neighbours
        // below. Only sticky blocks attract here — a non-sticky structure cell's ordinary
        // neighbours are the brush's job.
        Set<CustomBlockRegistry.LocationKey> seen = new HashSet<>(present);
        Deque<Block> queue = new ArrayDeque<>();
        List<Block> derived = new ArrayList<>();
        Set<Block> frontier = new LinkedHashSet<>();
        if (anchorCell != null) frontier.add(anchorCell);
        frontier.addAll(structure);
        for (Block b : frontier) {
            // A sticky frontier cell attracts only what it sticks to (a slime seed must not attract
            // honey); non-sticky structure cells attract every family — the structure attracts sticky.
            Family bf = familyOf(b, registry);
            for (BlockFace f : Faces.CARDINAL) {
                Block n = b.getRelative(f);
                Family nf = familyOf(n, registry);
                if (nf == null) continue;
                if (bf != null && !sticksTo(bf, nf)) continue;
                CustomBlockRegistry.LocationKey nk = CustomBlockRegistry.LocationKey.of(n);
                if (excluded.contains(nk)) {                       // mover self cell — bond refused
                    if (onBlocked != null && seen.add(nk)) onBlocked.accept(b, n);
                    continue;
                }
                if (!seen.add(nk)) continue;
                if (present.size() + derived.size() >= cap) return derived;   // cap is a hard ceiling
                derived.add(n);
                queue.add(n);
            }
        }
        // Sticky blocks already in the structure (authored or seed) bond too, not just derived ones.
        for (Block b : structure) if (familyOf(b, registry) != null) queue.add(b);

        // Bond: every sticky payload block pulls the neighbours its family rule allows — sticky
        // neighbours propagate onward by their own rule, anything else joins as a leaf.
        while (!queue.isEmpty()) {
            Block b = queue.poll();
            Family bf = familyOf(b, registry);
            if (bf == null) continue;   // unreachable — queue only ever holds sticky blocks
            for (BlockFace f : Faces.CARDINAL) {
                Block n = b.getRelative(f);
                Family nf = familyOf(n, registry);
                if (!sticksTo(bf, nf)) continue;                                  // family rule
                if (nf == null && !MovableBlocks.isMovable(n, registry)) continue; // leaf not pulled
                CustomBlockRegistry.LocationKey nk = CustomBlockRegistry.LocationKey.of(n);
                if (excluded.contains(nk)) {                       // mover self cell — bond refused
                    if (onBlocked != null && seen.add(nk)) onBlocked.accept(b, n);
                    continue;
                }
                if (!seen.add(nk)) continue;
                if (present.size() + derived.size() >= cap) return derived;   // cap is a hard ceiling
                derived.add(n);
                if (nf != null) queue.add(n);
            }
        }
        return derived;
    }

    /** {@code seed} plus its derived drag (sticky blocks + leaves) — for the movers' no-glue fallback seeds. */
    static List<Block> withDerived(List<Block> seed, CustomBlockRegistry registry, int cap) {
        return withDerived(seed, registry, cap, Set.of(), null);
    }

    /** As {@link #withDerived(List, CustomBlockRegistry, int)} with an exclusion barrier — see
     *  {@link #derived(Collection, Block, CustomBlockRegistry, int, Set, BiConsumer)}. */
    static List<Block> withDerived(List<Block> seed, CustomBlockRegistry registry, int cap,
                                   Set<CustomBlockRegistry.LocationKey> excluded,
                                   @Nullable BiConsumer<Block, Block> onBlocked) {
        if (seed.isEmpty()) return seed;
        List<Block> out = new ArrayList<>(seed);
        out.addAll(derived(seed, null, registry, cap, excluded, onBlocked));
        return out;
    }
}
