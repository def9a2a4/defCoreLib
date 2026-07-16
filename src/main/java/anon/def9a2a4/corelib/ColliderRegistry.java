package anon.def9a2a4.corelib;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

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
 * Resolves a {@link CollisionConfig} for a vanilla block in a mechanism, from {@code colliders.yml}.
 *
 * <p>Reuses BlockShips' collider schema (per-material or {@code #tag} keys, with per-state
 * {@code type: conditional} rules for slabs/stairs), but is written in corelib's static-load idiom
 * rather than the BlockShips singleton. Any material not listed resolves to
 * {@link CollisionConfig#DEFAULT} — <b>unspecified ⇒ full collider</b>, preserving current behaviour.
 * Wildcard ({@code *_fence}) keys are not supported; use explicit materials or {@code #tags}.</p>
 */
public final class ColliderRegistry {

    /** Matches a live {@link BlockData} state (e.g. a bottom slab vs a top slab). */
    public interface BlockDataMatcher {
        boolean matches(BlockData data);
    }

    private record Rule(BlockDataMatcher matcher, CollisionConfig collider) {}

    /** Either a flat collider ({@code simple}) or a list of state-conditional {@code rules}. */
    private record Entry(CollisionConfig simple, List<Rule> rules) {}

    private final Map<Material, Entry> byMaterial = new EnumMap<>(Material.class);

    /**
     * The collider for {@code material} in state {@code data}, or {@link CollisionConfig#DEFAULT}
     * (full block) if the material is unlisted or no conditional rule matches.
     */
    public CollisionConfig get(Material material, BlockData data) {
        Entry entry = byMaterial.get(material);
        if (entry == null) return CollisionConfig.DEFAULT;
        if (entry.rules != null) {
            for (Rule rule : entry.rules) {
                if (rule.matcher.matches(data)) return rule.collider;
            }
            return CollisionConfig.DEFAULT;
        }
        return entry.simple;
    }

    /** Parse {@code colliders.yml} from a resource stream into this registry. */
    public void load(InputStream in, Logger log) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                new InputStreamReader(in, StandardCharsets.UTF_8));
        for (String key : yaml.getKeys(false)) {
            Entry entry = parseEntry(yaml.get(key), log);
            if (entry == null) continue;
            for (Material m : resolveKey(key, log)) {
                byMaterial.put(m, entry);
            }
        }
    }

    private static Entry parseEntry(Object value, Logger log) {
        if (value instanceof ConfigurationSection sec && "conditional".equals(sec.getString("type"))) {
            List<Rule> rules = parseRules(sec.getList("rules"));
            return rules.isEmpty() ? null : new Entry(null, rules);
        }
        return new Entry(CollisionConfig.fromYaml(value), null);
    }

    private static List<Rule> parseRules(List<?> ruleObjs) {
        List<Rule> rules = new ArrayList<>();
        if (ruleObjs == null) return rules;
        for (Object ruleObj : ruleObjs) {
            if (!(ruleObj instanceof Map<?, ?> ruleMap)) continue;
            if (!(ruleMap.get("condition") instanceof Map<?, ?> conditionMap)) continue;
            BlockDataMatcher matcher = createMatcher(conditionMap);
            CollisionConfig collider = CollisionConfig.fromYaml(ruleMap.get("collider"));
            rules.add(new Rule(matcher, collider));
        }
        return rules;
    }

    /**
     * Build a matcher from a condition map like {@code {type: BOTTOM}} or {@code {half: TOP}}.
     * Ported from BlockShips' {@code createMatcherFromMap} — checks Slab {@code type}, Stairs
     * {@code half}/{@code shape}/{@code facing}, and Orientable {@code axis}.
     */
    private static BlockDataMatcher createMatcher(Map<?, ?> conditionMap) {
        Map<String, String> conditions = new HashMap<>();
        for (Map.Entry<?, ?> e : conditionMap.entrySet()) {
            conditions.put(e.getKey().toString(), e.getValue().toString());
        }
        return data -> {
            for (Map.Entry<String, String> c : conditions.entrySet()) {
                String property = c.getKey();
                String expected = c.getValue().toUpperCase(Locale.ROOT);
                if (data instanceof Slab slab) {
                    if (property.equals("type") && !slab.getType().name().equals(expected)) return false;
                } else if (data instanceof Stairs stairs) {
                    if (property.equals("half") && !stairs.getHalf().name().equals(expected)) return false;
                    if (property.equals("shape") && !stairs.getShape().name().equals(expected)) return false;
                    if (property.equals("facing") && !stairs.getFacing().name().equals(expected)) return false;
                } else if (data instanceof Orientable orientable) {
                    if (property.equals("axis") && !orientable.getAxis().name().equals(expected)) return false;
                }
            }
            return true;
        };
    }

    /**
     * Resolve a config key to materials: {@code #tag} → block-tag members; a key containing {@code *}
     * → every material whose name matches the wildcard (e.g. {@code *_stained_glass_pane},
     * {@code potted_*}); else a single material. Ported from BlockShips' WildcardMatcher.
     */
    private static Set<Material> resolveKey(String key, Logger log) {
        if (key.startsWith("#")) {
            String tagName = key.substring(1).toLowerCase(Locale.ROOT);
            Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_BLOCKS, NamespacedKey.minecraft(tagName), Material.class);
            if (tag == null) {
                log.warning("[colliders.yml] Unknown block tag: #" + tagName);
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
            if (matched.isEmpty()) log.warning("[colliders.yml] Wildcard matched no blocks: " + key);
            return matched;
        }
        Material m = Material.matchMaterial(key);
        if (m == null) {
            log.warning("[colliders.yml] Unknown material: " + key);
            return Set.of();
        }
        return Set.of(m);
    }
}
