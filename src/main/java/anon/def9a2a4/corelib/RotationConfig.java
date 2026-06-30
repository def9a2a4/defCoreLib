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
    int maxStructureSize = 256; // cap on flood-fill-selected mechanism structures (door/rotator/minecart)
    int glueMaxSize = 256;      // cap on a glued selection; defaults to maxStructureSize
    int glueOutlineInterval = 5;
    int glueSessionTimeout = 2400;
    int drillTickInterval = 4;
    int drillBreakStages = 10;
    int grindstoneTickInterval = 20;
    int grindstoneMaxBatch = 8;
    int fanTickInterval = 2;
    int fanRange = 5;
    double fanMinPush = 0.12;
    double fanMaxPush = 0.5;
    double fanPushPerSU = 0.08;
    int pressTickInterval = 20;
    int pressMaxBatch = 8;
    Set<Material> drillBlacklist = Set.of(
            Material.OBSIDIAN, Material.CRYING_OBSIDIAN, Material.SPAWNER,
            Material.MOVING_PISTON, Material.REINFORCED_DEEPSLATE);
    Map<Material, Integer> fuelValues = new HashMap<>();
    Map<String, Integer> powerValues = new HashMap<>();

    RotationConfig() {
        initDefaultFuel();
        initDefaultPower();
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

        ConfigurationSection grindstone = yaml.getConfigurationSection("grindstone");
        if (grindstone != null) {
            grindstoneTickInterval = grindstone.getInt("tick-interval", grindstoneTickInterval);
            grindstoneMaxBatch = grindstone.getInt("max-batch", grindstoneMaxBatch);
            loaded++;
        }

        ConfigurationSection fan = yaml.getConfigurationSection("fan");
        if (fan != null) {
            fanTickInterval = fan.getInt("tick-interval", fanTickInterval);
            fanRange = fan.getInt("range", fanRange);
            fanMinPush = fan.getDouble("min-push", fanMinPush);
            fanMaxPush = fan.getDouble("max-push", fanMaxPush);
            fanPushPerSU = fan.getDouble("push-per-su", fanPushPerSU);
            loaded++;
        }

        ConfigurationSection press = yaml.getConfigurationSection("press");
        if (press != null) {
            pressTickInterval = press.getInt("tick-interval", pressTickInterval);
            pressMaxBatch = press.getInt("max-batch", pressMaxBatch);
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
                    fuelValues.putIfAbsent(m, 150);
                }
                if (name.endsWith("_PLANKS")) {
                    fuelValues.putIfAbsent(m, 75);
                }
            }
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

        return loaded;
    }

    int getPower(String blockName, int defaultValue) {
        return powerValues.getOrDefault(blockName, defaultValue);
    }

    private void initDefaultFuel() {
        fuelValues.put(Material.COAL, 200);
        fuelValues.put(Material.CHARCOAL, 160);
        fuelValues.put(Material.COAL_BLOCK, 1600);
        fuelValues.put(Material.LAVA_BUCKET, 2000);
        fuelValues.put(Material.BLAZE_ROD, 300);
        fuelValues.put(Material.STICK, 25);
        for (Material m : Material.values()) {
            String name = m.name();
            if (name.endsWith("_LOG") || name.endsWith("_WOOD")
                    || name.endsWith("_STEM") || name.endsWith("_HYPHAE")) {
                fuelValues.putIfAbsent(m, 150);
            }
            if (name.endsWith("_PLANKS")) {
                fuelValues.putIfAbsent(m, 75);
            }
        }
    }

    private void initDefaultPower() {
        powerValues.put("windmill", 1);
        powerValues.put("large_windmill", 5);
        powerValues.put("huge_windmill", 15);
        powerValues.put("water_wheel", 2);
        powerValues.put("engine", 5);
        powerValues.put("generator", 1);
        powerValues.put("drill", 1);
        powerValues.put("grindstone", 1);
        powerValues.put("fan", 1);
        powerValues.put("press", 1);
    }
}
