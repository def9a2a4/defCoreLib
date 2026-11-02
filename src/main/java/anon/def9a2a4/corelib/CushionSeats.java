package anon.def9a2a4.corelib;

import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Shulker;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Public view of the 26.3 cushion for mechanism consumers (BlockShips seats). Every method is safe on servers
 * without cushions: there, nothing is ever a cushion and the maps are empty.
 *
 * <p>How the engine carries one: at assembly a cushion resting on a captured block is mounted on that block's
 * collider shulker (or the one below, if the block has no collider), rider and all, and steps back onto the
 * landed block on disassembly. A consumer that seats players on shulkers itself can find the cushion riding a
 * seat with {@link #carriedCushionOn}, and move the player between it and the shulker.
 */
@ApiStatus.Experimental
public final class CushionSeats {

    private CushionSeats() {}

    /** True when the server has cushions at all (26.3+). */
    public static boolean available() { return CushionCompat.available(); }

    public static boolean isCushion(@Nullable Entity e) { return CushionCompat.isCushion(e); }

    /** True for a cushion the engine is carrying on a mechanism collider. */
    public static boolean isCarried(@Nullable Entity e) {
        return CushionCompat.isCushion(e) && AttachedEntities.carriedTagOf(e) != null;
    }

    /** The carried cushion riding {@code seat}, or null. */
    public static @Nullable Entity carriedCushionOn(@Nullable Shulker seat) {
        if (seat == null) return null;
        for (Entity p : seat.getPassengers()) {
            if (isCarried(p)) return p;
        }
        return null;
    }

    /** The block a (resting, not carried) cushion sits on, matching vanilla's own support test; or null. */
    public static @Nullable Block anchorOf(Entity cushion) {
        return CushionCompat.isCushion(cushion) ? AttachedEntities.cushionAnchor(cushion) : null;
    }

    /**
     * The resting cushions whose support block is in {@code blocks}, keyed by that block (first one wins if two
     * share a block). Empty on servers without cushions.
     */
    public static Map<Block, Entity> cushionsOn(Collection<Block> blocks) {
        Map<Block, Entity> out = new HashMap<>();
        if (!CushionCompat.available() || blocks.isEmpty()) return out;
        BoundingBox region = null;
        for (Block b : blocks) {
            BoundingBox bb = BoundingBox.of(b);
            if (region == null) region = bb; else region.union(bb);
        }
        region.expand(0, 0, 0, 0, 2, 0); // a cushion on a 1.5-tall fence sits in the cell above it
        java.util.Set<Block> set = new java.util.HashSet<>(blocks);
        Block first = blocks.iterator().next();
        for (Entity e : first.getWorld().getNearbyEntities(region)) {
            if (!CushionCompat.isCushion(e) || e.getVehicle() != null) continue;
            Block anchor = AttachedEntities.cushionAnchor(e);
            if (anchor != null && set.contains(anchor)) out.putIfAbsent(anchor, e);
        }
        return out;
    }
}
