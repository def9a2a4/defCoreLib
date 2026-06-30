package anon.def9a2a4.corelib;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Rotatable;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.util.Transformation;
import org.joml.Matrix4f;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Exports the ground-truth "placed" appearance of every registered custom block to JSON,
 * for the docs website's 3D viewer. Runs only when {@code -Ddefcorelib.export=<path>} is set
 * (so it is inert in normal play): on {@link ServerLoadEvent} it places one of every block in
 * each placement orientation, lets the normal {@link CustomBlockRegistry#applyConfig} spawn the
 * real Display entities, reads their actual transforms back, bakes animation keyframes from the
 * real animation functions, writes the JSON, and shuts the server down.
 *
 * <p>Output shape (consumed by scripts/generate_catalog.py → docs/util/placed3d.js):
 * <pre>
 * { "ns:id": { "variants": [ {
 *     "id", "label", "baseHeadTextureUrl"|null,
 *     "displays": [ { "kind":"head|item|block", "ref", "position":[x,y,z], "matrix":[16],
 *                     "animation": { "period", "frames":[[16],...] } | null, "tag" } ] } ] } }
 * </pre>
 */
final class DisplayExporter implements Listener {

    private static final double GRID_Y = -58;       // build height (just above the superflat surface)
    private static final int VARIANT_SPACING = 6;   // +x between a block's own variants (close together)
    private static final int BLOCK_GAP = 12;        // +z between different blocks (well apart)
    private static final int SECTION_GAP = 24;      // +z between namespace sections
    private static final int MAX_PERIOD = 2400;     // tick cap for loop detection (2 min)
    private static final int MAX_FRAMES = 120;
    private static final float EPS = 1.0e-3f;

    // Namespace section order in the inspection grid; verticalslabs last (least important).
    private static final List<String> SECTION_ORDER = List.of("demo", "rotation");
    private static final String LAST_SECTION = "verticalslabs";

    private final CoreLibPlugin plugin;
    private final CustomBlockRegistry registry;
    private final Path outPath;
    private final boolean keepAlive;   // leave blocks placed + server running for in-game inspection
    private boolean done;
    private int rowZ;                  // grid cursor: current block's row (+z)

    private DisplayExporter(CoreLibPlugin plugin, CustomBlockRegistry registry, Path outPath, boolean keepAlive) {
        this.plugin = plugin;
        this.registry = registry;
        this.outPath = outPath;
        this.keepAlive = keepAlive;
    }

    /** Register the exporter listener if the export system property is set. Returns true if armed.
     *  With {@code -Ddefcorelib.exportKeep=true} the blocks are left placed and the server keeps
     *  running so you can join and inspect; otherwise it cleans up and shuts down. */
    static boolean armIfRequested(CoreLibPlugin plugin, CustomBlockRegistry registry) {
        String out = System.getProperty("defcorelib.export");
        if (out == null || out.isBlank()) return false;
        boolean keep = Boolean.getBoolean("defcorelib.exportKeep");
        DisplayExporter exporter = new DisplayExporter(plugin, registry, Path.of(out), keep);
        Bukkit.getPluginManager().registerEvents(exporter, plugin);
        plugin.getLogger().info("DisplayExporter armed → " + out
                + (keep ? " (keep-alive: blocks stay placed, server stays up for inspection)"
                        : " (server will export and shut down)"));
        return true;
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        if (done) return;
        done = true;
        Logger log = plugin.getLogger();
        try {
            Map<String, Object> result = exportAll(log);
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(result);
            Path tmp = outPath.resolveSibling(outPath.getFileName() + ".tmp");
            Files.createDirectories(outPath.toAbsolutePath().getParent());
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, outPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            log.info("DisplayExporter wrote " + result.size() + " block types → " + outPath);
        } catch (Throwable t) {
            log.severe("DisplayExporter failed: " + t);
            t.printStackTrace();
        } finally {
            if (keepAlive) {
                log.info("DisplayExporter keep-alive: join this server to inspect the placed blocks.");
            } else {
                Bukkit.getScheduler().runTask(plugin, Bukkit::shutdown);
            }
        }
    }

    // ── Export ─────────────────────────────────────────────────────────────

    private Map<String, Object> exportAll(Logger log) {
        World world = Bukkit.getWorlds().get(0);
        rowZ = 0;
        String curNs = null;
        Map<String, Object> out = new LinkedHashMap<>();
        for (CustomHeadBlock type : orderedTypes()) {
            if (!type.namespace().equals(curNs)) {
                if (curNs != null) rowZ += SECTION_GAP;
                curNs = type.namespace();
                log.info("== section: " + curNs + " (z=" + rowZ + ")");
            }
            try {
                List<Map<String, Object>> variants = exportType(type, world, log);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("variants", variants);
                out.put(type.fullId(), entry);
            } catch (Throwable t) {
                log.warning("export " + type.fullId() + ": " + t);
            }
            rowZ += BLOCK_GAP;
        }
        if (keepAlive) setupViewing(world, log);
        return out;
    }

    /** Types ordered by namespace section (demo, rotation, …, verticalslabs last). */
    private List<CustomHeadBlock> orderedTypes() {
        List<CustomHeadBlock> types = new ArrayList<>(registry.allTypes());
        types.sort(java.util.Comparator.comparingInt(t -> sectionRank(t.namespace())));
        return types;
    }

    private int sectionRank(String ns) {
        if (ns.equals(LAST_SECTION)) return 100;
        int i = SECTION_ORDER.indexOf(ns);
        return i >= 0 ? i : 50;
    }

    /** The placedOn faces to enumerate for a type (each becomes a placement variant). */
    private List<BlockFace> placedOnFaces(CustomHeadBlock type) {
        CustomHeadBlock.PlacementConfig pc = type.placement();
        if (pc != null && !pc.allowedFaces().isEmpty()) {
            return new ArrayList<>(pc.allowedFaces());
        }
        return List.of(BlockFace.DOWN);   // floor-only default
    }

    /** Emit each placement (floor/walls) × spin-state (idle / spinning / reversed). A block's
     *  variants share one row (+x), clustered together; different blocks sit on separate rows. */
    private List<Map<String, Object>> exportType(CustomHeadBlock type, World world, Logger log) {
        List<Map<String, Object>> variants = new ArrayList<>();
        int vi = 0;
        for (BlockFace placedOn : placedOnFaces(type)) {
            String idleState = type.defaultState();
            if (type.placementStateMap() != null) {
                String mapped = type.placementStateMap().get(placedOn);
                if (mapped != null) idleState = mapped;
            }
            String spinState = animatedSiblingOr(type, idleState);
            boolean animated = hasAnimation(type, spinState);

            String baseLabel = placedOn == BlockFace.DOWN ? "Floor" : ("Wall " + cap(placedOn.name()));
            String baseId = placedOn == BlockFace.DOWN ? "floor" : ("wall_" + placedOn.name().toLowerCase());

            // Idle (placement state, animation suppressed).
            variants.add(buildVariant(type, world, placedOn, idleState, baseId, baseLabel,
                    animated ? "idle" : "", false, false, vi++, log));
            if (animated) {
                variants.add(buildVariant(type, world, placedOn, spinState, baseId, baseLabel,
                        "spinning", true, false, vi++, log));
                variants.add(buildVariant(type, world, placedOn, spinState, baseId, baseLabel,
                        "reversed", true, true, vi++, log));
            }
        }
        return variants;
    }

    private Map<String, Object> buildVariant(CustomHeadBlock type, World world, BlockFace placedOn,
            String state, String baseId, String baseLabel, String suffix,
            boolean animate, boolean reversed, int vi, Logger log) {
        Location loc = new Location(world, vi * VARIANT_SPACING, GRID_Y, rowZ);
        loc.getChunk().load(true);
        Block block = loc.getBlock();

        boolean floor = placedOn == BlockFace.DOWN
                || (state != null && type.playerHeadStates().contains(state));
        BlockFace headFacing = floor ? null : placedOn.getOppositeFace();

        placeHead(block, floor, headFacing);
        registry.markBlock(block, type, state);
        registry.applyConfig(block, type, state, 0);

        String vid = suffix.isEmpty() ? baseId : (baseId + "_" + suffix);
        String label = suffix.isEmpty() ? baseLabel : (baseLabel + " · " + suffix);

        Map<String, Object> variant = new LinkedHashMap<>();
        variant.put("id", vid);
        variant.put("label", label);
        variant.put("baseHeadTextureUrl",
                type.itemMaterial() != null ? null : textureUrl(type.resolveTexture(state, 0, headFacing)));
        variant.put("displays", readDisplays(type, loc, state, animate, reversed));

        if (keepAlive) {
            log.info("  " + type.fullId() + " " + vid + " @ "
                    + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ());
        } else {
            cleanup(type, block);
        }
        return variant;
    }

    private boolean hasAnimation(CustomHeadBlock type, String state) {
        for (var d : type.resolveDisplayEntities(state)) if (d.animation() != null) return true;
        for (var d : type.resolveBlockDisplayEntities(state)) if (d.animation() != null) return true;
        return false;
    }

    /** The animated counterpart of a placement state, by the plugin's own naming convention
     *  (`idle*` → `spinning*`, e.g. idle_z → spinning_z). Falls back to the idle state. */
    private String animatedSiblingOr(CustomHeadBlock type, String idleState) {
        if (idleState == null || !idleState.contains("idle")) return idleState;
        String candidate = idleState.replace("idle", "spinning");
        return type.states().containsKey(candidate) ? candidate : idleState;
    }

    /** Keep-alive: make the world spawn at the grid, in clear daylight, so a joining player lands
     *  on the blocks without needing op/teleport. */
    private void setupViewing(World world, Logger log) {
        try {
            world.setSpawnLocation(2, (int) GRID_Y + 1, -5);
            world.setTime(6000);
            world.setStorm(false);
            world.setThundering(false);
            world.setDifficulty(org.bukkit.Difficulty.PEACEFUL);
            world.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false);
            world.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false);
            world.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, false);
            log.info("keep-alive: world spawn set to the grid; join localhost:25575 (MC 1.21.11).");
        } catch (Throwable t) {
            log.warning("setupViewing: " + t);
        }
    }

    // ── Block placement / cleanup ──────────────────────────────────────────

    private void placeHead(Block block, boolean floor, BlockFace headFacing) {
        if (floor) {
            block.setType(Material.PLAYER_HEAD, false);
            if (block.getBlockData() instanceof Rotatable r) {
                r.setRotation(BlockFace.NORTH);
                block.setBlockData(r, false);
            }
        } else {
            block.setType(Material.PLAYER_WALL_HEAD, false);
            if (block.getBlockData() instanceof Directional d) {
                d.setFacing(headFacing);
                block.setBlockData(d, false);
            }
        }
    }

    private void cleanup(CustomHeadBlock type, Block block) {
        try { registry.onBlockRemoved(block, type); } catch (Throwable ignored) {}
        // Belt-and-suspenders: clear any stray displays at this location.
        DisplayUtil.removeByTag(block.getLocation(),
                DisplayUtil.blockTagPrefix(type.namespace(), type.typeId(), block.getLocation()), 2.0);
        block.setType(Material.AIR, false);
    }

    // ── Reading spawned displays ───────────────────────────────────────────

    private List<Map<String, Object>> readDisplays(CustomHeadBlock type, Location scratch, String state,
            boolean animate, boolean reversed) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!type.hasDisplayEntities()) return out;

        Location blockLoc = scratch.getBlock().getLocation();
        Location center = blockLoc.clone().add(0.5, 0.5, 0.5);
        String prefix = DisplayUtil.blockTagPrefix(type.namespace(), type.typeId(), blockLoc);

        // Config map: tagSuffix → (animation, base transform) for baking motion.
        Map<String, CustomHeadBlock.DisplayEntityConfig> itemBySuffix = new LinkedHashMap<>();
        for (var d : type.resolveDisplayEntities(state)) itemBySuffix.put(nullKey(d.tagSuffix()), d);
        Map<String, CustomHeadBlock.BlockDisplayEntityConfig> blockBySuffix = new LinkedHashMap<>();
        for (var d : type.resolveBlockDisplayEntities(state)) blockBySuffix.put(nullKey(d.tagSuffix()), d);

        for (Display display : DisplayUtil.findByTag(blockLoc, prefix, 2.0)) {
            Map<String, Object> rec = new LinkedHashMap<>();
            String suffix = suffixOf(display, prefix);
            rec.put("tag", suffix);

            Location loc = display.getLocation();
            rec.put("position", new double[]{
                    loc.getX() - center.getX(), loc.getY() - center.getY(), loc.getZ() - center.getZ()});

            Matrix4f base = toMatrix(display.getTransformation());
            rec.put("matrix", arr(base));

            DisplayAnimation anim = null;
            if (display instanceof ItemDisplay item) {
                ItemStack stack = item.getItemStack();
                if (stack.getType() == Material.PLAYER_HEAD) {
                    rec.put("kind", "head");
                    rec.put("ref", textureUrl(itemTexture(stack)));
                } else {
                    rec.put("kind", "item");
                    rec.put("ref", stack.getType().getKey().getKey());
                }
                var cfg = itemBySuffix.get(nullKey(suffix));
                if (cfg != null) anim = cfg.animation();
            } else if (display instanceof BlockDisplay bd) {
                rec.put("kind", "block");
                rec.put("ref", bd.getBlock().getAsString());
                var cfg = blockBySuffix.get(nullKey(suffix));
                if (cfg != null) anim = cfg.animation();
            } else {
                continue;
            }

            rec.put("animation", (animate && anim != null) ? bake(anim, base, reversed) : null);
            out.add(rec);
        }
        return out;
    }

    /** Bake a looping matrix track by sampling the real animation function over its detected period.
     *  `reversed` negates the tick (matching CustomBlockRegistry's `if (reversed) tickAge = -tickAge`). */
    private Map<String, Object> bake(DisplayAnimation anim, Matrix4f base, boolean reversed) {
        Matrix4f f0 = new Matrix4f();
        anim.apply(base, 0, f0);
        int period = 0;
        Matrix4f f = new Matrix4f();
        for (int t = 1; t <= MAX_PERIOD; t++) {
            anim.apply(base, t, f);
            if (approxEqual(f, f0)) { period = t; break; }
        }
        if (period <= 0) period = MAX_FRAMES;
        int frames = Math.min(period, MAX_FRAMES);
        List<float[]> track = new ArrayList<>(frames);
        for (int k = 0; k < frames; k++) {
            long t = Math.round((double) period * k / frames);
            Matrix4f fk = new Matrix4f();
            anim.apply(base, reversed ? -t : t, fk);
            track.add(arr(fk));
        }
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("period", period);
        a.put("frames", track);
        return a;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static String nullKey(String s) { return s == null ? "" : s; }

    private static String suffixOf(Display display, String prefix) {
        for (String tag : display.getScoreboardTags()) {
            if (tag.startsWith(prefix)) {
                String rest = tag.substring(prefix.length());
                return rest.startsWith(":") ? rest.substring(1) : null;
            }
        }
        return null;
    }

    private static Matrix4f toMatrix(Transformation t) {
        return new Matrix4f()
                .translation(t.getTranslation())
                .rotate(t.getLeftRotation())
                .scale(t.getScale())
                .rotate(t.getRightRotation());
    }

    private static float[] arr(Matrix4f m) {
        float[] a = new float[16];
        m.get(a);                 // column-major, matches THREE.Matrix4.fromArray
        return a;
    }

    private static boolean approxEqual(Matrix4f a, Matrix4f b) {
        float[] x = new float[16], y = new float[16];
        a.get(x); b.get(y);
        for (int i = 0; i < 16; i++) if (Math.abs(x[i] - y[i]) > EPS) return false;
        return true;
    }

    private static String itemTexture(ItemStack head) {
        if (!(head.getItemMeta() instanceof SkullMeta sm)) return null;
        PlayerProfile profile = sm.getPlayerProfile();
        if (profile == null) return null;
        for (ProfileProperty p : profile.getProperties()) {
            if ("textures".equals(p.getName())) return p.getValue();
        }
        return null;
    }

    private static String textureUrl(String base64) {
        if (base64 == null) return null;
        return HeadUtil.parseTexture(base64).map(HeadUtil.TextureInfo::textureUrl).orElse(null);
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : s.charAt(0) + s.substring(1).toLowerCase();
    }
}
