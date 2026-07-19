package anon.def9a2a4.corelib.fluid;

import org.bukkit.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * Corelib↔pipes handshake for fluid transport: the pipes plugin registers a {@link Walker} at
 * enable, and the pump asks it to route units along pipe chains. Same dependency inversion as
 * {@code MechanismConduits}/{@code ContainerAdapterRegistry} — corelib never references pipes
 * classes — but the payload is a SERVICE, not data: the pipes plugin owns the per-world pipe
 * map and the chain walk, so the walking happens on its side.
 */
public final class FluidRouting {

    public interface Walker {
        /** Whether {@code block} is a pipe that can carry {@code fluid} (route entry check). */
        boolean isConduit(Block block, FluidType fluid);

        /**
         * Walk the chain starting at pipe {@code firstPipe} and deliver one unit of
         * {@code fluid} into the first accepting {@link FluidEndpoints endpoint}. Every segment
         * must carry the fluid (lava needs iron pipes end to end).
         *
         * @return true when the unit was delivered — the caller then drains its intake
         */
        boolean push(Block firstPipe, FluidType fluid);
    }

    private static @Nullable Walker walker;

    private FluidRouting() {}

    /** Registered by the pipes plugin at enable. Last write wins. */
    public static void register(Walker w) {
        walker = w;
    }

    public static boolean isConduit(Block block, FluidType fluid) {
        return walker != null && walker.isConduit(block, fluid);
    }

    public static boolean push(Block firstPipe, FluidType fluid) {
        return walker != null && walker.push(firstPipe, fluid);
    }
}
