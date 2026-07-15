package anon.def9a2a4.corelib;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Rail;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.type.Slab;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Anchor-owned block selection ("glue"). Stores a set of block offsets relative to an
 * {@link Anchor}'s origin in the anchor's PDC, and resolves them back to live world blocks.
 * Stateless — all persistent state lives in the anchor PDC.
 */
final class GlueManager {

    static final NamespacedKey GLUE_KEY = new NamespacedKey("corelib", "glue_offsets");

    /** Outcome of a single-block {@link #glue} authoring op. */
    enum Result { OK, NOT_CONNECTED, CAP_HIT, ALREADY_GLUED, IS_ANCHOR, AXIS_INCOMPATIBLE }

    private final int maxSize;
    // For casing detection (derived auto-glue) — see CasingExpansion. Nullable only for
    // registry-less construction in tests; derived glue is skipped without it.
    private final @Nullable CustomBlockRegistry registry;

    GlueManager(int maxSize, @Nullable CustomBlockRegistry registry) {
        this.maxSize = maxSize;
        this.registry = registry;
    }

    int maxSize() { return maxSize; }

    boolean hasGlue(Anchor a) {
        int[] o = a.readOffsets();
        return o != null && o.length >= 3 && o.length % 3 == 0;
    }

    /**
     * Resolve glued offsets to currently-present world blocks, or {@code null} if the anchor has no
     * glue. Read-only: only blocks that are now air are skipped (the block was removed). Custom blocks
     * and power components are kept — gluability was already vetted at authoring time (see {@link #glue}).
     * An empty (non-null) list means "glued, but every block is gone".
     */
    @Nullable List<Block> resolveStructure(Anchor a) {
        return resolveStructure(a, Set.of(), null);
    }

    /**
     * As {@link #resolveStructure(Anchor)}, but cells in {@code excluded} (a mover's
     * {@link MoverExclusion} set) are filtered from BOTH the resolved authored list and the derived
     * casing append. Authored offsets must be filtered too: nothing stops a player brush-gluing a
     * mover's own core or drive shaft onto its head, and the movers' shear guards only reject
     * <em>immovable</em> blocks — mover hardware is movable by design. Stored offsets are never
     * modified (rebind writes pre-move offsets verbatim; the barrier is resolve-time only).
     * {@code onBlocked} fires per filtered authored cell (null grabber → centre particle).
     */
    @Nullable List<Block> resolveStructure(Anchor a, Set<CustomBlockRegistry.LocationKey> excluded,
                                           java.util.function.@Nullable BiConsumer<Block, Block> onBlocked) {
        int[] o = a.readOffsets();
        if (o == null || o.length < 3 || o.length % 3 != 0) return null;
        World w = a.world();
        Block origin = a.originBlock();
        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        List<Block> out = new ArrayList<>(o.length / 3);
        for (int i = 0; i + 2 < o.length; i += 3) {
            Block b = w.getBlockAt(ox + o[i], oy + o[i + 1], oz + o[i + 2]);
            if (b.getType().isAir()) continue; // block gone — skip
            if (excluded.contains(CustomBlockRegistry.LocationKey.of(b))) { // mover self cell — never captured
                if (onBlocked != null) onBlocked.accept(null, b);
                continue;
            }
            out.add(b);
        }
        // Derived casing auto-glue: casings touching the structure (or the anchor) join and spread
        // casing-to-casing — computed fresh on every resolve, never stored (see CasingExpansion).
        if (registry != null && !out.isEmpty()) {
            out.addAll(CasingExpansion.derivedCasings(out, origin, registry, maxSize, excluded, onBlocked));
        }
        return out;
    }

    /**
     * Offsets of the DERIVED casing auto-glue for the authoring outline — the casings that would
     * join at resolve time but are not stored. Seeds off the authored structure AND the anchor
     * cell, so the root case (casings stacked directly on the anchor/cart, no authored glue yet)
     * shows up in the outline too.
     */
    Set<Vector3i> derivedOffsets(Anchor a) {
        Set<Vector3i> set = new LinkedHashSet<>();
        if (registry == null) return set;
        int[] o = a.readOffsets();
        Block origin = a.originBlock();
        World w = a.world();
        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        List<Block> authored = new ArrayList<>();
        if (o != null) {
            for (int i = 0; i + 2 < o.length; i += 3) {
                Block b = w.getBlockAt(ox + o[i], oy + o[i + 1], oz + o[i + 2]);
                if (!b.getType().isAir()) authored.add(b);
            }
        }
        for (Block d : CasingExpansion.derivedCasings(authored, origin, registry, maxSize)) {
            set.add(new Vector3i(d.getX() - ox, d.getY() - oy, d.getZ() - oz));
        }
        return set;
    }

