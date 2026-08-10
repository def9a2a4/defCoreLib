package anon.def9a2a4.corelib;

import org.bukkit.block.Block;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Factory and registry of glueable anchor block types — shared by {@link GlueAuthoring} (which
 * builds an anchor when a player brushes glue) and {@link GlueManager} (which, during a mover's
 * transitive capture, must recognise a <em>nested</em> anchor and pull its glued region along).
 * The chain hoist is the odd one out: its PDC is on the skull like the rest, but its offsets are
 * relative to the platform seed below the chain end ({@link HoistAnchor}).
 */
final class Anchors {

    private Anchors() {}

    /** Custom-block ids that can own a glued structure (skull anchors). */
    static final Set<String> ANCHOR_IDS =
        Set.of("demo:door_controller", "mech:rotator", "mech:piston_head", ChainHoistManager.HOIST_ID);

    /** Whether this block is a glueable anchor type. */
    static boolean isAnchorType(Block block, CustomBlockRegistry registry) {
        CustomHeadBlock type = registry.getTypeFromBlock(block);
        return type != null && ANCHOR_IDS.contains(type.fullId());
    }

    /** The right {@link Anchor} for an anchor block: hoists get the dynamic-origin {@link HoistAnchor}
     *  (PDC on the skull, offsets relative to the platform seed below the chain end), everything else a
     *  plain {@link BlockAnchor}. {@code hoistManager} supplies the mid-stroke gate (null-safe: a null
     *  manager means the hoist always reads at rest). */
    static Anchor forBlock(Block block, CustomBlockRegistry registry,
                           @Nullable ChainHoistManager hoistManager) {
        if (ChainHoistManager.isHoist(block, registry)) {
            return new HoistAnchor(block, registry,
                () -> hoistManager == null || !hoistManager.isMoving(block));
        }
        return new BlockAnchor(block, () -> true);
    }
}
