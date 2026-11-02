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
import org.jspecify.annotations.Nullable;

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
 *
 * <p>A spec may also carry {@code test_only: true} (a regression scenario, not a demo: skipped entirely
 * when only capturing, and never written into the capture), scripted {@code actions} run at a given tick,
 * and an {@code assert_at} tick for scenarios that need longer than {@link #FINISH_AT}. The run halts at
 * the latest assertion tick; capture still reads the machines at {@link #FINISH_AT}.
 *
 * <p><b>Restart scenarios</b> ({@code restart: clean|crash}) run only under {@code make showcase-restart-test},
 * one mode per pair of boots ({@code -Ddefcorelib.showcaseRestart=clean|crash}) on a throwaway world.
 * Phase 1 ({@code -Ddefcorelib.showcasePhase=1}) builds that mode's specs, runs their actions and
 * {@code expect}s, then ends the boot: {@code clean} = {@link Bukkit#shutdown()} (plugins disable, worlds
 * save); {@code crash} = {@link Runtime#halt} with {@link #CRASH_EXIT} — nothing disabled or saved beyond what
 * a {@code save} action already wrote. Phase 2 lays the same specs out at the same origins without building,
 * waits for their chunks' entities, then checks {@code after_restart}. One mode per run because a crash and a
 * clean stop can't end the same boot.
 */
final class ShowcaseRunner implements Listener {

    private static final int TEST_X = 1000;     // far from the docs grid → fresh, clean chunks
    private static final int FLOOR_Y = -60;     // every showcase's lowest block lands here
    private static final int SPACING = 16;      // machines spaced along z

    private static final long ACTIVATE_AT = 20; // ticks after build
    private static final long FINISH_AT = 80;   // default assertion tick, and the capture tick
    /** Exit code of a restart phase-1 boot that "crashed" on purpose (all its phase-1 expects passed). */
    static final int CRASH_EXIT = 3;
    private static final long RESTART_SETTLE = 40;   // phase 2: ticks after the entities load (recovery runs)
    private static final long RESTART_GIVE_UP = 200; // phase 2: assert anyway if they still haven't loaded

    /** Every {@code expect} type / action kind {@link #check} / {@link #runAction} know — anything else FAILs
     *  the spec (ShowcaseSpecTest also checks showcases.yml against these). */
    static final java.util.Set<String> EXPECT_TYPES = java.util.Set.of("spinning", "network_powered", "state",
            "mechanism_swung", "hoist_moved", "piston_stroked", "block_type", "persisted_mechanism_intact",
            "entity_count", "riding", "contents", "material_total", "mechanism_count", "mechanism_state_file",
            "engine_fuel", "frame_rotation");
    static final java.util.Set<String> ACTION_KINDS = java.util.Set.of("set", "corrupt_persisted_mechanism",
            "summon", "break", "drop", "persist_mechanism", "disassemble_mechanism", "save");

    private final CoreLibPlugin plugin;
    private final CustomBlockRegistry registry;
    private final RotationNetwork network;
    private final EngineFuelManager fuelManager;
    private final ShowcaseBuilder builder;
    private final java.util.Collection<ShowcaseSpec> showcases;
    private final boolean testMode;
    private final Path capturePath;   // null if not capturing
    private final Map<ShowcaseSpec, Map<String, String>> restRefs = new java.util.HashMap<>();   // capture: pre-swing refs
    private final RotationRotator rotator;   // for the `mechanism_swung` assertion
    private final ChainHoistManager hoists;  // for `hoist_moved`
    private final ExtendablePistonManager pistons; // for `piston_stroked`
    private final MechanismRegistry mechRegistry;  // for the corrupt-state probe
    private final int phase;                // 0 = a normal run; 1 / 2 = the boots of a restart run
    private final @Nullable String restartMode;   // clean | crash, restart runs only
    private final Map<ShowcaseSpec, Location> origins = new LinkedHashMap<>();
    // Each spec's verdicts, recorded at its own assert tick and printed together (in spec order) at the end.
    private final Map<ShowcaseSpec, List<String>> results = new LinkedHashMap<>();
    private int pass, fail;
    // `corrupt_persisted_mechanism` probes, keyed by "<spec id>@<world cell>" — read by `persisted_mechanism_intact`.
    private final Map<String, Probe> probes = new LinkedHashMap<>();
    // Specs whose build threw: they never reach `origins`, so the report counts their expects as failures.
    private final List<ShowcaseSpec> buildFailed = new ArrayList<>();
    // Specs whose assertion task ran (an action failure records lines before it, so `results` can't say).
    private final java.util.Set<ShowcaseSpec> asserted = new java.util.HashSet<>();
    // `persist_mechanism` ids, keyed like `probes` — read by `disassemble_mechanism` / `mechanism_state_file`.
    private final Map<String, java.util.UUID> persisted = new LinkedHashMap<>();
    // Chunks force-loaded for the specs' footprints; released before the boot ends.
    private final List<org.bukkit.Chunk> forced = new ArrayList<>();
    private boolean done;

    /** What a corrupt-state probe parked: the mechanism, how many tagged entities it had once parked,
     *  and the state file it corrupted. */
    private record Probe(java.util.UUID id, String world, org.bukkit.Chunk chunk, int entities, java.io.File file) {}

    private ShowcaseRunner(CoreLibPlugin plugin, CustomBlockRegistry registry, RotationNetwork network,
                           EngineFuelManager fuelManager, ShowcaseBuilder builder,
                           java.util.Collection<ShowcaseSpec> showcases, boolean testMode, Path capturePath,
                           RotationRotator rotator, ChainHoistManager hoists,
                           ExtendablePistonManager pistons, MechanismRegistry mechRegistry,
                           int phase, @Nullable String restartMode) {
        this.plugin = plugin;
        this.registry = registry;
        this.network = network;
        this.fuelManager = fuelManager;
        this.builder = builder;
        this.showcases = showcases;
        this.testMode = testMode;
        this.capturePath = capturePath;
        this.rotator = rotator;
        this.hoists = hoists;
        this.pistons = pistons;
        this.mechRegistry = mechRegistry;
        this.phase = phase;
        this.restartMode = restartMode;
    }

    static boolean armIfRequested(CoreLibPlugin plugin, CustomBlockRegistry registry, RotationNetwork network,
                                  EngineFuelManager fuelManager, ShowcaseBuilder builder,
                                  java.util.Collection<ShowcaseSpec> showcases, RotationRotator rotator,
                                  ChainHoistManager hoists, ExtendablePistonManager pistons,
                                  MechanismRegistry mechRegistry) {
        boolean test = Boolean.getBoolean("defcorelib.showcaseTest");
        String capture = System.getProperty("defcorelib.showcaseCapture");
        if (!test && (capture == null || capture.isBlank())) return false;
        Path capturePath = (capture == null || capture.isBlank()) ? null : Path.of(capture);
        int phase = Integer.getInteger("defcorelib.showcasePhase", 0);
        String mode = System.getProperty("defcorelib.showcaseRestart");
        if (phase != 0 && (!test || !("clean".equals(mode) || "crash".equals(mode)) || phase < 0 || phase > 2)) {
            plugin.getLogger().warning("ShowcaseRunner: showcasePhase needs showcaseTest=true and "
                    + "showcaseRestart=clean|crash — not armed");
            return false;
        }
        ShowcaseRunner runner = new ShowcaseRunner(plugin, registry, network, fuelManager, builder,
                showcases, test, capturePath, rotator, hoists, pistons, mechRegistry, phase, mode);
        Bukkit.getPluginManager().registerEvents(runner, plugin);
        plugin.getLogger().info("ShowcaseRunner armed (" + (test ? "test " : "") + (capturePath != null
                ? "capture→" + capturePath : "") + (phase != 0 ? "restart " + mode + " phase " + phase : "")
                + ") — " + showcases.size() + " showcases.");
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
            if (spec.testOnly && !testMode) continue;   // capture-only run: regression scenarios aren't demos
            // A restart run takes only its mode's restart specs; any other run none of them.
            if (phase == 0 ? spec.restart != null : !restartMode.equals(spec.restart)) continue;
            // Place the origin so the machine's lowest cell sits at FLOOR_Y (blocks extend up from there).
            int originY = FLOOR_Y - lowestOffsetY(spec);
            Location origin = new Location(world, TEST_X, originY, i * SPACING);
            forceLoad(spec, origin);   // must tick for the network to run
            if (phase == 2) {   // already built (and saved) by phase 1: just find it again
                origins.put(spec, origin);
                i++;
                continue;
            }
            try {
                int placed = builder.build(spec, origin);
                origins.put(spec, origin);
                plugin.getLogger().info("  [showcase] built " + spec.id + " (" + placed + " blocks) @ "
                        + TEST_X + " " + originY + " " + (i * SPACING));
            } catch (Throwable t) {
                plugin.getLogger().warning("[showcase] build " + spec.id + " failed: " + t);
                buildFailed.add(spec);
            }
            i++;
        }
        if (phase == 2) {
            awaitEntitiesThenAssert();
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, this::activateAll, ACTIVATE_AT);
        long finishAt = FINISH_AT;
        if (capturePath != null) Bukkit.getScheduler().runTaskLater(plugin, this::capture, FINISH_AT);
        for (Map.Entry<ShowcaseSpec, Location> e : origins.entrySet()) {
            ShowcaseSpec spec = e.getKey();
            Location origin = e.getValue();
            if (!testMode) continue;   // actions and assertions are test-only; capture reads the plain build
            long assertAt = spec.assertAt > 0 ? spec.assertAt : FINISH_AT;
            for (ShowcaseSpec.Action a : spec.actions) {
                if (a.tick() >= assertAt) {
                    plugin.getLogger().warning("[showcase] " + spec.id + ": action '" + a.kind() + "' at tick "
                            + a.tick() + " is not before its assertion tick " + assertAt + " — skipped");
                    continue;
                }
                Bukkit.getScheduler().runTaskLater(plugin, () -> runAction(spec, origin, a), a.tick());
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> assertSpec(spec, origin, spec.expect), assertAt);
            finishAt = Math.max(finishAt, assertAt);
        }
        // +1: every assertion task scheduled for the same tick has run before the report + halt.
        Bukkit.getScheduler().runTaskLater(plugin, this::finish, finishAt + 1);
    }

    private void activateAll() {
        // Capture: remember each build's vanilla blocks as they stand before anything moves, so a demo whose
        // blocks a hinge swings away is still drawn closed (and animated) — see capture().
        if (capturePath != null) {
            for (Map.Entry<ShowcaseSpec, Location> e : origins.entrySet()) {
                Map<String, String> refs = new java.util.HashMap<>();
                for (ShowcaseSpec.VanillaSpec vs : e.getKey().vanilla) {
                    refs.put(cellKey(vs.at()), blockRef(blockAt(e.getValue(), vs.at())));
                }
                restRefs.put(e.getKey(), refs);
            }
        }
        for (Map.Entry<ShowcaseSpec, Location> e : origins.entrySet()) {
            ShowcaseActivation.activate(e.getKey(), e.getValue(), network, fuelManager);
        }
    }

    /** Phase 2: once every footprint chunk's entities are in (persisted mechanisms recover on that load),
     *  give recovery {@link #RESTART_SETTLE} ticks, then check each spec's {@code after_restart}. */
    private void awaitEntitiesThenAssert() {
        long[] waited = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            waited[0] += 5;
            boolean loaded = forced.stream().allMatch(org.bukkit.Chunk::isEntitiesLoaded);
            if (!loaded && waited[0] < RESTART_GIVE_UP) return;
            task.cancel();
            if (!loaded) plugin.getLogger().warning("[showcase] restart: entities still not loaded after "
                    + waited[0] + " ticks — asserting anyway");
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                origins.forEach((spec, origin) -> assertSpec(spec, origin, spec.afterRestart));
                finish();
            }, RESTART_SETTLE);
        }, 5, 5);
    }

    private void finish() {
        int fail = testMode ? report() : 0;
        cleanupProbes();
        for (org.bukkit.Chunk c : forced) c.setForceLoaded(false);   // a clean stop must not save them forced
        if (phase == 1 && fail == 0 && "clean".equals(restartMode)) {
            plugin.getLogger().info("[showcase] restart phase 1 done — stopping cleanly");
            Bukkit.shutdown();
            return;
        }
        boolean crash = phase == 1 && fail == 0;
        if (crash) plugin.getLogger().info("[showcase] restart phase 1 done — crashing (halt " + CRASH_EXIT + ")");
        System.out.flush();
        System.err.flush();
        Runtime.getRuntime().halt(crash ? CRASH_EXIT : fail == 0 ? 0 : 1);
    }

    /** Force-load every chunk the spec's footprint touches — its blocks, banner hosts, action cells and
     *  expect boxes — so a machine or check that spills past the origin chunk still ticks / reads loaded. */
    private void forceLoad(ShowcaseSpec spec, Location origin) {
        List<int[]> cells = new ArrayList<>();
        for (ShowcaseSpec.BlockSpec b : spec.blocks) cells.add(b.at());
        for (ShowcaseSpec.VanillaSpec v : spec.vanilla) cells.add(v.at());
        for (ShowcaseSpec.BannerSpec b : spec.banners) cells.add(b.host());
        for (ShowcaseSpec.Action a : spec.actions) { cells.add(a.at()); if (a.to() != null) cells.add(a.to()); }
        for (List<ShowcaseSpec.Expect> list : List.of(spec.expect, spec.afterRestart)) {
            for (ShowcaseSpec.Expect e : list) { cells.add(e.at()); if (e.to() != null) cells.add(e.to()); }
        }
        cells.add(new int[]{0, 0, 0});
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (int[] c : cells) {
            minX = Math.min(minX, c[0]); maxX = Math.max(maxX, c[0]);
            minZ = Math.min(minZ, c[2]); maxZ = Math.max(maxZ, c[2]);
        }
        World w = origin.getWorld();
        int ox = origin.getBlockX(), oz = origin.getBlockZ();
        for (int cx = (ox + minX) >> 4; cx <= (ox + maxX) >> 4; cx++) {
            for (int cz = (oz + minZ) >> 4; cz <= (oz + maxZ) >> 4; cz++) {
                org.bukkit.Chunk c = w.getChunkAt(cx, cz);   // loads (generating if new)
                c.setForceLoaded(true);
                forced.add(c);
            }
        }
    }

    // ── Test ─────────────────────────────────────────────────────────────────

    /** Evaluate one spec's expects now (its assert tick); the verdicts are printed by {@link #report}. */
    private void assertSpec(ShowcaseSpec spec, Location origin, List<ShowcaseSpec.Expect> expects) {
        asserted.add(spec);
        List<String> lines = results.computeIfAbsent(spec, k -> new ArrayList<>());
        // A scenario about an entity this server doesn't have (cushions before 26.3) is skipped, not failed.
        String missing = missingEntityKey(spec);
        if (missing != null) {
            for (ShowcaseSpec.Expect ex : expects) {
                lines.add("  [SKIP] " + spec.id + " " + describe(ex) + " (no minecraft:" + missing + " on this server)");
            }
            return;
        }
        for (ShowcaseSpec.Expect ex : expects) {
            boolean ok;
            try {
                ok = check(spec, origin, ex, blockAt(origin, ex.at()));
            } catch (RuntimeException t) {
                plugin.getLogger().warning("[showcase] " + spec.id + " " + describe(ex) + " threw: " + t);
                ok = false;
            }
            lines.add("  [" + (ok ? "PASS" : "FAIL") + "] " + spec.id + " " + describe(ex));
            if (ok) pass++; else fail++;
        }
    }

    private int report() {
        Logger log = plugin.getLogger();
        log.info("──────── showcase tests ────────");
        for (ShowcaseSpec spec : origins.keySet()) {
            List<String> lines = results.getOrDefault(spec, List.of());
            lines.forEach(log::info);
            if (!asserted.contains(spec)) {   // its assertion task never ran — count every expect as a failure
                for (ShowcaseSpec.Expect ex : phase == 2 ? spec.afterRestart : spec.expect) {
                    log.info("  [FAIL] " + spec.id + " " + describe(ex) + " (never evaluated)");
                    fail++;
                }
            }
        }
        for (ShowcaseSpec spec : buildFailed) {
            for (ShowcaseSpec.Expect ex : spec.expect) {
                log.info("  [FAIL] " + spec.id + " " + describe(ex) + " (build failed)");
                fail++;
            }
        }
        log.info("──────── " + pass + " passed, " + fail + " failed ────────");
        return fail;
    }

    private boolean check(ShowcaseSpec spec, Location origin, ShowcaseSpec.Expect ex, Block block) {
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
            // With a value (`"0,1,0; 1,1,0"`, cells relative to the origin), the latest swing must have carried
            // exactly those blocks — a hinge that swings the wrong block still "swung".
            case "mechanism_swung" -> {
                if (rotator == null || rotator.swingCount(block) == 0) return false;
                if (ex.value() == null || ex.value().isBlank()) return true;
                java.util.Set<String> want = new java.util.TreeSet<>();
                for (String cell : ex.value().split(";")) want.add(cell.replace(" ", ""));
                java.util.Set<String> got = new java.util.TreeSet<>();
                for (Block b : rotator.lastSwungBlocks(block)) {
                    got.add((b.getX() - origin.getBlockX()) + "," + (b.getY() - origin.getBlockY()) + ","
                            + (b.getZ() - origin.getBlockZ()));
                }
                if (!want.equals(got)) plugin.getLogger().info("[showcase] " + spec.id + ": swung " + got
                        + ", expected " + want);
                return want.equals(got);
            }
            // Both mirror mechanism_swung: a COUNTER, not a live-state read, because the stroke assembles a
            // mechanism and tears it down again — by assert time the machine is idle either way, so only a
            // count distinguishes "ran and finished" from "never ran".
            case "hoist_moved" -> {
                return hoists != null && hoists.moveCount(block) > 0;
            }
            case "piston_stroked" -> {
                return pistons != null && pistons.strokeCount(block) > 0;
            }
            // Material of a cell — or, with `to`, of EVERY cell in the inclusive box. A leading '!' negates
            // (`!IRON_CHAIN` over a column = "no chain anywhere in it").
            case "block_type" -> {
                String v = ex.value();
                if (v == null) return false;
                boolean negate = v.startsWith("!");
                org.bukkit.Material m = org.bukkit.Material.matchMaterial(negate ? v.substring(1) : v);
                if (m == null) {
                    plugin.getLogger().warning("[showcase] " + spec.id + ": unknown material in block_type: " + v);
                    return false;
                }
                for (Block b : cells(origin, ex.at(), ex.to())) {
                    if ((b.getType() == m) == negate) return false;
                }
                return true;
            }
            // Entities of a vanilla type (or `item` for dropped items) inside the inclusive box at..to:
            // value `KEY=N` — exactly N.
            case "entity_count" -> {
                String v = ex.value();
                int eq = v == null ? -1 : v.indexOf('=');
                if (eq < 0) return false;
                // A `:QUALIFIER` narrows the count: `item:MATERIAL` (dropped items of one material),
                // `painting:NORTH` / `item_frame:UP` (hanging the way it faces), `painting:bust` (one art).
                String key = v.substring(0, eq);
                String qual = null;
                int colon = key.indexOf(':');
                if (colon > 0) {
                    qual = key.substring(colon + 1).trim();
                    key = key.substring(0, colon);
                }
                org.bukkit.entity.EntityType t = entityType(key);
                if (t == null) return false;
                org.bukkit.Material only = null;
                org.bukkit.block.BlockFace facing = null;
                if (qual != null && t == org.bukkit.entity.EntityType.ITEM) {
                    only = org.bukkit.Material.matchMaterial(qual);
                    if (only == null) return false;
                } else if (qual != null) {
                    try {
                        facing = org.bukkit.block.BlockFace.valueOf(qual.toUpperCase(java.util.Locale.ROOT));
                    } catch (IllegalArgumentException notAFace) {   // an art key — a typo must not count 0 and pass
                        if (t != org.bukkit.entity.EntityType.PAINTING
                                || org.bukkit.Registry.ART.get(org.bukkit.NamespacedKey.minecraft(qual.toLowerCase(java.util.Locale.ROOT))) == null) {
                            plugin.getLogger().warning("[showcase] " + spec.id + ": unknown entity_count qualifier " + qual);
                            return false;
                        }
                    }
                }
                int want = Integer.parseInt(v.substring(eq + 1).trim());
                int n = 0;
                for (org.bukkit.entity.Entity e : origin.getWorld().getNearbyEntities(box(origin, ex.at(), ex.to()))) {
                    if (e.getType() != t) continue;
                    if (only != null && !(e instanceof org.bukkit.entity.Item it && it.getItemStack().getType() == only)) continue;
                    if (facing != null && !(e instanceof org.bukkit.entity.Hanging h && h.getFacing() == facing)) continue;
                    if (qual != null && only == null && facing == null
                            && !(e instanceof org.bukkit.entity.Painting p && artKey(p).equals(qual))) continue;
                    n += e instanceof org.bukkit.entity.Item it ? it.getItemStack().getAmount() : 1;
                }
                if (n != want) plugin.getLogger().info("[showcase] " + spec.id + ": " + v.substring(0, eq)
                        + " count " + n + ", expected " + want);
                return n == want;
            }
            // Some RIDER in the box is riding a VEHICLE: value `RIDER/VEHICLE` (vanilla entity keys).
            case "riding" -> {
                String v = ex.value();
                int sl = v == null ? -1 : v.indexOf('/');
                if (sl < 0) return false;
                org.bukkit.entity.EntityType rider = entityType(v.substring(0, sl));
                org.bukkit.entity.EntityType vehicle = entityType(v.substring(sl + 1));
                if (rider == null || vehicle == null) return false;
                for (org.bukkit.entity.Entity e : origin.getWorld().getNearbyEntities(box(origin, ex.at(), ex.to()))) {
                    if (e.getType() == rider && e.getVehicle() != null && e.getVehicle().getType() == vehicle) return true;
                }
                return false;
            }
            // Item frames in the box holding MAT: value `MAT=ROT|ROT…` (Rotation names). At least one, and every
            // one turned to one of those — a floor frame on a 90° turntable turns its item one quarter.
            case "frame_rotation" -> {
                String v = ex.value();
                int eq = v == null ? -1 : v.indexOf('=');
                org.bukkit.Material m = eq < 0 ? null : org.bukkit.Material.matchMaterial(v.substring(0, eq));
                if (m == null) return false;
                java.util.Set<org.bukkit.Rotation> allowed = java.util.EnumSet.noneOf(org.bukkit.Rotation.class);
                for (String r : v.substring(eq + 1).split("\\|")) {
                    allowed.add(org.bukkit.Rotation.valueOf(r.trim().toUpperCase(java.util.Locale.ROOT)));
                }
                int seen = 0;
                for (org.bukkit.entity.Entity e : origin.getWorld().getNearbyEntities(box(origin, ex.at(), ex.to()))) {
                    if (!(e instanceof org.bukkit.entity.ItemFrame f) || f.getItem().getType() != m) continue;
                    seen++;
                    if (!allowed.contains(f.getRotation())) {
                        plugin.getLogger().info("[showcase] " + spec.id + ": " + m + " frame at rotation "
                                + f.getRotation() + ", expected " + allowed);
                        return false;
                    }
                }
                return seen > 0;
            }
            case "persisted_mechanism_intact" -> {
                Probe p = probes.get(probeKey(spec, block));
                return p != null && p.entities() > 0 && p.file().exists()
                        && mechRegistry.hasPersistedState(origin.getWorld(), p.id())
                        && countTagged(p.chunk(), p.id()) == p.entities();
            }
            // The inventory at the cell (custom storage, container half, lectern, pot) holds exactly the listed
            // count of each listed material (`DIAMOND x0` = none); unlisted materials aren't checked.
            case "contents" -> {
                org.bukkit.inventory.Inventory inv = ShowcaseBuilder.inventoryAt(registry, block);
                if (inv == null) return false;
                Map<org.bukkit.Material, Integer> want = new LinkedHashMap<>();
                for (ShowcaseSpec.ItemSpec it : ex.items()) want.merge(it.material(), it.count(), Integer::sum);
                boolean ok = true;
                for (Map.Entry<org.bukkit.Material, Integer> w : want.entrySet()) {
                    int n = 0;
                    for (org.bukkit.inventory.ItemStack s : inv.getContents()) {
                        if (s != null && s.getType() == w.getKey()) n += s.getAmount();
                    }
                    if (n != w.getValue()) {
                        plugin.getLogger().info("[showcase] " + spec.id + ": " + w.getKey() + " in inventory "
                                + n + ", expected " + w.getValue());
                        ok = false;
                    }
                }
                return ok;
            }
            // Conservation: dropped items of MAT plus MAT blocks in the box, value `MAT=N` — exactly N. For stock
            // that may land either as blocks or as items (a hoist's chain broken mid-stroke).
            case "material_total" -> {
                String v = ex.value();
                int eq = v == null ? -1 : v.indexOf('=');
                org.bukkit.Material m = eq < 0 ? null : org.bukkit.Material.matchMaterial(v.substring(0, eq));
                if (m == null) return false;
                int n = 0;
                for (Block b : cells(origin, ex.at(), ex.to())) if (b.getType() == m) n++;
                for (org.bukkit.entity.Entity e : origin.getWorld().getNearbyEntities(box(origin, ex.at(), ex.to()))) {
                    if (e instanceof org.bukkit.entity.Item it && it.getItemStack().getType() == m) {
                        n += it.getItemStack().getAmount();
                    }
                }
                int want = Integer.parseInt(v.substring(eq + 1).trim());
                if (n != want) plugin.getLogger().info("[showcase] " + spec.id + ": " + m + " total " + n
                        + ", expected " + want);
                return n == want;
            }
            // Live (assembled or crash-recovered) mechanisms whose pivot lies in the box: value N — exactly N.
            case "mechanism_count" -> {
                org.bukkit.util.BoundingBox bb = box(origin, ex.at(), ex.to());
                int n = 0;
                for (Mechanism m : mechRegistry.activeMechanisms()) {
                    Location p = m.pivot();
                    if (origin.getWorld().equals(p.getWorld()) && bb.contains(p.toVector())) n++;
                }
                if (ex.value() == null || n != Integer.parseInt(ex.value().trim())) {
                    plugin.getLogger().info("[showcase] " + spec.id + ": " + n + " mechanisms in the box");
                    return false;
                }
                return true;
            }
            // The state file of the mechanism `persist_mechanism` lifted at this cell is on disk.
            case "mechanism_state_file" -> {
                java.util.UUID id = persisted.get(probeKey(spec, block));
                return id != null && mechRegistry.persistedStateFile(origin.getWorld().getName(), id).exists();
            }
            // An engine's fuel counter (engine-ticks): value N — at least N.
            case "engine_fuel" -> {
                int f = fuelManager.getFuel(CustomBlockRegistry.LocationKey.of(block));
                if (ex.value() == null || f < Integer.parseInt(ex.value().trim())) {
                    plugin.getLogger().info("[showcase] " + spec.id + ": engine fuel " + f);
                    return false;
                }
                return true;
            }
            default -> {
                plugin.getLogger().warning("[showcase] " + spec.id + ": unknown expect type: " + ex.type());
                return false;
            }
        }
    }

    // ── Scripted actions ─────────────────────────────────────────────────────

    private void runAction(ShowcaseSpec spec, Location origin, ShowcaseSpec.Action a) {
        try {
            switch (a.kind()) {
                case "set" -> {
                    String data = a.extra().get("data");
                    for (Block b : cells(origin, a.at(), a.to())) {
                        if (a.only() != null && b.getType() != a.only()) continue;
                        // Same block, new data (a chest's pairing) keeps its tile entity, as a vanilla shape update does.
                        if (data != null) b.setBlockData(Bukkit.createBlockData(a.block(), data), a.physics());
                        else b.setType(a.block(), a.physics());
                    }
                }
                case "corrupt_persisted_mechanism" -> corruptPersistedMechanism(spec, blockAt(origin, a.at()));
                case "summon" -> {
                    if (missingEntityKey(spec) == null) summon(origin, a);
                }
                // As an explosion breaks it (no player): a custom block spills its storage, drops its self
                // item and runs its removal callbacks (CoreLibPlugin.breakWithoutPlayer); a vanilla block
                // breakNaturally()s (drops + container contents). No BlockBreakEvent fires, so per-player
                // drop rules (tool / silk touch) and other plugins' break listeners don't run.
                case "break" -> {
                    for (Block b : cells(origin, a.at(), a.to())) {
                        if (!plugin.breakWithoutPlayer(b)) b.breakNaturally();
                    }
                }
                // Spawn the item stacks at the cell's centre, unscattered (for hopper / cauldron intake).
                case "drop" -> {
                    Location at = blockAt(origin, a.at()).getLocation().add(0.5, 0.5, 0.5);
                    for (ShowcaseSpec.ItemSpec it : a.items()) {
                        origin.getWorld().dropItem(at, it.toStack(), e -> e.setVelocity(new org.bukkit.util.Vector()));
                    }
                }
                // Lift the cell into a crash-safe persisted mechanism (same path as the corrupt probe, no parking).
                case "persist_mechanism" -> {
                    Block b = blockAt(origin, a.at());
                    Mechanism mech = mechRegistry.assembleMechanism("corelib:showcase_probe", List.of(b),
                            b.getLocation().add(0.5, 0.5, 0.5), null);
                    mechRegistry.persist(mech);
                    persisted.put(probeKey(spec, b), mech.id());
                }
                case "disassemble_mechanism" -> {
                    java.util.UUID id = persisted.get(probeKey(spec, blockAt(origin, a.at())));
                    Mechanism mech = id == null ? null : mechRegistry.byId(id);
                    if (mech == null) throw new IllegalStateException("no live persist_mechanism at that cell");
                    mech.disassemble();
                }
                // Write the world to disk now (flushing the chunk writer) — what a crash run keeps.
                case "save" -> origin.getWorld().save(true);
                default -> throw new IllegalArgumentException("unknown action");
            }
        } catch (RuntimeException t) {
            // A step that didn't happen invalidates the scenario: FAIL it rather than assert on a half-run.
            results.computeIfAbsent(spec, k -> new ArrayList<>()).add("  [FAIL] " + spec.id + " action '"
                    + a.kind() + "' @ tick " + a.tick() + ": " + t);
            fail++;
        }
    }

    /**
     * Regression probe for 8d75a15 (a corrupt mechanism state file must never be culled). Lifts
     * {@code block} into a persisted mechanism, parks it exactly as a chunk unload does (state saved, dropped
     * from the live set, its persistent entities left in place), overwrites the state file with garbage, then
     * runs the chunk-load recovery pass over it — the same {@code recoverMechanismsInChunk} an
     * {@code EntitiesLoadEvent} drives. The bug deleted the file there and the same pass's orphan sweep reaped
     * every entity; {@code persisted_mechanism_intact} checks both survived. The chunk is never really
     * unloaded (the harness force-loads it): what is under test is the recovery pass, not Paper's entity I/O.
     */
    private void corruptPersistedMechanism(ShowcaseSpec spec, Block block) {
        if (mechRegistry == null || block.getType().isAir()) {
            plugin.getLogger().warning("[showcase] " + spec.id + ": nothing to persist at " + block);
            return;
        }
        Mechanism mech = mechRegistry.assembleMechanism("corelib:showcase_probe", List.of(block),
                block.getLocation().add(0.5, 0.5, 0.5), null);
        mechRegistry.persist(mech);
        org.bukkit.entity.Entity vehicle = mech.vehicle();
        org.bukkit.Chunk chunk = (vehicle != null ? vehicle.getLocation() : mech.pivot()).getChunk();
        String world = chunk.getWorld().getName();
        java.io.File file = mechRegistry.persistedStateFile(world, mech.id());
        // Recorded before anything below can fail, so cleanup always removes the file. Entities 0 until the
        // park succeeds — persisted_mechanism_intact requires > 0, so an aborted probe FAILs.
        probes.put(probeKey(spec, block), new Probe(mech.id(), world, chunk, 0, file));
        mechRegistry.onEntitiesUnload(chunk);   // park: save + drop from the live set, entities stay
        // Both preconditions are what make this a real test: a mechanism still live is skipped by recovery,
        // and recovery returns early on a chunk whose entities aren't loaded — either way the check below
        // would pass on the pre-fix code too.
        if (mechRegistry.byId(mech.id()) != null || !chunk.isEntitiesLoaded()) {
            plugin.getLogger().warning("[showcase] " + spec.id + ": probe could not park the mechanism"
                    + " (still live or entities not loaded) — the check will fail");
            return;
        }
        int entities = countTagged(chunk, mech.id());
        try {
            Files.writeString(file.toPath(), "foo: bar\n", StandardCharsets.UTF_8);   // parses, but is no state
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("[showcase] " + spec.id + ": could not corrupt " + file + ": " + e);
            return;
        }
        plugin.getLogger().info("[showcase] " + spec.id + ": corrupted " + file.getName()
                + " — the 'corrupt or unreadable state file' warning that follows is expected");
        mechRegistry.recoverMechanismsInChunk(chunk);
        probes.put(probeKey(spec, block), new Probe(mech.id(), world, chunk, entities, file));
    }

    /** Remove what the probes left: the parked entities and the corrupt file (and its in-memory shield). */
    private void cleanupProbes() {
        for (Probe p : probes.values()) {
            try {
                for (org.bukkit.entity.Entity e : p.chunk().getEntities()) {
                    if (isTagged(e, p.id())) e.remove();
                }
                mechRegistry.forgetPersistedState(p.world(), p.id());
            } catch (RuntimeException t) {
                plugin.getLogger().warning("[showcase] probe cleanup failed for " + p.id() + ": " + t);
            }
        }
    }

    private static int countTagged(org.bukkit.Chunk chunk, java.util.UUID id) {
        int n = 0;
        for (org.bukkit.entity.Entity e : chunk.getEntities()) if (isTagged(e, id)) n++;
        return n;
    }

    private static boolean isTagged(org.bukkit.entity.Entity e, java.util.UUID id) {
        String prefix = "corelib:mech:" + id;
        for (String tag : e.getScoreboardTags()) {
            if (tag.equals(prefix) || tag.startsWith(prefix + ":")) return true;
        }
        return false;
    }

    private static String probeKey(ShowcaseSpec spec, Block block) {
        return spec.id + "@" + block.getX() + "," + block.getY() + "," + block.getZ();
    }

    /** Every cell of the inclusive box {@code at}..{@code to} (just {@code at} when {@code to} is null). */
    private static List<Block> cells(Location origin, int[] at, int[] to) {
        if (to == null) return List.of(blockAt(origin, at));
        List<Block> out = new ArrayList<>();
        for (int x = Math.min(at[0], to[0]); x <= Math.max(at[0], to[0]); x++)
            for (int y = Math.min(at[1], to[1]); y <= Math.max(at[1], to[1]); y++)
                for (int z = Math.min(at[2], to[2]); z <= Math.max(at[2], to[2]); z++)
                    out.add(blockAt(origin, new int[]{x, y, z}));
        return out;
    }

    // ── Capture ────────────────────────────────────────────────────────────

    private void capture() {
        Logger log = plugin.getLogger();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<ShowcaseSpec, Location> e : origins.entrySet()) {
            ShowcaseSpec spec = e.getKey();
            if (spec.testOnly) continue;   // regression scenario — never published to the docs
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
                rec.put("baseHeadTextureUrl", (type.itemMaterial() != null || type.physicalMaterial() != null) ? null
                        : DisplayCapture.textureUrl(type.resolveTexture(state, 0, headFacing)));
                rec.put("displays", DisplayCapture.readDisplays(type, block.getLocation(), state, true, false));
                blocks.add(rec);

                // Chain-pulley strand: a separate BlockDisplay (corelib:mech:chain_strand:…) not owned
                // by the pulley type, so readDisplays misses it. Capture it as its own record so the
                // linked chain renders. Static frame (a powered strand is mid-spin at capture time).
                if (ChainPulley.PULLEY_ID.equals(bs.id())) {
                    Location cellCenter = block.getLocation().add(0.5, 0.5, 0.5);
                    List<Map<String, Object>> strandDisplays = new ArrayList<>();
                    for (var d : DisplayUtil.findByTag(block.getLocation(),
                            ChainPulley.strandTagPrefix(block.getLocation()), 2.0)) {
                        Map<String, Object> raw = DisplayCapture.readRawDisplay(d, cellCenter);
                        if (raw != null) strandDisplays.add(raw);
                    }
                    if (!strandDisplays.isEmpty()) {
                        Map<String, Object> strandRec = new LinkedHashMap<>();
                        strandRec.put("id", null);
                        strandRec.put("offset", new int[]{bs.at()[0], bs.at()[1], bs.at()[2]});
                        strandRec.put("baseHeadTextureUrl", null);
                        strandRec.put("displays", strandDisplays);
                        blocks.add(strandRec);
                    }
                }
            }
            // Vanilla support blocks: emit a single static "block" display so the webpage renders them.
            // The ref carries the LIVE blockstate (e.g. "oak_slab[type=top,waterlogged=false]") read
            // back from the placed block — generate_catalog strips it to the model id for vendoring, and
            // placed3d parses it to orient the block (slab half, lever/sign facing, stairs, …).
            // Fluids are skipped here: `physics: true` water/lava spreads after placement, so the spec
            // cells aren't the render truth — the world scan below captures the settled extent instead.
            // A block a rotator swung is drawn at rest (its pre-swing blockstate) with a baked open/close loop.
            Map<String, RotationRotator.Swing> swung = new java.util.HashMap<>();
            if (rotator != null) {
                for (ShowcaseSpec.BlockSpec bs : spec.blocks) {
                    if (!RotationRotator.ROTATOR_ID.equals(bs.id())) continue;
                    RotationRotator.Swing sw = rotator.lastSwing(blockAt(origin, bs.at()));
                    if (sw == null) continue;
                    for (Block b : sw.blocks()) swung.put(cellKey(relative(origin, b)), sw);
                }
            }
            Map<String, String> rest = restRefs.getOrDefault(spec, Map.of());
            for (ShowcaseSpec.VanillaSpec vs : spec.vanilla) {
                if (isFluid(vs.material())) continue;
                Block b = blockAt(origin, vs.at());
                RotationRotator.Swing sw = swung.get(cellKey(vs.at()));
                String ref = sw != null && rest.containsKey(cellKey(vs.at())) ? rest.get(cellKey(vs.at())) : blockRef(b);
                Map<String, Object> rec = vanillaRecord(ref, vs.at()[0], vs.at()[1], vs.at()[2], signLines(b));
                if (sw != null) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> ds = (List<Map<String, Object>>) rec.get("displays");
                    ds.get(0).put("animation", swingTrack(sw, relative(origin, sw.hinge()), vs.at()));
                }
                blocks.add(rec);
            }
            // World fluids: scan the build's bounding box (padded 3 out/down, 1 up — well inside the
            // 16-block z spacing between showcases) and emit every water/lava cell with its level so
            // the viewer can size flowing water (`water[level=3]`).
            for (Map<String, Object> rec : captureFluids(spec, origin)) blocks.add(rec);
            Map<String, Object> sc = new LinkedHashMap<>();
            sc.put("id", spec.id);
            sc.put("name", spec.name);
            sc.put("blurb", spec.blurb);
            sc.put("description", spec.description);
            sc.put("images", spec.images);
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

    private static boolean isFluid(org.bukkit.Material m) {
        return m == org.bukkit.Material.WATER || m == org.bukkit.Material.LAVA;
    }

    private static String blockRef(Block b) {
        String ref = b.getBlockData().getAsString();
        return ref.startsWith("minecraft:") ? ref.substring("minecraft:".length()) : ref;
    }

    private static String cellKey(int[] at) { return at[0] + "," + at[1] + "," + at[2]; }

    private static int[] relative(Location origin, Block b) {
        return new int[]{b.getX() - origin.getBlockX(), b.getY() - origin.getBlockY(), b.getZ() - origin.getBlockZ()};
    }

    /** Swing loop period and length of each phase (open, hold open, close, hold closed), in ticks. */
    private static final int SWING_PHASE = 25, SWING_PERIOD = 4 * SWING_PHASE, SWING_STEP = 2;

    /**
     * A baked keyframe loop for one block of a rotator swing: open to the swing's target angle, hold, swing
     * shut, hold. Each frame turns the block about the hinge cell's centre exactly as
     * {@code BasicMechanism.rotate} does (−angle about the axis), expressed in the display's frame (its origin is
     * the cell's min corner, the cell centre sitting at the block's offset).
     */
    private static Map<String, Object> swingTrack(RotationRotator.Swing sw, int[] hinge, int[] at) {
        org.joml.Vector3f corner = new org.joml.Vector3f(at[0] - 0.5f, at[1] - 0.5f, at[2] - 0.5f);
        org.joml.Vector3f pivot = new org.joml.Vector3f(hinge[0], hinge[1], hinge[2]);
        List<float[]> frames = new ArrayList<>();
        for (int t = 0; t < SWING_PERIOD; t += SWING_STEP) {
            int phase = t / SWING_PHASE;
            double f = (double) (t % SWING_PHASE) / SWING_PHASE;
            double eased = f * f * (3 - 2 * f);
            double open = switch (phase) { case 0 -> eased; case 1 -> 1; case 2 -> 1 - eased; default -> 0; };
            float rad = (float) Math.toRadians(-sw.targetDeg() * open);
            org.joml.Matrix4f m = new org.joml.Matrix4f()
                    .translate(-corner.x, -corner.y, -corner.z)
                    .translate(pivot)
                    .rotate(rad, sw.axis().x, sw.axis().y, sw.axis().z)
                    .translate(-pivot.x, -pivot.y, -pivot.z)
                    .translate(corner);
            frames.add(m.get(new float[16]));
        }
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("period", SWING_PERIOD);
        a.put("frames", frames);
        return a;
    }

    private static Map<String, Object> vanillaRecord(String ref, int dx, int dy, int dz) {
        return vanillaRecord(ref, dx, dy, dz, null);
    }

    /** The static single-"block"-display record used for vanilla support blocks and world fluids.
     *  {@code signText}, when non-null, carries a sign's front-side lines for the viewer's overlay. */
    private static Map<String, Object> vanillaRecord(String ref, int dx, int dy, int dz, List<String> signText) {
        Map<String, Object> display = new LinkedHashMap<>();
        display.put("kind", "block");
        display.put("ref", ref);   // e.g. "oak_planks", "oak_slab[type=top]", "water[level=3]"
        // A "block" display is corner-origin ([0,1]³, center +0.5), but the capture frame's origin
        // is the cell CENTER (positions are entity − (corner+0.5)). Shift −0.5 so the cube centers
        // on the cell, matching the custom blocks — exactly what a real corner BlockDisplay records.
        display.put("position", new double[]{-0.5, -0.5, -0.5});
        display.put("matrix", new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1});
        display.put("animation", null);
        if (signText != null) display.put("sign", signText);

        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("id", null);
        rec.put("offset", new int[]{dx, dy, dz});
        rec.put("baseHeadTextureUrl", null);
        rec.put("displays", List.of(display));
        return rec;
    }

    /** The front-side lines of a placed sign (trailing blanks trimmed), or null if {@code b} is not a
     *  sign / has no text — used to overlay labels in the docs viewer. */
    private static List<String> signLines(Block b) {
        if (!(b.getState() instanceof org.bukkit.block.Sign sign)) return null;
        String[] raw = sign.getSide(org.bukkit.block.sign.Side.FRONT).getLines();
        int last = -1;
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < raw.length; i++) {
            lines.add(raw[i]);
            if (raw[i] != null && !raw[i].isEmpty()) last = i;
        }
        if (last < 0) return null;                 // all blank → no overlay
        return new ArrayList<>(lines.subList(0, last + 1));
    }

    /** Every water/lava cell inside the spec's padded bounding box, as vanilla records with the fluid
     *  level baked into the ref ({@code water[level=N]}; 0 = source, 1-7 flowing, ≥8 falling). */
    private List<Map<String, Object>> captureFluids(ShowcaseSpec spec, Location origin) {
        int minX = 0, minY = 0, minZ = 0, maxX = 0, maxY = 0, maxZ = 0;
        boolean first = true;
        List<int[]> ats = new ArrayList<>();
        for (ShowcaseSpec.BlockSpec bs : spec.blocks) ats.add(bs.at());
        for (ShowcaseSpec.VanillaSpec vs : spec.vanilla) ats.add(vs.at());
        for (int[] at : ats) {
            if (first) {
                minX = maxX = at[0]; minY = maxY = at[1]; minZ = maxZ = at[2];
                first = false;
            } else {
                minX = Math.min(minX, at[0]); maxX = Math.max(maxX, at[0]);
                minY = Math.min(minY, at[1]); maxY = Math.max(maxY, at[1]);
                minZ = Math.min(minZ, at[2]); maxZ = Math.max(maxZ, at[2]);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        if (first) return out;
        for (int x = minX - 3; x <= maxX + 3; x++) {
            for (int y = minY - 3; y <= maxY + 1; y++) {
                for (int z = minZ - 3; z <= maxZ + 3; z++) {
                    Block b = blockAt(origin, new int[]{x, y, z});
                    if (!isFluid(b.getType())) continue;
                    int level = b.getBlockData() instanceof org.bukkit.block.data.Levelled l
                            ? l.getLevel() : 0;
                    out.add(vanillaRecord(b.getType().getKey().getKey() + "[level=" + level + "]",
                            x, y, z));
                }
            }
        }
        return out;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Spawn {@code a.extra().entity} in cell {@code at}: a hanging entity on face {@code face} (its outward
     * normal) of the block behind it, holding {@code item} / painted {@code art}; anything else at the cell's
     * bottom center — so a cushion summoned into the air cell above a block rests on it. {@code rider} seats a
     * still, silent mob on it.
     */
    @SuppressWarnings("unchecked")
    private void summon(Location origin, ShowcaseSpec.Action a) {
        Map<String, String> x = a.extra();
        org.bukkit.entity.EntityType type = entityType(x.get("entity"));
        if (type == null || type.getEntityClass() == null) return;
        Block cell = blockAt(origin, a.at());
        org.bukkit.block.BlockFace face = x.get("face") == null ? null
                : org.bukkit.block.BlockFace.valueOf(x.get("face").toUpperCase(java.util.Locale.ROOT));
        // A hanging entity goes mid-cell; anything else rests on whatever is in the cell (a carpet, a slab).
        Location loc = face != null ? cell.getLocation().add(0.5, 0.5, 0.5)
                : cell.getLocation().add(0.5, cell.getType().isAir() ? 0 : cell.getBoundingBox().getMaxY() - cell.getY(), 0.5);
        org.bukkit.entity.Entity e = origin.getWorld().spawn(loc,
                (Class<org.bukkit.entity.Entity>) type.getEntityClass(), ent -> {
            if (face != null && ent instanceof org.bukkit.entity.Hanging h) h.setFacingDirection(face, true);
            if (x.get("item") != null && ent instanceof org.bukkit.entity.ItemFrame f) {
                org.bukkit.Material m = org.bukkit.Material.matchMaterial(x.get("item"));
                if (m != null) f.setItem(new org.bukkit.inventory.ItemStack(m));
            }
            if (x.get("art") != null && ent instanceof org.bukkit.entity.Painting p) {
                org.bukkit.Art art = org.bukkit.Registry.ART.get(org.bukkit.NamespacedKey.minecraft(x.get("art")));
                if (art != null) p.setArt(art, true);
            }
        });
        org.bukkit.entity.EntityType riderType = entityType(x.get("rider"));
        if (riderType != null && riderType.getEntityClass() != null) {
            org.bukkit.entity.Entity r = origin.getWorld().spawn(loc,
                    (Class<org.bukkit.entity.Entity>) riderType.getEntityClass(), ent -> {
                if (ent instanceof org.bukkit.entity.LivingEntity le) le.setAI(false);
                ent.setSilent(true);
                ent.setPersistent(true);
            });
            e.addPassenger(r);
        }
    }

    /** The first entity key a spec summons or asserts on that this server doesn't have, else null. */
    private static @Nullable String missingEntityKey(ShowcaseSpec spec) {
        for (ShowcaseSpec.Action a : spec.actions) {
            if (!"summon".equals(a.kind())) continue;
            for (String k : new String[]{"entity", "rider"}) {
                String v = a.extra().get(k);
                if (v != null && entityType(v) == null) return v;
            }
        }
        for (ShowcaseSpec.Expect ex : spec.expect) {
            String v = ex.value();
            if (v == null) continue;
            if ("entity_count".equals(ex.type()) && v.indexOf('=') > 0) {
                String k = v.substring(0, v.indexOf('=')).split(":")[0];
                if (entityType(k) == null) return k;
            } else if ("riding".equals(ex.type()) && v.indexOf('/') > 0) {
                for (String k : v.split("/")) if (entityType(k) == null) return k;
            }
        }
        return null;
    }

    private static String artKey(org.bukkit.entity.Painting p) {
        org.bukkit.NamespacedKey k = org.bukkit.Registry.ART.getKey(p.getArt());
        return k == null ? "" : k.getKey();
    }

    private static org.bukkit.entity.@Nullable EntityType entityType(@Nullable String key) {
        if (key == null) return null;
        try {
            return org.bukkit.Registry.ENTITY_TYPE.get(org.bukkit.NamespacedKey.minecraft(key.trim().toLowerCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The inclusive cell box at..to (or the single cell at) as a world bounding box. */
    private static org.bukkit.util.BoundingBox box(Location origin, int[] at, int @Nullable [] to) {
        int[] b = to != null ? to : at;
        int ox = origin.getBlockX(), oy = origin.getBlockY(), oz = origin.getBlockZ();
        return new org.bukkit.util.BoundingBox(
                ox + Math.min(at[0], b[0]), oy + Math.min(at[1], b[1]), oz + Math.min(at[2], b[2]),
                ox + Math.max(at[0], b[0]) + 1, oy + Math.max(at[1], b[1]) + 1, oz + Math.max(at[2], b[2]) + 1);
    }

    private static String describe(ShowcaseSpec.Expect ex) {
        String at = "[" + ex.at()[0] + "," + ex.at()[1] + "," + ex.at()[2] + "]";
        if (ex.to() != null) at += ".." + "[" + ex.to()[0] + "," + ex.to()[1] + "," + ex.to()[2] + "]";
        return ex.type() + (ex.value() != null ? "=" + ex.value() : "") + " @ " + at;
    }

    private static Block blockAt(Location origin, int[] at) {
        return origin.getWorld().getBlockAt(
                origin.getBlockX() + at[0], origin.getBlockY() + at[1], origin.getBlockZ() + at[2]);
    }

    /** The most-negative Y offset across a spec's custom + vanilla blocks (0 if empty) — how far the
     *  machine reaches below its origin, used to seat its lowest cell at {@link #FLOOR_Y}. */
    private static int lowestOffsetY(ShowcaseSpec spec) {
        int min = 0;
        for (ShowcaseSpec.BlockSpec b : spec.blocks) min = Math.min(min, b.at()[1]);
        for (ShowcaseSpec.VanillaSpec v : spec.vanilla) min = Math.min(min, v.at()[1]);
        return min;
    }
}
