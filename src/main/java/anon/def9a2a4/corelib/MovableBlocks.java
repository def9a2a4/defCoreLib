package anon.def9a2a4.corelib;

import org.bukkit.Material;
import org.bukkit.block.Block;

import java.util.EnumSet;
import java.util.Set;

/**
 * Shared MECHANISM-movability test: may a block be captured into a moving mechanism (glued to a
 * cart/door/rotator, carried as an extendable-piston payload)? A block qualifies unless it is
 * empty (air/fluid) or a hard-immovable world block (bedrock, spawners, portals, …).
 *
 * <p><b>Deliberately NOT vanilla-piston semantics.</b> Custom blocks are always mechanism-movable:
 * {@code assembleCore} snapshots their identity/state/storage and tears down world-keyed tracking,
 * and disassembly fully restores them — that round-trip is the basis of rotation parts working on
 * mechanisms. The {@code cancel_pistons}/{@code break_on_piston} type flags protect skulls from
 * <em>vanilla piston</em> relocation only, and are enforced separately in
 * {@code CoreLibPlugin.onPistonExtend} — checking them here (as this class originally did) made
 * every rotation part un-gluable.
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

    /** @return true if {@code b} may be captured/carried by a mechanism (glue, cart, piston payload).
     *  {@code registry} is currently unused but kept: it's the hook for a future data-driven
     *  {@code gluable: false} type flag should some custom block need to opt out. */
    static boolean isMovable(Block b, CustomBlockRegistry registry) {
        Material m = b.getType();
        if (m.isAir() || m == Material.WATER || m == Material.LAVA) return false;
        return !IMMOVABLE.contains(m);
    }
}
