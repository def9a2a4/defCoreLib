package anon.def9a2a4.corelib;

import com.google.gson.GsonBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Headless harness that builds every {@link ShowcaseSpec} as a real machine, drives it for a bounded
 * number of ticks (network recalc + spin-up), then either:
 *   <ul><li><b>tests</b> ({@code -Ddefcorelib.showcaseTest=true}) — asserts each YAML {@code expect}, exits
 *   0 (all pass) / 1 (any fail) so {@code make showcase-test} gates; and/or</li>
 *   <li><b>captures</b> ({@code -Ddefcorelib.showcaseCapture=<path>}) — reads the running displays + bakes
 *   animations into {@code showcase-spec.json} for the docs.</li></ul>
 * Builds at fresh, far coords (untouched chunks ⇒ deterministic). Inert when neither property is set.
 */
final class ShowcaseRunner implements Listener {

    private static final int TEST_X = 1000;     // far from the docs grid → fresh, clean chunks
    private static final int TEST_Y = -50;      // in air above the superflat surface
    private static final int SPACING = 16;      // machines spaced along z

    private static final long ACTIVATE_AT = 20; // ticks after build
    private static final long FINISH_AT = 80;

    private final CoreLibPlugin plugin;
    private final CustomBlockRegistry registry;
    private final RotationNetwork network;
    private final EngineFuelManager fuelManager;
    private final ShowcaseBuilder builder;
    private final java.util.Collection<ShowcaseSpec> showcases;
    private final boolean testMode;
    private final Path capturePath;   // null if not capturing
    private final RotationRotator rotator;   // for the `mechanism_swung` assertion
    private final Map<ShowcaseSpec, Location> origins = new LinkedHashMap<>();
    private boolean done;

    private ShowcaseRunner(CoreLibPlugin plugin, CustomBlockRegistry registry, RotationNetwork network,
                           EngineFuelManager fuelManager, ShowcaseBuilder builder,
                           java.util.Collection<ShowcaseSpec> showcases, boolean testMode, Path capturePath,
                           RotationRotator rotator) {
        this.plugin = plugin;
        this.registry = registry;
        this.network = network;
        this.fuelManager = fuelManager;
        this.builder = builder;
        this.showcases = showcases;
        this.testMode = testMode;
        this.capturePath = capturePath;
        this.rotator = rotator;
    }

    static boolean armIfRequested(CoreLibPlugin plugin, CustomBlockRegistry registry, RotationNetwork network,
                                  EngineFuelManager fuelManager, ShowcaseBuilder builder,
                                  java.util.Collection<ShowcaseSpec> showcases, RotationRotator rotator) {
        boolean test = Boolean.getBoolean("defcorelib.showcaseTest");
        String capture = System.getProperty("defcorelib.showcaseCapture");
        if (!test && (capture == null || capture.isBlank())) return false;
        Path capturePath = (capture == null || capture.isBlank()) ? null : Path.of(capture);
        ShowcaseRunner runner = new ShowcaseRunner(plugin, registry, network, fuelManager, builder,
                showcases, test, capturePath, rotator);
        Bukkit.getPluginManager().registerEvents(runner, plugin);
        plugin.getLogger().info("ShowcaseRunner armed (" + (test ? "test " : "") + (capturePath != null
                ? "capture→" + capturePath : "") + ") — " + showcases.size() + " showcases.");
        return true;
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        if (done) return;
        if (event.getType() != ServerLoadEvent.LoadType.STARTUP) return;
        done = true;
        World world = Bukkit.getWorlds().get(0);
        int i = 0;
        for (ShowcaseSpec spec : showcases) {
            Location origin = new Location(world, TEST_X, TEST_Y, i * SPACING);
            origin.getChunk().load(true);
            origin.getChunk().setForceLoaded(true);   // must tick for the network to run
            try {
                int placed = builder.build(spec, origin);
                origins.put(spec, origin);
                plugin.getLogger().info("  [showcase] built " + spec.id + " (" + placed + " blocks) @ "
                        + TEST_X + " " + TEST_Y + " " + (i * SPACING));
            } catch (Throwable t) {
                plugin.getLogger().warning("[showcase] build " + spec.id + " failed: " + t);
            }
            i++;
        }
        Bukkit.getScheduler().runTaskLater(plugin, this::activateAll, ACTIVATE_AT);
        Bukkit.getScheduler().runTaskLater(plugin, this::finish, FINISH_AT);
    }

    private void activateAll() {
        for (Map.Entry<ShowcaseSpec, Location> e : origins.entrySet()) {
            ShowcaseActivation.activate(e.getKey(), e.getValue(), network, fuelManager);
        }
    }

    private void finish() {
        if (capturePath != null) capture();
        int fail = testMode ? assertAll() : 0;
        System.out.flush();
        System.err.flush();
        Runtime.getRuntime().halt(fail == 0 ? 0 : 1);
    }

