package anon.def9a2a4.corelib;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
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
    enum Result { OK, NOT_CONNECTED, CAP_HIT, ALREADY_GLUED, IS_ANCHOR }

    private final CustomBlockRegistry registry;
    private final int maxSize;

    GlueManager(CustomBlockRegistry registry, int maxSize) {
        this.registry = registry;
        this.maxSize = maxSize;
    }

    int maxSize() { return maxSize; }

    boolean hasGlue(Anchor a) {
        int[] o = a.readOffsets();
        return o != null && o.length >= 3 && o.length % 3 == 0;
    }

    /**
     * Resolve glued offsets to currently-present world blocks, or {@code null} if the anchor has no
     * glue. Read-only: blocks that are now air, or are themselves custom rotation/power blocks, are
     * skipped (the rotator never pulls a power node into a moving structure). An empty (non-null) list
     * means "glued, but every block is gone".
     */
    @Nullable List<Block> resolveStructure(Anchor a) {
        int[] o = a.readOffsets();
        if (o == null || o.length < 3 || o.length % 3 != 0) return null;
        World w = a.world();
        Block origin = a.originBlock();
        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        List<Block> out = new ArrayList<>(o.length / 3);
        for (int i = 0; i + 2 < o.length; i += 3) {
            Block b = w.getBlockAt(ox + o[i], oy + o[i + 1], oz + o[i + 2]);
            if (b.getType().isAir()) continue;                  // block gone — skip
            if (registry.getTypeFromBlock(b) != null) continue; // never pull a custom rotation/power block
            out.add(b);
        }
        return out;
    }

    /**
     * Overwrite the glued set from an explicit block list — used by the rebind-on-disassembly hook and
     * by the authoring cuboid/single-edit commit. No connectivity check (the caller is authoritative).
     */
    void setStructure(Anchor a, List<Block> blocks) {
        a.writeOffsets(packBlocks(a, blocks));
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

    /** Authoring: add one block. Connectivity- and cap-checked. */
    Result glue(Anchor a, Block b) {
        Block origin = a.originBlock();
        Vector3i off = new Vector3i(b.getX() - origin.getX(), b.getY() - origin.getY(),
            b.getZ() - origin.getZ());
        if (off.x == 0 && off.y == 0 && off.z == 0) return Result.IS_ANCHOR;
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
     * out air, custom rotation/power blocks, the anchor cell, and already-glued cells, then grows the set
     * by fixpoint from the origin / existing glued cells until nothing more connects or the cap is hit.
     */
    FillResult glueCuboid(Anchor a, List<Block> candidates) {
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
            if (registry.getTypeFromBlock(b) != null) continue;      // never a custom rotation/power block
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
