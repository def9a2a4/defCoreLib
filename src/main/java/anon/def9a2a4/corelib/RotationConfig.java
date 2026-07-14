package anon.def9a2a4.corelib;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.logging.Logger;

final class RotationConfig {

    int maxNetworkSize = 256;
    int maxStructureSize = 256; // default cap for glued selections; overridable via glue.max-size
    int glueMaxSize = 256;      // cap on a glued selection; defaults to maxStructureSize
    int glueOutlineInterval = 5;
    int glueSessionTimeout = 2400;
    int drillTickInterval = 4;
    int drillBreakStages = 10;
    int millstoneTickInterval = 20;
    int millstoneMaxBatch = 8;
    int fanTickInterval = 2;
    int fanRange = 5;
    double fanMinPush = 0.12;
    double fanMaxPush = 0.5;
    double fanPushPerPower = 0.08;
    int pressTickInterval = 20;
    int pressMaxBatch = 8;
    int placerTickInterval = 20;
    int suctionTickInterval = 2;
    int suctionPullRange = 1;          // Chebyshev radius: 1 → 3×3×3 cube
    double suctionPullStrength = 0.14; // fixed inward velocity (≈ fanMinPush)
    int chainPulleyMaxDistance = 32;   // max chain-pulley link distance (blocks)
    double pistonMaxStep = 0.5;        // extendable piston: cap on per-tick slide velocity (blocks/tick)
    int dynamoTickInterval = 10;       // redstone dynamo: ticks between comparator-output refreshes
    String dynamoDefaultMode = "TOTAL";      // TOTAL | USED | UNUSED
    String dynamoDefaultScaling = "CLAMP";   // CLAMP | MOD15 | DIV15
    Set<Material> drillBlacklist = Set.of(
            Material.OBSIDIAN, Material.CRYING_OBSIDIAN, Material.SPAWNER,
            Material.MOVING_PISTON, Material.REINFORCED_DEEPSLATE);
    Map<Material, Integer> fuelValues = new HashMap<>();
    Map<String, Integer> powerValues = new HashMap<>();
    Map<String, MechRotationMeta> mechMetaValues = new HashMap<>();

    // ── Mechanism-mode metadata (the `mechanism:` YAML section) ─────────────
    // How each rotation block behaves while assembled into a moving mechanism
    // (see MechanismRotationDriver). Data-driven like `power:`; power itself is
    // NOT duplicated here — a mechanism node's supply/demand comes from the
    // same `power:` map the static network uses.

    /** Role of a rotation block while assembled into a mechanism. */
    enum MechKind {
        /** Passes power along; never supplies or consumes. */ TRANSMITTER,
        /** Adds demand and actuates at its live world position. */ CONSUMER,
        /** Fuel-burning source: supplies only while running (driver-owned fuel counter). */ ENGINE,
        /** Always-on source while assembled (windmills; the redstone motor sees no redstone). */ CONSTANT_SOURCE
    }

    /** How a mechanism-mounted block's network axis is derived from its captured snapshot. */
    enum MechAxisRule {
        /** {@code RotationNetwork.axisFromState} on the captured state (shaft, gear, drill, …). */ FROM_STATE,
        /** Always Y — driven by a shaft on top (millstone, press, placer). */ FIXED_Y,
        /** {@code RotationNetwork.axisFromFace} on the captured facing (fan). */ FROM_FACING,
        /** Omni consumer: draws from the first aligned neighbor on any non-mounted face (suction hopper). */ OMNI
    }

    record MechRotationMeta(MechKind kind, boolean gearLike, MechAxisRule axisRule) {}

    /** Mechanism-mode metadata for a full block id ({@code mech:shaft}), or null when the block
     *  takes no part in a mechanism's rotation network. Keyed by short name, like {@code power:}. */
    @org.jetbrains.annotations.Nullable MechRotationMeta mechMeta(String blockTypeId) {
        int i = blockTypeId.indexOf(':');
        return mechMetaValues.get(i >= 0 ? blockTypeId.substring(i + 1) : blockTypeId);
    }

    RotationConfig() {
        initDefaultFuel();
        initDefaultPower();
        initDefaultMechMeta();
    }