    /**
     * Rebind an anchor's glue after a mechanism ride: write the PRE-MOVE authored offsets,
     * transformed by the mechanism's snapped landing rotation (landed offset = R × old offset —
     * 90°-snapped rigid moves map integer offsets to integers). Derived casing glue (casings and
     * the leaves they drag, see {@link CasingExpansion}) never enters storage this way: a rigid
     * move preserves adjacency, so it re-derives at the landed cells on the next resolve — storing
     * it would bake casually-touching neighbours into authored glue. Blocks destroyed during
     * landing linger in the stored offsets — harmless, {@link #resolveStructure} skips air.
     * No-op when {@code preMoveOffsets} is null (the anchor had no authored glue).
     */
    void rebindTransformed(Anchor a, int @Nullable [] preMoveOffsets, Matrix4f rotation) {
        if (preMoveOffsets == null) return;
        int[] out = new int[preMoveOffsets.length];
        Vector3f v = new Vector3f();
        for (int i = 0; i + 2 < preMoveOffsets.length; i += 3) {
            v.set(preMoveOffsets[i], preMoveOffsets[i + 1], preMoveOffsets[i + 2]);
            rotation.transformPosition(v);
            out[i] = Math.round(v.x);
            out[i + 1] = Math.round(v.y);
            out[i + 2] = Math.round(v.z);
        }
        a.writeOffsets(out);
    }

    /**
     * Overwrite the glued set from an explicit block list — used by the authoring cuboid/single-edit
     * commit (and the gluetest command). No connectivity check (the caller is authoritative).
     * Casings are never stored: their glue is derived fresh on every resolve (see
     * {@link CasingExpansion}). Movers rebind via {@link #rebindTransformed}, never through here —
     * the landed payload contains derived casings/leaves that must not be baked into authored glue.
     */
    void setStructure(Anchor a, List<Block> blocks) {
        List<Block> authored = registry == null ? blocks
            : blocks.stream().filter(b -> !CasingExpansion.isCasing(b, registry)).toList();
        a.writeOffsets(packBlocks(a, authored));
    }

    void unglueAll(Anchor a) { a.clearOffsets(); }

    /** Current glued offsets (for the authoring outline). Insertion-ordered. */
    Set<Vector3i> offsets(Anchor a) {
        Set<Vector3i> set = new LinkedHashSet<>();
        int[] o = a.readOffsets();
        if (o == null) return set;
        for (int i = 0; i + 2 < o.length; i += 3) set.add(new Vector3i(o[i], o[i + 1], o[i + 2]));
        return set;
    }

    /**
     * Authoring: add one block. Connectivity- and cap-checked. On a horizontal-axis (drawbridge) anchor,
     * orientation-bearing blocks are rejected — {@code BlockRotation} can only rotate about Y, so stairs/
     * slabs/etc. (and custom skulls) can't be validly represented after an X/Z rotation.
     */
    Result glue(Anchor a, Block b, boolean horizontalAxis) {
        Block origin = a.originBlock();
        Vector3i off = new Vector3i(b.getX() - origin.getX(), b.getY() - origin.getY(),
            b.getZ() - origin.getZ());
        if (off.x == 0 && off.y == 0 && off.z == 0) return Result.IS_ANCHOR;
        if (horizontalAxis && isOrientationBearing(b.getBlockData())) return Result.AXIS_INCOMPATIBLE;
        Set<Vector3i> set = offsets(a);
        if (set.contains(off)) return Result.ALREADY_GLUED;
        if (set.size() >= maxSize) return Result.CAP_HIT;
        if (!connects(off, set)) return Result.NOT_CONNECTED;
        set.add(off);
        a.writeOffsets(packOffsets(set));
        return Result.OK;
    }

