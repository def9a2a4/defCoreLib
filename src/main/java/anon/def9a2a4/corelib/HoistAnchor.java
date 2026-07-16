package anon.def9a2a4.corelib;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.jspecify.annotations.Nullable;

import java.util.function.BooleanSupplier;

/**
 * {@link Anchor} for a chain hoist's hanging platform. The PDC lives on the HOIST skull (the
 * composed {@link BlockAnchor}) because the platform seed is normally a plain block with nowhere
 * to persist — but offsets stay relative to {@link #originBlock()}: the block below the chain end,
 * below the hoist itself at extension 0. The origin is <b>dynamic</b>, recomputed from the live
 * column on every call ({@link ChainHoistManager#platformSeed}), exactly as the hoist re-derives
 * its depth per trigger — no second copy of the truth.
 *
 * <p><b>No landing rebind, ever</b> (contrast every other mover's {@code rebindTransformed}): a
 * stroke is a whole-block Y translation of seed and platform together, and its ghost links land as
 * real chain before the disassembly hook runs, so a later scan always finds the landed seed and
 * seed-relative offsets are invariant. A seed block broken after authoring self-heals — offsets
 * resolve to air and are skipped — though a chain link added or mined by hand shifts the origin
 * and with it what the stored offsets mean; {@link GlueAuthoring} closes open sessions on such
 * drift.
 */
final class HoistAnchor implements Anchor {

    private final Block hoist;
    private final CustomBlockRegistry registry;
    private final BooleanSupplier atRest;
    /** PDC storage on the hoist skull — same GLUE_KEY, zero duplicated persistence code. */
    private final BlockAnchor pdc;

    HoistAnchor(Block hoist, CustomBlockRegistry registry, BooleanSupplier atRest) {
        this.hoist = hoist;
        this.registry = registry;
        this.atRest = atRest;
        this.pdc = new BlockAnchor(hoist, () -> true);
    }

    @Override public World world() { return hoist.getWorld(); }

    @Override public Block originBlock() { return ChainHoistManager.platformSeed(hoist, registry); }

    @Override public Location pivot() { return originBlock().getLocation().add(0.5, 0, 0.5); }

    @Override public boolean isAtRest() { return atRest.getAsBoolean(); }

    @Override public @Nullable int[] readOffsets() { return pdc.readOffsets(); }

    @Override public void writeOffsets(int[] offsets) { pdc.writeOffsets(offsets); }

    @Override public void clearOffsets() { pdc.clearOffsets(); }

    @Override public Object identityKey() { return CustomBlockRegistry.LocationKey.of(hoist); }

    /**
     * Whether a cell lies in the rope's right-of-way: the vertical corridor above the seed in the
     * hoist's own column — every chain link, the hoist head, and everything above it. Authoring is
     * refused there ({@link GlueAuthoring}): glue in the corridor either tears the mover's own
     * hardware or, resolved at a shallower depth, points above the hoist and grabs the ceiling.
     * Unbounded upward on purpose — it must cover the hoist skull itself, which the LEFT_CLICK
     * authoring path never routes through the anchor gate.
     */
    boolean inRopeColumn(Block b) {
        return b.getX() == hoist.getX() && b.getZ() == hoist.getZ()
            && b.getY() > originBlock().getY();
    }
}
