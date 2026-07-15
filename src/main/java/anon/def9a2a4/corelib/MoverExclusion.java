package anon.def9a2a4.corelib;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * A powered mover's "self" cells — the blocks the glue/casing gather must never capture,
 * because moving them would tear the mover apart mid-move: its own structural hardware
 * (core/rod, hoist/rope, controller head) plus its whole drive train (every node in its
 * {@link RotationNetwork} component, and the network's passive windmill sources, which are
 * boundary blocks rather than graph nodes). Excluded cells behave like immovable barriers
 * at gather time: not captured, move proceeds without them, and the mover's own obstruction
 * check decides whether it can still run.
 *
 * <p>Deliberately identity-based, not type-based: a rotation part that is NOT wired to the
 * driver is a different network and still rides along — "rotation parts work on mechanisms"
 * is load-bearing (see {@link MovableBlocks}'s javadoc for the type-flag mistake this avoids).
 */
final class MoverExclusion {

    private MoverExclusion() {}

    /**
     * Build a mover's exclusion set: {@code hardwareCells} ∪ the drive train of
     * {@code driverKey} (network members + passive sources). Always a fresh mutable set —
     * {@code getNetworkMembers} returns the network's live internal set, which must never
     * be mutated or aliased.
     */
    static Set<CustomBlockRegistry.LocationKey> exclusionFor(RotationNetwork network,
            CustomBlockRegistry.@Nullable LocationKey driverKey, Collection<Block> hardwareCells) {
        Set<CustomBlockRegistry.LocationKey> out = new HashSet<>();
        for (Block b : hardwareCells) out.add(CustomBlockRegistry.LocationKey.of(b));
        if (driverKey != null) {
            Set<CustomBlockRegistry.LocationKey> members = network.getNetworkMembers(driverKey);
            if (members != null) out.addAll(members);
            out.addAll(network.getNetworkPassiveSources(driverKey));
            out.add(driverKey);   // belt-and-braces: an unregistered/mid-recalc driver still excludes itself
        }
        return out;
    }

    /**
     * The standard "bond refused" cue for a gather skip: a small puff at the midpoint of the
     * shared face between {@code from} (the casing/structure cell doing the grabbing) and
     * {@code to} (the excluded cell it failed to grab) — at {@code to}'s centre when
     * {@code from} is null (authored-glue filter skips have no adjacent grabber). Movers pass
     * this as the {@code onBlocked} callback; authoring-outline paths pass null instead.
     */
    static void blockedParticle(@Nullable Block from, Block to) {
        Location at = from == null
            ? to.getLocation().add(0.5, 0.5, 0.5)
            : to.getLocation().add(0.5, 0.5, 0.5)
                .add(from.getLocation().add(0.5, 0.5, 0.5)).multiply(0.5);
        to.getWorld().spawnParticle(Particle.WAX_OFF, at, 3, 0.1, 0.1, 0.1, 0);
    }
}