    int load(InputStream stream, Logger logger) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(stream));
        int loaded = 0;

        maxNetworkSize = yaml.getInt("max-network-size", maxNetworkSize);
        maxStructureSize = yaml.getInt("max-structure-size", maxStructureSize);
        glueMaxSize = maxStructureSize; // default; overridden by glue.max-size below

        ConfigurationSection glue = yaml.getConfigurationSection("glue");
        if (glue != null) {
            glueMaxSize = glue.getInt("max-size", maxStructureSize);
            glueOutlineInterval = glue.getInt("outline-interval", glueOutlineInterval);
            glueSessionTimeout = glue.getInt("session-timeout", glueSessionTimeout);
            loaded++;
        }

        ConfigurationSection drill = yaml.getConfigurationSection("drill");
        if (drill != null) {
            drillTickInterval = drill.getInt("tick-interval", drillTickInterval);
            drillBreakStages = drill.getInt("break-stages", drillBreakStages);
            List<String> bl = drill.getStringList("blacklist");
            if (!bl.isEmpty()) {
                Set<Material> set = new HashSet<>();
                for (String name : bl) {
                    Material m = Material.matchMaterial(name);
                    if (m != null) set.add(m);
                    else logger.warning("rotation-config: unknown blacklist material: " + name);
                }
                drillBlacklist = Set.copyOf(set);
            }
            loaded++;
        }

        ConfigurationSection millstone = yaml.getConfigurationSection("millstone");
        if (millstone != null) {
            millstoneTickInterval = millstone.getInt("tick-interval", millstoneTickInterval);
            millstoneMaxBatch = millstone.getInt("max-batch", millstoneMaxBatch);
            loaded++;
        }

        ConfigurationSection fan = yaml.getConfigurationSection("fan");
        if (fan != null) {
            fanTickInterval = fan.getInt("tick-interval", fanTickInterval);
            fanRange = fan.getInt("range", fanRange);
            fanMinPush = fan.getDouble("min-push", fanMinPush);
            fanMaxPush = fan.getDouble("max-push", fanMaxPush);
            fanPushPerPower = fan.getDouble("push-per-power", fanPushPerPower);
            loaded++;
        }

        ConfigurationSection press = yaml.getConfigurationSection("press");
        if (press != null) {
            pressTickInterval = press.getInt("tick-interval", pressTickInterval);
            pressMaxBatch = press.getInt("max-batch", pressMaxBatch);
            loaded++;
        }

        ConfigurationSection placer = yaml.getConfigurationSection("placer");
        if (placer != null) {
            placerTickInterval = placer.getInt("tick-interval", placerTickInterval);
            loaded++;
        }

        ConfigurationSection suction = yaml.getConfigurationSection("suction_hopper");
        if (suction != null) {
            suctionTickInterval = suction.getInt("tick-interval", suctionTickInterval);
            suctionPullRange    = suction.getInt("pull-range", suctionPullRange);
            suctionPullStrength = suction.getDouble("pull-strength", suctionPullStrength);
            loaded++;
        }

        ConfigurationSection fuel = yaml.getConfigurationSection("fuel");
        if (fuel != null) {
            fuelValues.clear();
            for (String key : fuel.getKeys(false)) {
                Material m = Material.matchMaterial(key);
                if (m != null) fuelValues.put(m, fuel.getInt(key));
                else logger.warning("rotation-config: unknown fuel material: " + key);
            }
            // Dynamic scan for wood types
            for (Material m : Material.values()) {
                String name = m.name();
                if (name.endsWith("_LOG") || name.endsWith("_WOOD")
                        || name.endsWith("_STEM") || name.endsWith("_HYPHAE")) {
                    fuelValues.putIfAbsent(m, 8);
                }
                if (name.endsWith("_PLANKS")) {
                    fuelValues.putIfAbsent(m, 3);
                }
            }
            loaded++;
        }

        ConfigurationSection chainPulley = yaml.getConfigurationSection("chain-pulley");
        if (chainPulley != null) {
            chainPulleyMaxDistance = chainPulley.getInt("max-distance", chainPulleyMaxDistance);
            loaded++;
        }

        ConfigurationSection piston = yaml.getConfigurationSection("piston");
        if (piston != null) {
            pistonMaxStep = piston.getDouble("max-step", pistonMaxStep);
            loaded++;
        }

        ConfigurationSection dynamo = yaml.getConfigurationSection("redstone_dynamo");
        if (dynamo != null) {
            dynamoTickInterval = dynamo.getInt("tick-interval", dynamoTickInterval);
            dynamoDefaultMode = dynamo.getString("default-mode", dynamoDefaultMode);
            dynamoDefaultScaling = dynamo.getString("default-scaling", dynamoDefaultScaling);
            loaded++;
        }

        ConfigurationSection power = yaml.getConfigurationSection("power");
        if (power != null) {
            powerValues.clear();
            for (String key : power.getKeys(false)) {
                powerValues.put(key, power.getInt(key));
            }
            loaded++;
        }

        ConfigurationSection mechanism = yaml.getConfigurationSection("mechanism");
        if (mechanism != null) {
            for (String key : mechanism.getKeys(false)) {
                ConfigurationSection entry = mechanism.getConfigurationSection(key);
                String kindStr = entry != null ? entry.getString("kind") : mechanism.getString(key);
                if (kindStr == null) {
                    logger.warning("rotation-config: mechanism." + key + " has no 'kind' — skipping");
                    continue;
                }
                if (kindStr.equalsIgnoreCase("none")) {   // opt a default entry out
                    mechMetaValues.remove(key);
                    continue;
                }
                MechKind kind; MechAxisRule axis;
                try {
                    kind = MechKind.valueOf(kindStr.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    logger.warning("rotation-config: mechanism." + key + " unknown kind: " + kindStr);
                    continue;
                }
                String axisStr = entry != null ? entry.getString("axis", "state") : "state";
                axis = switch (axisStr.toLowerCase(Locale.ROOT)) {
                    case "state" -> MechAxisRule.FROM_STATE;
                    case "y" -> MechAxisRule.FIXED_Y;
                    case "facing" -> MechAxisRule.FROM_FACING;
                    case "omni" -> MechAxisRule.OMNI;
                    default -> null;
                };
                if (axis == null) {
                    logger.warning("rotation-config: mechanism." + key + " unknown axis: " + axisStr);
                    continue;
                }
                boolean gearLike = entry != null && entry.getBoolean("gear_like", false);
                mechMetaValues.put(key, new MechRotationMeta(kind, gearLike, axis));
            }
            loaded++;
        }

        return loaded;
    }

    int getPower(String blockName, int defaultValue) {
        return powerValues.getOrDefault(blockName, defaultValue);
    }

    private void initDefaultFuel() {
        fuelValues.put(Material.COAL, 16);
        fuelValues.put(Material.CHARCOAL, 12);
        fuelValues.put(Material.COAL_BLOCK, 144);
        fuelValues.put(Material.LAVA_BUCKET, 100);
        fuelValues.put(Material.BLAZE_ROD, 20);
        fuelValues.put(Material.STICK, 1);
        for (Material m : Material.values()) {
            String name = m.name();
            if (name.endsWith("_LOG") || name.endsWith("_WOOD")
                    || name.endsWith("_STEM") || name.endsWith("_HYPHAE")) {
                fuelValues.putIfAbsent(m, 8);
            }
            if (name.endsWith("_PLANKS")) {
                fuelValues.putIfAbsent(m, 3);
            }
        }
    }

    private void initDefaultPower() {
        powerValues.put("windmill", 1);
        powerValues.put("large_windmill", 5);
        powerValues.put("huge_windmill", 15);
        powerValues.put("water_wheel", 2);
        powerValues.put("engine", 5);
        powerValues.put("redstone_motor", 1);
        powerValues.put("drill", 1);
        powerValues.put("millstone", 1);
        powerValues.put("fan", 1);
        powerValues.put("press", 1);
        powerValues.put("placer", 1);
        powerValues.put("suction_hopper", 1);
    }

    private void initDefaultMechMeta() {
        var t = new MechRotationMeta(MechKind.TRANSMITTER, false, MechAxisRule.FROM_STATE);
        mechMetaValues.put("shaft", t);
        mechMetaValues.put("reverser", t);        // redstone inert while riding → plain shaft
        mechMetaValues.put("clutch", t);          // redstone inert → never locks
        mechMetaValues.put("chain_pulley", t);    // chain edges deferred on mechanisms
        mechMetaValues.put("redstone_dynamo", t);
        mechMetaValues.put("water_wheel", t);     // no live water check while riding → never a source
        mechMetaValues.put("gear", new MechRotationMeta(MechKind.TRANSMITTER, true, MechAxisRule.FROM_STATE));
        mechMetaValues.put("engine", new MechRotationMeta(MechKind.ENGINE, false, MechAxisRule.FROM_STATE));
        var src = new MechRotationMeta(MechKind.CONSTANT_SOURCE, false, MechAxisRule.FROM_STATE);
        mechMetaValues.put("redstone_motor", src);
        mechMetaValues.put("windmill", src);
        mechMetaValues.put("large_windmill", src);
        mechMetaValues.put("huge_windmill", src);
        var consumer = new MechRotationMeta(MechKind.CONSUMER, false, MechAxisRule.FROM_STATE);
        var consumerY = new MechRotationMeta(MechKind.CONSUMER, false, MechAxisRule.FIXED_Y);
        mechMetaValues.put("drill", consumer);
        mechMetaValues.put("millstone", consumerY);
        mechMetaValues.put("press", consumerY);
        mechMetaValues.put("placer", consumerY);
        mechMetaValues.put("fan", new MechRotationMeta(MechKind.CONSUMER, false, MechAxisRule.FROM_FACING));
        mechMetaValues.put("suction_hopper", new MechRotationMeta(MechKind.CONSUMER, false, MechAxisRule.OMNI));
    }
}
