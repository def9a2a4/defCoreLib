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

        // Optional named-texture registry: alias -> base64. Display/block textures may
        // reference these as "@alias" instead of embedding the full base64 string.
        Map<String, String> textures = new HashMap<>();
        ConfigurationSection texSec = yaml.getConfigurationSection("textures");
        if (texSec != null) {
            for (String alias : texSec.getKeys(false)) {
                String val = texSec.getString(alias);
                if (val != null) textures.put(alias, val);
            }
        }

        ConfigurationSection blocks = yaml.getConfigurationSection("blocks");
        if (blocks == null) return 0;

        // Parse and register all blocks (recipes store block IDs as strings,
        // resolved at recipe registration time in CustomBlockRegistry)
        int count = 0;
        for (String id : blocks.getKeys(false)) {
            ConfigurationSection sec = blocks.getConfigurationSection(id);
            if (sec == null) continue;
            try {
                CustomHeadBlock block = parseBlock(namespace, id, sec, textures);
                registry.register(block);
                count++;
            } catch (Exception e) {
                logger.warning("Failed to load block '" + id + "': " + e.getMessage());
            }
        }
        return count;
    }

    private static CustomHeadBlock parseBlock(String namespace, String id, ConfigurationSection sec,
                                              Map<String, String> textures) {
        CustomHeadBlock.Builder b = CustomHeadBlock.builder(namespace, id);

        // Base texture (required) + optional item texture
        b.texture(resolveTexture(requireString(sec, "texture"), textures));
        String itemTex = sec.getString("item_texture");
        if (itemTex != null) b.itemTexture(resolveTexture(itemTex, textures));
        String itemMat = sec.getString("item_material");
        if (itemMat != null) b.itemMaterial(Material.valueOf(itemMat.toUpperCase(java.util.Locale.ROOT)));
        if (sec.getBoolean("item_glint")) b.itemGlint(true);

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

        // Drops — either "self" (string) or a list of conditional rules
        String dropStr = sec.getString("drops");
        if ("self".equals(dropStr)) {
            b.drops(CustomHeadBlock.DropRule.self());
        } else {
            for (Map<?, ?> entry : sec.getMapList("drops")) {
                b.drops(parseDropRule(entry));
            }
        }

        // Flags
        if (sec.getBoolean("cancel_pistons")) b.cancelPistons(true);

        // Sounds
        ConfigurationSection placeSoundSec = sec.getConfigurationSection("place_sound");
        if (placeSoundSec != null) b.placeSound(parseSoundConfig(placeSoundSec));
        ConfigurationSection breakSoundSec = sec.getConfigurationSection("break_sound");
        if (breakSoundSec != null) b.breakSound(parseSoundConfig(breakSoundSec));
        ConfigurationSection interactSoundSec = sec.getConfigurationSection("interact_sound");
        if (interactSoundSec != null) b.interactSound(parseSoundConfig(interactSoundSec));

        // Placement restrictions
        ConfigurationSection placementSec = sec.getConfigurationSection("placement");
        if (placementSec != null) {
            Set<BlockFace> faces = new HashSet<>();
            for (String f : placementSec.getStringList("allowed_faces")) {
                faces.add(BlockFace.valueOf(f.toUpperCase()));
            }
            b.placement(new CustomHeadBlock.PlacementConfig(faces, placementSec.getBoolean("require_solid")));
        }

        // Placement face → initial state map
        ConfigurationSection psmSec = sec.getConfigurationSection("placement_state_map");
        if (psmSec != null) {
            Map<BlockFace, String> psm = new HashMap<>();
            for (String faceStr : psmSec.getKeys(false)) {
                BlockFace face = BlockFace.valueOf(faceStr.toUpperCase());
                String stateName = psmSec.getString(faceStr);
                if (stateName != null) psm.put(face, stateName);
            }
            if (!psm.isEmpty()) b.placementStateMap(psm);
        }

        // Storage
        ConfigurationSection storageSec = sec.getConfigurationSection("storage");
        if (storageSec != null) {
            String layoutStr = storageSec.getString("layout", "CHEST_3ROW");
            b.storage(CustomHeadBlock.InventoryLayout.valueOf(layoutStr.toUpperCase()));
        }

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
            List<Map<?, ?>> displayMaps = sec.getMapList("display_entities");
            b.displayEntities(parseDisplayEntities(displayMaps, textures));
            b.blockDisplayEntities(parseBlockDisplayEntities(displayMaps));
        }

        // Directional textures
        ConfigurationSection dirTexSec = sec.getConfigurationSection("directional_textures");
        if (dirTexSec != null) {
            b.directionalTextures(parseDirectionalTextures(dirTexSec, textures));
        }

        // States
        ConfigurationSection statesSec = sec.getConfigurationSection("states");
        if (statesSec != null) {
            for (String stateName : statesSec.getKeys(false)) {
                ConfigurationSection stateSec = statesSec.getConfigurationSection(stateName);
                if (stateSec == null || stateSec.getKeys(false).isEmpty()) {
                    b.state(stateName);
                } else {
                    b.state(stateName, sb -> parseStateOverrides(sb, stateSec, statesSec, stateName, textures));
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
            parseRedstone(b, rsSec, textures);
        }

        // Recipes
        ConfigurationSection recipesSec = sec.getConfigurationSection("recipes");
        if (recipesSec != null) {
            parseRecipes(b, recipesSec, namespace);
        }

        return b.build();
    }

    private static void parseRecipes(CustomHeadBlock.Builder b, ConfigurationSection sec, String namespace) {
        String unlockAdv = sec.getString("unlock_advancement");
        if (unlockAdv != null) b.unlockAdvancement(unlockAdv);

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
        Object tagObj = map.get("tag");
        if (tagObj != null) {
            String tagName = String.valueOf(tagObj).toLowerCase(java.util.Locale.ROOT);
            org.bukkit.Tag<Material> tag = org.bukkit.Bukkit.getTag(
                    org.bukkit.Tag.REGISTRY_ITEMS,
                    org.bukkit.NamespacedKey.minecraft(tagName),
                    Material.class);
            if (tag == null) throw new IllegalArgumentException("Unknown item tag: " + tagName);
            return new CustomHeadBlock.IngredientSpec(null, null, tag);
        }
        throw new IllegalArgumentException("Ingredient must have 'material', 'block', or 'tag' key");
    }

    // ── Parsers ──────────────────────────────────────────────────────────

    private static void parseStateOverrides(CustomHeadBlock.StateBuilder sb, ConfigurationSection sec,
                                            ConfigurationSection statesSec, String stateName,
                                            Map<String, String> textures) {
        String tex = sec.getString("texture");
        if (tex != null) sb.texture(resolveTexture(tex, textures));

        ConfigurationSection dirTexSec = sec.getConfigurationSection("directional_textures");
        if (dirTexSec != null) sb.directionalTextures(parseDirectionalTextures(dirTexSec, textures));

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

        parseStateDisplays(sb, sec, statesSec, stateName, textures);
    }

    /**
     * Resolve a state's display entities, supporting {@code copy_from} (inherit a sibling
     * state's display_entities verbatim) and a state-level {@code animation} that is applied
     * to every resolved entity lacking its own animation. Both item and block displays are
     * handled. Materialized at parse time, so the runtime path is unchanged.
     */
    private static void parseStateDisplays(CustomHeadBlock.StateBuilder sb, ConfigurationSection sec,
                                           ConfigurationSection statesSec, String stateName,
                                           Map<String, String> textures) {
        String copyFrom = sec.getString("copy_from");
        // A nested mapping read off a ConfigurationSection comes back as a (Memory)Section,
        // not a java.util.Map — so detect both forms.
        boolean hasStateAnim = sec.isConfigurationSection("animation")
                || (sec.get("animation") instanceof Map<?, ?>);

        if (copyFrom == null && !hasStateAnim) {
            // No inheritance/animation sugar — preserve the original behavior.
            if (sec.getBoolean("no_display_entities")) {
                sb.noDisplayEntities();
            } else {
                List<?> displayList = sec.getList("display_entities");
                if (displayList != null) {
                    List<Map<?, ?>> displayMaps = sec.getMapList("display_entities");
                    sb.displayEntities(parseDisplayEntities(displayMaps, textures));
                    sb.blockDisplayEntities(parseBlockDisplayEntities(displayMaps));
                }
            }
            return;
        }

        if (sec.getBoolean("no_display_entities")) {
            throw new IllegalArgumentException("state '" + stateName
                    + "' cannot combine 'copy_from'/'animation' with 'no_display_entities'");
        }

        // Resolve the raw display_entities map-list (from copy_from target, or this state's own).
        List<Map<?, ?>> displayMaps;
        if (copyFrom != null) {
            if (sec.contains("display_entities")) {
                throw new IllegalArgumentException("state '" + stateName
                        + "' cannot set both 'copy_from' and 'display_entities'");
            }
            if (copyFrom.equals(stateName)) {
                throw new IllegalArgumentException("state '" + stateName + "' cannot copy_from itself");
            }
            ConfigurationSection target = statesSec.getConfigurationSection(copyFrom);
            if (target == null) {
                throw new IllegalArgumentException("state '" + stateName
                        + "' copy_from unknown state '" + copyFrom + "'");
            }
            if (target.contains("copy_from")) {
                throw new IllegalArgumentException("state '" + stateName + "' copy_from '" + copyFrom
                        + "', which itself uses copy_from (chains not allowed)");
            }
            List<?> targetList = target.getList("display_entities");
            if (targetList == null || targetList.isEmpty()) {
                throw new IllegalArgumentException("state '" + stateName + "' copy_from '" + copyFrom
                        + "', which has no display_entities");
            }
            displayMaps = target.getMapList("display_entities");
        } else {
            displayMaps = sec.getMapList("display_entities");
        }

        List<CustomHeadBlock.DisplayEntityConfig> items = parseDisplayEntities(displayMaps, textures);
        List<CustomHeadBlock.BlockDisplayEntityConfig> blocks = parseBlockDisplayEntities(displayMaps);

        if (hasStateAnim) {
            Map<?, ?> animMap = sec.isConfigurationSection("animation")
                    ? sec.getConfigurationSection("animation").getValues(false)
                    : (Map<?, ?>) sec.get("animation");
            DisplayAnimation stateAnim = parseStateAnimation(animMap, stateName);
            items = fillItemAnimation(items, partitionMaps(displayMaps, false), stateAnim);
            blocks = fillBlockAnimation(blocks, partitionMaps(displayMaps, true), stateAnim);
        }

        sb.displayEntities(items);
        sb.blockDisplayEntities(blocks);
    }

    /** Validate a state-level animation map (strict for rotate — parseAnimation defaults would
     *  otherwise silently accept a missing/zero axis or speed), then build it. */
    private static DisplayAnimation parseStateAnimation(Map<?, ?> animMap, String stateName) {
        if ("rotate".equals(String.valueOf(animMap.get("type")))) {
            Object axisObj = animMap.get("axis");
            if (!(axisObj instanceof List<?> axisList) || axisList.size() != 3) {
                throw new IllegalArgumentException("state '" + stateName
                        + "' rotate animation requires a length-3 'axis'");
            }
            double ax = toDouble(axisList.get(0), 0), ay = toDouble(axisList.get(1), 0), az = toDouble(axisList.get(2), 0);
            if (ax * ax + ay * ay + az * az < 1.0e-9) {
                throw new IllegalArgumentException("state '" + stateName + "' rotate animation 'axis' must be non-zero");
            }
            if (animMap.get("speed") == null) {
                throw new IllegalArgumentException("state '" + stateName + "' rotate animation requires 'speed'");
            }
        }
        return parseAnimation(animMap);
    }

    /** Sublist of display maps matching one kind: block displays (block_data present) or item displays. */
    private static List<Map<?, ?>> partitionMaps(List<Map<?, ?>> maps, boolean blockKind) {
        List<Map<?, ?>> out = new ArrayList<>();
        for (Map<?, ?> m : maps) {
            if ((m.get("block_data") != null) == blockKind) out.add(m);
        }
        return out;
    }

    /** Fill the state animation into each item display that has no animation of its own.
     *  {@code itemMaps} must be the {@code block_data == null} partition, aligned 1:1 with {@code items}. */
    private static List<CustomHeadBlock.DisplayEntityConfig> fillItemAnimation(
            List<CustomHeadBlock.DisplayEntityConfig> items, List<Map<?, ?>> itemMaps, DisplayAnimation anim) {
        List<CustomHeadBlock.DisplayEntityConfig> out = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            CustomHeadBlock.DisplayEntityConfig c = items.get(i);
            boolean fill = c.animation() == null && i < itemMaps.size() && !itemMaps.get(i).containsKey("animation");
            out.add(fill
                    ? new CustomHeadBlock.DisplayEntityConfig(c.displayItem(), c.transform(), c.tagSuffix(),
                            anim, c.interpolationDuration(), c.wallOffset())
                    : c);
        }
        return out;
    }

    /** Fill the state animation into each block display that has no animation of its own.
     *  {@code blockMaps} must be the {@code block_data != null} partition, aligned 1:1 with {@code blocks}. */
    private static List<CustomHeadBlock.BlockDisplayEntityConfig> fillBlockAnimation(
            List<CustomHeadBlock.BlockDisplayEntityConfig> blocks, List<Map<?, ?>> blockMaps, DisplayAnimation anim) {
        List<CustomHeadBlock.BlockDisplayEntityConfig> out = new ArrayList<>(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            CustomHeadBlock.BlockDisplayEntityConfig c = blocks.get(i);
            boolean fill = c.animation() == null && i < blockMaps.size() && !blockMaps.get(i).containsKey("animation");
            out.add(fill
                    ? new CustomHeadBlock.BlockDisplayEntityConfig(c.blockData(), c.transform(), c.tagSuffix(),
                            anim, c.interpolationDuration(), c.wallOffset())
                    : c);
        }
        return out;
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

        // Parse particle-specific data for types that require it. Driven by the particle's
        // declared data type so enum aliases (e.g. BLOCK_CRACK -> Particle.BLOCK) are handled.
        Object data = null;
        Class<?> dataType = type.getDataType();
        if (dataType == Particle.DustOptions.class) {
            List<Integer> color = sec.getIntegerList("color");
            float size = (float) sec.getDouble("size", 1.0);
            int r = color.size() > 0 ? color.get(0) : 255;
            int g = color.size() > 1 ? color.get(1) : 255;
            int b = color.size() > 2 ? color.get(2) : 255;
            data = new Particle.DustOptions(org.bukkit.Color.fromRGB(r, g, b), size);
        } else if (dataType == org.bukkit.Color.class) {
            List<Integer> color = sec.getIntegerList("color");
            int r = color.size() > 0 ? color.get(0) : 255;
            int g = color.size() > 1 ? color.get(1) : 255;
            int b = color.size() > 2 ? color.get(2) : 255;
            data = org.bukkit.Color.fromRGB(r, g, b);
        } else if (dataType == org.bukkit.block.data.BlockData.class) {
            // BLOCK / BLOCK_CRACK / BLOCK_MARKER / FALLING_DUST / DUST_PILLAR ...
            Material mat = Material.matchMaterial(sec.getString("block", "STRIPPED_OAK_LOG"));
            if (mat == null || !mat.isBlock()) mat = Material.STRIPPED_OAK_LOG;
            data = mat.createBlockData();
        } else if (dataType == org.bukkit.inventory.ItemStack.class) {
            Material mat = Material.matchMaterial(sec.getString("item", "STONE"));
            if (mat == null || !mat.isItem()) mat = Material.STONE;
            data = new org.bukkit.inventory.ItemStack(mat);
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

    private static Transformation parseTransform(Map<?, ?> map) {
        Object tObj = map.get("transform");
        if (tObj instanceof Map<?, ?> tMap) {
            Vector3f translation = parseVector3f(tMap.get("translation"), new Vector3f(0, 0, 0));
            Vector3f scale = parseVector3f(tMap.get("scale"), new Vector3f(1, 1, 1));
            AxisAngle4f leftRotation = new AxisAngle4f(0, 0, 0, 1);
            Object lrObj = tMap.get("left_rotation");
            if (lrObj instanceof List<?> lr && lr.size() >= 4) {
                float ax = (float) toDouble(lr.get(1), 0);
                float ay = (float) toDouble(lr.get(2), 0);
                float az = (float) toDouble(lr.get(3), 0);
                float len = (float) Math.sqrt(ax * ax + ay * ay + az * az);
                if (len > 0) { ax /= len; ay /= len; az /= len; }
                leftRotation = new AxisAngle4f(
                        (float) Math.toRadians(toDouble(lr.get(0), 0)),
                        ax, ay, az);
            }
            return new Transformation(
                    translation,
                    leftRotation,
                    scale,
                    new AxisAngle4f(0, 0, 0, 1));
        }
        return new Transformation(
                new Vector3f(0, 0, 0),
                new AxisAngle4f(0, 0, 0, 1),
                new Vector3f(1, 1, 1),
                new AxisAngle4f(0, 0, 0, 1));
    }

    private static List<CustomHeadBlock.DisplayEntityConfig> parseDisplayEntities(List<Map<?, ?>> list,
                                                                                  Map<String, String> textures) {
        List<CustomHeadBlock.DisplayEntityConfig> result = new ArrayList<>();
        for (Map<?, ?> map : list) {
            if (map.get("block_data") != null) continue;

            Object texObj = map.get("texture");
            Object matObj = map.get("material");
            org.bukkit.inventory.ItemStack displayItem;
            if (matObj != null) {
                Material mat = Material.valueOf(String.valueOf(matObj).toUpperCase(java.util.Locale.ROOT));
                displayItem = new org.bukkit.inventory.ItemStack(mat);
            } else if (texObj != null) {
                displayItem = HeadUtil.createHead(resolveTexture(String.valueOf(texObj), textures), 1);
            } else {
                throw new IllegalArgumentException("display entity requires 'texture', 'material', or 'block_data'");
            }
            String tag = map.get("tag") != null ? String.valueOf(map.get("tag")) : null;
            Transformation transform = parseTransform(map);

            DisplayAnimation animation = null;
            Object animObj = map.get("animation");
            if (animObj instanceof Map<?, ?> animMap) {
                animation = parseAnimation(animMap);
            }

            int interpolation = toInt((Object) map.get("interpolation"), 2);
            float wallOffset = (float) toDouble((Object) map.get("wall_offset"), 0);

            result.add(new CustomHeadBlock.DisplayEntityConfig(
                    displayItem, transform, tag, animation, interpolation, wallOffset));
        }
        return result;
    }

    private static List<CustomHeadBlock.BlockDisplayEntityConfig> parseBlockDisplayEntities(List<Map<?, ?>> list) {
        List<CustomHeadBlock.BlockDisplayEntityConfig> result = new ArrayList<>();
        for (Map<?, ?> map : list) {
            Object blockDataObj = map.get("block_data");
            if (blockDataObj == null) continue;

            org.bukkit.block.data.BlockData blockData =
                    org.bukkit.Bukkit.createBlockData(String.valueOf(blockDataObj));
            String tag = map.get("tag") != null ? String.valueOf(map.get("tag")) : null;
            Transformation transform = parseTransform(map);

            DisplayAnimation animation = null;
            Object animObj = map.get("animation");
            if (animObj instanceof Map<?, ?> animMap) {
                animation = parseAnimation(animMap);
            }

            int interpolation = toInt((Object) map.get("interpolation"), 2);
            float wallOffset = (float) toDouble((Object) map.get("wall_offset"), 0);

            result.add(new CustomHeadBlock.BlockDisplayEntityConfig(
                    blockData, transform, tag, animation, interpolation, wallOffset));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static DisplayAnimation parseAnimation(Map<?, ?> map) {
        String type = String.valueOf(map.get("type"));
        return switch (type) {
            case "rotate" -> {
                Vector3f axis = parseVector3f(map.get("axis"), new Vector3f(0, 1, 0));
                float speed = (float) toDouble((Object) map.get("speed"), 1.0);
                yield Animations.rotate(axis, speed);
            }
            case "bob" -> {
                float amplitude = (float) toDouble((Object) map.get("amplitude"), 0.1);
                int period = toInt((Object) map.get("period"), 40);
                yield Animations.bob(amplitude, period);
            }
            case "pulse" -> {
                float min = (float) toDouble((Object) map.get("min_scale"), 0.8);
                float max = (float) toDouble((Object) map.get("max_scale"), 1.2);
                int period = toInt((Object) map.get("period"), 40);
                yield Animations.pulse(min, max, period);
            }
            case "orbit" -> {
                float radius = (float) toDouble((Object) map.get("radius"), 0.5);
                int period = toInt((Object) map.get("period"), 40);
                Vector3f axis = parseVector3f(map.get("axis"), new Vector3f(0, 1, 0));
                yield Animations.orbit(radius, period, axis);
            }
            case "compose" -> {
                List<Map<?, ?>> layers = (List<Map<?, ?>>) map.get("layers");
                if (layers == null || layers.isEmpty()) {
                    throw new IllegalArgumentException("compose animation requires a non-empty 'layers' list");
                }
                DisplayAnimation[] anims = layers.stream()
                        .map(BlockLoader::parseAnimation)
                        .toArray(DisplayAnimation[]::new);
                yield Animations.compose(anims);
            }
            default -> throw new IllegalArgumentException("Unknown animation type: " + type);
        };
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

        float volume = (float) toDouble(map.get("volume"), 1.0);
        float pitch = (float) toDouble(map.get("pitch"), 1.0);
        boolean consume = Boolean.TRUE.equals(map.get("consume"));
        int consumeAmount = toInt(map.get("consume_amount"), 1);

        return new CustomHeadBlock.StateTransition(trigger, from, to, sound, volume, pitch, transParticle,
                consume, consumeAmount);
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

    private static void parseRedstone(CustomHeadBlock.Builder b, ConfigurationSection sec,
                                      Map<String, String> textureAliases) {
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
                textures.put(Integer.parseInt(key), resolveTexture(texSec.getString(key), textureAliases));
            }
            b.redstoneTextures(textures);
        }

        // Range-based textures
        ConfigurationSection rangeSec = sec.getConfigurationSection("texture_ranges");
        if (rangeSec != null) {
            Map<CustomHeadBlock.PowerRange, String> ranges = new HashMap<>();
            for (String key : rangeSec.getKeys(false)) {
                ranges.put(parseRange(key), resolveTexture(rangeSec.getString(key), textureAliases));
            }
            b.redstoneTextureRanges(ranges);
        }
    }

    private static CustomHeadBlock.SoundConfig parseSoundConfig(ConfigurationSection sec) {
        Sound sound = Sound.valueOf(sec.getString("sound", "BLOCK_STONE_BREAK").toUpperCase());
        float volume = (float) sec.getDouble("volume", 1.0);
        float pitch = (float) sec.getDouble("pitch", 1.0);
        return new CustomHeadBlock.SoundConfig(sound, volume, pitch);
    }

    private static Map<BlockFace, String> parseDirectionalTextures(ConfigurationSection sec,
                                                                   Map<String, String> textures) {
        Map<BlockFace, String> map = new HashMap<>();
        for (String key : sec.getKeys(false)) {
            map.put(BlockFace.valueOf(key.toUpperCase()), resolveTexture(sec.getString(key), textures));
        }
        return map;
    }

    /**
     * Resolve a texture reference. Values beginning with "@" are looked up in the file's
     * named-texture registry; any other value is returned as-is (literal base64).
     * Throws on an unknown alias so the block fails to load with a clear message.
     */
    private static String resolveTexture(String value, Map<String, String> textures) {
        if (value == null || !value.startsWith("@")) return value;
        String key = value.substring(1);
        String resolved = textures.get(key);
        if (resolved == null) {
            throw new IllegalArgumentException("Unknown texture alias: @" + key);
        }
        return resolved;
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

    private static CustomHeadBlock.DropRule parseDropRule(Map<?, ?> m) {
        String inState = m.get("in_state") != null ? String.valueOf(m.get("in_state")) : null;
        Boolean silkTouch = m.get("silk_touch") instanceof Boolean b ? b : null;
        Material requiredTool = m.get("required_tool") != null
                ? Material.matchMaterial(String.valueOf(m.get("required_tool"))) : null;

        List<CustomHeadBlock.ItemDrop> itemDrops = new ArrayList<>();
        Object itemsObj = m.get("items");
        if (itemsObj instanceof List<?> il) {
            for (Object item : il) {
                if (item instanceof Map<?, ?> im) {
                    Material mat = Material.matchMaterial(String.valueOf(im.get("material")));
                    int amount = toInt(im.get("amount"), 1);
                    if (mat != null) itemDrops.add(new CustomHeadBlock.ItemDrop(mat, amount));
                }
            }
        }
        // empty itemDrops with "items: self" or no items key → isSelfDrop() = true
        return new CustomHeadBlock.DropRule(inState, requiredTool, silkTouch, itemDrops);
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
