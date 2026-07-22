package anon.def9a2a4.corelib;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * BetterMinecarts tuning, loaded from {@code carts-config.yml}. Holds the fuel whitelist + burn
 * durations shared by the coal tender and blast-furnace cart, and the cart speed knobs. Defaults are
 * baked in so a missing/partial file still yields a working config.
 */
final class CartConfig {

    /** Materials accepted by a cart's fuel-only inventory → burn ticks (game ticks) when consumed. */
    final Map<Material, Integer> fuelBurnTicks = new HashMap<>();

    /** Vanilla furnace-minecart locomotive top speed (blocks/tick). */
    double furnaceCartSpeed = 0.4d;
    /** Blast-furnace-cart top speed (blocks/tick). Only reachable once trains position-drive. */
    double blastFurnaceCartSpeed = 2.0d;

    CartConfig() {
        // Baked-in defaults (vanilla furnace burn times), overridden by the file if present.
        fuelBurnTicks.put(Material.COAL, 1600);
        fuelBurnTicks.put(Material.CHARCOAL, 1600);
        fuelBurnTicks.put(Material.COAL_BLOCK, 16000);
        fuelBurnTicks.put(Material.DRIED_KELP_BLOCK, 4000);
        fuelBurnTicks.put(Material.BLAZE_ROD, 2400);
    }

    void load(InputStream stream, Logger logger) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(stream));

        ConfigurationSection fuel = yaml.getConfigurationSection("fuel");
        if (fuel != null) {
            fuelBurnTicks.clear();
            for (String key : fuel.getKeys(false)) {
                Material m = Material.matchMaterial(key);
                if (m != null) {
                    fuelBurnTicks.put(m, fuel.getInt(key));
                } else {
                    logger.warning("carts-config: unknown fuel material '" + key + "' — skipped");
                }
            }
        }

        ConfigurationSection carts = yaml.getConfigurationSection("carts");
        if (carts != null) {
            furnaceCartSpeed = carts.getDouble("furnace-cart-speed", furnaceCartSpeed);
            blastFurnaceCartSpeed = carts.getDouble("blast-furnace-cart-speed", blastFurnaceCartSpeed);
        }
    }

    /** Whether a material may enter a cart's fuel-only inventory. */
    boolean isFuel(Material m) {
        return m != null && fuelBurnTicks.containsKey(m);
    }

    /** Burn ticks for one item of the given fuel material, or 0 if not a fuel. */
    int burnTicks(Material m) {
        return fuelBurnTicks.getOrDefault(m, 0);
    }
}
