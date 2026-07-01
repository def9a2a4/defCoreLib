package anon.def9a2a4.corelib;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Generic processing-machine recipe map: one input {@link Material} → one or more outputs.
 * Shared by every container-processing rotation consumer (millstone, extractor press, …).
 *
 * <p>An output is either a vanilla material or a custom block/item referenced by its
 * {@code namespace:id} (produced via {@link CustomHeadBlock#createItem}). Recipes support
 * {@code input_amount} (items consumed per application) and per-output {@code chance}
 * ({@code < 1.0} reserved for the future sieve; mill/press recipes use 1.0).
 *
 * <p>YAML schema (both forms accepted; the scalar form keeps mill-recipes.yml valid):
 * <pre>
 * recipes:
 *   - { input: BONE, output: BONE_MEAL, amount: 3 }              # scalar, single output
 *   - input: SWEET_BERRIES                                       # full form
 *     input_amount: 3
 *     outputs:
 *       - { custom: mech:sweet_berry_juice }
 *       - { output: IRON_NUGGET, amount: 1, chance: 0.1 }
 * </pre>
 */
final class MachineRecipes {

    /** One result of a recipe; exactly one of {@code material}/{@code customId} is non-null. */
    record Output(@Nullable Material material, @Nullable String customId, int amount, double chance) {}
    record Recipe(Material input, int inputAmount, List<Output> outputs) {}

    private final Map<Material, Recipe> byInput = new HashMap<>();

    int load(InputStream input, Logger logger) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(input));
        List<Map<?, ?>> list = yaml.getMapList("recipes");
        int count = 0;
        for (Map<?, ?> entry : list) {
            try {
                Material in = Material.matchMaterial(String.valueOf(entry.get("input")).toUpperCase(Locale.ROOT));
                if (in == null) {
                    logger.warning("MachineRecipes: unknown input material '" + entry.get("input") + "'");
                    continue;
                }
                int inAmt = entry.get("input_amount") instanceof Number n ? Math.max(1, n.intValue()) : 1;

                List<Output> outputs = new ArrayList<>();
                if (entry.get("outputs") instanceof List<?> outs) {
                    for (Object o : outs) {
                        if (o instanceof Map<?, ?> om) {
                            Output parsed = parseOutput(om, logger);
                            if (parsed != null) outputs.add(parsed);
                        }
                    }
                } else {
                    Output parsed = parseOutput(entry, logger); // scalar back-compat
                    if (parsed != null) outputs.add(parsed);
                }
                if (outputs.isEmpty()) {
                    logger.warning("MachineRecipes: no valid outputs for input " + in);
                    continue;
                }
                byInput.put(in, new Recipe(in, inAmt, outputs));
                count++;
            } catch (Exception e) {
                logger.warning("MachineRecipes parse error: " + e.getMessage());
            }
        }
        return count;
    }

    private @Nullable Output parseOutput(Map<?, ?> m, Logger logger) {
        Object spec = m.get("output");
        if (spec == null) spec = m.get("custom");
        if (spec == null) return null;
        String s = String.valueOf(spec);
        int amount = m.get("amount") instanceof Number n ? Math.max(1, n.intValue()) : 1;
        double chance = m.get("chance") instanceof Number c ? c.doubleValue() : 1.0;
        if (s.contains(":")) {
            return new Output(null, s, amount, chance); // custom block/item id, resolved at roll time
        }
        Material mat = Material.matchMaterial(s.toUpperCase(Locale.ROOT));
        if (mat == null) {
            logger.warning("MachineRecipes: unknown output material '" + s + "'");
            return null;
        }
        return new Output(mat, null, amount, chance);
    }

    /** The recipe for an input material, or null. */
    @Nullable Recipe match(Material input) {
        return byInput.get(input);
    }

    /** Resolve a recipe's outputs into concrete stacks, applying per-output chance. */
    List<ItemStack> roll(Recipe r, CustomBlockRegistry registry) {
        List<ItemStack> out = new ArrayList<>(r.outputs().size());
        for (Output o : r.outputs()) {
            if (o.chance() < 1.0 && ThreadLocalRandom.current().nextDouble() >= o.chance()) continue;
            if (o.customId() != null) {
                CustomHeadBlock type = registry.getType(o.customId());
                if (type != null) {
                    out.add(type.createItem(o.amount()));
                } else {
                    registry.getPlugin().getLogger().warning(
                        "MachineRecipes: unknown custom output '" + o.customId() + "' — recipe output skipped");
                }
            } else {
                out.add(new ItemStack(o.material(), o.amount()));
            }
        }
        return out;
    }
}
