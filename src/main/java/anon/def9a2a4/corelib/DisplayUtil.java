package anon.def9a2a4.corelib;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Static utilities for spawning, finding, and removing ItemDisplay entities
 * associated with custom blocks.
 */
public final class DisplayUtil {

    private DisplayUtil() {}

    /**
     * Spawn an ItemDisplay entity at a block's center.
     *
     * @param blockLoc      the block location (integer coords)
     * @param displayItem   the item to display
     * @param transform     the display transformation
     * @param scoreboardTag a scoreboard tag for identification and cleanup
     * @return the spawned display entity
     */
    public static ItemDisplay spawn(Location blockLoc, ItemStack displayItem,
                                    Transformation transform, String scoreboardTag) {
        World world = blockLoc.getWorld();
        Location spawnLoc = blockLoc.clone().add(0.5, 0.5, 0.5);
        return world.spawn(spawnLoc, ItemDisplay.class, display -> {
            display.setItemStack(displayItem);
            display.setPersistent(true);
            display.setTransformation(transform);
            display.addScoreboardTag(scoreboardTag);
        });
    }

    /**
     * Spawn a BlockDisplay entity at a block's center.
     *
     * @param blockLoc      the block location (integer coords)
     * @param data          the block data to display
     * @param transform     the display transformation matrix
     * @param scoreboardTag a scoreboard tag for identification and cleanup
     * @return the spawned display entity
     */
    public static BlockDisplay spawnBlock(Location blockLoc, BlockData data,
                                          Matrix4f transform, String scoreboardTag) {
        World world = blockLoc.getWorld();
        Location spawnLoc = blockLoc.getBlock().getLocation().add(0.5, 0.5, 0.5);
        return world.spawn(spawnLoc, BlockDisplay.class, display -> {
            display.setBlock(data);
            display.setPersistent(true);
            display.setTransformationMatrix(transform);
            display.addScoreboardTag(scoreboardTag);
        });
    }

    /**
     * Find Display entities near a block location that have a tag starting with the given prefix.
     * Matches both ItemDisplay and BlockDisplay.
     *
     * @param blockLoc  the block location
     * @param tagPrefix the scoreboard tag prefix to match
     * @param radius    search radius from block center
     * @return matching display entities
     */
    public static List<Display> findByTag(Location blockLoc, String tagPrefix, double radius) {
        World world = blockLoc.getWorld();
        if (world == null) return List.of();

        Location center = blockLoc.getBlock().getLocation().add(0.5, 0.5, 0.5);
        Collection<Entity> nearby = world.getNearbyEntities(center, radius, radius, radius);

        List<Display> result = new ArrayList<>();
        for (Entity entity : nearby) {
            if (!(entity instanceof Display display)) continue;
            for (String tag : display.getScoreboardTags()) {
                if (tag.startsWith(tagPrefix)) {
                    result.add(display);
                    break;
                }
            }
        }
        return result;
    }

    /**
     * Remove all Display entities near a block location matching a tag prefix.
     *
     * @param blockLoc  the block location
     * @param tagPrefix the scoreboard tag prefix to match
     * @param radius    search radius from block center
     * @return number of entities removed
     */
    public static int removeByTag(Location blockLoc, String tagPrefix, double radius) {
        List<Display> displays = findByTag(blockLoc, tagPrefix, radius);
        for (Display display : displays) {
            display.remove();
        }
        return displays.size();
    }

    /**
     * Remove a specific display entity by UUID, with a fallback to tag-based search
     * if the UUID is stale (e.g., after server restart).
     *
     * @param world            the world to search in
     * @param entityId         the entity UUID to remove
     * @param fallbackLoc      fallback block location for tag-based search (nullable)
     * @param fallbackTagPrefix fallback tag prefix for tag-based search (nullable)
     * @return true if an entity was found and removed
     */
    public static boolean remove(World world, UUID entityId,
                                 @Nullable Location fallbackLoc,
                                 @Nullable String fallbackTagPrefix) {
        Entity entity = world.getEntity(entityId);
        if (entity instanceof Display) {
            entity.remove();
            return true;
        }
        if (fallbackLoc != null && fallbackTagPrefix != null) {
            return removeByTag(fallbackLoc, fallbackTagPrefix, 1.5) > 0;
        }
        return false;
    }

    /**
     * Build a standard display entity tag for a custom block.
     * Format: "corelib:{namespace}:{typeId}:{x}_{y}_{z}[:{suffix}]"
     */
    public static String blockTag(String namespace, String typeId, Location blockLoc,
                                  @Nullable String suffix) {
        String base = "corelib:" + namespace + ":" + typeId + ":"
                + blockLoc.getBlockX() + "_" + blockLoc.getBlockY() + "_" + blockLoc.getBlockZ();
        return suffix != null ? base + ":" + suffix : base;
    }

    /** Tag prefix for finding all display entities of a given block type at a location. */
    public static String blockTagPrefix(String namespace, String typeId, Location blockLoc) {
        return "corelib:" + namespace + ":" + typeId + ":"
                + blockLoc.getBlockX() + "_" + blockLoc.getBlockY() + "_" + blockLoc.getBlockZ();
    }

    /** Tag prefix for finding all display entities of a given block type (any location). */
    public static String typeTagPrefix(String namespace, String typeId) {
        return "corelib:" + namespace + ":" + typeId + ":";
    }
}
