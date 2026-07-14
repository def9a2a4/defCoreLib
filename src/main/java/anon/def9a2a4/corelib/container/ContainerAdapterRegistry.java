package anon.def9a2a4.corelib.container;

import anon.def9a2a4.corelib.CoreLibPlugin;
import anon.def9a2a4.corelib.CustomBlockRegistry;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * THE gateway for resolving world containers. All plugin code that moves items into or out of a
 * world block's inventory must resolve the block here — either {@link #findAdapter} (pipes; full
 * adapter chain) or {@link #findVanillaContainer} (machines; plain vanilla containers only) —
 * never via a raw {@code getState() instanceof Container}. Both lookups enforce the
 * locked-container rule: a {@code physical_material} custom block with {@code lockContainer}
 * (e.g. the redstone dynamo's comparator-drive barrel) resolves to "no container", so pipes route
 * around it and machines skip it. Only the owning code (the block's own tick, and the registry's
 * removal wipe) may touch such an inventory directly.
 */
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
        Optional<ContainerAdapter> found = adapters.stream()
            .filter(a -> a.canReceive(block))
            .findFirst();
        // Locked-container guard, deliberately POST-match: by now every canReceive has called
        // getState(), so the block's chunk is loaded and the registry's location index is fresh
        // (checking before the match would race a synchronous chunk load at chunk borders).
        // Virtual-storage adapters (usesRealInventory() == false) are exempt — the lock only
        // owns the real tile inventory.
        if (found.isPresent() && found.get().usesRealInventory() && isLocked(block)) {
            return Optional.empty();
        }
        return found;
    }

    /**
     * The one sanctioned way to resolve a block as a plain vanilla container: its {@link Container}
     * state, or null if it isn't one or its inventory is plugin-owned (locked). State access runs
     * FIRST, so the lock check is order-safe by construction (see {@code isLockedContainer}).
     */
    public static @Nullable Container findVanillaContainer(Block block) {
        if (!(block.getState() instanceof Container container)) return null;
        return isLocked(block) ? null : container;
    }

    /** Locked-container check via the CoreLib registry; fails open (unlocked) during the brief
     *  plugin-init window before the registry exists — unreachable through normal gameplay. */
    private static boolean isLocked(Block block) {
        CoreLibPlugin plugin = CoreLibPlugin.getInstance();
        if (plugin == null) return false;
        CustomBlockRegistry registry = plugin.getRegistry();
        return registry != null && registry.isLockedContainer(block);
    }
}
