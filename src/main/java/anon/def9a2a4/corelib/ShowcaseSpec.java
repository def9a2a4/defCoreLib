package anon.def9a2a4.corelib;

import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * A demo "showcase": a named multi-block machine (a list of custom blocks + optional vanilla support),
 * loaded from {@code showcases.yml}. Phase 1 only needs placement; activation/expectations/blurb-driven
 * docs come later.
 */
final class ShowcaseSpec {

    /** One custom block in a showcase. {@code at} is [dx,dy,dz] from the build origin; {@code facing}
     *  is the placement face (DOWN = floor). {@code state} is optional (defaults to the placement map). */
    record BlockSpec(String id, BlockFace facing, int[] at, @Nullable String state) {}

    /** One vanilla support block (redstone, planks, container, …) at an offset. */
    record VanillaSpec(Material material, int[] at) {}

    /** How the runner powers the machine: {@code passive} (no-op / recalc), {@code pulse} (redstone at
     *  {@code at}), or {@code fuel} (insert fuel into the block at {@code at}). */
    record Activate(String kind, @Nullable int[] at) {}

    /** A behavioural assertion (phase 2). {@code at} = offset from the build origin. */
    record Expect(String type, int[] at, @Nullable String value) {}

    final String id;
    final String name;
    final String blurb;
    final List<BlockSpec> blocks;
    final List<VanillaSpec> vanilla;
    final Activate activate;
    final List<Expect> expect;

    ShowcaseSpec(String id, String name, String blurb, List<BlockSpec> blocks, List<VanillaSpec> vanilla,
                 Activate activate, List<Expect> expect) {
        this.id = id;
        this.name = name;
        this.blurb = blurb;
        this.blocks = blocks;
        this.vanilla = vanilla;
        this.activate = activate;
        this.expect = expect;
    }

    /** Parse {@code showcases.yml} into an insertion-ordered id → spec map. Malformed entries are
     *  warned and skipped, never fatal. */
    static Map<String, ShowcaseSpec> load(InputStream stream, Logger log) {
        Map<String, ShowcaseSpec> out = new LinkedHashMap<>();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(stream));
        for (Map<?, ?> entry : yaml.getMapList("showcases")) {
            try {
                String id = str(entry.get("id"));
                if (id == null) { log.warning("showcases.yml: entry missing id, skipped"); continue; }
                String name = str(entry.get("name"));
                String blurb = str(entry.get("blurb"));
                List<BlockSpec> blocks = new ArrayList<>();
                for (Object o : asList(entry.get("blocks"))) {
                    if (!(o instanceof Map<?, ?> m)) continue;
                    String bid = str(m.get("id"));
                    BlockFace face = face(str(m.get("facing")));
                    int[] at = vec(m.get("at"));
                    if (bid == null || at == null) {
                        log.warning("showcases.yml [" + id + "]: block entry needs id + at, skipped");
                        continue;
                    }
                    blocks.add(new BlockSpec(bid, face, at, str(m.get("state"))));
                }
                List<VanillaSpec> vanilla = new ArrayList<>();
                for (Object o : asList(entry.get("vanilla"))) {
                    if (!(o instanceof Map<?, ?> m)) continue;
                    Material mat = Material.matchMaterial(str(m.get("block")) == null ? "" : str(m.get("block")));
                    int[] at = vec(m.get("at"));
                    if (mat == null || at == null) {
                        log.warning("showcases.yml [" + id + "]: vanilla entry needs valid block + at, skipped");
                        continue;
                    }
                    vanilla.add(new VanillaSpec(mat, at));
                }
                Activate activate = parseActivate(entry.get("activate"));
                List<Expect> expect = new ArrayList<>();
                for (Object o : asList(entry.get("expect"))) {
                    if (!(o instanceof Map<?, ?> m)) continue;
                    String type = str(m.get("type"));
                    int[] at = vec(m.get("at"));
                    if (type == null || at == null) {
                        log.warning("showcases.yml [" + id + "]: expect entry needs type + at, skipped");
                        continue;
                    }
                    expect.add(new Expect(type, at, str(m.get("value"))));
                }
                out.put(id, new ShowcaseSpec(id, name == null ? id : name, blurb == null ? "" : blurb,
                        blocks, vanilla, activate, expect));
            } catch (Exception e) {
                log.warning("showcases.yml: failed to parse an entry: " + e);
            }
        }
        return out;
    }

    /** `passive` (string) | `{ pulse: [x,y,z] }` | `{ fuel: [x,y,z] }`. Defaults to passive. */
    private static Activate parseActivate(@Nullable Object o) {
        if (o instanceof Map<?, ?> m) {
            for (String kind : new String[]{"pulse", "fuel"}) {
                if (m.containsKey(kind)) return new Activate(kind, vec(m.get(kind)));
            }
        }
        return new Activate("passive", null);
    }

    // ── parsing helpers ──────────────────────────────────────────────────────

    private static @Nullable String str(@Nullable Object o) {
        return o == null ? null : o.toString();
    }

    private static List<?> asList(@Nullable Object o) {
        return (o instanceof List<?> l) ? l : List.of();
    }

    /** Coerce [a,b,c] (List<Number>) into an int[3]; null if malformed. */
    private static int @Nullable [] vec(@Nullable Object o) {
        if (!(o instanceof List<?> l) || l.size() != 3) return null;
        int[] v = new int[3];
        for (int i = 0; i < 3; i++) {
            if (!(l.get(i) instanceof Number n)) return null;
            v[i] = n.intValue();
        }
        return v;
    }

    /** Placement face: down = floor (default); north/south/east/west = wall. */
    private static BlockFace face(@Nullable String s) {
        if (s == null) return BlockFace.DOWN;
        return switch (s.toLowerCase()) {
            case "north" -> BlockFace.NORTH;
            case "south" -> BlockFace.SOUTH;
            case "east" -> BlockFace.EAST;
            case "west" -> BlockFace.WEST;
            case "up" -> BlockFace.UP;
            default -> BlockFace.DOWN;
        };
    }
}
