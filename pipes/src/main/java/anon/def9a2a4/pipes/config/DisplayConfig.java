package anon.def9a2a4.pipes.config;

import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.Map;

/**
 * Internal display configuration from display.yml.
 * Contains texture sets and display transformation settings.
 */
public class DisplayConfig {
    // Texture sets keyed by ID
    private final Map<String, TextureSet> textureSets;

    // Pipe display settings
    private final double facingScale;
    private final double perpendicularScale;
    private final DirectionalOffset offsetHorizontal;
    private final DirectionalOffset offsetUp;
    private final DirectionalOffset offsetDown;

    // Corner pipe display settings
    private final double cornerScale;
    private final double cornerHeight;
    private final double cornerDirectionalForwardOffset;

    // Adjustment maps for source and destination
    private final Map<String, DirectionalAdjustment> sourceAdjustments;
    private final Map<String, DirectionalAdjustment> destinationAdjustments;
    private final Map<String, DirectionalAdjustment> cornerDestinationAdjustments;

    public DisplayConfig(FileConfiguration config) {
        // Load texture sets
        this.textureSets = loadTextureSets(config);

        // Pipe display
        this.facingScale = config.getDouble("display.facing-scale", 2.0);
        this.perpendicularScale = config.getDouble("display.perpendicular-scale", 0.998);

        this.offsetHorizontal = new DirectionalOffset(
            config.getDouble("display.offset-horizontal.forward", 0.5),
            config.getDouble("display.offset-horizontal.right", 0.0),
            config.getDouble("display.offset-horizontal.up", 0.2495)
        );
        this.offsetUp = new DirectionalOffset(
            config.getDouble("display.offset-up.forward", 1.0),
            config.getDouble("display.offset-up.right", 0.0),
            config.getDouble("display.offset-up.up", 0.0)
        );
        this.offsetDown = new DirectionalOffset(
            config.getDouble("display.offset-down.forward", 0.0),
            config.getDouble("display.offset-down.right", 0.0),
            config.getDouble("display.offset-down.up", 0.0)
        );

        // Corner pipe display
        this.cornerScale = config.getDouble("corner-pipe.display.scale", 1.1);
        this.cornerHeight = config.getDouble("corner-pipe.display.height", 0.775);
        this.cornerDirectionalForwardOffset = config.getDouble("corner-pipe.display.directional-forward-offset", 0.0);

        // Load adjustment maps
        this.sourceAdjustments = loadAdjustments(config, "adjustments.source");
        this.destinationAdjustments = loadAdjustments(config, "adjustments.destination");
        this.cornerDestinationAdjustments = loadAdjustments(config, "corner-pipe.adjustments.destination");
    }

    private Map<String, TextureSet> loadTextureSets(FileConfiguration config) {
        Map<String, TextureSet> sets = new HashMap<>();
        ConfigurationSection section = config.getConfigurationSection("texture-sets");
        if (section == null) {
            return sets;
        }

        for (String setId : section.getKeys(false)) {
            ConfigurationSection setSection = section.getConfigurationSection(setId);
            if (setSection == null) continue;

            String itemTexture = setSection.getString("item");
            if (itemTexture == null || itemTexture.isEmpty()) continue;

            // Parse head textures
            Map<BlockFace, String> headTextures = new HashMap<>();
            ConfigurationSection headSection = setSection.getConfigurationSection("head");
            if (headSection != null) {
                String horizontal = headSection.getString("horizontal");
                if (horizontal != null) headTextures.put(BlockFace.NORTH, horizontal);

                String up = headSection.getString("up");
                if (up != null) headTextures.put(BlockFace.UP, up);

                String down = headSection.getString("down");
                if (down != null) headTextures.put(BlockFace.DOWN, down);
            }
            if (headTextures.isEmpty()) {
                headTextures.put(BlockFace.NORTH, itemTexture);
            }

            // Parse item-display textures
            Map<BlockFace, String> itemDisplayTextures = new HashMap<>();
            if (setSection.isString("item-display")) {
                // Single texture for all directions (corner pipes)
                String singleTexture = setSection.getString("item-display");
                itemDisplayTextures.put(BlockFace.NORTH, singleTexture);
            } else {
                ConfigurationSection displaySection = setSection.getConfigurationSection("item-display");
                if (displaySection != null) {
                    String horizontal = displaySection.getString("horizontal");
                    if (horizontal != null) itemDisplayTextures.put(BlockFace.NORTH, horizontal);

                    String up = displaySection.getString("up");
                    if (up != null) itemDisplayTextures.put(BlockFace.UP, up);

                    String down = displaySection.getString("down");
                    if (down != null) itemDisplayTextures.put(BlockFace.DOWN, down);
                }
            }
            if (itemDisplayTextures.isEmpty()) {
                itemDisplayTextures.put(BlockFace.NORTH, itemTexture);
            }

            // Parse directional-display textures (for corner pipe second display)
            Map<BlockFace, String> directionalDisplayTextures = new HashMap<>();
            ConfigurationSection directionalSection = setSection.getConfigurationSection("directional-display");
            if (directionalSection != null) {
                String horizontal = directionalSection.getString("horizontal");
                if (horizontal != null) directionalDisplayTextures.put(BlockFace.NORTH, horizontal);

                String down = directionalSection.getString("down");
                if (down != null) directionalDisplayTextures.put(BlockFace.DOWN, down);
            }

            sets.put(setId, new TextureSet(itemTexture, headTextures, itemDisplayTextures, directionalDisplayTextures));
        }

        return sets;
    }

