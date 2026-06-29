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
    int drillTickInterval = 4;
    int drillBreakStages = 10;
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
    }
}
