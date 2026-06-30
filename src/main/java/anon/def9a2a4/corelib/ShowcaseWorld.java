package anon.def9a2a4.corelib;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.Collection;
import java.util.logging.Logger;

/**
 * Places every {@link ShowcaseSpec} into the docs test-world during the {@link DisplayExporter} run, so
 * they live in the same world as the per-block grid (joinable with {@code make docs KEEP_ALIVE=1}).
 * Laid out in the windmill quadrant {@code (−x,−z)} — the reserved "large display" area for multi-block
 * assemblies — past the windmill block rows so they don't collide. Pure placement; {@code DisplayExporter}
 * owns the world spawn/viewing setup.
 */
final class ShowcaseWorld {

    private static final double GRID_Y = -60;   // superflat surface (matches DisplayExporter)
    private static final int LANE_X = -12;      // a clear lane in the (−x,−z) quadrant
    private static final int Z0 = -56;          // start past the windmill rows (z = -8, -20, -32)
    private static final int SPACING = 16;      // showcases spaced along −z

    private ShowcaseWorld() {}

    static void placeAll(World world, ShowcaseBuilder builder,
                         Collection<ShowcaseSpec> showcases, Logger log) {
        int i = 0;
        for (ShowcaseSpec spec : showcases) {
            int z = Z0 - i * SPACING;
            Location origin = new Location(world, LANE_X, GRID_Y, z);
            origin.getChunk().load(true);
            origin.getChunk().setForceLoaded(true);   // keep animations running off-screen
            try {
                int placed = builder.build(spec, origin);
                log.info("  showcase " + spec.id + " (" + placed + " blocks) @ "
                        + LANE_X + " " + (int) GRID_Y + " " + z);
            } catch (Throwable t) {
                log.warning("showcase " + spec.id + " failed: " + t);
            }
            i++;
        }
    }
}
