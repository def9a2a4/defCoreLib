package anon.def9a2a4.corelib;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
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

    private static final double GRID_Y = -60;       // build height (on the superflat surface)
    private static final int AXIS_GAP = 8;          // offset of each quadrant from the centre axes
                                                    // (nearest cells across an axis are 2×AXIS_GAP
                                                    // apart, so quadrants never overlap)
    private static final int SPACING = 4;           // cells 4 apart (demo / rotation / slabs)
    private static final int WINDMILL_SPACING = 12; // windmill "large display" quadrant

    private final CoreLibPlugin plugin;
    private final CustomBlockRegistry registry;
    private final Path outPath;
    private final boolean keepAlive;   // leave blocks placed + server running for in-game inspection
    private final ShowcaseBuilder showcaseBuilder;
    private final java.util.Collection<ShowcaseSpec> showcases;
    private final RotationNetwork network;        // keep-alive: drive the showcase machines
    private final EngineFuelManager fuelManager;  // keep-alive: fuel the engine showcases
    private boolean done;

    private DisplayExporter(CoreLibPlugin plugin, CustomBlockRegistry registry, Path outPath, boolean keepAlive,
                            ShowcaseBuilder showcaseBuilder, java.util.Collection<ShowcaseSpec> showcases,
                            RotationNetwork network, EngineFuelManager fuelManager) {
        this.plugin = plugin;
        this.registry = registry;
        this.outPath = outPath;
        this.keepAlive = keepAlive;
        this.showcaseBuilder = showcaseBuilder;
        this.showcases = showcases;
        this.network = network;
        this.fuelManager = fuelManager;
    }

    /** Register the exporter listener if the export system property is set. Returns true if armed.
     *  With {@code -Ddefcorelib.exportKeep=true} the blocks are left placed and the server keeps
     *  running so you can join and inspect; otherwise it cleans up and shuts down. */
    static boolean armIfRequested(CoreLibPlugin plugin, CustomBlockRegistry registry,
                                  ShowcaseBuilder showcaseBuilder, java.util.Collection<ShowcaseSpec> showcases,
                                  RotationNetwork network, EngineFuelManager fuelManager) {
        String out = System.getProperty("defcorelib.export");
        if (out == null || out.isBlank()) return false;
        boolean keep = Boolean.getBoolean("defcorelib.exportKeep");
        DisplayExporter exporter = new DisplayExporter(plugin, registry, Path.of(out), keep, showcaseBuilder,
                showcases, network, fuelManager);
        Bukkit.getPluginManager().registerEvents(exporter, plugin);
        plugin.getLogger().info("DisplayExporter armed → " + out
                + (keep ? " (keep-alive: blocks stay placed, server stays up for inspection)"
                        : " (server will export and shut down)"));
        return true;
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        if (done) return;
        if (event.getType() != ServerLoadEvent.LoadType.STARTUP) return;   // not on /reload
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
                // The test-server world is disposable scratch — nothing to save — so skip
                // Bukkit.shutdown()/System.exit() entirely. Its save-on-main-thread shutdown hook
                // (saveAll → ensureMain → Waitable.get()) deadlocks once the main thread has stopped,
                // leaving a wedged ~1 GB JVM. The JSON is already durably written above (synchronous
                // Files.writeString + atomic Files.move); halt(0) ends the JVM, not the OS.
                log.info("DisplayExporter: export complete, halting (scratch world, nothing to save).");
                System.out.flush();
                System.err.flush();
                Runtime.getRuntime().halt(0);
            }
        }
    }

    // ── Export ─────────────────────────────────────────────────────────────

    /** One layout quadrant: a 2D grid growing outward from the origin in (sx, sz). One block per
     *  row (variants along x); blocks step along z. */
    private static final class Quad {
        final int sx, sz, spacing;
        int row;
        Quad(int sx, int sz, int spacing) { this.sx = sx; this.sz = sz; this.spacing = spacing; }
    }

    private Map<String, Object> exportAll(Logger log) {
        World world = Bukkit.getWorlds().get(0);
        Quad demo = new Quad(1, 1, SPACING);
        Quad rotation = new Quad(-1, 1, SPACING);
        Quad slabs = new Quad(1, -1, SPACING);
        Quad windmill = new Quad(-1, -1, WINDMILL_SPACING);

        Map<String, Object> out = new LinkedHashMap<>();
        for (CustomHeadBlock type : registry.allTypes()) {
            Quad quad = quadFor(type, demo, rotation, slabs, windmill);
            try {
                List<Map<String, Object>> variants = exportType(type, world, quad, log);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("variants", variants);
                // Banner tier gates which custom banner crafts this block (e.g. windmills);
                // the docs read it to show the real required banner instead of a generic tag.
                entry.put("bannerTier", type.bannerTier() == null ? null : type.bannerTier().name());
                out.put(type.fullId(), entry);
            } catch (Throwable t) {
                log.warning("export " + type.fullId() + ": " + t);
            }
        }
        if (keepAlive) {
            // Multi-block showcases live in the same world (windmill quadrant), but only in keep-alive:
            // the non-keepalive run halt(0)s immediately, so their deferred registration would never run.
            Map<ShowcaseSpec, Location> origins = ShowcaseWorld.placeAll(world, showcaseBuilder, showcases, log);
            startShowcaseDriver(origins, log);
            setupViewing(world, log);
        }
        return out;
    }

    private static final long DRIVE_FIRST = 20;     // ticks: let the deferred build + network settle
    private static final long DRIVE_PERIOD = 100;   // ticks (~5 s): re-fuel engines / re-pulse triggers

    /** Keep every machine visibly running for the join-and-look world: fuel engines (and keep topping
     *  them up), kick passive networks, and pulse — then re-pulse — redstone triggers so swing machines
     *  cycle instead of opening once. Mirrors {@link ShowcaseRunner} activation but loops indefinitely. */
    private void startShowcaseDriver(Map<ShowcaseSpec, Location> origins, Logger log) {
        if (origins.isEmpty()) return;
        // First pass: standard activation (fuel / pulse / passive recalc).
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Map.Entry<ShowcaseSpec, Location> e : origins.entrySet()) {
                ShowcaseActivation.activate(e.getKey(), e.getValue(), network, fuelManager);
            }
            log.info("DisplayExporter keep-alive: activated " + origins.size() + " showcase machines.");
        }, DRIVE_FIRST);
        // Looping driver: top up fuel; toggle pulse triggers so they re-fire on each rising edge.
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Map.Entry<ShowcaseSpec, Location> e : origins.entrySet()) {
                ShowcaseSpec.Activate act = e.getKey().activate;
                if (act.at() == null) continue;
                Block at = ShowcaseActivation.blockAt(e.getValue(), act.at());
                switch (act.kind()) {
                    case "fuel" -> fuelManager.addFuel(
                            CustomBlockRegistry.LocationKey.of(at), ShowcaseActivation.FUEL_TICKS);
                    // Toggle the trigger block: AIR on this tick → REDSTONE_BLOCK next → fresh rising edge.
                    case "pulse" -> at.setType(at.getType() == Material.REDSTONE_BLOCK
                            ? Material.AIR : Material.REDSTONE_BLOCK, true);
                    default -> { }
                }
            }
        }, DRIVE_FIRST + DRIVE_PERIOD, DRIVE_PERIOD);
    }

    /** Quadrant for a type: demo (+x,+z), rotation (−x,+z), verticalslabs (+x,−z), windmills the
     *  "large display" quadrant (−x,−z). */
    private Quad quadFor(CustomHeadBlock type, Quad demo, Quad rotation, Quad slabs, Quad windmill) {
        String ns = type.namespace();
        if (ns.equals("verticalslabs")) return slabs;
        if (ns.equals("rotation")) return type.typeId().contains("windmill") ? windmill : rotation;
        return demo;   // demo + any other namespace
    }

    /** The placedOn faces to enumerate for a type. Blocks declare orientations via `allowed_faces`
     *  and/or `placement_state_map`; we union both (→ DOWN-only if neither is present) so wall
     *  orientations (gears/shafts/windmills) are enumerated, not just the floor. */
    private List<BlockFace> placedOnFaces(CustomHeadBlock type) {
        java.util.LinkedHashSet<BlockFace> faces = new java.util.LinkedHashSet<>();
        CustomHeadBlock.PlacementConfig pc = type.placement();
        if (pc != null) faces.addAll(pc.allowedFaces());
        if (type.placementStateMap() != null) faces.addAll(type.placementStateMap().keySet());
        if (faces.isEmpty()) faces.add(BlockFace.DOWN);
        return new ArrayList<>(faces);
    }

    /** Emit each placement (floor/walls) × spin-state (idle / spinning / reversed) into the type's
     *  quadrant. A block's variants share one row (+x); the next block steps along z. */
    private List<Map<String, Object>> exportType(CustomHeadBlock type, World world, Quad quad, Logger log) {
        List<Map<String, Object>> variants = new ArrayList<>();
        int vi = 0;
        for (BlockFace placedOn : placedOnFaces(type)) {
            String placement = type.defaultState();
            if (type.placementStateMap() != null) {
                String mapped = type.placementStateMap().get(placedOn);
                if (mapped != null) placement = mapped;
            }
            String baseLabel = placedOn == BlockFace.DOWN ? "Floor" : ("Wall " + cap(placedOn.name()));
            String baseId = placedOn == BlockFace.DOWN ? "floor" : ("wall_" + placedOn.name().toLowerCase());

            // Resolve the idle (rest) state and its spinning/running sibling for this orientation.
            String[] pair = idleSpinPair(type, placement);
            String idleState = pair[0], spinState = pair[1];
            boolean idleAnimated = idleState != null && hasAnimation(type, idleState);
            boolean spinExists = spinState != null && hasAnimation(type, spinState);
            String animState = spinExists ? spinState : (idleAnimated ? idleState : null);

            // Stopped: only when a genuinely static state exists (don't fake one for always-animated).
            if (!idleAnimated) {
                variants.add(buildVariant(type, world, quad, placedOn, idleState, baseId, baseLabel,
                        animState != null ? "stopped" : "", false, false, vi++, log));
            }
            // CW + CCW for the animated state.
            if (animState != null) {
                variants.add(buildVariant(type, world, quad, placedOn, animState, baseId, baseLabel,
                        "cw", true, false, vi++, log));
                variants.add(buildVariant(type, world, quad, placedOn, animState, baseId, baseLabel,
                        "ccw", true, true, vi++, log));
            }
        }
        quad.row++;
        return variants;
    }

    private Map<String, Object> buildVariant(CustomHeadBlock type, World world, Quad quad, BlockFace placedOn,
            String state, String baseId, String baseLabel, String suffix,
            boolean animate, boolean reversed, int vi, Logger log) {
        int x = quad.sx * (AXIS_GAP + vi * quad.spacing);
        int z = quad.sz * (AXIS_GAP + quad.row * quad.spacing);
        Location loc = new Location(world, x, GRID_Y, z);
        loc.getChunk().load(true);
        if (keepAlive) loc.getChunk().setForceLoaded(true);   // keep animations running off-screen
        Block block = loc.getBlock();

        boolean floor = placedOn == BlockFace.DOWN
                || (state != null && type.playerHeadStates().contains(state));
        BlockFace headFacing = floor ? null : placedOn.getOppositeFace();

        placeHead(block, floor, headFacing);
        // NOTE: deliberately NOT markBlock'd — these inspection blocks must not join the rotation
        // network, or RotationNetwork.updateBlockState would reset consumers (drill/fan) to idle.
        registry.applyConfig(block, type, state, 0);
        if (animate) {   // make the in-game block spin the intended way (no effect on the tick-0 read-back)
            registry.setAnimationDirection(CustomBlockRegistry.LocationKey.of(block),
                    reversed ? RotationNetwork.SpinDirection.CCW : RotationNetwork.SpinDirection.CW);
        }

        String vid = suffix.isEmpty() ? baseId : (baseId + "_" + suffix);
        String label = suffix.isEmpty() ? baseLabel : (baseLabel + " · " + prettySuffix(suffix));

        Map<String, Object> variant = new LinkedHashMap<>();
        variant.put("id", vid);
        variant.put("label", label);
        variant.put("baseHeadTextureUrl",
                type.itemMaterial() != null ? null
                        : DisplayCapture.textureUrl(type.resolveTexture(state, 0, headFacing)));
        // Base-head placement: a floor PLAYER_HEAD is seated, a PLAYER_WALL_HEAD is mounted
        // vertically-centred and pushed onto the wall. The frontend seats the skull accordingly.
        variant.put("baseHeadWall", !floor);
        variant.put("baseHeadFacing", headFacing == null ? null : headFacing.name().toLowerCase());
        variant.put("displays", DisplayCapture.readDisplays(type, loc, state, animate, reversed));

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

    /** Resolve the rest (idle) state and its animated sibling for an orientation, from the
     *  placement state's `idle_`/`spinning_`/`running_` prefix + shared suffix. Returns
     *  {idleState, spinState|null}; for a non-rotation state returns {placement, null}. */
    private String[] idleSpinPair(CustomHeadBlock type, String placement) {
        if (placement == null) return new String[]{null, null};
        String suffix;
        if (placement.startsWith("idle_")) suffix = placement.substring(5);
        else if (placement.startsWith("spinning_")) suffix = placement.substring(9);
        else if (placement.startsWith("running_")) suffix = placement.substring(8);
        else if (placement.equals("idle") || placement.equals("spinning") || placement.equals("running")) suffix = "";
        else return new String[]{placement, null};   // not a rotation state

        String idle = suffix.isEmpty() ? "idle" : "idle_" + suffix;
        if (!type.states().containsKey(idle)) idle = placement;
        String spin = null;
        for (String pre : new String[]{"spinning", "running"}) {
            String cand = suffix.isEmpty() ? pre : pre + "_" + suffix;
            if (type.states().containsKey(cand)) { spin = cand; break; }
        }
        return new String[]{idle, spin};
    }

    /** Keep-alive: make the world spawn at the grid, in clear daylight, so a joining player lands
     *  on the blocks without needing op/teleport. */
    private void setupViewing(World world, Logger log) {
        try {
            world.setSpawnLocation(0, (int) GRID_Y + 2, 0);   // centre of the four quadrants
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

    // ── Helpers ────────────────────────────────────────────────────────────

    private static String cap(String s) {
        return s.isEmpty() ? s : s.charAt(0) + s.substring(1).toLowerCase();
    }

    private static String prettySuffix(String s) {
        return switch (s) {
            case "cw" -> "CW";
            case "ccw" -> "CCW";
            default -> s;
        };
    }
}
