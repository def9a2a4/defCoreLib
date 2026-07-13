package anon.def9a2a4.corelib;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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

    /**
     * Force-load every showcase's chunks and blank all of its target cells to AIR, so a rebuild starts
     * from a clean slate. Airing a cell strips its custom-block identity, which turns any display
     * entity still tagged to that cell into a <em>true</em> orphan (owner is now air) — detectable by
     * {@link CustomBlockRegistry#scanOrphanedDisplays}, which otherwise treats every display on a
     * still-valid block as "live" and can't tell a stale/wrong one from the right one.
     *
     * <p>Uses the exact same origin/offset math as {@link #placeAll}, so the cells cleared here are
     * precisely the cells {@code placeAll} is about to (re)build. The force-load also brings the prior
     * run's persisted displays into the world and keeps the owner chunks loaded, both of which
     * {@code scanOrphanedDisplays} requires to actually remove an orphan (it skips unloaded-owner ones).
     *
     * <p>Run this <em>before</em> {@code placeAll}; the caller should defer the orphan scan a few ticks
     * so Paper's async entity load has attached those displays first.
     */
    static void clearArea(World world, Collection<ShowcaseSpec> showcases, Logger log) {
        Set<Long> forced = new HashSet<>();
        int cells = 0;
        int i = 0;
        for (ShowcaseSpec spec : showcases) {
            int ox = LANE_X, oy = (int) GRID_Y, oz = Z0 - i * SPACING;
            for (ShowcaseSpec.BlockSpec bs : spec.blocks) {
                if (airCell(world, ox + bs.at()[0], oy + bs.at()[1], oz + bs.at()[2], forced)) cells++;
            }
            for (ShowcaseSpec.VanillaSpec vs : spec.vanilla) {
                if (airCell(world, ox + vs.at()[0], oy + vs.at()[1], oz + vs.at()[2], forced)) cells++;
            }
            i++;
        }
        log.info("  showcase clear: blanked " + cells + " cells across " + forced.size() + " chunks");
    }

    /** Force-load the containing chunk (once) and set the cell to AIR. Returns true (one cell cleared). */
    private static boolean airCell(World world, int x, int y, int z, Set<Long> forced) {
        long chunkKey = (((long) (x >> 4)) << 32) ^ (z >> 4) & 0xffffffffL;
        if (forced.add(chunkKey)) {
            world.getChunkAt(x >> 4, z >> 4).load(true);
            world.getChunkAt(x >> 4, z >> 4).setForceLoaded(true);
        }
        Block block = world.getBlockAt(x, y, z);
        block.setType(Material.AIR, false);
        return true;
    }

    /** Build every showcase; returns the build origin of each so the caller can activate/drive it. */
    static Map<ShowcaseSpec, Location> placeAll(World world, ShowcaseBuilder builder,
                                                Collection<ShowcaseSpec> showcases, Logger log) {
        Map<ShowcaseSpec, Location> origins = new LinkedHashMap<>();
        int i = 0;
        for (ShowcaseSpec spec : showcases) {
            int z = Z0 - i * SPACING;
            Location origin = new Location(world, LANE_X, GRID_Y, z);
            origin.getChunk().load(true);
            origin.getChunk().setForceLoaded(true);   // keep animations running off-screen
            try {
                int placed = builder.build(spec, origin);
                origins.put(spec, origin);
                log.info("  showcase " + spec.id + " (" + placed + " blocks) @ "
                        + LANE_X + " " + (int) GRID_Y + " " + z);
            } catch (Throwable t) {
                log.warning("showcase " + spec.id + " failed: " + t);
            }
            i++;
        }
        return origins;
    }
}
