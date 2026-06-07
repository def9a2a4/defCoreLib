package anon.def9a2a4.corelib;

import org.bukkit.Material;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * Classifies blocks as fragile (destroyable during mechanism disassembly)
 * or attachable (need support, must be removed before their support blocks).
 */
public final class FragileBlocks {

    private FragileBlocks() {}

    private static final Set<Material> FRAGILE_BLOCKS;
    private static final Set<Material> ATTACHABLE_MATERIALS;

    static {
        Set<Material> fragile = new HashSet<>(Set.of(
            // Grass and plants
            Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN,
            Material.DEAD_BUSH, Material.SEAGRASS, Material.TALL_SEAGRASS,
            Material.KELP, Material.KELP_PLANT, Material.HANGING_ROOTS,
            Material.SMALL_DRIPLEAF, Material.BIG_DRIPLEAF, Material.BIG_DRIPLEAF_STEM,
            Material.SPORE_BLOSSOM, Material.GLOW_LICHEN, Material.MOSS_CARPET,
            Material.PINK_PETALS, Material.PITCHER_PLANT, Material.TORCHFLOWER,
            Material.CAVE_VINES, Material.CAVE_VINES_PLANT,
            Material.WEEPING_VINES, Material.WEEPING_VINES_PLANT,
            Material.TWISTING_VINES, Material.TWISTING_VINES_PLANT,
            Material.VINE, Material.NETHER_SPROUTS, Material.WARPED_ROOTS, Material.CRIMSON_ROOTS,
            // Flowers
            Material.DANDELION, Material.POPPY, Material.BLUE_ORCHID, Material.ALLIUM,
            Material.AZURE_BLUET, Material.RED_TULIP, Material.ORANGE_TULIP,
            Material.WHITE_TULIP, Material.PINK_TULIP, Material.OXEYE_DAISY,
            Material.CORNFLOWER, Material.LILY_OF_THE_VALLEY, Material.WITHER_ROSE,
            Material.SUNFLOWER, Material.LILAC, Material.ROSE_BUSH, Material.PEONY,
            // Crops and mushrooms
            Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS,
            Material.MELON_STEM, Material.PUMPKIN_STEM,
            Material.ATTACHED_MELON_STEM, Material.ATTACHED_PUMPKIN_STEM,
            Material.SWEET_BERRY_BUSH, Material.RED_MUSHROOM, Material.BROWN_MUSHROOM,
            Material.CRIMSON_FUNGUS, Material.WARPED_FUNGUS,
            // Leaves
            Material.OAK_LEAVES, Material.SPRUCE_LEAVES, Material.BIRCH_LEAVES,
            Material.JUNGLE_LEAVES, Material.ACACIA_LEAVES, Material.DARK_OAK_LEAVES,
            Material.MANGROVE_LEAVES, Material.CHERRY_LEAVES,
            Material.AZALEA_LEAVES, Material.FLOWERING_AZALEA_LEAVES,
            // Saplings
            Material.OAK_SAPLING, Material.SPRUCE_SAPLING, Material.BIRCH_SAPLING,
            Material.JUNGLE_SAPLING, Material.ACACIA_SAPLING, Material.DARK_OAK_SAPLING,
            Material.CHERRY_SAPLING, Material.MANGROVE_PROPAGULE,
            // Other
            Material.SNOW, Material.POWDER_SNOW, Material.COBWEB,
            Material.FIRE, Material.SOUL_FIRE, Material.LILY_PAD,
            Material.SUGAR_CANE, Material.CACTUS, Material.BAMBOO, Material.BAMBOO_SAPLING,
            Material.CHORUS_PLANT, Material.CHORUS_FLOWER
        ));
        // Version-specific
        Material paleLeaves = Material.getMaterial("PALE_OAK_LEAVES");
        if (paleLeaves != null) fragile.add(paleLeaves);
        Material paleSapling = Material.getMaterial("PALE_OAK_SAPLING");
        if (paleSapling != null) fragile.add(paleSapling);
        FRAGILE_BLOCKS = Set.copyOf(fragile);

        // Attachable blocks: need support from other blocks, removed first during assembly
        Set<Material> attachable = EnumSet.noneOf(Material.class);
        String[] patterns = {
            "BANNER", "SIGN", "TORCH", "BUTTON", "LEVER", "CARPET", "PRESSURE_PLATE",
            "LADDER", "LANTERN", "BELL", "CANDLE", "REPEATER", "COMPARATOR", "TRIPWIRE", "RAIL"
        };
        for (Material mat : Material.values()) {
            String name = mat.name();
            if (name.equals("REDSTONE")) { attachable.add(mat); continue; }
            for (String pattern : patterns) {
                if (name.contains(pattern)) { attachable.add(mat); break; }
            }
        }
        ATTACHABLE_MATERIALS = Set.copyOf(attachable);
    }

    public static boolean isFragile(Material material) {
        return FRAGILE_BLOCKS.contains(material);
    }

    public static boolean isAttachable(Material material) {
        return ATTACHABLE_MATERIALS.contains(material);
    }
}
