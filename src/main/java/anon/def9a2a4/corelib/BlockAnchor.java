package anon.def9a2a4.corelib;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Skull;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

import java.util.function.BooleanSupplier;

/**
 * {@link Anchor} backed by a skull custom-block (door/rotator hinge). The skull's PDC
 * auto-persists across chunk unload/reload (see {@code CustomBlockRegistry}), so the glued set
 * survives restarts for free. The skull's BlockState is re-captured on every read/write so a
 * concurrent state change (e.g. the door toggling closed→open) is never clobbered.
 */
final class BlockAnchor implements Anchor {

    private final Block block;
    private final BooleanSupplier atRest;

    BlockAnchor(Block block, BooleanSupplier atRest) {
        this.block = block;
        this.atRest = atRest;
    }

    @Override public World world() { return block.getWorld(); }

    @Override public Block originBlock() { return block; }

    @Override public Location pivot() { return block.getLocation().add(0.5, 0, 0.5); }

    @Override public boolean isAtRest() { return atRest.getAsBoolean(); }

    @Override public @Nullable int[] readOffsets() {
        if (!(block.getState() instanceof Skull skull)) return null;
        return skull.getPersistentDataContainer().get(GlueManager.GLUE_KEY, PersistentDataType.INTEGER_ARRAY);
    }

    @Override public void writeOffsets(int[] offsets) {
        if (!(block.getState() instanceof Skull skull)) return;
        skull.getPersistentDataContainer().set(GlueManager.GLUE_KEY, PersistentDataType.INTEGER_ARRAY, offsets);
        // A glue-offset write mutates only a PDC key — no appearance/blockstate/redstone change — so it
        // must not emit a BlockPhysicsEvent (rebindLandedGlue calls this mid-landing on a half-placed
        // structure, which would otherwise fire a spurious reactive recalc). update(force, applyPhysics).
        skull.update(false, false);
    }

    @Override public void clearOffsets() {
        if (!(block.getState() instanceof Skull skull)) return;
        skull.getPersistentDataContainer().remove(GlueManager.GLUE_KEY);
        skull.update(false, false);   // PDC-only change — suppress physics (see writeOffsets)
    }

    @Override public Object identityKey() { return CustomBlockRegistry.LocationKey.of(block); }
}
