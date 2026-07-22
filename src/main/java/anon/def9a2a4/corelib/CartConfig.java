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
    /** Blast-furnace-cart top speed (blocks/tick). */
    double blastFurnaceCartSpeed = 1.0d;

    // ── Train drive (model B: position-driven, arc-length rail-walking) ──────
    /** Arc-length gap kept between coupled carts (blocks). */
    double spacing = 1.5d;
    /** Rail-walk sub-step granularity (blocks) so fast carts never skip a rail. */
    double subStep = 0.25d;
    /** Acceleration per tick per engine, scaled by 1/size (blocks/tick²). */
    double baseAccel = 0.02d;
    /** Speed a coasting (engine-less) train loses per tick (blocks/tick²). */
    double rollingDrag = 0.01d;
    /** Max straight-line distance between two carts to couple them (blocks). */
    double maxLinkDistance = 3.0d;
    /** Engine fuel level (ticks) below which a tender tops it up. */
    int feedThresholdTicks = 600;

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
            spacing = carts.getDouble("spacing", spacing);
            subStep = carts.getDouble("sub-step", subStep);
            baseAccel = carts.getDouble("base-accel", baseAccel);
            rollingDrag = carts.getDouble("rolling-drag", rollingDrag);
            maxLinkDistance = carts.getDouble("max-link-distance", maxLinkDistance);
            feedThresholdTicks = carts.getInt("feed-threshold-ticks", feedThresholdTicks);
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
