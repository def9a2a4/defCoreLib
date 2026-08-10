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

    // Foreign-plugin anchor providers, in registration order (see CoreLibPlugin.registerAnchorProvider).
    // Keyed by plugin id so a re-register replaces rather than stacks. Main-thread only, like every
    // other authoring path.
    private static final Map<String, ExternalAnchor.Provider> PROVIDERS = new LinkedHashMap<>();

    static void registerProvider(String pluginId, ExternalAnchor.Provider provider) {
        PROVIDERS.put(pluginId, provider);
    }

    /** The first registered provider that claims this block, or null. */
    static @Nullable ExternalAnchor externalFor(Block block) {
        if (PROVIDERS.isEmpty()) return null;
        for (ExternalAnchor.Provider p : PROVIDERS.values()) {
            ExternalAnchor a = p.anchorFor(block);
            if (a != null) return a;
        }
        return null;
    }

    /** Whether this block is a glueable anchor type — an engine type id, or a foreign plugin's. */
    static boolean isAnchorType(Block block, CustomBlockRegistry registry) {
        CustomHeadBlock type = registry.getTypeFromBlock(block);
        if (type != null && ANCHOR_IDS.contains(type.fullId())) return true;
        return externalFor(block) != null;
    }

    /** The right {@link Anchor} for an anchor block: hoists get the dynamic-origin {@link HoistAnchor}
     *  (PDC on the skull, offsets relative to the platform seed below the chain end), a foreign plugin's
     *  block a {@link ProvidedAnchor}, everything else a plain {@link BlockAnchor}. {@code hoistManager}
     *  supplies the mid-stroke gate (null-safe: a null manager means the hoist always reads at rest).
     *
     *  <p>Engine types are checked FIRST so a provider can never shadow a rotator or hoist. */
    static Anchor forBlock(Block block, CustomBlockRegistry registry,
                           @Nullable ChainHoistManager hoistManager) {
        if (ChainHoistManager.isHoist(block, registry)) {
            return new HoistAnchor(block, registry,
                () -> hoistManager == null || !hoistManager.isMoving(block));
        }
        CustomHeadBlock type = registry.getTypeFromBlock(block);
        if (type == null || !ANCHOR_IDS.contains(type.fullId())) {
            ExternalAnchor ext = externalFor(block);
            if (ext != null) return new ProvidedAnchor(ext);
        }
        return new BlockAnchor(block, () -> true);
    }
}
