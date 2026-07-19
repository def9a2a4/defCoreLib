package anon.def9a2a4.corelib.fluid;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.jspecify.annotations.Nullable;

/**
 * Bare world fluid: a SOURCE block (level 0) provides one unit and is CONSUMED by draining
 * (set to air); an air cell accepts one unit by becoming a source block. Both directions run
 * WITH physics — draining lets neighbouring liquid flow back into the vacated cell (vanilla
 * infinite-water pools stay the intended cheap water source), filling lets the placed source
 * spread. Flowing (non-source) liquid is never drained.
 */
final class WorldSourceEndpoint implements FluidEndpoint {

    @Override
    public @Nullable FluidType provided(Block block) {
        Material type = block.getType();
        if (!(block.getBlockData() instanceof Levelled levelled) || levelled.getLevel() != 0) return null;
        if (type == Material.WATER) return FluidType.WATER;
        if (type == Material.LAVA) return FluidType.LAVA;
        return null;
    }

    @Override
    public boolean drain(Block block, FluidType fluid) {
        if (provided(block) != fluid) return false;
        block.setType(Material.AIR, true);
        return true;
    }

    @Override
    public boolean canAccept(Block block, FluidType fluid) {
        return block.getType() == Material.AIR;
    }

    @Override
    public boolean fill(Block block, FluidType fluid) {
        if (!canAccept(block, fluid)) return false;
        block.setType(fluid.sourceBlock(), true);
        return true;
    }
}
