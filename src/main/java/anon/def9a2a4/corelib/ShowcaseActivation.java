package anon.def9a2a4.corelib;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;

/**
 * Shared "kick the machine into life" logic for a built {@link ShowcaseSpec}: apply its {@code activate:}
 * directive (fuel an engine, pulse a redstone trigger, or nudge a passive network recalc). Used by both
 * the headless {@link ShowcaseRunner} (test/capture) and the keep-alive docs world ({@link DisplayExporter}).
 */
final class ShowcaseActivation {

    /** Default fuel charge (ticks) — keeps a fuelled engine running well past the spin-up window. */
    static final int FUEL_TICKS = 6000;

    private ShowcaseActivation() {}

    /** Apply {@code spec.activate} to the machine built at {@code origin}. */
    static void activate(ShowcaseSpec spec, Location origin, RotationNetwork network,
                         EngineFuelManager fuelManager) {
        ShowcaseSpec.Activate act = spec.activate;
        switch (act.kind()) {
            case "pulse" -> {
                if (act.at() != null) blockAt(origin, act.at()).setType(Material.REDSTONE_BLOCK, true);
            }
            case "fuel" -> {
                if (act.at() != null) {
                    fuelManager.addFuel(CustomBlockRegistry.LocationKey.of(blockAt(origin, act.at())),
                            FUEL_TICKS);
                }
            }
            default -> {   // passive: nudge a recalc on the first block so the network is fresh
                if (!spec.blocks.isEmpty()) {
                    network.recalculate(CustomBlockRegistry.LocationKey.of(
                            blockAt(origin, spec.blocks.get(0).at())));
                }
            }
        }
    }

    static Block blockAt(Location origin, int[] at) {
        return origin.getWorld().getBlockAt(
                origin.getBlockX() + at[0], origin.getBlockY() + at[1], origin.getBlockZ() + at[2]);
    }
}
