package anon.def9a2a4.corelib.fluid;

import org.bukkit.Material;
import org.jspecify.annotations.Nullable;

/**
 * The fluids the pipe/pump system moves. The atomic unit everywhere (tanks, pump cycles,
 * cauldrons) is ONE BUCKET — there are no partial units (see docs/todo/mechanism/fluids.md).
 */
public enum FluidType {
    WATER(Material.WATER, Material.WATER_BUCKET, Material.WATER_CAULDRON),
    LAVA(Material.LAVA, Material.LAVA_BUCKET, Material.LAVA_CAULDRON);

    private final Material sourceBlock;
    private final Material bucket;
    private final Material filledCauldron;

    FluidType(Material sourceBlock, Material bucket, Material filledCauldron) {
        this.sourceBlock = sourceBlock;
        this.bucket = bucket;
        this.filledCauldron = filledCauldron;
    }

    public Material sourceBlock() { return sourceBlock; }
    public Material bucket() { return bucket; }
    public Material filledCauldron() { return filledCauldron; }

    /** Case-insensitive name lookup ({@code "water"}/{@code "lava"}), null for anything else. */
    public static @Nullable FluidType fromName(@Nullable String name) {
        if (name == null) return null;
        for (FluidType f : values()) {
            if (f.name().equalsIgnoreCase(name)) return f;
        }
        return null;
    }
}
