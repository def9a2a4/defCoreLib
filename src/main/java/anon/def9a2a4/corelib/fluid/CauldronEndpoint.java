package anon.def9a2a4.corelib.fluid;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.jspecify.annotations.Nullable;

/**
 * Cauldrons as one-unit vessels: a FULL water/lava cauldron provides one unit (drain leaves
 * an empty cauldron), an EMPTY cauldron accepts one unit of either fluid (fill sets it full).
 * Partial water cauldrons (levels 1-2) are neither drainable nor fillable — bucket-granular
 * by design (docs/todo/mechanism/fluids.md).
 */
final class CauldronEndpoint implements FluidEndpoint {

    @Override
    public @Nullable FluidType provided(Block block) {
        Material type = block.getType();
        if (type == Material.LAVA_CAULDRON) return FluidType.LAVA;
        if (type == Material.WATER_CAULDRON
                && block.getBlockData() instanceof Levelled levelled
                && levelled.getLevel() == levelled.getMaximumLevel()) {
            return FluidType.WATER;
        }
        return null;
    }

    @Override
    public boolean drain(Block block, FluidType fluid) {
        if (provided(block) != fluid) return false;
        block.setType(Material.CAULDRON);
        return true;
    }

    @Override
    public boolean canAccept(Block block, FluidType fluid) {
        return block.getType() == Material.CAULDRON;
    }

    @Override
    public boolean fill(Block block, FluidType fluid) {
        if (!canAccept(block, fluid)) return false;
        block.setType(fluid.filledCauldron());
        if (fluid == FluidType.WATER && block.getBlockData() instanceof Levelled levelled) {
            levelled.setLevel(levelled.getMaximumLevel());
            block.setBlockData(levelled);
        }
        return true;
    }
}
