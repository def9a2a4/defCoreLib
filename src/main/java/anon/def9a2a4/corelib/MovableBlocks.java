package anon.def9a2a4.corelib;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.jspecify.annotations.Nullable;

import java.util.EnumSet;
import java.util.Set;

/**
 * Shared piston-movability test. A block may be grabbed/pushed unless it is empty (air/fluid), a
 * hard-coded immovable material, or a custom block that opts out of being moved by a piston
 * (either {@code cancel_pistons} or {@code break_on_piston}). Mirrors vanilla-piston refusal
 * semantics so mechanisms never scoop up bedrock, world-boundary blocks, or live machinery.
 */
final class MovableBlocks {

    private MovableBlocks() {}

    /** Materials that can never be grabbed/pushed. Built once at class load. */
    private static final Set<Material> IMMOVABLE = buildImmovable();

    private static Set<Material> buildImmovable() {
        Set<Material> s = EnumSet.of(
            // drill blacklist (see RotationConfig.drillBlacklist)
            Material.OBSIDIAN, Material.CRYING_OBSIDIAN, Material.SPAWNER,
            Material.MOVING_PISTON, Material.REINFORCED_DEEPSLATE,
            // structural / world-boundary blocks
            Material.BEDROCK, Material.BARRIER, Material.STRUCTURE_BLOCK, Material.STRUCTURE_VOID,
            Material.JIGSAW, Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK,
            Material.REPEATING_COMMAND_BLOCK, Material.END_PORTAL, Material.END_PORTAL_FRAME,
            Material.END_GATEWAY, Material.NETHER_PORTAL,
            // piston hardware
            Material.PISTON, Material.STICKY_PISTON, Material.PISTON_HEAD);
        // 1.21 additions — resolve by name so a differing runtime doesn't hard-fail at class load.
        addByName(s, "TRIAL_SPAWNER");
        addByName(s, "VAULT");
        return s;
    }

    private static void addByName(Set<Material> s, String id) {
        Material m = Material.matchMaterial(id);
        if (m != null) s.add(m);
    }

    /** @return true if {@code b} may be grabbed/pushed by a piston-style mechanism. */
    static boolean isMovable(Block b, CustomBlockRegistry registry) {
        Material m = b.getType();
        if (m.isAir() || m == Material.WATER || m == Material.LAVA) return false;
        if (IMMOVABLE.contains(m)) return false;
        @Nullable CustomHeadBlock type = registry.getTypeFromBlock(b);
        if (type != null && (type.cancelPistons() || type.breakOnPiston())) return false;
        return true;
    }
}
