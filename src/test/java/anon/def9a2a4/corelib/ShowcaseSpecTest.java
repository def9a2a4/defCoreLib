package anon.def9a2a4.corelib;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shipped {@code showcases.yml} parses cleanly, and every scenario only uses assertion/action kinds
 * {@link ShowcaseRunner} knows. A typo here otherwise surfaces only as an in-game {@code [FAIL]} (or a
 * silently skipped entry) on the CI server matrix.
 */
class ShowcaseSpecTest {

    private static final Set<String> EXPECT_TYPES = ShowcaseRunner.EXPECT_TYPES;
    private static final Set<String> ACTION_KINDS = ShowcaseRunner.ACTION_KINDS;
    private static final long DEFAULT_ASSERT_AT = 80;

    private static Map<String, ShowcaseSpec> load(List<String> warnings) throws Exception {
        Logger log = Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override public void publish(LogRecord r) { warnings.add(r.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        try (InputStream in = ShowcaseSpec.class.getClassLoader().getResourceAsStream("showcases.yml")) {
            assertNotNull(in, "showcases.yml on the classpath");
            return ShowcaseSpec.load(in, log);
        }
    }

    @Test
    void everyEntryParsesWithKnownKinds() throws Exception {
        List<String> warnings = new java.util.ArrayList<>();
        Map<String, ShowcaseSpec> specs = load(warnings);
        assertEquals(List.of(), warnings, "showcases.yml parse warnings (a skipped entry)");
        assertFalse(specs.isEmpty());
        for (ShowcaseSpec s : specs.values()) {
            long assertAt = s.assertAt > 0 ? s.assertAt : DEFAULT_ASSERT_AT;
            for (ShowcaseSpec.Expect e : allExpects(s)) {
                assertTrue(EXPECT_TYPES.contains(e.type()), s.id + ": unknown expect type " + e.type());
            }
            // A restart scenario is a test (never a demo) and must check something after the restart.
            if (s.restart != null) {
                assertTrue(s.testOnly, s.id + ": restart scenarios must be test_only");
                assertFalse(s.afterRestart.isEmpty(), s.id + ": restart scenario has no after_restart");
            } else {
                assertTrue(s.afterRestart.isEmpty(), s.id + ": after_restart without restart: clean|crash");
            }
            for (ShowcaseSpec.Action a : s.actions) {
                assertTrue(ACTION_KINDS.contains(a.kind()), s.id + ": unknown action " + a.kind());
                assertTrue(a.tick() < assertAt, s.id + ": action at " + a.tick() + " is not before " + assertAt);
            }
            // A demo must not lean on test-only machinery the docs world never runs.
            if (!s.testOnly) assertTrue(s.actions.isEmpty(), s.id + ": actions on a public showcase");
        }
    }

    private static List<ShowcaseSpec.Expect> allExpects(ShowcaseSpec s) {
        List<ShowcaseSpec.Expect> all = new java.util.ArrayList<>(s.expect);
        all.addAll(s.afterRestart);
        return all;
    }

    /** The item-spec shorthand: {@code "MAT xN"}, a bare material, and the map form with nested contents. */
    @Test
    void itemSpecsParse() throws Exception {
        ShowcaseSpec s = load(new java.util.ArrayList<>()).get("test_placer_skips_filled_shulker");
        assertNotNull(s);
        List<ShowcaseSpec.ItemSpec> items = s.vanilla.get(0).contents();
        assertEquals(org.bukkit.Material.SHULKER_BOX, items.get(0).material());
        assertEquals(5, items.get(0).contents().get(0).count());
        assertEquals(org.bukkit.Material.STONE, items.get(1).material());
        assertEquals(1, items.get(1).count());
    }

    @Test
    void regressionScenariosAreTestOnly() throws Exception {
        Map<String, ShowcaseSpec> specs = load(new java.util.ArrayList<>());
        for (String id : List.of("test_ceiling_mounts", "test_hoist_reel_in",
                "test_hoist_rising_ghost_no_dupe", "test_corrupt_state_file_kept",
                "test_carried_frame_painting", "test_carried_cushion", "test_painting_drops_on_roll",
                "test_carried_frame_painting_floor", "test_carried_painting_2x2", "test_carried_frames_move_and_turn",
                "test_carried_frame_blocked_drops", "test_carried_cushion_carpet_and_moved",
                "test_carried_cushion_blocked_drops",
                "test_hoist_broken_mid_descent_refunds", "test_carried_lectern_book",
                "test_placer_skips_filled_shulker", "test_carried_mech_dispenser_contents",
                "test_engine_returns_lava_bucket", "test_water_wheel_break_keeps_chest",
                "test_log_axis_rolls_with_drawbridge",
                "test_restart_crash_persisted_mechanism", "test_restart_crash_disassembled_state_kept",
                "test_restart_clean_engine_fuel", "test_restart_clean_disassembled_no_ghost")) {
            ShowcaseSpec s = specs.get(id);
            assertNotNull(s, id);
            assertTrue(s.testOnly, id + " must be test_only (kept out of the docs)");
            assertFalse(s.expect.isEmpty(), id + " asserts nothing");
        }
        ShowcaseSpec dupe = specs.get("test_hoist_rising_ghost_no_dupe");
        assertEquals(110, dupe.assertAt);
        assertEquals(2, dupe.actions.size());
        assertNotNull(dupe.actions.get(0).to());
        assertNotNull(dupe.actions.get(0).only());
    }

    /** Every custom block a showcase places must exist, and every state it sets or asserts must be one of
     *  that block's states — checked against the shipped block YAML of every module. */
    @Test
    void blockIdsAndStatesExist() throws Exception {
        Map<String, Set<String>> statesById = new java.util.HashMap<>();
        Path root = Path.of(System.getProperty("repoRoot", System.getProperty("user.dir")));
        try (var files = Files.walk(root, 5)) {
            for (Path f : files.filter(x -> x.toString().endsWith(".yml")
                    && x.toString().contains("src" + java.io.File.separator + "main" + java.io.File.separator + "resources")).toList()) {
                org.yaml.snakeyaml.LoaderOptions opts = new org.yaml.snakeyaml.LoaderOptions();
                opts.setMaxAliasesForCollections(10_000);   // the block YAML shares states via anchors
                Object doc = new Yaml(opts).load(Files.readString(f));
                if (!(doc instanceof Map<?, ?> m) || !(m.get("namespace") instanceof String ns)
                        || !(m.get("blocks") instanceof Map<?, ?> blocks)) continue;
                for (Map.Entry<?, ?> e : blocks.entrySet()) {
                    Set<String> states = new java.util.HashSet<>();
                    if (e.getValue() instanceof Map<?, ?> def && def.get("states") instanceof Map<?, ?> st) {
                        for (Object k : st.keySet()) states.add(String.valueOf(k));
                    }
                    statesById.put(ns + ":" + e.getKey(), states);
                }
            }
        }
        assertFalse(statesById.isEmpty(), "no block definitions found under " + root);

        List<String> problems = new java.util.ArrayList<>();
        for (ShowcaseSpec s : load(new java.util.ArrayList<>()).values()) {
            Map<String, ShowcaseSpec.BlockSpec> byCell = new java.util.HashMap<>();
            for (ShowcaseSpec.BlockSpec b : s.blocks) {
                byCell.put(java.util.Arrays.toString(b.at()), b);
                Set<String> states = statesById.get(b.id());
                if (states == null) { problems.add(s.id + ": unknown block " + b.id()); continue; }
                if (b.state() != null && !states.isEmpty() && !states.contains(b.state())) {
                    problems.add(s.id + ": " + b.id() + " has no state " + b.state());
                }
            }
            for (ShowcaseSpec.Expect e : allExpects(s)) {
                if (e.type().equals("state") && e.value() != null) {
                    ShowcaseSpec.BlockSpec b = byCell.get(java.util.Arrays.toString(e.at()));
                    Set<String> states = b == null ? null : statesById.get(b.id());
                    if (states != null && !states.isEmpty() && !states.contains(e.value())) {
                        problems.add(s.id + ": expects state " + e.value() + " but " + b.id() + " has no such state");
                    }
                }
                if (e.type().equals("block_type")) {
                    String v = e.value() == null ? "" : e.value().replaceFirst("^!", "");
                    if (org.bukkit.Material.matchMaterial(v) == null) {
                        problems.add(s.id + ": block_type names unknown material " + e.value());
                    }
                }
            }
        }
        assertEquals(List.of(), problems);
    }
}
