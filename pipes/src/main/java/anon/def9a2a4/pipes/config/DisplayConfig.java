package anon.def9a2a4.pipes.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.Map;

public class DisplayConfig {

    private final double facingScale;
    private final double perpendicularScale;
    private final DirectionalOffset offsetHorizontal;
    private final DirectionalOffset offsetUp;
    private final DirectionalOffset offsetDown;

    private final double cornerScale;
    private final double cornerHeight;
    private final double cornerDirectionalForwardOffset;

    private final Map<String, DirectionalAdjustment> sourceAdjustments;
    private final Map<String, DirectionalAdjustment> destinationAdjustments;
    private final Map<String, DirectionalAdjustment> cornerDestinationAdjustments;

    public DisplayConfig(FileConfiguration config) {
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

        this.cornerScale = config.getDouble("corner-pipe.display.scale", 1.1);
        this.cornerHeight = config.getDouble("corner-pipe.display.height", 0.775);
        this.cornerDirectionalForwardOffset = config.getDouble("corner-pipe.display.directional-forward-offset", 0.0);

        this.sourceAdjustments = loadAdjustments(config, "adjustments.source");
        this.destinationAdjustments = loadAdjustments(config, "adjustments.destination");
        this.cornerDestinationAdjustments = loadAdjustments(config, "corner-pipe.adjustments.destination");
    }

    private Map<String, DirectionalAdjustment> loadAdjustments(FileConfiguration config, String basePath) {
        Map<String, DirectionalAdjustment> map = new HashMap<>();
        ConfigurationSection section = config.getConfigurationSection(basePath);
        if (section == null) return map;

        for (String category : section.getKeys(false)) {
            map.put(category, new DirectionalAdjustment(
                config.getDouble(basePath + "." + category + ".side", 0.0),
                config.getDouble(basePath + "." + category + ".up", 0.0),
                config.getDouble(basePath + "." + category + ".down", 0.0)
            ));
        }
        return map;
    }

    public double getFacingScale() { return facingScale; }
    public double getPerpendicularScale() { return perpendicularScale; }
    public DirectionalOffset getOffsetHorizontal() { return offsetHorizontal; }
    public DirectionalOffset getOffsetUp() { return offsetUp; }
    public DirectionalOffset getOffsetDown() { return offsetDown; }
    public double getCornerScale() { return cornerScale; }
    public double getCornerHeight() { return cornerHeight; }
    public double getCornerDirectionalForwardOffset() { return cornerDirectionalForwardOffset; }

    public double getSourceAdjustment(String category, String direction) {
        DirectionalAdjustment adj = sourceAdjustments.get(category);
        if (adj == null) return 0.0;
        return adj.get(direction);
    }

    public double getDestinationAdjustment(String category, String direction) {
        DirectionalAdjustment adj = destinationAdjustments.get(category);
        if (adj == null) return 0.0;
        return adj.get(direction);
    }

    public double getCornerDestinationAdjustment(String category, String direction) {
        DirectionalAdjustment adj = cornerDestinationAdjustments.get(category);
        if (adj == null) return getDestinationAdjustment(category, direction);
        return adj.get(direction);
    }

    public record DirectionalOffset(double forward, double right, double up) {}

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
}
