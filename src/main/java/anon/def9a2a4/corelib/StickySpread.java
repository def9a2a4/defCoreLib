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
 * Derived auto-glue for the sticky block families.
 *
 * <ul>
 *   <li><b>Frame blocks</b> — casing / gearbox / chassis ({@code mech:casing_<wood>} etc.). One
 *       "frame" concept keyed by <b>wood</b>: a frame block AUTO-glues only to adjacent frame blocks
 *       of the SAME wood (oak casing ↔ oak gearbox ↔ oak chassis; never oak ↔ birch). A frame is NOT
 *       auto-grabbed by a plain block or the bare mover anchor — it rides only when it is the block
 *       the machine carries, when it bonds a same-wood frame that rides, when a chassis/slime grabs
 *       it, or when it is <b>manually brush-glued</b> (which is stored, and bypasses the wood gate —
 *       so a player can pin two different woods, or a frame to a plain block, together).</li>
 *   <li><b>Chassis</b> — a frame block that additionally has slime reach: it grabs every movable
 *       neighbour except honey (plain blocks, slime), but for frame targets it still respects wood
 *       (a {@code chassis_oak} ignores a {@code casing_birch}).</li>
 *   <li><b>Vanilla slime / honey</b> — grab every movable neighbour (any wood) except the opposite
 *       one; vanilla piston semantics. Unchanged.</li>
 * </ul>
 *
 * <p>A grabbed block that is itself sticky spreads onward by ITS OWN rule ({@link #bonds}). Immovable
 * neighbours are not pulled ({@link MovableBlocks#isMovable}); the mover's own obstruction check stops
 * travel if one sits in the path.
 *
 * <p>The <b>derived</b> closure (auto-glue) is computed fresh at resolution time and never stored — it
 * costs nothing, cannot be un-brushed, and self-heals. <b>Brush-glued</b> frame blocks, in contrast,
 * ARE stored as authored offsets ({@link GlueManager}) and move rigidly like any authored block —
 * two intentional models for one block type (auto = self-heal, pinned = rigid). Rebind-on-disassembly
 * re-writes only the pre-move authored offsets ({@link GlueManager#rebindTransformed}); the derived
 * closure re-derives at the landed cells on the next resolve, so casually-touching leaves are never
 * baked.
 */
final class StickySpread {

    // Frame-block id prefixes. Casing, gearbox, and chassis are one "frame" concept keyed by wood
    // (the id suffix): they AUTO-glue only to same-wood frame blocks (see bonds()). Chassis additionally
    // has slime reach (grabs plain movables). The wood suffix follows each prefix (e.g. casing_dark_oak).
    static final String CASING_ID_PREFIX = "mech:casing_";
    static final String GEARBOX_ID_PREFIX = "mech:gearbox_";
    static final String CHASSIS_ID_PREFIX = "mech:chassis_";
    static final List<String> FRAME_PREFIXES = List.of(CASING_ID_PREFIX, GEARBOX_ID_PREFIX, CHASSIS_ID_PREFIX);

    /** The sticky families. Order is meaningless; {@code null} family = not sticky. */
    enum Family { CASING, GEARBOX, CHASSIS, SLIME, HONEY }

    private StickySpread() {}

    /** True iff {@code b} is a casing (strictly — not a gearbox). Kept semantically pure; the family
     *  gate lives in {@link #familyOf}. */
    static boolean isCasing(Block b, CustomBlockRegistry registry) {
        CustomHeadBlock t = registry.getTypeFromBlock(b);
        return t != null && t.fullId().startsWith(CASING_ID_PREFIX);
    }

    /** {@code b}'s sticky family, or null for ordinary (non-sticky) blocks. Casing / gearbox / chassis
     *  are the three "frame" families; they auto-bond only same-wood (see {@link #bonds}). Chassis
     *  additionally has slime reach (grabs plain movables). */
    static @Nullable Family familyOf(Block b, CustomBlockRegistry registry) {
        Material m = b.getType();
        if (m == Material.SLIME_BLOCK) return Family.SLIME;
        if (m == Material.HONEY_BLOCK) return Family.HONEY;
        CustomHeadBlock t = registry.getTypeFromBlock(b);
        if (t != null) {
            String id = t.fullId();
            if (id.startsWith(CHASSIS_ID_PREFIX)) return Family.CHASSIS;
            if (id.startsWith(GEARBOX_ID_PREFIX)) return Family.GEARBOX;
            if (id.startsWith(CASING_ID_PREFIX)) return Family.CASING;
        }
        return null;
    }

    /** True iff sticky (any family). NB frame blocks (casing/gearbox/chassis) CAN now be brush-glued and
     *  stored; slime/honey never are. Use {@link #isFrameBlock}/{@link #isSlimeOrHoney} to split them. */
    static boolean isSticky(Block b, CustomBlockRegistry registry) {
        return familyOf(b, registry) != null;
    }

    static boolean isFrame(@Nullable Family f) {
        return f == Family.CASING || f == Family.GEARBOX || f == Family.CHASSIS;
    }

    /** A frame block (casing/gearbox/chassis) — the wood-keyed, brush-gluable family. */
    static boolean isFrameBlock(Block b, CustomBlockRegistry registry) {
        return isFrame(familyOf(b, registry));
    }

    /** Vanilla slime/honey — auto-grab only, never brush-stored. */
    static boolean isSlimeOrHoney(Block b) {
        Material m = b.getType();
        return m == Material.SLIME_BLOCK || m == Material.HONEY_BLOCK;
    }

    /** Wood key of a frame block (id suffix after its family prefix, e.g. {@code casing_dark_oak}
     *  → {@code "dark_oak"}), or null for slime/honey/plain blocks. */
    static @Nullable String woodOf(Block b, CustomBlockRegistry registry) {
        CustomHeadBlock t = registry.getTypeFromBlock(b);
        if (t == null) return null;
        String id = t.fullId();
        for (String p : FRAME_PREFIXES) if (id.startsWith(p)) return id.substring(p.length());
        return null;
    }

    /** Family rule for the grabber. Frame families all bond ANY frame here — the same-wood restriction
     *  is applied in {@link #bonds}. Chassis/slime grab everything but honey; honey everything but slime. */
    private static boolean sticksTo(Family grabber, @Nullable Family target) {
        return switch (grabber) {
            case CASING, GEARBOX -> isFrame(target);
            case CHASSIS -> target != Family.HONEY;
            case SLIME -> target != Family.HONEY;
            case HONEY -> target != Family.SLIME;
        };
    }

    /** Whether block {@code a} (family {@code fa}) bonds neighbour {@code b} (family {@code fb}): the
     *  {@link #sticksTo} family rule AND the wood gate — two FRAME blocks bond only when SAME WOOD; any
     *  pair involving a wood-less block (plain/slime/honey) is unaffected. Manual brush glue bypasses this
     *  entirely (it is stored, not derived), so a player can still glue two different woods together. */
    private static boolean bonds(Block a, Family fa, Block b, @Nullable Family fb, CustomBlockRegistry reg) {
        if (!sticksTo(fa, fb)) return false;
        if (isFrame(fa) && isFrame(fb)) {
            String wa = woodOf(a, reg), wb = woodOf(b, reg);
            return wa != null && wa.equals(wb);
        }
        return true;
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
            // A sticky frontier cell attracts what it bonds (same-wood frame, or its slime/chassis grab).
            // A NON-sticky cell — a plain authored block or the bare mover anchor — attracts ONLY
            // slime/honey, NOT frame blocks: a frame rides only as a stored member or via a
            // frame/chassis/slime grab, never because a plain block happens to sit next to it.
            Family bf = familyOf(b, registry);
            for (BlockFace f : Faces.CARDINAL) {
                Block n = b.getRelative(f);
                Family nf = familyOf(n, registry);
                if (nf == null) continue;
                if (bf == null) {
                    if (nf != Family.SLIME && nf != Family.HONEY) continue;
                } else if (!bonds(b, bf, n, nf, registry)) {
                    continue;
                }
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
                if (!bonds(b, bf, n, nf, registry)) continue;                     // family rule + wood gate
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
