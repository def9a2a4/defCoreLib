package anon.def9a2a4.corelib;

import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.banner.PatternType;
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
     *  is the placement face (DOWN = floor). {@code state} is optional (defaults to the placement map).
     *  {@code link} is an optional [dx,dy,dz] offset of a chain-pulley partner (only meaningful for
     *  {@code mech:chain_pulley}): the builder writes it into the pulley's chain-link PDC so the pair
     *  transmits power, mirroring an in-game chain link. */
    record BlockSpec(String id, BlockFace facing, int[] at, @Nullable String state, int @Nullable [] link) {}

    /** One vanilla support block (redstone, planks, container, …) at an offset. {@code data} is an
     *  optional Bukkit block-data string (e.g. {@code "[face=floor,powered=true]"} for a lit lever).
     *  {@code physics} places with block-updates on (e.g. so a {@code WATER} source actually flows).
     *  {@code text} is the front-side lines for a sign (up to 4); null/empty for non-signs. */
    record VanillaSpec(Material material, int[] at, @Nullable String data, boolean physics,
                       @Nullable List<String> text) {}

    /** How the runner powers the machine: {@code passive} (no-op / recalc), {@code pulse} (redstone at
     *  {@code at}), or {@code fuel} (insert fuel into the block at {@code at}). */
    record Activate(String kind, @Nullable int[] at) {}

    /** One dye + pattern layer of a banner's design (bottom to top, as {@link org.bukkit.inventory.meta.BannerMeta#setPatterns}
     *  expects). Stored so the full design round-trips through {@code showcases.yml} even though the
     *  current docs viewer renders every banner white. */
    record PatternSpec(DyeColor color, PatternType pattern) {}

    /** A BetterBanners banner placed programmatically by the showcase builder (a flag, a large/huge
     *  banner, or a bed banner — none of these are blocks; they're player-interaction-spawned
     *  ItemDisplay entities, so a showcase must place and capture them separately from {@code blocks}).
     *  {@code item} is the base-color banner Material; {@code mode} is {@code flag | wall | standing |
     *  bed}; {@code host} is the host block's offset (a fence/wall for flag, any block for wall/
     *  standing, a bed for bed); {@code face} is the wall/standing mounting face (unused for flag/bed);
     *  {@code step} (0-15) is the flag/standing rotation; {@code scale} is {@code 1.0/2.2/3.6} for
     *  normal/large/huge; {@code patterns} is the full banner design (stored, not yet rendered). */
    record BannerSpec(Material item, String mode, int[] host, @Nullable BlockFace face, int step,
                       float scale, List<PatternSpec> patterns) {}

    /** A behavioural assertion (phase 2). {@code at} = offset from the build origin; {@code to} is an
     *  optional opposite corner (inclusive box) for types that check a region ({@code block_type}). */
    record Expect(String type, int[] at, @Nullable String value, int @Nullable [] to) {}

    /** A scripted mid-test step, run {@code tick} ticks after the build (test harness only — the docs
     *  keep-alive world ignores it). {@code kind}: {@code set} writes {@code block} into every cell of
     *  {@code at}..{@code to} (or just {@code at}), optionally only where the cell is currently
     *  {@code only}, with block updates iff {@code physics}; {@code corrupt_persisted_mechanism} is a
     *  Java-side probe (see {@code ShowcaseRunner}); {@code summon} spawns {@code entity} (a vanilla entity
     *  key) in cell {@code at} — hanging on {@code face}, holding {@code item}, painted {@code art}, ridden by a
     *  still {@code rider} — all optional keys of {@code extra}. */
    record Action(long tick, String kind, int[] at, int @Nullable [] to, @Nullable Material block,
                  @Nullable Material only, boolean physics, Map<String, String> extra) {}

    final String id;
    final String name;
    final String blurb;        // short one-liner (catalog/list card)
    final String description;  // longer "deeper explanation" (detail page); defaults to blurb
    final List<String> images; // optional screenshot paths for the detail page, relative to docs/
    final List<BlockSpec> blocks;
    final List<VanillaSpec> vanilla;
    final List<BannerSpec> banners;
    final Activate activate;
    final List<Expect> expect;
    /** Regression scenario, not a demo: built and asserted only by the test harness; never captured into
     *  the docs ({@code showcases.json}) nor placed in the keep-alive docs world. */
    final boolean testOnly;
    /** Scripted mid-test steps (test harness only). */
    final List<Action> actions;
    /** Ticks after the build at which this spec's {@code expect}s are checked; 0 = the harness default. */
    final long assertAt;

    ShowcaseSpec(String id, String name, String blurb, String description, List<String> images,
                 List<BlockSpec> blocks, List<VanillaSpec> vanilla, List<BannerSpec> banners,
                 Activate activate, List<Expect> expect, boolean testOnly, List<Action> actions,
                 long assertAt) {
        this.id = id;
        this.name = name;
        this.blurb = blurb;
        this.description = description;
        this.images = images;
        this.blocks = blocks;
        this.vanilla = vanilla;
        this.banners = banners;
        this.activate = activate;
        this.expect = expect;
        this.testOnly = testOnly;
        this.actions = actions;
        this.assertAt = assertAt;
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
                String description = str(entry.get("description"));
                List<String> images = new ArrayList<>();
                for (Object o : asList(entry.get("images"))) {
                    String img = str(o);
                    if (img != null) images.add(img);
                }
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
                    blocks.add(new BlockSpec(bid, face, at, str(m.get("state")), vec(m.get("link"))));
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
                    List<String> text = null;
                    if (m.get("text") != null) {
                        text = new ArrayList<>();
                        for (Object o2 : asList(m.get("text"))) text.add(str(o2) == null ? "" : str(o2));
                    }
                    vanilla.add(new VanillaSpec(mat, at, str(m.get("data")),
                            Boolean.TRUE.equals(m.get("physics")), text));
                }
                List<BannerSpec> banners = new ArrayList<>();
                for (Object o : asList(entry.get("banners"))) {
                    if (!(o instanceof Map<?, ?> m)) continue;
                    Material item = Material.matchMaterial(str(m.get("item")) == null ? "" : str(m.get("item")));
                    String mode = str(m.get("mode"));
                    int[] host = vec(m.get("host"));
                    if (item == null || mode == null || host == null) {
                        log.warning("showcases.yml [" + id + "]: banner entry needs item + mode + host, skipped");
                        continue;
                    }
                    BlockFace bface = str(m.get("face")) == null ? null : face(str(m.get("face")));
                    int step = (m.get("step") instanceof Number n) ? n.intValue() : 0;
                    float scale = (m.get("scale") instanceof Number n) ? n.floatValue() : 1.0f;
                    List<PatternSpec> patterns = new ArrayList<>();
                    for (Object po : asList(m.get("patterns"))) {
                        if (!(po instanceof Map<?, ?> pm)) continue;
                        DyeColor color = dyeColor(str(pm.get("color")));
                        PatternType type = patternType(str(pm.get("pattern")));
                        if (color == null || type == null) {
                            log.warning("showcases.yml [" + id + "]: banner pattern needs valid color + pattern, skipped");
                            continue;
                        }
                        patterns.add(new PatternSpec(color, type));
                    }
                    banners.add(new BannerSpec(item, mode, host, bface, step, scale, patterns));
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
                    expect.add(new Expect(type, at, str(m.get("value")), vec(m.get("to"))));
                }
                List<Action> actions = new ArrayList<>();
                for (Object o : asList(entry.get("actions"))) {
                    if (!(o instanceof Map<?, ?> m)) continue;
                    String kind = str(m.get("do"));
                    int[] at = vec(m.get("at"));
                    if (kind == null || at == null || !(m.get("tick") instanceof Number tick)) {
                        log.warning("showcases.yml [" + id + "]: action entry needs do + tick + at, skipped");
                        continue;
                    }
                    Material block = m.get("block") == null ? null : Material.matchMaterial(str(m.get("block")));
                    Material only = m.get("only") == null ? null : Material.matchMaterial(str(m.get("only")));
                    if ((m.get("block") != null && block == null) || (m.get("only") != null && only == null)
                            || ("set".equals(kind) && block == null)) {
                        log.warning("showcases.yml [" + id + "]: action has a missing/unknown block, skipped");
                        continue;
                    }
                    Map<String, String> extra = new java.util.LinkedHashMap<>();
                    for (String k : new String[]{"entity", "face", "item", "art", "rider"}) {
                        if (m.get(k) != null) extra.put(k, str(m.get(k)));
                    }
                    if ("summon".equals(kind) && !extra.containsKey("entity")) {
                        log.warning("showcases.yml [" + id + "]: summon needs an entity, skipped");
                        continue;
                    }
                    actions.add(new Action(tick.longValue(), kind, at, vec(m.get("to")), block, only,
                            Boolean.TRUE.equals(m.get("physics")), extra));
                }
                boolean testOnly = Boolean.TRUE.equals(entry.get("test_only"));
                long assertAt = (entry.get("assert_at") instanceof Number n) ? n.longValue() : 0;
                String shortBlurb = blurb == null ? "" : blurb;
                out.put(id, new ShowcaseSpec(id, name == null ? id : name, shortBlurb,
                        description == null ? shortBlurb : description, images, blocks, vanilla, banners, activate, expect,
                        testOnly, actions, assertAt));
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

    private static @Nullable DyeColor dyeColor(@Nullable String s) {
        if (s == null) return null;
        try {
            return DyeColor.valueOf(s.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Resolve a vanilla banner pattern by its bare identifier (e.g. {@code "border"}, {@code "creeper"};
     *  implicitly the {@code minecraft} namespace) via {@code Registry.BANNER_PATTERN.get} —
     *  {@link PatternType} is registry-backed on modern Paper, so this (not the deprecated
     *  {@code OldEnum} {@code valueOf}/{@code getByIdentifier}/{@code Registry.match} shims) is the
     *  supported lookup. */
    private static @Nullable PatternType patternType(@Nullable String s) {
        return s == null ? null : org.bukkit.Registry.BANNER_PATTERN.get(
                org.bukkit.NamespacedKey.minecraft(s.toLowerCase(java.util.Locale.ROOT)));
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
