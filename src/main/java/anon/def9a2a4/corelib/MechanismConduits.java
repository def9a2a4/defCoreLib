package anon.def9a2a4.corelib;

import org.bukkit.block.BlockFace;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Registry of item-conduit block types that function while assembled into a moving mechanism
 * (see {@link MechanismRotationDriver}). Conduit plugins (Pipes) register their types at enable —
 * the same dependency inversion as {@code ContainerAdapterRegistry}/{@code MachineEjectEvent}, so
 * corelib never references a conduit plugin's classes.
 *
 * <p>A conduit is a pure directed edge: it has a facing and a throughput, holds no inventory, and
 * moves items instantly from the container behind it along a facing-chain to a destination
 * (mirroring the Pipes ground model). The {@code facingFromState} resolver is supplied by the
 * owning plugin because facing conventions are plugin-specific (vertical pipes are floor heads
 * whose facing lives in the state name).
 */
public final class MechanismConduits {

    /** One registered conduit type. {@code corner} conduits never pull — they only bend a chain. */
    public record Conduit(boolean corner, int intervalTicks, int itemsPerTransfer,
                          Function<@Nullable String, @Nullable BlockFace> facingFromState) {}

    private static final Map<String, Conduit> CONDUITS = new HashMap<>();

    private MechanismConduits() {}

    /** Register a conduit block type (full id, e.g. {@code pipes:copper_pipe}). Last write wins. */
    public static void register(String typeId, boolean corner, int intervalTicks,
                                int itemsPerTransfer,
                                Function<@Nullable String, @Nullable BlockFace> facingFromState) {
        CONDUITS.put(typeId, new Conduit(corner, Math.max(1, intervalTicks),
            Math.max(1, itemsPerTransfer), facingFromState));
    }

    static @Nullable Conduit get(@Nullable String typeId) {
        return typeId == null ? null : CONDUITS.get(typeId);
    }
}
