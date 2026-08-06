package anon.def9a2a4.corelib;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Resolves a per-block <b>inertial mass</b> (always {@code >= 0}) for a block in a mechanism, from
 * {@code mass.yml}. Heavier total mass makes a mechanism move slower (rotator swing / hoist lift /
 * piston extend) and, later, lowers its power-to-mass ratio in the ship-stats system.
 *
 * <p>This is <b>not</b> buoyancy: buoyancy is a separate signed density scale owned by BlockShips.
 * {@code mass.yml} is seeded from BlockShips' {@code blocks.yml} {@code weight} field, but every value
 * is clamped to {@code >= 0} (mirroring BlockShips' own {@code mass = Σ max(0, weight)} rule, with
 * buoyant-but-solid blocks given a small positive mass rather than zero).
 *
 * <p>Keys mirror {@link ColliderRegistry}: a material name ({@code stone}), a {@code #block-tag}
 * ({@code #slabs}), or a {@code *wildcard} ({@code *_copper_chain}). Overlapping keys resolve
 * <b>most-specific-wins</b>: {@code #tag} entries apply first, then {@code *wildcard}, then exact
 * material names last, so a specific material always overrides a wildcard/tag regardless of file order.
 * Two reserved top-level keys are handled specially: {@code default} overrides the fallback mass, and
 * {@code custom-blocks} is a section mapping custom-block type ids to masses. Any material not listed
 * resolves to {@link #DEFAULT}.
 */
public final class MassRegistry {

    /** Fallback mass for any material not listed (overridable by a top-level {@code default:} key). */
    private double defaultMass = 1.0;

    private final Map<Material, Double> byMaterial = new EnumMap<>(Material.class);
    private final Map<String, Double> byCustomType = new HashMap<>();

    /**
     * The inertial mass for {@code material}, or for {@code customTypeId} if that custom-block type has
     * its own configured mass (a custom-type mass wins over the underlying material). Unlisted ⇒
     * {@link #DEFAULT}.
     */
    public double get(Material material, @Nullable String customTypeId) {
        if (customTypeId != null) {
            Double custom = byCustomType.get(customTypeId);
            if (custom != null) return custom;
        }
        Double m = byMaterial.get(material);
        return m != null ? m : defaultMass;
    }

    /** The fallback mass for unlisted materials. */
    public double DEFAULT() { return defaultMass; }

    /** Parse {@code mass.yml} from a resource stream into this registry. */
    public void load(InputStream in, Logger log) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                new InputStreamReader(in, StandardCharsets.UTF_8));

        // Bucket keys by specificity so exact materials override wildcards override tags, regardless of
        // authoring order (most-specific-wins). `default` and `custom-blocks` are reserved and handled
        // before any material resolution.
        List<String> tags = new ArrayList<>();
        List<String> wildcards = new ArrayList<>();
        List<String> materials = new ArrayList<>();
        for (String key : yaml.getKeys(false)) {
            if (key.equals("default")) {
                Double d = parseMass(yaml.get(key), key, log);
                if (d != null) defaultMass = d;
            } else if (key.equals("custom-blocks")) {
                ConfigurationSection sec = yaml.getConfigurationSection(key);
                if (sec != null) {
                    for (String id : sec.getKeys(false)) {
                        Double d = parseMass(sec.get(id), "custom-blocks." + id, log);
                        if (d != null) byCustomType.put(id, d);
                    }
                }
            } else if (key.startsWith("#")) {
                tags.add(key);
            } else if (key.indexOf('*') >= 0) {
                wildcards.add(key);
            } else {
                materials.add(key);
            }
        }

        // Apply in ascending specificity; each tier overwrites the previous.
        for (String key : tags) applyKey(key, yaml, log);
        for (String key : wildcards) applyKey(key, yaml, log);
        for (String key : materials) applyKey(key, yaml, log);
    }

    private void applyKey(String key, YamlConfiguration yaml, Logger log) {
        Double mass = parseMass(yaml.get(key), key, log);
        if (mass == null) return;
        for (Material m : resolveKey(key, log)) {
            byMaterial.put(m, mass);
        }
    }

    /**
     * Read a mass value tolerantly: SnakeYAML types {@code 2} as an Integer and {@code 1.5} as a Double,
     * so accept any {@link Number} (and a numeric String) rather than casting to a specific box type.
     */
    private static @Nullable Double parseMass(Object value, String key, Logger log) {
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            log.warning("[mass.yml] Non-numeric mass for '" + key + "': " + value);
            return null;
        }
    }

    /**
     * Resolve a config key to materials: {@code #tag} → block-tag members; a key containing {@code *}
     * → every material whose name matches the wildcard; else a single material. Ported from
     * {@link ColliderRegistry#resolveKey}.
     */
    private static Set<Material> resolveKey(String key, Logger log) {
        if (key.startsWith("#")) {
            String tagName = key.substring(1).toLowerCase(Locale.ROOT);
            Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_BLOCKS, NamespacedKey.minecraft(tagName), Material.class);
            if (tag == null) {
                log.warning("[mass.yml] Unknown block tag: #" + tagName);
                return Set.of();
            }
            return tag.getValues();
        }
        if (key.indexOf('*') >= 0) {
            String regex = key.toLowerCase(Locale.ROOT).replace("*", ".*");
            EnumSet<Material> matched = EnumSet.noneOf(Material.class);
            for (Material m : Material.values()) {
                if (m.isBlock() && m.name().toLowerCase(Locale.ROOT).matches(regex)) matched.add(m);
            }
            if (matched.isEmpty()) log.warning("[mass.yml] Wildcard matched no blocks: " + key);
            return matched;
        }
        Material m = Material.matchMaterial(key);
        if (m == null) {
            log.warning("[mass.yml] Unknown material: " + key);
            return Set.of();
        }
        return Set.of(m);
    }
}
