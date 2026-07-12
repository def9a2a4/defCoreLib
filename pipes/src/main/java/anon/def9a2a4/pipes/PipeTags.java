package anon.def9a2a4.pipes;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;

/**
 * Utility class for pipe display entity identification via PersistentDataContainer.
 * Reads PDC first, falls back to legacy scoreboard tags for backwards compatibility.
 *
 * Tag value format: {variant_id}:{x}_{y}_{z}_{facing}[_dir|_head]
 * Legacy scoreboard tag format: pipe:{variant_id}:{x}_{y}_{z}_{facing}[_dir]
 */
public final class PipeTags {
    public static final NamespacedKey PIPE_TAG_KEY = new NamespacedKey("pipe", "tag");

    /** Legacy scoreboard tag prefix (pre-PDC migration) */
    public static final String LEGACY_TAG_PREFIX = "pipe:";

    public static final String DIRECTIONAL_SUFFIX = "_dir";
    public static final String HEAD_DISPLAY_SUFFIX = "_head";

    private PipeTags() {}

    /**
     * Create a tag value for a pipe at the given location.
     */
    public static String createTag(Location location, BlockFace facing, PipeVariant variant) {
        return variant.getId() + ":" +
                location.getBlockX() + "_" +
                location.getBlockY() + "_" +
                location.getBlockZ() + "_" +
                facing.name();
    }

    /**
     * Create a tag for a directional display entity (corner pipes only).
     */
    public static String createDirectionalTag(Location location, BlockFace facing, PipeVariant variant) {
        return createTag(location, facing, variant) + DIRECTIONAL_SUFFIX;
    }

    /**
     * Create a tag for an extra head display entity (down-facing corner pipes).
     */
    public static String createHeadDisplayTag(Location location, BlockFace facing, PipeVariant variant) {
        return createTag(location, facing, variant) + HEAD_DISPLAY_SUFFIX;
    }

    /**
     * Check if a tag is for a directional display entity.
     */
    public static boolean isDirectionalTag(String tag) {
        return tag != null && tag.endsWith(DIRECTIONAL_SUFFIX);
    }

    /**
     * Check if a tag is for a head display entity.
     */
    public static boolean isHeadDisplayTag(String tag) {
        return tag != null && tag.endsWith(HEAD_DISPLAY_SUFFIX);
    }

    /**
     * Check if an entity is a pipe display entity.
     * Checks PDC first, falls back to legacy scoreboard tags.
     */
    public static boolean isPipeEntity(Entity entity) {
        if (entity.getPersistentDataContainer().has(PIPE_TAG_KEY, PersistentDataType.STRING)) {
            return true;
        }
        // Legacy fallback
        for (String tag : entity.getScoreboardTags()) {
            if (tag.startsWith(LEGACY_TAG_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the pipe tag value from an entity.
     * Checks PDC first, falls back to legacy scoreboard tags.
     * Returns the tag value WITHOUT the legacy "pipe:" prefix (normalized).
     */
    public static String getPipeTag(Entity entity) {
        String pdcTag = entity.getPersistentDataContainer().get(PIPE_TAG_KEY, PersistentDataType.STRING);
        if (pdcTag != null) return pdcTag;

        // Legacy fallback: strip the "pipe:" prefix to normalize
        for (String tag : entity.getScoreboardTags()) {
            if (tag.startsWith(LEGACY_TAG_PREFIX)) {
                return tag.substring(LEGACY_TAG_PREFIX.length());
            }
        }
        return null;
    }

    /**
     * Write a pipe tag to an entity's PDC and remove any legacy scoreboard tag.
     */
    public static void addPipeTag(Entity entity, String tagValue) {
        entity.getPersistentDataContainer().set(PIPE_TAG_KEY, PersistentDataType.STRING, tagValue);
        // Clean up legacy scoreboard tag if present
        entity.getScoreboardTags().removeIf(tag -> tag.startsWith(LEGACY_TAG_PREFIX));
    }

    /**
     * Migrate an entity from legacy scoreboard tags to PDC if needed.
     * @return true if migration occurred
     */
    public static boolean migrateIfNeeded(Entity entity) {
        if (entity.getPersistentDataContainer().has(PIPE_TAG_KEY, PersistentDataType.STRING)) {
            return false; // Already migrated
        }

        for (String tag : entity.getScoreboardTags()) {
            if (tag.startsWith(LEGACY_TAG_PREFIX)) {
                String tagValue = tag.substring(LEGACY_TAG_PREFIX.length());
                entity.getPersistentDataContainer().set(PIPE_TAG_KEY, PersistentDataType.STRING, tagValue);
                entity.removeScoreboardTag(tag);
                return true;
            }
        }
        return false;
    }

    /**
     * Parse the variant ID from a tag value.
     */
    public static String parseVariantId(String tag) {
        if (tag == null) return null;

        String workingTag = stripDisplaySuffix(tag);

        int colonIdx = workingTag.indexOf(':');
        if (colonIdx > 0) {
            return workingTag.substring(0, colonIdx);
        }

        return null;
    }

    /**
     * Get the data portion of a tag value (coordinates and facing).
     */
    private static String getTagData(String tag) {
        if (tag == null) return null;

        String workingTag = stripDisplaySuffix(tag);

        int colonIdx = workingTag.indexOf(':');
        if (colonIdx > 0 && colonIdx < workingTag.length() - 1) {
            return workingTag.substring(colonIdx + 1);
        }

        return null;
    }

    /**
     * Parse location from a pipe tag value.
     */
    public static Location parseLocation(String tag, World world) {
        String data = getTagData(tag);
        if (data == null) return null;

        String[] parts = data.split("_");
        if (parts.length != 4) return null;

        try {
            int x = Integer.parseInt(parts[0]);
            int y = Integer.parseInt(parts[1]);
            int z = Integer.parseInt(parts[2]);
            return new Location(world, x, y, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parse facing direction from a pipe tag value.
     */
    public static BlockFace parseFacing(String tag) {
        String data = getTagData(tag);
        if (data == null) return null;

        String[] parts = data.split("_");
        if (parts.length != 4) return null;

        try {
            return BlockFace.valueOf(parts[3]);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Check if a pipe tag value matches the given location.
     */
    public static boolean matchesLocation(String tag, Location location) {
        Location parsed = parseLocation(tag, location.getWorld());
        if (parsed == null) return false;

        return parsed.getBlockX() == location.getBlockX() &&
                parsed.getBlockY() == location.getBlockY() &&
                parsed.getBlockZ() == location.getBlockZ();
    }

    private static String stripDisplaySuffix(String tag) {
        if (tag == null) return null;
        if (tag.endsWith(HEAD_DISPLAY_SUFFIX)) {
            return tag.substring(0, tag.length() - HEAD_DISPLAY_SUFFIX.length());
        }
        if (tag.endsWith(DIRECTIONAL_SUFFIX)) {
            return tag.substring(0, tag.length() - DIRECTIONAL_SUFFIX.length());
        }
        return tag;
    }
}
