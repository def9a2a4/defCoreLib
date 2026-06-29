package anon.def9a2a4.corelib;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Skull;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages fuel for rotation engine blocks.
 * Fuel is stored in memory and only written to PDC on chunk unload
 * to avoid expensive skull.update() calls every tick.
 */
final class EngineFuelManager {

    private static final NamespacedKey FUEL_KEY = new NamespacedKey("rotation", "fuel_ticks");

    private final Map<Material, Integer> fuelValues;

    // In-memory fuel counters (not PDC — avoid skull.update() every tick)
    private final Map<CustomBlockRegistry.LocationKey, Integer> fuel = new HashMap<>();

    EngineFuelManager(Map<Material, Integer> fuelValues) {
        this.fuelValues = fuelValues;
    }

    /** Get fuel ticks for a material, or -1 if not a fuel. */
    int getFuelValue(Material material) {
        return fuelValues.getOrDefault(material, -1);
    }

    /** Add fuel ticks. Returns new total. */
    int addFuel(CustomBlockRegistry.LocationKey key, int ticks) {
        int current = fuel.getOrDefault(key, 0);
        int newVal = current + ticks;
        fuel.put(key, newVal);
        return newVal;
    }

    /** Decrement fuel by 1. Returns remaining ticks (0 = empty). */
    int tick(CustomBlockRegistry.LocationKey key) {
        int current = fuel.getOrDefault(key, 0);
        if (current <= 0) return 0;
        int remaining = current - 1;
        if (remaining <= 0) {
            fuel.remove(key);
            return 0;
        }
        fuel.put(key, remaining);
        return remaining;
    }

    /** Get current fuel level. */
    int getFuel(CustomBlockRegistry.LocationKey key) {
        return fuel.getOrDefault(key, 0);
    }

    /** Write fuel to PDC (called on chunk unload). */
    void writeToPDC(Block block) {
        CustomBlockRegistry.LocationKey key = CustomBlockRegistry.LocationKey.of(block);
        int ticks = fuel.getOrDefault(key, 0);
        if (!(block.getState() instanceof Skull skull)) return;
        if (ticks > 0) {
            skull.getPersistentDataContainer().set(FUEL_KEY, PersistentDataType.INTEGER, ticks);
        } else {
            skull.getPersistentDataContainer().remove(FUEL_KEY);
        }
        skull.update();
    }

    /** Read fuel from PDC (called on chunk load). */
    void readFromPDC(Block block) {
        CustomBlockRegistry.LocationKey key = CustomBlockRegistry.LocationKey.of(block);
        if (!(block.getState() instanceof Skull skull)) return;
        Integer ticks = skull.getPersistentDataContainer().get(FUEL_KEY, PersistentDataType.INTEGER);
        if (ticks != null && ticks > 0) {
            fuel.put(key, ticks);
        }
    }

    /** Remove fuel tracking for a location (chunk unload cleanup after PDC write). */
    void remove(CustomBlockRegistry.LocationKey key) {
        fuel.remove(key);
    }
}
