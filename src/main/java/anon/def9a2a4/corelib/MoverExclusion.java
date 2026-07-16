package anon.def9a2a4.corelib;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A mover's "self" cells — the blocks the glue/sticky gather must never capture, because moving
 * them would tear the mover apart mid-move: its own structural hardware (piston core + rod, hoist +
 * chain column, controller head). Excluded cells behave like immovable barriers at gather time: not
 * captured, move proceeds without them, and the mover's own obstruction check decides whether it
 * can still run.
 *
 * <p>Deliberately ONLY structural cells — power components (shafts, gears, sources) are ordinary
 * cargo. Capturing one tears its network node down cleanly ({@code onBlockRemoved → removeNode})
 * and a mid-move power cut already parks the stroke at the next whole cell; and since casings bond
 * only to casings ({@link StickySpread}), grabbing a drive part is always a deliberate act (slime,
 * honey, or brush glue), never an accident of proximity.
 *
 * <p>Also deliberately SELF-only: mover A's exclusion does not cover mover B's hardware, so slime
 * in A's payload CAN capture another hoist's chain links or another piston's rod. Accepted: it is
 * contrived to build, the victim self-heals (its geometry re-derives from the world on its next
 * trigger, same as a player mining it), and a global all-movers barrier isn't worth the coupling
 * until someone actually hits this.
 */
final class MoverExclusion {

    private MoverExclusion() {}

    /** Refused-bond particle throttle: last emit tick per cell, so a permanently blocked bond on a
     *  timer-driven mover (piston/hoist re-gather every few ticks while powered) glints instead of
     *  strobing. Entries are overwritten on emit and pruned wholesale when the map grows past a
     *  bound — blocked bonds are rare, so this stays tiny in practice. */
    private static final Map<CustomBlockRegistry.LocationKey, Integer> LAST_EMIT = new HashMap<>();
    private static final int EMIT_EVERY_TICKS = 20;
    private static final int PRUNE_ABOVE = 1024;

    /** Build a mover's exclusion set from its structural cells. Always a fresh mutable set. */
    static Set<CustomBlockRegistry.LocationKey> exclusionFor(Collection<Block> hardwareCells) {
        Set<CustomBlockRegistry.LocationKey> out = new HashSet<>();
        for (Block b : hardwareCells) out.add(CustomBlockRegistry.LocationKey.of(b));
        return out;
    }

    /**
     * The standard "bond refused" cue for a gather skip: a small puff at the midpoint of the
     * shared face between {@code from} (the sticky/structure cell doing the grabbing) and
     * {@code to} (the excluded cell it failed to grab) — at {@code to}'s centre when
     * {@code from} is null (authored-glue filter skips have no adjacent grabber). Movers pass
     * this as the {@code onBlocked} callback; authoring-outline paths pass null instead.
     */
    static void blockedParticle(@Nullable Block from, Block to) {
        // Throttle per refused CELL across gathers (dedup within one gather is the caller's `seen`).
        int now = Bukkit.getCurrentTick();
        CustomBlockRegistry.LocationKey key = CustomBlockRegistry.LocationKey.of(to);
        Integer last = LAST_EMIT.get(key);
        if (last != null && now - last < EMIT_EVERY_TICKS) return;
        if (LAST_EMIT.size() > PRUNE_ABOVE) LAST_EMIT.values().removeIf(t -> now - t >= EMIT_EVERY_TICKS);
        LAST_EMIT.put(key, now);

        Location at = from == null
            ? to.getLocation().add(0.5, 0.5, 0.5)
            : to.getLocation().add(0.5, 0.5, 0.5)
                .add(from.getLocation().add(0.5, 0.5, 0.5)).multiply(0.5);
        to.getWorld().spawnParticle(Particle.WAX_OFF, at, 3, 0.1, 0.1, 0.1, 0);
    }
}