    private Map<String, DirectionalAdjustment> loadAdjustments(FileConfiguration config, String basePath) {
        Map<String, DirectionalAdjustment> map = new HashMap<>();
        ConfigurationSection section = config.getConfigurationSection(basePath);
        if (section == null) {
            return map;
        }

        for (String category : section.getKeys(false)) {
            map.put(category, new DirectionalAdjustment(
                config.getDouble(basePath + "." + category + ".side", 0.0),
                config.getDouble(basePath + "." + category + ".up", 0.0),
                config.getDouble(basePath + "." + category + ".down", 0.0)
            ));
        }
        return map;
    }

    /**
     * Get a texture set by ID.
     * @return The texture set, or null if not found
     */
    public TextureSet getTextureSet(String id) {
        return textureSets.get(id);
    }

    /**
     * Check if a texture set exists.
     */
    public boolean hasTextureSet(String id) {
        return textureSets.containsKey(id);
    }

    public double getFacingScale() {
        return facingScale;
    }

    public double getPerpendicularScale() {
        return perpendicularScale;
    }

    public DirectionalOffset getOffsetHorizontal() {
        return offsetHorizontal;
    }

    public DirectionalOffset getOffsetUp() {
        return offsetUp;
    }

    public DirectionalOffset getOffsetDown() {
        return offsetDown;
    }

    public double getCornerScale() {
        return cornerScale;
    }

    public double getCornerHeight() {
        return cornerHeight;
    }

    public double getCornerDirectionalForwardOffset() {
        return cornerDirectionalForwardOffset;
    }

    public double getSourceAdjustment(String category, String direction) {
        DirectionalAdjustment adj = sourceAdjustments.get(category);
        if (adj == null) {
            return 0.0;
        }
        return adj.get(direction);
    }

    public double getDestinationAdjustment(String category, String direction) {
        DirectionalAdjustment adj = destinationAdjustments.get(category);
        if (adj == null) {
            return 0.0;
        }
        return adj.get(direction);
    }

    public double getCornerDestinationAdjustment(String category, String direction) {
        DirectionalAdjustment adj = cornerDestinationAdjustments.get(category);
        if (adj == null) {
            // Fall back to global destination adjustments
            return getDestinationAdjustment(category, direction);
        }
        return adj.get(direction);
    }

    /**
     * Holds forward/right/up offsets for a pipe orientation
     */
    public record DirectionalOffset(double forward, double right, double up) {}

    /**
     * Holds side/up/down adjustments for a block category
     */
    public record DirectionalAdjustment(double side, double up, double down) {
        public double get(String direction) {
            return switch (direction) {
                case "side" -> side;
                case "up" -> up;
                case "down" -> down;
                default -> 0.0;
            };
        }
    }

    /**
     * Holds texture data for a pipe variant.
     */
    public record TextureSet(
            String item,
            Map<BlockFace, String> head,
            Map<BlockFace, String> itemDisplay,
            Map<BlockFace, String> directionalDisplay
    ) {
        /**
         * Get the head texture for a given facing direction.
         */
        public String getHeadTexture(BlockFace facing) {
            if (facing == BlockFace.UP || facing == BlockFace.DOWN) {
                String texture = head.get(facing);
                if (texture != null) return texture;
            }
            return head.getOrDefault(BlockFace.NORTH, item);
        }

        /**
         * Get the item display texture for a given facing direction.
         */
        public String getItemDisplayTexture(BlockFace facing) {
            if (facing == BlockFace.UP || facing == BlockFace.DOWN) {
                String texture = itemDisplay.get(facing);
                if (texture != null) return texture;
            }
            return itemDisplay.getOrDefault(BlockFace.NORTH, item);
        }

        /**
         * Get the directional display texture for corner pipes.
         * Falls back to item display texture if not specified.
         */
        public String getDirectionalDisplayTexture(BlockFace facing) {
            if (facing == BlockFace.DOWN) {
                String texture = directionalDisplay.get(BlockFace.DOWN);
                if (texture != null) return texture;
            }
            // Horizontal directions use NORTH key
            String texture = directionalDisplay.get(BlockFace.NORTH);
            if (texture != null) return texture;
            // Fall back to item display texture
            return getItemDisplayTexture(facing);
        }
    }
}