    /** Result of a cuboid fill: how many cells were newly glued vs left out (disconnected or cap-blocked). */
    record FillResult(int added, int skipped) {}

    /**
     * Authoring: glue the anchor-connected subset of an explicit candidate block list (a cuboid). Filters
     * out air, the anchor cell, already-glued cells, and (on a horizontal-axis drawbridge) orientation-
     * bearing blocks; then grows the set by fixpoint from the origin / existing glued cells until nothing
     * more connects or the cap is hit.
     */
    FillResult glueCuboid(Anchor a, List<Block> candidates, boolean horizontalAxis) {
        Block origin = a.originBlock();
        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        Set<Vector3i> accepted = offsets(a);
        int before = accepted.size();
        List<Vector3i> pending = new ArrayList<>();
        for (Block b : candidates) {
            Vector3i off = new Vector3i(b.getX() - ox, b.getY() - oy, b.getZ() - oz);
            if (off.x == 0 && off.y == 0 && off.z == 0) continue;     // the anchor itself
            if (accepted.contains(off)) continue;                    // already glued
            if (b.getType().isAir()) continue;
            if (horizontalAxis && isOrientationBearing(b.getBlockData())) continue; // can't rotate on X/Z
            pending.add(off);
        }
        boolean changed = true;
        while (changed && accepted.size() < maxSize) {
            changed = false;
            var it = pending.iterator();
            while (it.hasNext()) {
                if (accepted.size() >= maxSize) break;
                Vector3i off = it.next();
                if (connects(off, accepted)) {
                    accepted.add(off);
                    it.remove();
                    changed = true;
                }
            }
        }
        a.writeOffsets(packOffsets(accepted));
        return new FillResult(accepted.size() - before, pending.size());
    }

    /** Authoring: remove one block. Returns whether it was glued. */
    boolean unglue(Anchor a, Block b) {
        Block origin = a.originBlock();
        Vector3i off = new Vector3i(b.getX() - origin.getX(), b.getY() - origin.getY(),
            b.getZ() - origin.getZ());
        Set<Vector3i> set = offsets(a);
        if (!set.remove(off)) return false;
        a.writeOffsets(packOffsets(set));
        return true;
    }

    // A candidate connects if it is cardinally adjacent to the origin (0,0,0) or to an already-glued cell.
    private static boolean connects(Vector3i off, Set<Vector3i> set) {
        if (cardinallyAdjacent(off, 0, 0, 0)) return true;
        for (Vector3i m : set) if (cardinallyAdjacent(off, m.x, m.y, m.z)) return true;
        return false;
    }

    private static boolean cardinallyAdjacent(Vector3i o, int x, int y, int z) {
        return Math.abs(o.x - x) + Math.abs(o.y - y) + Math.abs(o.z - z) == 1;
    }

    /**
     * Whether a block's appearance depends on an orientation that a Y-only {@code BlockRotation} can't
     * fix after an X/Z (drawbridge) rotation — stairs, slabs, logs, fences/panes, rails, and custom
     * skull blocks (PLAYER_HEAD is Rotatable, PLAYER_WALL_HEAD is Directional). Such blocks can only be
     * glued to a Y-axis mechanism (floor door / Y rotator).
     */
    private static boolean isOrientationBearing(BlockData bd) {
        return bd instanceof Directional
            || bd instanceof Orientable
            || bd instanceof Rotatable
            || bd instanceof MultipleFacing
            || bd instanceof Bisected
            || bd instanceof Slab
            || bd instanceof Rail;
    }

    private static int[] packBlocks(Anchor a, List<Block> blocks) {
        Block origin = a.originBlock();
        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        int[] arr = new int[blocks.size() * 3];
        int j = 0;
        for (Block b : blocks) {
            arr[j++] = b.getX() - ox;
            arr[j++] = b.getY() - oy;
            arr[j++] = b.getZ() - oz;
        }
        return arr;
    }

    private static int[] packOffsets(Set<Vector3i> set) {
        int[] arr = new int[set.size() * 3];
        int j = 0;
        for (Vector3i v : set) {
            arr[j++] = v.x;
            arr[j++] = v.y;
            arr[j++] = v.z;
        }
        return arr;
    }
}