    // ── Test ─────────────────────────────────────────────────────────────────

    private int assertAll() {
        Logger log = plugin.getLogger();
        int pass = 0, fail = 0;
        log.info("──────── showcase tests ────────");
        for (Map.Entry<ShowcaseSpec, Location> e : origins.entrySet()) {
            for (ShowcaseSpec.Expect ex : e.getKey().expect) {
                boolean ok = check(ex, blockAt(e.getValue(), ex.at()));
                log.info("  [" + (ok ? "PASS" : "FAIL") + "] " + e.getKey().id + " " + describe(ex));
                if (ok) pass++; else fail++;
            }
        }
        log.info("──────── " + pass + " passed, " + fail + " failed ────────");
        return fail;
    }

    private boolean check(ShowcaseSpec.Expect ex, Block block) {
        switch (ex.type()) {
            case "spinning" -> {
                String s = registry.getState(block);
                return s != null && (s.contains("spinning") || s.contains("running"));
            }
            case "network_powered" -> {
                return network.isPowered(CustomBlockRegistry.LocationKey.of(block));
            }
            case "state" -> {
                return ex.value() != null && ex.value().equals(registry.getState(block));
            }
            case "mechanism_swung" -> {
                return rotator != null && rotator.swingCount(block) > 0;
            }
            default -> {
                plugin.getLogger().warning("[showcase] unknown expect type: " + ex.type());
                return false;
            }
        }
    }

    // ── Capture ────────────────────────────────────────────────────────────

    private void capture() {
        Logger log = plugin.getLogger();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<ShowcaseSpec, Location> e : origins.entrySet()) {
            ShowcaseSpec spec = e.getKey();
            Location origin = e.getValue();
            List<Map<String, Object>> blocks = new ArrayList<>();
            for (ShowcaseSpec.BlockSpec bs : spec.blocks) {
                CustomHeadBlock type = registry.getType(bs.id());
                if (type == null) continue;
                Block block = blockAt(origin, bs.at());
                String state = registry.getState(block);
                boolean floor = bs.facing() == BlockFace.DOWN || bs.facing() == BlockFace.UP;
                BlockFace headFacing = floor ? null : bs.facing().getOppositeFace();

                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("id", bs.id());   // custom-block fullId, for showcase↔item interlinking
                rec.put("offset", new int[]{bs.at()[0], bs.at()[1], bs.at()[2]});
                rec.put("facing", floor ? "floor" : "wall_" + bs.facing().name().toLowerCase());
                rec.put("baseHeadTextureUrl", type.itemMaterial() != null ? null
                        : DisplayCapture.textureUrl(type.resolveTexture(state, 0, headFacing)));
                rec.put("displays", DisplayCapture.readDisplays(type, block.getLocation(), state, true, false));
                blocks.add(rec);
            }
            // Vanilla support blocks: emit a single static "block" display so the webpage renders them
            // (bare material name — generate_catalog vendors the model; placed3d draws it corner-origin).
            for (ShowcaseSpec.VanillaSpec vs : spec.vanilla) {
                Map<String, Object> display = new LinkedHashMap<>();
                display.put("kind", "block");
                display.put("ref", vs.material().getKey().getKey());   // e.g. "oak_planks"
                display.put("position", new double[]{0, 0, 0});
                display.put("matrix", new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1});
                display.put("animation", null);

                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("id", null);
                rec.put("offset", new int[]{vs.at()[0], vs.at()[1], vs.at()[2]});
                rec.put("baseHeadTextureUrl", null);
                rec.put("displays", List.of(display));
                blocks.add(rec);
            }
            Map<String, Object> sc = new LinkedHashMap<>();
            sc.put("id", spec.id);
            sc.put("name", spec.name);
            sc.put("blurb", spec.blurb);
            sc.put("description", spec.description);
            sc.put("blocks", blocks);
            out.add(sc);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("showcases", out);
        try {
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(root);
            Path tmp = capturePath.resolveSibling(capturePath.getFileName() + ".tmp");
            Files.createDirectories(capturePath.toAbsolutePath().getParent());
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, capturePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            log.info("[showcase] captured " + out.size() + " showcases → " + capturePath);
        } catch (Throwable t) {
            log.severe("[showcase] capture write failed: " + t);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String describe(ShowcaseSpec.Expect ex) {
        String at = "[" + ex.at()[0] + "," + ex.at()[1] + "," + ex.at()[2] + "]";
        return ex.type() + (ex.value() != null ? "=" + ex.value() : "") + " @ " + at;
    }

    private static Block blockAt(Location origin, int[] at) {
        return origin.getWorld().getBlockAt(
                origin.getBlockX() + at[0], origin.getBlockY() + at[1], origin.getBlockZ() + at[2]);
    }
}
