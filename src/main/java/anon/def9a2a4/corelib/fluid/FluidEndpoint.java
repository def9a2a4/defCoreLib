package anon.def9a2a4.corelib.fluid;

import org.bukkit.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * A world block that can provide or accept whole-bucket fluid units: a source block, a
 * cauldron, or a machine tank. Resolved through {@link FluidEndpoints} — the fluid analogue
 * of {@code ContainerAdapterRegistry}. All operations move exactly one unit.
 */
public interface FluidEndpoint {

    /** The fluid one unit of which could be drained from {@code block} right now, or null. */
    @Nullable FluidType provided(Block block);

    /** Remove one unit of {@code fluid} from {@code block}. Caller checked {@link #provided}. */
    boolean drain(Block block, FluidType fluid);

    /** Whether {@code block} could take one unit of {@code fluid} right now. */
    boolean canAccept(Block block, FluidType fluid);

    /** Add one unit of {@code fluid} to {@code block}. Caller checked {@link #canAccept}. */
    boolean fill(Block block, FluidType fluid);
}
