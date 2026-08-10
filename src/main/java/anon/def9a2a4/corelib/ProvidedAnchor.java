package anon.def9a2a4.corelib;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.joml.Vector3i;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * {@link Anchor} adapter for a foreign plugin's {@link ExternalAnchor}. Storage is delegated to a
 * composed {@link BlockAnchor} — which needs nothing but a {@code Skull} state, so it works verbatim
 * on a plain {@code PLAYER_HEAD} with no custom-block identity — while at-rest, permission,
 * connectivity and prune policy come from the owner.
 *
 * <p>Composing rather than subclassing mirrors {@link HoistAnchor}, which does the same for its
 * dynamic seed-relative origin.
 */
final class ProvidedAnchor implements Anchor {

    private final ExternalAnchor external;
    private final BlockAnchor storage;

    ProvidedAnchor(ExternalAnchor external) {
        this.external = external;
        // atRest is answered by us, not the composed anchor — BlockAnchor's own supplier is unused.
        this.storage = new BlockAnchor(external.originBlock(), () -> true);
    }

    /** The owner's view, for callers that need to re-ask it (e.g. the nested-capture at-rest gate). */
    ExternalAnchor external() { return external; }

    @Override public World world() { return external.originBlock().getWorld(); }

    @Override public Block originBlock() { return external.originBlock(); }

    @Override public Location pivot() { return external.originBlock().getLocation().add(0.5, 0, 0.5); }

    @Override public boolean isAtRest() { return external.isAtRest(); }

    @Override public @Nullable int[] readOffsets() { return storage.readOffsets(); }

    @Override public void writeOffsets(int[] offsets) { storage.writeOffsets(offsets); }

    @Override public void clearOffsets() { storage.clearOffsets(); }

    // Same key type and derivation as BlockAnchor: one anchor per block cell, so a ship wheel can
    // never collide with an engine anchor (they would have to be the same block).
    @Override public Object identityKey() { return CustomBlockRegistry.LocationKey.of(external.originBlock()); }

    @Override public boolean canAuthor(Player player) { return external.canAuthor(player); }

    @Override public boolean canAuthorCell(Player player, Block cell) {
        return external.canAuthorCell(player, cell);
    }

    @Override public boolean prunesOnLanding() { return external.prunesOnLanding(); }

    @Override public Set<Vector3i> extraConnectors() {
        Set<Block> blocks = external.connectorBlocks();
        if (blocks.isEmpty()) return Set.of();
        Block origin = external.originBlock();
        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        Set<Vector3i> out = new LinkedHashSet<>(Math.max(16, blocks.size() * 2));
        for (Block b : blocks) {
            out.add(new Vector3i(b.getX() - ox, b.getY() - oy, b.getZ() - oz));
        }
        return out;
    }
}
