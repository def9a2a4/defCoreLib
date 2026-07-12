package anon.def9a2a4.pipes.adapter;

import org.bukkit.block.Block;

import java.util.List;
import java.util.Optional;

/**
 * Registry for container adapters. Checks specialized adapters first,
 * falls back to vanilla generic container adapter.
 */
public class ContainerAdapterRegistry {

    private static final List<ContainerAdapter> ADAPTERS = List.of(
        new BrewingStandContainerAdapter(),
        new FurnaceContainerAdapter(),
        new RotationMachineAdapter(),
        new VanillaContainerAdapter()   // catch-all, must be last
    );

    public static Optional<ContainerAdapter> findAdapter(Block block) {
        return ADAPTERS.stream()
            .filter(a -> a.canReceive(block))
            .findFirst();
    }
}
