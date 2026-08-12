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
    int maxStructureSize = 256; // cap on a mover's captured structure
    boolean dynamicLights = true; // tag light-emitting mechanism blocks for the optional DynLight plugin
    // Cap on a glued selection. Deliberately independent of (and larger than) maxStructureSize: a
    // vehicle-sized anchor — a BlockShips ship, whose own limit is 1000 blocks — needs room to glue
    // extras across a whole hull, whereas maxStructureSize bounds a door or drawbridge. Safe to raise
    // only because GlueManager.connects() probes six neighbours instead of scanning the connector set;
    // with the old linear scan a fill this large would have been quadratic on the main thread.
    static final int DEFAULT_GLUE_MAX_SIZE = 1024;
    int glueMaxSize = DEFAULT_GLUE_MAX_SIZE;
    int glueOutlineInterval = 5;
    int glueOutlineRange = 10;   // blocks from the player within which an anchor's declared body (hull) is outlined
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
    int sieveTickInterval = 40;
    int sieveMaxBatch = 1;
    int sieveTankUnits = 4;
    int sieveWaterPerCycles = 16;
    int pumpTickInterval = 40;
    double pumpStretchToCorner = 0.1985; // body-top raise under a corner pipe; 1.0015 + d = 1.2, a vertical pipe's height under a corner
    int boilerTankUnits = 8;
    int steamWaterIntervalTicks = 1200;   // running time per boiler water unit (~60 s)
    int placerTickInterval = 20;
    int suctionTickInterval = 2;
    double suctionPullRange = 2.5;     // pull-box side = 2*(range+0.75); 2.5 → 6.5×6.5×6.5 cube
    double suctionPullStrength = 0.14; // fixed inward velocity (≈ fanMinPush)
    int chainPulleyMaxDistance = 32;   // max chain-pulley link distance (blocks)
    double pistonMaxStep = 0.25;       // extendable piston (+ hoist): cap on per-tick slide velocity (blocks/tick)
    int dynamoTickInterval = 10;       // redstone dynamo: ticks between comparator-output refreshes
    String dynamoDefaultMode = "TOTAL";      // TOTAL | USED | UNUSED
    String dynamoDefaultScaling = "CLAMP";   // CLAMP | MOD15 | DIV15
    // Mechanical dispenser: extra launch velocity (blocks/tick) added along the facing when powered.
    // Boost scales with the network's supplied power, clamped at mechanicalDispenserPowerCap.
    int mechanicalDispenserPowerCap = 10;        // "anything over 10 power does nothing"
    double mechanicalDispenserMinBoost = 0.4;    // boost floor when powered
    double mechanicalDispenserBoostPerPower = 0.2;
    double mechanicalDispenserMaxBoost = 2.0;    // cap for arrows/items (~3.1 b/t arrow at max power)
    double mechanicalDispenserTntMaxBoost = 1.5; // forward TNT throw at max power
    double mechanicalDispenserMinStraightness = 0.8; // spread reduction at min power (1.0 = dead straight)
    Set<Material> drillBlacklist = Set.of(
            Material.BEDROCK, Material.SPAWNER,
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

    /**
     * @param blowsOutward    the machine acts AWAY from its mount, so a floor-mounted one aims UP
     *                        (a fan blows upward off the floor). Replaces what used to be a hardcoded
     *                        {@code "mech:fan"} string test in the driver, so propellers and thrusters
     *                        can share the rule.
     * @param mechPowerScale  multiplier applied to this block's SUPPLY while riding a mechanism, and
     *                        only to supply — the same {@code power:} value also feeds demand, so
     *                        scaling the raw number would quietly make consumers cheaper too. Lets a
     *                        windmill be worth less aboard a ship than bolted to the ground.
     */
    record MechRotationMeta(MechKind kind, boolean gearLike, boolean gearbox, MechAxisRule axisRule,
                            boolean blowsOutward, double mechPowerScale) {

        /** Defaults: acts along its mount, full power aboard. */
        MechRotationMeta(MechKind kind, boolean gearLike, boolean gearbox, MechAxisRule axisRule) {
            this(kind, gearLike, gearbox, axisRule, false, 1.0);
        }

        /** This block's supply while riding, from its configured power. */
        int scaledSupply(int power) {
            if (mechPowerScale >= 1.0) return power;
            // Ceil so a tier-1 windmill (power 1) at 0.5 still supplies 1 rather than silently
            // becoming dead weight aboard.
            return power <= 0 ? power : (int) Math.ceil(power * mechPowerScale);
        }
    }

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
        dynamicLights = yaml.getBoolean("dynamic-lights", dynamicLights);
        glueMaxSize = DEFAULT_GLUE_MAX_SIZE; // overridden by glue.max-size below

        ConfigurationSection glue = yaml.getConfigurationSection("glue");
        if (glue != null) {
            glueMaxSize = glue.getInt("max-size", DEFAULT_GLUE_MAX_SIZE);
            glueOutlineInterval = glue.getInt("outline-interval", glueOutlineInterval);
            glueOutlineRange = glue.getInt("outline-range", glueOutlineRange);
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

        ConfigurationSection pd = yaml.getConfigurationSection("mechanical-dispenser");
        if (pd != null) {
            mechanicalDispenserPowerCap = pd.getInt("power-cap", mechanicalDispenserPowerCap);
            mechanicalDispenserMinBoost = pd.getDouble("min-boost", mechanicalDispenserMinBoost);
            mechanicalDispenserBoostPerPower = pd.getDouble("boost-per-power", mechanicalDispenserBoostPerPower);
            mechanicalDispenserMaxBoost = pd.getDouble("max-boost", mechanicalDispenserMaxBoost);
            mechanicalDispenserTntMaxBoost = pd.getDouble("tnt-max-boost", mechanicalDispenserTntMaxBoost);
            mechanicalDispenserMinStraightness = pd.getDouble("min-straightness", mechanicalDispenserMinStraightness);
            loaded++;
        }

        ConfigurationSection press = yaml.getConfigurationSection("press");
        if (press != null) {
            pressTickInterval = press.getInt("tick-interval", pressTickInterval);
            pressMaxBatch = press.getInt("max-batch", pressMaxBatch);
            loaded++;
        }

        ConfigurationSection sieve = yaml.getConfigurationSection("sieve");
        if (sieve != null) {
            sieveTickInterval = sieve.getInt("tick-interval", sieveTickInterval);
            sieveMaxBatch = sieve.getInt("max-batch", sieveMaxBatch);
            sieveTankUnits = sieve.getInt("tank-units", sieveTankUnits);
            sieveWaterPerCycles = sieve.getInt("water-per-cycles", sieveWaterPerCycles);
            loaded++;
        }

        ConfigurationSection pump = yaml.getConfigurationSection("pump");
        if (pump != null) {
            pumpTickInterval = pump.getInt("tick-interval", pumpTickInterval);
            pumpStretchToCorner = pump.getDouble("stretch-to-corner", pumpStretchToCorner);
            loaded++;
        }

        ConfigurationSection steam = yaml.getConfigurationSection("steam");
        if (steam != null) {
            boilerTankUnits = steam.getInt("boiler-tank-units", boilerTankUnits);
            steamWaterIntervalTicks = steam.getInt("water-interval-ticks", steamWaterIntervalTicks);
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
            suctionPullRange    = suction.getDouble("pull-range", suctionPullRange);
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
                boolean gearbox = entry != null && entry.getBoolean("gearbox", false);
                boolean blowsOutward = entry != null && entry.getBoolean("blows_outward", false);
                double powerScale = entry != null ? entry.getDouble("mech_power_scale", 1.0) : 1.0;
                mechMetaValues.put(key,
                    new MechRotationMeta(kind, gearLike, gearbox, axis, blowsOutward, powerScale));
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

    /**
     * Mirrors the {@code power:} block of rotation-config.yml key for key. That YAML always wins
     * (it is read from the JAR, never a data folder), so these only surface if a key is dropped
     * from it — but because this map is pre-populated, a key present here makes the per-call-site
     * fallback in {@link #getPower} dead, so THIS is the value a dropped key falls back to. Keep
     * the two in sync; a silent revert to an old balance is the failure mode otherwise.
     */
    private void initDefaultPower() {
        powerValues.put("windmill", 1);
        powerValues.put("large_windmill", 5);
        powerValues.put("huge_windmill", 15);
        powerValues.put("water_wheel", 2);
        powerValues.put("engine", 10);
        powerValues.put("steam_piston", 20);
        powerValues.put("redstone_motor", 1);
        powerValues.put("drill", 5);
        powerValues.put("millstone", 5);
        powerValues.put("fan", 2);
        powerValues.put("press", 4);
        powerValues.put("sieve", 10);
        powerValues.put("pump", 4);
        powerValues.put("placer", 2);
        powerValues.put("suction_hopper", 3);
        powerValues.put("mechanical_dispenser", 1);
        powerValues.put("piston_core", 1);
        powerValues.put("chain_hoist", 1);
        powerValues.put("propeller", 5);
        powerValues.put("large_propeller", 10);
        powerValues.put("huge_propeller", 20);
        powerValues.put("reaction_wheel", 1);
    }

    private void initDefaultMechMeta() {
        var t = new MechRotationMeta(MechKind.TRANSMITTER, false, false, MechAxisRule.FROM_STATE);
        mechMetaValues.put("shaft", t);
        mechMetaValues.put("reverser", t);        // redstone inert while riding → plain shaft
        mechMetaValues.put("clutch", t);          // redstone inert → never locks
        mechMetaValues.put("ratchet", t);         // freewheel gating inert while riding → plain shaft
        mechMetaValues.put("chain_pulley", t);    // chain edges deferred on mechanisms
        mechMetaValues.put("redstone_dynamo", t);
        mechMetaValues.put("water_wheel", t);     // no live water check while riding → never a source
        mechMetaValues.put("gear", new MechRotationMeta(MechKind.TRANSMITTER, true, false, MechAxisRule.FROM_STATE));
        // Gearbox: omnidirectional transmitter (couples aligned shafts/gears on all six faces without
        // reversing) — one per casing wood. Axis nominal (FROM_STATE → Y); connectivity is gearbox-driven.
        var gearboxMeta = new MechRotationMeta(MechKind.TRANSMITTER, false, true, MechAxisRule.FROM_STATE);
        for (String wood : RotationBlocks.CASING_WOODS) {
            mechMetaValues.put("gearbox_" + wood, gearboxMeta);
        }
        mechMetaValues.put("engine", new MechRotationMeta(MechKind.ENGINE, false, false, MechAxisRule.FROM_STATE));
        var src = new MechRotationMeta(MechKind.CONSTANT_SOURCE, false, false, MechAxisRule.FROM_STATE);
        mechMetaValues.put("redstone_motor", src);
        // Windmills supply HALF aboard a mechanism. They are constant sources with no fuel and no wind
        // check, so at full strength a wall of them makes a flying ship free to run forever and the
        // fuel-burning thruster pointless. Halved (ceil, so tier 1 still supplies 1) they remain
        // worth carrying without dominating.
        var windSrc = new MechRotationMeta(MechKind.CONSTANT_SOURCE, false, false, MechAxisRule.FROM_STATE,
            false, 0.5);
        mechMetaValues.put("windmill", windSrc);
        mechMetaValues.put("large_windmill", windSrc);
        mechMetaValues.put("huge_windmill", windSrc);
        var consumer = new MechRotationMeta(MechKind.CONSUMER, false, false, MechAxisRule.FROM_STATE);
        var consumerY = new MechRotationMeta(MechKind.CONSUMER, false, false, MechAxisRule.FIXED_Y);
        mechMetaValues.put("drill", consumer);
        mechMetaValues.put("millstone", consumerY);
        mechMetaValues.put("press", consumerY);
        mechMetaValues.put("placer", consumerY);
        // blows_outward: a fan acts away from whatever it is mounted on, so a floor fan aims UP.
        mechMetaValues.put("fan", new MechRotationMeta(MechKind.CONSUMER, false, false,
            MechAxisRule.FROM_FACING, true, 1.0));
        mechMetaValues.put("suction_hopper", new MechRotationMeta(MechKind.CONSUMER, false, false, MechAxisRule.OMNI));
    }
}
