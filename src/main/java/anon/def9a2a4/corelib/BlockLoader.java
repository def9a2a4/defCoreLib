package anon.def9a2a4.corelib;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;

import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.logging.Logger;

/**
 * Loads {@link CustomHeadBlock} definitions from YAML files.
 * This is the data-driven entry point — plugins define blocks in YAML,
 * and this loader turns them into registered CustomHeadBlock instances.
 */
public final class BlockLoader {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private BlockLoader() {}

    /**
     * Load all block definitions from a YAML input stream and register them.
     * @return number of blocks loaded
     */
    public static int load(InputStream input, CustomBlockRegistry registry, Logger logger) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(input));
        String namespace = yaml.getString("namespace", "custom");
        ConfigurationSection blocks = yaml.getConfigurationSection("blocks");
        if (blocks == null) return 0;

        // Parse and register all blocks (recipes store block IDs as strings,
        // resolved at recipe registration time in CustomBlockRegistry)
        int count = 0;
        for (String id : blocks.getKeys(false)) {
            ConfigurationSection sec = blocks.getConfigurationSection(id);
            if (sec == null) continue;
            try {
                CustomHeadBlock block = parseBlock(namespace, id, sec);
                registry.register(block);
                count++;
            } catch (Exception e) {
                logger.warning("Failed to load block '" + id + "': " + e.getMessage());
            }
        }
        return count;
    }

    private static CustomHeadBlock parseBlock(String namespace, String id, ConfigurationSection sec) {
        CustomHeadBlock.Builder b = CustomHeadBlock.builder(namespace, id);

        // Base texture (required)
        b.texture(requireString(sec, "texture"));

        // Name and lore
        String nameStr = sec.getString("name");
        if (nameStr != null) {
            b.name(LEGACY.deserialize(nameStr).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        }
        List<String> loreStrs = sec.getStringList("lore");
        if (!loreStrs.isEmpty()) {
            b.lore(loreStrs.stream()
                    .map(s -> (Component) LEGACY.deserialize(s).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false))
                    .toList());
        }

        // Drops
        String drops = sec.getString("drops");
        if ("self".equals(drops)) {
            b.drops(CustomHeadBlock.DropRule.self());
        }

        // Flags

        if (sec.getBoolean("cancel_pistons")) b.cancelPistons(true);

        // Interact GUI
        String gui = sec.getString("interact_gui");
        if (gui != null) {
            b.interactGUI(CustomHeadBlock.InteractGUI.valueOf(gui.toUpperCase()));
        }

        // Base light
        ConfigurationSection lightSec = sec.getConfigurationSection("light");
        if (lightSec != null) {
            CustomHeadBlock.LightConfig lc = parseLight(lightSec);
            b.light(lc.level(), lc.offsetX(), lc.offsetY(), lc.offsetZ());
        }

        // Base particles
        ConfigurationSection particleSec = sec.getConfigurationSection("particles");
        if (particleSec != null) {
            b.particles(parseParticles(particleSec));
        }

        // Display entities
        List<?> displayList = sec.getList("display_entities");
        if (displayList != null) {
            b.displayEntities(parseDisplayEntities(sec.getMapList("display_entities")));
        }

        // States
        ConfigurationSection statesSec = sec.getConfigurationSection("states");
        if (statesSec != null) {
            for (String stateName : statesSec.getKeys(false)) {
                ConfigurationSection stateSec = statesSec.getConfigurationSection(stateName);
                if (stateSec == null || stateSec.getKeys(false).isEmpty()) {
                    b.state(stateName);
                } else {
                    b.state(stateName, sb -> parseStateOverrides(sb, stateSec));
                }
            }
        }

        String defaultState = sec.getString("default_state");
        if (defaultState != null) b.defaultState(defaultState);

        // Transitions
        List<Map<?, ?>> transitionsList = sec.getMapList("transitions");
        for (Map<?, ?> tMap : transitionsList) {
            b.transition(parseTransition(tMap));
        }

        // Redstone
        ConfigurationSection rsSec = sec.getConfigurationSection("redstone");
        if (rsSec != null) {
            parseRedstone(b, rsSec);
        }

        // Recipes
        ConfigurationSection recipesSec = sec.getConfigurationSection("recipes");
        if (recipesSec != null) {
            parseRecipes(b, recipesSec, namespace);
        }

        return b.build();
    }

    private static void parseRecipes(CustomHeadBlock.Builder b, ConfigurationSection sec, String namespace) {
        ConfigurationSection craftSec = sec.getConfigurationSection("craft");
        if (craftSec != null) {
            // Shaped recipes
            int shapedIdx = 0;
            for (Map<?, ?> m : craftSec.getMapList("shaped")) {
                String id = m.get("id") != null ? String.valueOf(m.get("id")) : "shaped_" + shapedIdx++;
                int amount = toInt(m.get("amount"), 1);
                List<String> pattern = new ArrayList<>();
                if (m.get("pattern") instanceof List<?> pl) {
                    for (Object row : pl) pattern.add(String.valueOf(row));
                }
                Map<Character, CustomHeadBlock.IngredientSpec> key = new HashMap<>();
                if (m.get("key") instanceof Map<?, ?> km) {
                    for (Map.Entry<?, ?> e : km.entrySet()) {
                        String k = String.valueOf(e.getKey());
                        if (k.length() != 1) continue;
                        if (e.getValue() instanceof Map<?, ?> iv) {
                            key.put(k.charAt(0), parseIngredient(iv, namespace));
                        }
                    }
                }
                b.shapedRecipe(new CustomHeadBlock.ShapedRecipeDef(id, amount, pattern, key));
            }

            // Shapeless recipes
            int shapelessIdx = 0;
            for (Map<?, ?> m : craftSec.getMapList("shapeless")) {
                String id = m.get("id") != null ? String.valueOf(m.get("id")) : "shapeless_" + shapelessIdx++;
                int amount = toInt(m.get("amount"), 1);
                List<CustomHeadBlock.IngredientSpec> ingredients = new ArrayList<>();
                if (m.get("ingredients") instanceof List<?> il) {
                    for (Object o : il) {
                        if (o instanceof Map<?, ?> im) {
                            ingredients.add(parseIngredient(im, namespace));
                        }
                    }
                }
                b.shapelessRecipe(new CustomHeadBlock.ShapelessRecipeDef(id, amount, ingredients));
            }
        }

        // Stonecutter recipes
        int stonecutIdx = 0;
        for (Map<?, ?> m : sec.getMapList("stonecutter")) {
            String id = m.get("id") != null ? String.valueOf(m.get("id")) : "sc_" + stonecutIdx++;
            int amount = toInt(m.get("amount"), 1);
            if (m.get("input") instanceof Map<?, ?> im) {
                CustomHeadBlock.IngredientSpec input = parseIngredient(im, namespace);
                b.stonecutterRecipe(new CustomHeadBlock.StonecutterRecipeDef(id, amount, input));
            }
        }
    }

    private static CustomHeadBlock.IngredientSpec parseIngredient(Map<?, ?> map, String namespace) {
        Object matObj = map.get("material");
        Object blockObj = map.get("block");
        if (matObj != null) {
            String matName = String.valueOf(matObj).toUpperCase(java.util.Locale.ROOT);
            Material mat = Material.matchMaterial(matName);
            if (mat == null) throw new IllegalArgumentException("Unknown material: " + matName);
            return new CustomHeadBlock.IngredientSpec(mat, null);
        }
        if (blockObj != null) {
            String blockId = String.valueOf(blockObj);
            // If no namespace prefix, prepend the file's namespace
            if (!blockId.contains(":")) blockId = namespace + ":" + blockId;
            return new CustomHeadBlock.IngredientSpec(null, blockId);
        }
        throw new IllegalArgumentException("Ingredient must have 'material' or 'block' key");
    }

    // ── Parsers ──────────────────────────────────────────────────────────

    private static void parseStateOverrides(CustomHeadBlock.StateBuilder sb, ConfigurationSection sec) {
        String tex = sec.getString("texture");
        if (tex != null) sb.texture(tex);

        if (sec.getBoolean("no_light")) {
            sb.noLight();
        } else {
            ConfigurationSection lightSec = sec.getConfigurationSection("light");
            if (lightSec != null) {
                CustomHeadBlock.LightConfig lc = parseLight(lightSec);
                sb.light(lc.level(), lc.offsetX(), lc.offsetY(), lc.offsetZ());
            }
        }

        if (sec.getBoolean("no_particles")) {
            sb.noParticles();
        } else {
            ConfigurationSection particleSec = sec.getConfigurationSection("particles");
            if (particleSec != null) {
                sb.particles(parseParticles(particleSec));
            }
        }

        if (sec.getBoolean("no_display_entities")) {
            sb.noDisplayEntities();
        }
    }

    private static CustomHeadBlock.LightConfig parseLight(ConfigurationSection sec) {
        int level = sec.getInt("level", 14);
        List<Integer> offset = sec.getIntegerList("offset");
        int ox = offset.size() > 0 ? offset.get(0) : 0;
        int oy = offset.size() > 1 ? offset.get(1) : 0;
        int oz = offset.size() > 2 ? offset.get(2) : 0;
        return new CustomHeadBlock.LightConfig(level, ox, oy, oz);
    }

    private static CustomHeadBlock.ParticleConfig parseParticles(ConfigurationSection sec) {
        Particle type = Particle.valueOf(sec.getString("type", "FLAME").toUpperCase());
        CustomHeadBlock.Scaling count = parseScaling(sec.get("count"), 1);
        CustomHeadBlock.Scaling speed = parseScaling(sec.get("speed"), 0);
        int interval = Math.max(1, sec.getInt("interval", 5));
        Vector floorOffset = parseVector(sec.get("floor_offset"), new Vector(0, 0.5, 0));

        Map<BlockFace, Vector> wallOffsets = new HashMap<>();
        ConfigurationSection wallSec = sec.getConfigurationSection("wall_offsets");
        if (wallSec != null) {
            for (String faceStr : wallSec.getKeys(false)) {
                BlockFace face = BlockFace.valueOf(faceStr.toUpperCase());
                wallOffsets.put(face, parseVector(wallSec.get(faceStr), new Vector(0, 0.5, 0)));
            }
        }

        // Parse particle-specific data for types that require it
        Object data = null;
        if (type == Particle.DUST) {
            List<Integer> color = sec.getIntegerList("color");
            float size = (float) sec.getDouble("size", 1.0);
            int r = color.size() > 0 ? color.get(0) : 255;
            int g = color.size() > 1 ? color.get(1) : 255;
            int b = color.size() > 2 ? color.get(2) : 255;
            data = new Particle.DustOptions(org.bukkit.Color.fromRGB(r, g, b), size);
        } else if (type == Particle.ENTITY_EFFECT) {
            List<Integer> color = sec.getIntegerList("color");
            int r = color.size() > 0 ? color.get(0) : 255;
            int g = color.size() > 1 ? color.get(1) : 255;
            int b = color.size() > 2 ? color.get(2) : 255;
            data = org.bukkit.Color.fromRGB(r, g, b);
        }

        return new CustomHeadBlock.ParticleConfig(type, count, speed, interval, floorOffset, wallOffsets, data);
    }

    private static CustomHeadBlock.Scaling parseScaling(Object obj, double defaultVal) {
        if (obj == null) return CustomHeadBlock.Scaling.fixed(defaultVal);
        if (obj instanceof Number n) return CustomHeadBlock.Scaling.fixed(n.doubleValue());
        if (obj instanceof Map<?, ?> map) {
            double base = toDouble(map.get("base"), defaultVal);
            double perPower = toDouble(map.get("per_power"), 0);
            return new CustomHeadBlock.Scaling(base, perPower);
        }
        return CustomHeadBlock.Scaling.fixed(defaultVal);
    }

    private static Vector parseVector(Object obj, Vector defaultVal) {
        if (obj instanceof List<?> list && list.size() >= 3) {
            return new Vector(toDouble(list.get(0), 0), toDouble(list.get(1), 0), toDouble(list.get(2), 0));
        }
        return defaultVal;
    }

    private static List<CustomHeadBlock.DisplayEntityConfig> parseDisplayEntities(List<Map<?, ?>> list) {
        List<CustomHeadBlock.DisplayEntityConfig> result = new ArrayList<>();
        for (Map<?, ?> map : list) {
            String tex = String.valueOf(map.get("texture"));
            String tag = map.get("tag") != null ? String.valueOf(map.get("tag")) : null;

            Transformation transform;
            Object tObj = map.get("transform");
            if (tObj instanceof Map<?, ?> tMap) {
                Vector3f translation = parseVector3f(tMap.get("translation"), new Vector3f(0, 0, 0));
                Vector3f scale = parseVector3f(tMap.get("scale"), new Vector3f(1, 1, 1));
                transform = new Transformation(
                        translation,
                        new AxisAngle4f(0, 0, 0, 1),
                        scale,
                        new AxisAngle4f(0, 0, 0, 1));
            } else {
                transform = new Transformation(
                        new Vector3f(0, 0, 0),
                        new AxisAngle4f(0, 0, 0, 1),
                        new Vector3f(1, 1, 1),
                        new AxisAngle4f(0, 0, 0, 1));
            }

            result.add(new CustomHeadBlock.DisplayEntityConfig(tex, transform, tag));
        }
        return result;
    }

    private static CustomHeadBlock.StateTransition parseTransition(Map<?, ?> map) {
        Object triggerObj = map.get("trigger");
        if (!(triggerObj instanceof Map<?, ?> triggerMap)) {
            throw new IllegalArgumentException("Transition missing 'trigger' map");
        }
        CustomHeadBlock.Trigger trigger = parseTrigger(triggerMap);

        Object fromObj = map.get("from");
        Object toObj = map.get("to");
        if (fromObj == null || toObj == null) {
            throw new IllegalArgumentException("Transition missing 'from' or 'to' state");
        }
        String from = String.valueOf(fromObj);
        String to = String.valueOf(map.get("to"));

        Sound sound = null;
        Object soundObj = map.get("sound");
        if (soundObj != null) {
            try {
                sound = Sound.valueOf(String.valueOf(soundObj).toUpperCase());
            } catch (IllegalArgumentException ignored) {}
        }

        CustomHeadBlock.TransitionParticle transParticle = null;
        Object pObj = map.get("particle");
        if (pObj instanceof Map<?, ?> pMap) {
            Particle pType = Particle.valueOf(String.valueOf(pMap.get("type")).toUpperCase());
            int pCount = toInt(pMap.get("count"), 5);
            double pSpread = toDouble(pMap.get("spread"), 0.2);
            transParticle = new CustomHeadBlock.TransitionParticle(pType, pCount, pSpread);
        }

        return new CustomHeadBlock.StateTransition(trigger, from, to, sound, 1f, 1f, transParticle);
    }

    private static CustomHeadBlock.Trigger parseTrigger(Map<?, ?> map) {
        String type = String.valueOf(map.get("type"));
        return switch (type) {
            case "interact" -> {
                Object itemObj = map.get("item");
                if (itemObj != null) {
                    yield new CustomHeadBlock.Trigger.Interact(Material.valueOf(String.valueOf(itemObj).toUpperCase()));
                }
                yield new CustomHeadBlock.Trigger.Interact(null);
            }
            case "redstone" -> {
                String range = String.valueOf(map.get("range"));
                yield new CustomHeadBlock.Trigger.RedstonePower(parseRange(range));
            }
            default -> throw new IllegalArgumentException("Unknown trigger type: " + type);
        };
    }

    private static void parseRedstone(CustomHeadBlock.Builder b, ConfigurationSection sec) {
        CustomHeadBlock.Sensitivity sens = CustomHeadBlock.Sensitivity.valueOf(
                sec.getString("sensitivity", "NONE").toUpperCase());
        CustomHeadBlock.PowerReader reader = CustomHeadBlock.PowerReader.valueOf(
                sec.getString("reader", "DIRECT").toUpperCase());
        b.redstone(sens, reader);

        // Per-power textures (exact match)
        ConfigurationSection texSec = sec.getConfigurationSection("textures");
        if (texSec != null) {
            Map<Integer, String> textures = new HashMap<>();
            for (String key : texSec.getKeys(false)) {
                textures.put(Integer.parseInt(key), texSec.getString(key));
            }
            b.redstoneTextures(textures);
        }

        // Range-based textures
        ConfigurationSection rangeSec = sec.getConfigurationSection("texture_ranges");
        if (rangeSec != null) {
            Map<CustomHeadBlock.PowerRange, String> ranges = new HashMap<>();
            for (String key : rangeSec.getKeys(false)) {
                ranges.put(parseRange(key), rangeSec.getString(key));
            }
            b.redstoneTextureRanges(ranges);
        }
    }

    private static CustomHeadBlock.PowerRange parseRange(String s) {
        s = s.trim();
        try {
            if (s.contains("-")) {
                String[] parts = s.split("-", 2);
                return new CustomHeadBlock.PowerRange(
                        Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
            }
            int val = Integer.parseInt(s);
            return new CustomHeadBlock.PowerRange(val, val);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid power range: '" + s + "'", e);
        }
    }

    // ── Utility ──────────────────────────────────────────────────────────

    private static String requireString(ConfigurationSection sec, String path) {
        String v = sec.getString(path);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("Missing required field: " + path);
        return v;
    }

    private static double toDouble(Object obj, double defaultVal) {
        if (obj instanceof Number n) return n.doubleValue();
        if (obj instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return defaultVal; }
        }
        return defaultVal;
    }

    private static int toInt(Object obj, int defaultVal) {
        if (obj instanceof Number n) return n.intValue();
        if (obj instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return defaultVal; }
        }
        return defaultVal;
    }

    private static Vector3f parseVector3f(Object obj, Vector3f defaultVal) {
        if (obj instanceof List<?> list && list.size() >= 3) {
            return new Vector3f(
                    (float) toDouble(list.get(0), 0),
                    (float) toDouble(list.get(1), 0),
                    (float) toDouble(list.get(2), 0));
        }
        return defaultVal;
    }

}
