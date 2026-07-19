package anon.def9a2a4.corelib.fluid;

import org.bukkit.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * Machine tanks ({@link FluidTanks}) as pipe-visible fluid endpoints: a machine with a
 * registered tank accepts its declared fluid until full and provides it while non-empty.
 * This is how pumped water reaches the sieve/boiler without any machine-specific plumbing.
 */
final class MachineTankEndpoint implements FluidEndpoint {

    @Override
    public @Nullable FluidType provided(Block block) {
        FluidTanks.TankSpec spec = FluidTanks.spec(block);
        return spec != null && FluidTanks.units(block) > 0 ? spec.fluid() : null;
    }

    @Override
    public boolean drain(Block block, FluidType fluid) {
        return provided(block) == fluid && FluidTanks.takeUnit(block);
    }

    @Override
    public boolean canAccept(Block block, FluidType fluid) {
        FluidTanks.TankSpec spec = FluidTanks.spec(block);
        return spec != null && spec.fluid() == fluid && FluidTanks.units(block) < spec.capacity();
    }

    @Override
    public boolean fill(Block block, FluidType fluid) {
        return canAccept(block, fluid) && FluidTanks.addUnit(block);
    }
}
