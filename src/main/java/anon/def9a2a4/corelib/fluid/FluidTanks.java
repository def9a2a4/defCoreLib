package anon.def9a2a4.corelib.fluid;

import anon.def9a2a4.corelib.CoreLibPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Whole-bucket fluid tanks on custom machine blocks (sieve water, boiler water, pump buffer …).
 * A tank is declared per block TYPE ({@link #registerTank}); the fill level is written through
 * immediately — tank levels change at machine-cycle cadence, so there is no in-memory counter
 * to persist on unload (contrast {@code EngineFuelManager}, which ticks every second).
 *
 * <p>Storage: the block's tile-entity PDC ({@code corelib:fluid_units}) when it has one
 * (skull/barrel machines); otherwise a per-cell map on the CHUNK PDC
 * ({@code corelib:fluid_units_cells}, {@code "x,y,z=n;…"}) — bare base_block machines like the
 * glass boiler have no tile entity, and a loaded chunk's PDC is durable and writable on demand
 * (tanks are only touched from ticks/interacts, so the chunk is always loaded).
 */
public final class FluidTanks {

    public record TankSpec(FluidType fluid, int capacity) {}

    private static final Map<String, TankSpec> SPECS = new HashMap<>();

    private FluidTanks() {}

    private static NamespacedKey unitsKey() {
        return new NamespacedKey(CoreLibPlugin.getInstance(), "fluid_units");
    }

    private static NamespacedKey cellsKey() {
        return new NamespacedKey(CoreLibPlugin.getInstance(), "fluid_units_cells");
    }

    /** Declare that blocks of {@code typeId} (e.g. {@code mech:sieve}) carry a fluid tank. */
    public static void registerTank(String typeId, FluidType fluid, int capacity) {
        SPECS.put(typeId, new TankSpec(fluid, Math.max(1, capacity)));
    }

    /** The tank spec for the custom block at {@code block}, or null if it has no tank. */
    public static @Nullable TankSpec spec(Block block) {
        var type = CoreLibPlugin.getInstance().getRegistry().getTypeFromBlock(block);
        return type == null ? null : SPECS.get(type.fullId());
    }

    /** Current fill level in units. */
    public static int units(Block block) {
        if (block.getState() instanceof TileState tile) {
            Integer units = tile.getPersistentDataContainer().get(unitsKey(), PersistentDataType.INTEGER);
            return units == null ? 0 : units;
        }
        return readCell(block);
    }

    /** Add one unit; false when full or tankless. */
    public static boolean addUnit(Block block) {
        TankSpec spec = spec(block);
        if (spec == null) return false;
        int units = units(block);
        if (units >= spec.capacity()) return false;
        writeUnits(block, units + 1);
        return true;
    }

    /** Remove one unit; false when empty. */
    public static boolean takeUnit(Block block) {
        int units = units(block);
        if (units <= 0) return false;
        writeUnits(block, units - 1);
        return true;
    }

    /** Drop any stored level for {@code block} (call from onBlockRemoved of bare tank blocks —
     *  tile-entity levels vanish with the tile, chunk-PDC cells need the explicit wipe). */
    public static void clear(Block block) {
        if (block.getState() instanceof TileState tile) {
            tile.getPersistentDataContainer().remove(unitsKey());
            tile.update();
        } else {
            writeCell(block, 0);
        }
    }

    private static void writeUnits(Block block, int units) {
        if (block.getState() instanceof TileState tile) {
            if (units <= 0) tile.getPersistentDataContainer().remove(unitsKey());
            else tile.getPersistentDataContainer().set(unitsKey(), PersistentDataType.INTEGER, units);
            tile.update();
        } else {
            writeCell(block, units);
        }
    }

    // ── Chunk-PDC cell map for tile-less (bare base_block) tanks ─────────────

    private static String cellId(Block block) {
        return block.getX() + "," + block.getY() + "," + block.getZ();
    }

    private static int readCell(Block block) {
        String map = block.getChunk().getPersistentDataContainer()
            .get(cellsKey(), PersistentDataType.STRING);
        if (map == null || map.isEmpty()) return 0;
        String prefix = cellId(block) + "=";
        for (String entry : map.split(";")) {
            if (entry.startsWith(prefix)) {
                try {
                    return Integer.parseInt(entry.substring(prefix.length()));
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static void writeCell(Block block, int units) {
        var pdc = block.getChunk().getPersistentDataContainer();
        String map = pdc.get(cellsKey(), PersistentDataType.STRING);
        String prefix = cellId(block) + "=";
        StringBuilder next = new StringBuilder();
        if (map != null && !map.isEmpty()) {
            for (String entry : map.split(";")) {
                if (entry.isEmpty() || entry.startsWith(prefix)) continue;
                if (next.length() > 0) next.append(';');
                next.append(entry);
            }
        }
        if (units > 0) {
            if (next.length() > 0) next.append(';');
            next.append(prefix).append(units);
        }
        if (next.length() == 0) pdc.remove(cellsKey());
        else pdc.set(cellsKey(), PersistentDataType.STRING, next.toString());
    }
}
