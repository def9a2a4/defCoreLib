package anon.def9a2a4.corelib;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Headless integration-test harness for the showcases. Armed by {@code -Ddefcorelib.showcaseTest=true}
 * (inert otherwise). On startup it builds every {@link ShowcaseSpec} as a real machine, drives it for a
 * bounded number of ticks (so the rotation network recalcs + consumers spin up), then asserts each
 * YAML-declared {@code expect} and exits with code 0 (all pass) or 1 (any fail) so {@code make
 * showcase-test} can gate. Builds at fresh, far coords (untouched chunks ⇒ deterministic).
 */
final class ShowcaseRunner implements Listener {

    private static final int TEST_X = 1000;     // far from the docs grid → fresh, clean chunks
    private static final int TEST_Y = -50;      // in air above the superflat surface
    private static final int SPACING = 16;      // machines spaced along z

    private static final long ACTIVATE_AT = 20; // ticks after build
    private static final long ASSERT_AT = 80;

    private final CoreLibPlugin plugin;
    private final CustomBlockRegistry registry;
    private final RotationNetwork network;
    private final ShowcaseBuilder builder;
    private final java.util.Collection<ShowcaseSpec> showcases;
    private final Map<ShowcaseSpec, Location> origins = new LinkedHashMap<>();
    private boolean done;

    private ShowcaseRunner(CoreLibPlugin plugin, CustomBlockRegistry registry, RotationNetwork network,
                           ShowcaseBuilder builder, java.util.Collection<ShowcaseSpec> showcases) {
        this.plugin = plugin;
        this.registry = registry;
        this.network = network;
        this.builder = builder;
        this.showcases = showcases;
    }

    static boolean armIfRequested(CoreLibPlugin plugin, CustomBlockRegistry registry, RotationNetwork network,
                                  ShowcaseBuilder builder, java.util.Collection<ShowcaseSpec> showcases) {
        if (!Boolean.getBoolean("defcorelib.showcaseTest")) return false;
        ShowcaseRunner runner = new ShowcaseRunner(plugin, registry, network, builder, showcases);
        Bukkit.getPluginManager().registerEvents(runner, plugin);
        plugin.getLogger().info("ShowcaseRunner armed — will build + test " + showcases.size()
                + " showcases, then exit.");
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
                plugin.getLogger().info("  [test] built " + spec.id + " (" + placed + " blocks) @ "
                        + TEST_X + " " + TEST_Y + " " + (i * SPACING));
            } catch (Throwable t) {
                plugin.getLogger().warning("[test] build " + spec.id + " failed: " + t);
            }
            i++;
        }
        Bukkit.getScheduler().runTaskLater(plugin, this::activateAll, ACTIVATE_AT);
        Bukkit.getScheduler().runTaskLater(plugin, this::assertAndExit, ASSERT_AT);
    }

    private void activateAll() {
        for (Map.Entry<ShowcaseSpec, Location> e : origins.entrySet()) {
            ShowcaseSpec spec = e.getKey();
            Location origin = e.getValue();
            ShowcaseSpec.Activate act = spec.activate;
            switch (act.kind()) {
                case "pulse" -> {
                    if (act.at() != null) blockAt(origin, act.at()).setType(Material.REDSTONE_BLOCK, true);
                }
                case "fuel" -> plugin.getLogger().warning("[test] " + spec.id
                        + ": activate=fuel not implemented yet");
                default -> {   // passive: nudge a recalc on the first block so the network is fresh
                    if (!spec.blocks.isEmpty()) {
                        network.recalculate(CustomBlockRegistry.LocationKey.of(
                                blockAt(origin, spec.blocks.get(0).at())));
                    }
                }
            }
        }
    }

    private void assertAndExit() {
        Logger log = plugin.getLogger();
        int pass = 0, fail = 0;
        log.info("──────── showcase tests ────────");
        for (Map.Entry<ShowcaseSpec, Location> e : origins.entrySet()) {
            ShowcaseSpec spec = e.getKey();
            Location origin = e.getValue();
            for (ShowcaseSpec.Expect ex : spec.expect) {
                Block block = blockAt(origin, ex.at());
                boolean ok = check(ex, block);
                log.info("  [" + (ok ? "PASS" : "FAIL") + "] " + spec.id + " " + describe(ex));
                if (ok) pass++; else fail++;
            }
        }
        log.info("──────── " + pass + " passed, " + fail + " failed ────────");
        System.out.flush();
        System.err.flush();
        Runtime.getRuntime().halt(fail == 0 ? 0 : 1);
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
            default -> {
                plugin.getLogger().warning("[test] unknown expect type: " + ex.type());
                return false;
            }
        }
    }

    private static String describe(ShowcaseSpec.Expect ex) {
        String at = "[" + ex.at()[0] + "," + ex.at()[1] + "," + ex.at()[2] + "]";
        return ex.type() + (ex.value() != null ? "=" + ex.value() : "") + " @ " + at;
    }

    private static Block blockAt(Location origin, int[] at) {
        return origin.getWorld().getBlockAt(
                origin.getBlockX() + at[0], origin.getBlockY() + at[1], origin.getBlockZ() + at[2]);
    }
}
