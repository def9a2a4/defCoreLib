package anon.def9a2a4.corelib.container;

import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ContainerAdapterRegistry {

    private static final List<ContainerAdapter> adapters = new ArrayList<>();

    static {
        adapters.add(new BrewingStandContainerAdapter());
        adapters.add(new FurnaceContainerAdapter());
        adapters.add(new RotationMachineAdapter());
        adapters.add(new VanillaContainerAdapter());
    }

    public static void register(ContainerAdapter adapter) {
        adapters.add(adapters.size() - 1, adapter);
    }

    public static Optional<ContainerAdapter> findAdapter(Block block) {
        return adapters.stream()
            .filter(a -> a.canReceive(block))
            .findFirst();
    }
}
