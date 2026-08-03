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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

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
    // For sticky-block detection (derived auto-glue) — see StickySpread. Nullable only for
    // registry-less construction in tests; derived glue is skipped without it.
    private final @Nullable CustomBlockRegistry registry;

    // Resolves a block to its owning Anchor when the block is a glueable anchor TYPE (else null).
    // Injected post-construction because the canonical factory needs the ChainHoistManager, which is
    // built after this manager (see CoreLibPlugin). Null → transitive capture degrades to a plain
    // resolve (no nested-anchor expansion) — the safe default for registry-less test construction.
    private @Nullable AnchorFactory anchorFactory;

    /** Given a world block, its owning {@link Anchor} if the block is a glueable anchor type, else null. */
    @FunctionalInterface
    interface AnchorFactory { @Nullable Anchor of(Block block); }

    /** Result of {@link #resolveTransitive}: the captured block union, or {@code refused} to abort the
     *  move outright (a mid-stroke or non-upright hoist in the closure, or the cap exceeded). A null
     *  {@code blocks} with {@code refused == false} means "root has no glue" — the caller falls back. */
    record Transitive(java.util.@Nullable List<Block> blocks, boolean refused) {}

    GlueManager(int maxSize, @Nullable CustomBlockRegistry registry) {
        this.maxSize = maxSize;
        this.registry = registry;
    }

    void setAnchorFactory(AnchorFactory anchorFactory) { this.anchorFactory = anchorFactory; }

    int maxSize() { return maxSize; }

    boolean hasGlue(Anchor a) {
        return isValidOffsets(a.readOffsets());
    }

    /** Whether a raw offset array is well-formed glue (non-null, whole x,y,z triples). Static so callers
     *  without a GlueManager instance — e.g. BasicMechanism's carried-hoist chain-break guard — apply the
     *  exact same predicate as {@link #hasGlue}. */
    static boolean isValidOffsets(int @org.jspecify.annotations.Nullable [] o) {
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
     * sticky append. Authored offsets must be filtered too: nothing stops a player brush-gluing a
     * mover's own core or rod onto its head, and the movers' shear guards only reject
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
            // Slime/honey are never authored (the brush auto-manages them) — skip any legacy stored one;
            // they re-derive while adjacent. Frame blocks (casing/gearbox/chassis) ARE stored now: a
            // brush-glued frame is authored and rides rigidly; its same-wood auto-glue still augments via
            // the derived append below (deduped by present/seen).
            if (registry != null && StickySpread.isSlimeOrHoney(b)) continue;
            if (excluded.contains(CustomBlockRegistry.LocationKey.of(b))) { // mover self cell — never captured
                if (onBlocked != null) onBlocked.accept(null, b);
                continue;
            }
            out.add(b);
        }
        // Derived sticky auto-glue: sticky blocks touching the structure (or the anchor) join and
        // bond by their family rules — computed fresh on every resolve, never stored (StickySpread).
        if (registry != null && !out.isEmpty()) {
            out.addAll(StickySpread.derived(out, origin, registry, maxSize, excluded, onBlocked));
        }
        return out;
    }

    /**
     * Offsets of the DERIVED sticky auto-glue for the authoring outline — the sticky blocks (and
     * their leaves) that would join at resolve time but are not stored. Seeds off the authored
     * structure AND the anchor cell, so the root case (casings stacked directly on the anchor/cart,
     * no authored glue yet) shows up in the outline too.
     */
    Set<Vector3i> derivedOffsets(Anchor a) {
        return derivedFrom(a, offsets(a));
    }

    /**
     * The derived sticky closure over an explicit authored offset set, as offsets. Beyond the
     * outline, this is what lets authored glue connect THROUGH sticky blocks: everything here moves
     * with the structure at resolve time, so it all counts as connectivity for {@link #glue} /
     * {@link #glueCuboid} — brushing the plank on the far side of a casing frame works.
     */
    private Set<Vector3i> derivedFrom(Anchor a, Set<Vector3i> authoredOffsets) {
        Set<Vector3i> set = new LinkedHashSet<>();
        if (registry == null) return set;
        Block origin = a.originBlock();
        World w = a.world();
        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        List<Block> authored = new ArrayList<>(authoredOffsets.size());
        for (Vector3i v : authoredOffsets) {
            Block b = w.getBlockAt(ox + v.x, oy + v.y, oz + v.z);
            if (!b.getType().isAir()) authored.add(b);
        }
        for (Block d : StickySpread.derived(authored, origin, registry, maxSize)) {
            set.add(new Vector3i(d.getX() - ox, d.getY() - oy, d.getZ() - oz));
        }
        return set;
    }

    /**
     * Rebind an anchor's glue after a mechanism ride: write the PRE-MOVE authored offsets,
     * transformed by the mechanism's snapped landing rotation (landed offset = R × old offset —
     * 90°-snapped rigid moves map integer offsets to integers). Only AUTHORED offsets are written —
     * which now legitimately include brush-PINNED frame blocks (they ride rigidly like any authored
     * block). The DERIVED closure (auto-glue: same-wood frame bonds, slime/honey grabs and their leaves,
     * see {@link StickySpread}) still never enters storage: a rigid move preserves adjacency, so it
     * re-derives at the landed cells on the next resolve — storing it would bake casually-touching
     * neighbours. Blocks destroyed during landing linger in the stored offsets — harmless,
     * {@link #resolveStructure} skips air.
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
     * Rebind an anchor's glue to EXACTLY the cells that actually landed — the disassembly-time
     * counterpart to {@link #rebindTransformed}. Same transform (R × preMoveOffset, 90°-snapped), but:
     * <ol>
     *   <li>an offset is kept only if its landed block is in {@code placed} — a block that failed to
     *       place (off-world / protected / ghost-collision / solid-block-wins) is dropped from the glue,
     *       so glue never claims a cell that holds no block; and</li>
     *   <li>disconnection is propagated: after removing the dropped cells, any surviving offset no longer
     *       connected to the anchor (origin 0,0,0) — cardinally, through remaining glued cells OR the
     *       derived sticky closure — is pruned too (it stays in the world as a plain un-glued block; glue
     *       is metadata, so this never breaks or drops it).</li>
     * </ol>
     * Runs for EVERY disassembly (completion, chunk unload, block-break, destroy), so glue self-corrects
     * to reality whenever anything fails to place. No-op when {@code preMoveOffsets} is null.
     */
    void rebindLanded(Anchor a, int @Nullable [] preMoveOffsets, Matrix4f rotation, Set<Block> placed) {
        rebindLanded(registry, a, preMoveOffsets, rotation, placed);
    }

    /** Static form so callers without a {@link GlueManager} (the engine's per-block captured-anchor
     *  rebind in {@link BasicMechanism#disassemble}) share the identical prune. */
    static void rebindLanded(@Nullable CustomBlockRegistry registry, Anchor a,
                             int @Nullable [] preMoveOffsets, Matrix4f rotation, Set<Block> placed) {
        if (preMoveOffsets == null) return;
        Block origin = a.originBlock();
        World w = a.world();
        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        // (1) transform + (2) keep only offsets whose landed block actually placed
        Set<Vector3i> landed = new LinkedHashSet<>();
        Vector3f v = new Vector3f();
        for (int i = 0; i + 2 < preMoveOffsets.length; i += 3) {
            v.set(preMoveOffsets[i], preMoveOffsets[i + 1], preMoveOffsets[i + 2]);
            rotation.transformPosition(v);
            int x = Math.round(v.x), y = Math.round(v.y), z = Math.round(v.z);
            if (placed.contains(w.getBlockAt(ox + x, oy + y, oz + z))) landed.add(new Vector3i(x, y, z));
        }
        // (3) prune disconnection, then (4) write
        a.writeOffsets(packOffsets(pruneConnected(registry, a, landed)));
    }

    /** The subset of {@code landed} still connected to the anchor origin — cardinally, through other
     *  landed cells OR the derived sticky closure of the landed set. Origin-seeded fixpoint, the inverse
     *  of {@link #glueCuboid}'s growth. */
    private static Set<Vector3i> pruneConnected(@Nullable CustomBlockRegistry registry, Anchor a,
                                                Set<Vector3i> landed) {
        Set<Vector3i> derived = derivedFromStatic(registry, a, landed); // sticky bridges — count as connectors
        Set<Vector3i> reachable = new LinkedHashSet<>();
        List<Vector3i> pending = new ArrayList<>(landed);
        boolean changed = true;
        while (changed) {
            changed = false;
            Set<Vector3i> connectors = new LinkedHashSet<>(reachable);
            connectors.addAll(derived);
            var it = pending.iterator();
            while (it.hasNext()) {
                Vector3i off = it.next();
                if (connects(off, connectors)) { reachable.add(off); it.remove(); changed = true; }
            }
        }
        return reachable;
    }

    /** {@link #derivedFrom} without a GlueManager instance and with no size cap (the landed set is
     *  already bounded; we want the FULL sticky closure as connectors, never a truncated one). */
    private static Set<Vector3i> derivedFromStatic(@Nullable CustomBlockRegistry registry, Anchor a,
                                                   Set<Vector3i> authoredOffsets) {
        Set<Vector3i> set = new LinkedHashSet<>();
        if (registry == null) return set;
        Block origin = a.originBlock();
        World w = a.world();
        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        List<Block> authored = new ArrayList<>(authoredOffsets.size());
        for (Vector3i off : authoredOffsets) {
            Block b = w.getBlockAt(ox + off.x, oy + off.y, oz + off.z);
            if (!b.getType().isAir()) authored.add(b);
        }
        for (Block d : StickySpread.derived(authored, origin, registry, Integer.MAX_VALUE)) {
            set.add(new Vector3i(d.getX() - ox, d.getY() - oy, d.getZ() - oz));
        }
        return set;
    }

    /**
     * Resolve a mover's structure like {@link #resolveStructure}, then <b>transitively expand it</b>:
     * any captured block that is itself a glue anchor (e.g. a chain hoist glued onto a rotator)
     * contributes its own glued region — a hoist additionally brings its platform seed and chain
     * column so head + chain + platform ride as one rigid body. The blocks physically move; their
     * stored offsets are preserved and reoriented at landing by the mechanism engine (see
     * {@code MechanismRegistry.assembleCore} / {@code BasicMechanism.disassemble}).
     *
     * <p>{@code hoistAllowed} is false for a mover rotating about a horizontal axis (an X/Z drawbridge):
     * a hoist's offsets are relative to a dynamic seed that only survives an upright landing, so a
     * hoist in the closure of a tipping move is refused rather than corrupted.
     *
     * @return a {@link Transitive}: {@code blocks==null, refused==false} when the root has no glue (the
     *   caller falls back to a single seed block); {@code refused==true} to abort the move (mid-stroke
     *   or non-upright hoist, or the cap exceeded); else the deduped captured union.
     */
    Transitive resolveTransitive(Anchor root, Set<CustomBlockRegistry.LocationKey> excluded,
                                 @Nullable BiConsumer<Block, Block> onBlocked, boolean hoistAllowed) {
        List<Block> resolved = resolveStructure(root, excluded, onBlocked);
        if (resolved == null) return new Transitive(null, false); // no glue → caller falls back
        List<Block> expanded = expandNested(resolved, excluded, onBlocked, hoistAllowed);
        return new Transitive(expanded, expanded == null);
    }

    /**
     * Expand a resolved block set with the glued regions of any NESTED anchors it contains (fixpoint,
     * deduped by cell). Returns {@code null} to refuse the whole move: a nested hoist mid-stroke or
     * (when {@code !hoistAllowed}) about to be tipped off its vertical column, or the union exceeding
     * {@link #maxSize} (refuse rather than land a sheared body). With no {@link AnchorFactory} injected
     * this is a plain dedupe of the input — nested expansion is skipped (test/registry-less path).
     */
    @Nullable List<Block> expandNested(List<Block> resolved, Set<CustomBlockRegistry.LocationKey> excluded,
                                       @Nullable BiConsumer<Block, Block> onBlocked, boolean hoistAllowed) {
        Map<CustomBlockRegistry.LocationKey, Block> out = new LinkedHashMap<>();
        for (Block b : resolved) out.putIfAbsent(CustomBlockRegistry.LocationKey.of(b), b);
        if (anchorFactory == null || registry == null) return new ArrayList<>(out.values());

        Set<CustomBlockRegistry.LocationKey> visitedAnchors = new HashSet<>();
        Deque<Block> work = new ArrayDeque<>(out.values());
        while (!work.isEmpty()) {
            Block b = work.poll();
            CustomBlockRegistry.LocationKey bk = CustomBlockRegistry.LocationKey.of(b);
            if (!visitedAnchors.add(bk)) continue;
            Anchor nested = anchorFactory.of(b);
            if (nested == null) continue;
            boolean isHoist = nested instanceof HoistAnchor;
            if (!isHoist && !hasGlue(nested)) continue;

            // Hoists have no valid tipped orientation (a floor winch can't land sideways), so they are
            // never carried by a drawbridge (horizontal-axis rotator); and capturing one mid-stroke would
            // tear its own in-flight mechanism. Refuse either way, for ANY hoist — bare or loaded.
            if (isHoist && (!hoistAllowed || !nested.isAtRest())) return null;

            boolean glued = hasGlue(nested);
            List<Block> extra = new ArrayList<>();
            if (glued) {   // authored platform region (may be absent for a hoist)
                List<Block> region = resolveStructure(nested, excluded, onBlocked);
                if (region != null) extra.addAll(region);
            }
            if (isHoist) {
                // A carried hoist behaves like its own stroke: its chain column + the movable block
                // directly below it (its load/seed) always ride; authored platform glue is captured by
                // the `if (glued)` block above. resolveStructure never returns the anchor origin (the
                // seed), and the chain isn't glue, so both are added here. Matches
                // ChainHoistManager.resolveGroup ("the seed rides whenever it is movable").
                HoistAnchor h = (HoistAnchor) nested;
                List<Block> raw = new ArrayList<>(ChainHoistManager.ropeColumnFor(h.hoist(), registry));
                Block seed = h.originBlock();
                // Skip a seed that is the mover's own excluded hardware: the add-loop skips excluded
                // cells anyway, and counting one in `raw` would let a truncated family land un-refused
                // (it shifts the cap check by one). This keeps every `raw` cell "clean" (present,
                // non-air, not excluded) — the invariant the probe below relies on. (Chain links are
                // never the mover's hardware, so they're clean already.)
                if (MovableBlocks.isMovable(seed, registry)
                        && !excluded.contains(CustomBlockRegistry.LocationKey.of(seed))) {
                    raw.add(seed);
                }
                if (!raw.isEmpty()) {
                    extra.addAll(raw);
                    // Fully close the raw seed + chain's sticky family now, so the mover's trailing
                    // StickySpread.withDerived (which truncates) can't land a sheared casing frame. Probe
                    // ONE past the cap. Invariant: a family too big to fit comes back as EXACTLY
                    // maxSize+1 distinct clean cells — which alone exceed the cap, so the dedupe loop
                    // below trips `out.size() > maxSize` and refuses (regardless of overlap with `out`,
                    // since out <= maxSize < maxSize+1). A family that fits comes back whole (never hits
                    // the probe) → no truncation → no shear.
                    int probe = maxSize < Integer.MAX_VALUE ? maxSize + 1 : maxSize;
                    extra.addAll(StickySpread.derived(raw, null, registry, probe, excluded, onBlocked));
                }
            }
            for (Block nb : extra) {
                CustomBlockRegistry.LocationKey nk = CustomBlockRegistry.LocationKey.of(nb);
                if (excluded.contains(nk) || out.containsKey(nk) || nb.getType().isAir()) continue;
                out.put(nk, nb);
                if (out.size() > maxSize) return null; // cap exceeded → refuse, don't shear
                work.add(nb);
            }
        }
        return new ArrayList<>(out.values());
    }

    /**
     * Overwrite the glued set from an explicit block list — used by the authoring cuboid/single-edit
     * commit (and the gluetest command). No connectivity check (the caller is authoritative).
     * Only slime/honey are dropped (their glue is derived fresh every resolve, see {@link StickySpread});
     * frame blocks (casing/gearbox/chassis) ARE stored — a brush-pinned frame rides rigidly. Movers rebind
     * via {@link #rebindTransformed}, never through here — the landed payload still contains the DERIVED
     * closure (slime/honey grabs + auto-glued same-wood extras) which must not be baked into authored glue.
     */
    void setStructure(Anchor a, List<Block> blocks) {
        List<Block> authored = registry == null ? blocks
            : blocks.stream().filter(b -> !StickySpread.isSlimeOrHoney(b)).toList();
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
     * Authoring: add one block. Connectivity- and cap-checked; the derived closure counts as
     * connectivity (a same-wood frame bridges authored glue — it rides at resolve, so brushing a block
     * on its far side is connected). NB since plain/anchor cells no longer auto-attract frames, a frame
     * bridges connectivity only once ONE of its blocks is glued adjacent to the anchor (or is the
     * mount seed). On a horizontal-axis (drawbridge) anchor, orientation-bearing blocks are rejected —
     * {@code BlockRotation} only rotates about Y — EXCEPT frame blocks, which re-pin their fixed
     * base_block_data on landing and so ride any axis.
     */
    Result glue(Anchor a, Block b, boolean horizontalAxis) {
        Block origin = a.originBlock();
        Vector3i off = new Vector3i(b.getX() - origin.getX(), b.getY() - origin.getY(),
            b.getZ() - origin.getZ());
        if (off.x == 0 && off.y == 0 && off.z == 0) return Result.IS_ANCHOR;
        // Frame blocks are orientation-bearing stairs, but they re-pin their fixed base_block_data on
        // landing (BasicMechanism), so they ride a drawbridge fine — exempt by TYPE, not by loosening
        // the shared BlockData predicate (which other stair/slab custom blocks depend on).
        if (horizontalAxis && isOrientationBearing(b.getBlockData())
                && !(registry != null && StickySpread.isFrameBlock(b, registry))) {
            return Result.AXIS_INCOMPATIBLE;
        }
        Set<Vector3i> set = offsets(a);
        if (set.contains(off)) return Result.ALREADY_GLUED;
        if (set.size() >= maxSize) return Result.CAP_HIT;
        Set<Vector3i> connectors = new LinkedHashSet<>(set);
        connectors.addAll(derivedFrom(a, set));
        if (!connects(off, connectors)) return Result.NOT_CONNECTED;
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
     * more connects or the cap is hit. The derived sticky closure counts as connectivity, and is
     * recomputed per pass — an accepted block can attract a new casing frame that in turn bridges to
     * candidates on its far side.
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
            if (horizontalAxis && isOrientationBearing(b.getBlockData())
                    && !(registry != null && StickySpread.isFrameBlock(b, registry))) continue; // can't rotate on X/Z
            pending.add(off);
        }
        boolean changed = true;
        while (changed && accepted.size() < maxSize) {
            changed = false;
            Set<Vector3i> connectors = new LinkedHashSet<>(accepted);
            connectors.addAll(derivedFrom(a, accepted));
            var it = pending.iterator();
            while (it.hasNext()) {
                if (accepted.size() >= maxSize) break;
                Vector3i off = it.next();
                if (connects(off, connectors)) {
                    accepted.add(off);
                    connectors.add(off);   // accepted cells connect immediately; new derived waits a pass
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
