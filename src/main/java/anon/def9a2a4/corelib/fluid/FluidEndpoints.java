package anon.def9a2a4.corelib.fluid;

import org.bukkit.block.Block;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * THE gateway for resolving fluid endpoints, mirroring {@code ContainerAdapterRegistry}:
 * plugin code that moves fluid units into or out of a world block resolves the block here.
 * Built-ins cover machine tanks, cauldrons, and bare source blocks; {@link #register} inserts
 * ahead of them for plugin-supplied endpoints.
 */
public final class FluidEndpoints {

    private static final List<FluidEndpoint> endpoints = new ArrayList<>(List.of(
        new MachineTankEndpoint(),
        new CauldronEndpoint(),
        new WorldSourceEndpoint()));

    private FluidEndpoints() {}

    public static void register(FluidEndpoint endpoint) {
        endpoints.add(0, endpoint);
    }

    /** The endpoint that can drain a unit from {@code block} right now, else null. */
    public static @Nullable FluidEndpoint providing(Block block) {
        for (FluidEndpoint e : endpoints) {
            if (e.provided(block) != null) return e;
        }
        return null;
    }

    /** The endpoint that can accept a unit of {@code fluid} at {@code block} right now, else null. */
    public static @Nullable FluidEndpoint accepting(Block block, FluidType fluid) {
        for (FluidEndpoint e : endpoints) {
            if (e.canAccept(block, fluid)) return e;
        }
        return null;
    }
}
