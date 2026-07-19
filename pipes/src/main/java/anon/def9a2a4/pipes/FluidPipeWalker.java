package anon.def9a2a4.pipes;

import anon.def9a2a4.corelib.fluid.FluidRouting;
import anon.def9a2a4.corelib.fluid.FluidType;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Map;

/**
 * The pipes side of {@link FluidRouting}: corelib's pump asks, the per-world
 * {@link PipeManager} walks. Registered once at plugin enable with the same live
 * per-world map the {@link MachineEjectListener} uses.
 */
final class FluidPipeWalker implements FluidRouting.Walker {

    private final Map<World, PipeManager> pipeManagers;

    FluidPipeWalker(Map<World, PipeManager> pipeManagers) {
        this.pipeManagers = pipeManagers;
    }

    @Override
    public boolean isConduit(Block block, FluidType fluid) {
        PipeManager manager = pipeManagers.get(block.getWorld());
        return manager != null && manager.isFluidConduit(block, fluid);
    }

    @Override
    public boolean push(Block firstPipe, FluidType fluid) {
        PipeManager manager = pipeManagers.get(firstPipe.getWorld());
        return manager != null && manager.pushFluid(firstPipe, fluid);
    }
}
