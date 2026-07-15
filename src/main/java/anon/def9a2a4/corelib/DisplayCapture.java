package anon.def9a2a4.corelib;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.util.Transformation;
import org.jspecify.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a placed custom block's spawned Display entities into the docs JSON shape, and bakes animation
 * keyframe tracks from the real animation functions. Shared by {@link DisplayExporter} (per-block grid)
 * and the showcase capture (multi-block machines) so both emit identical display records.
 *
 * <p>Each display → {@code { kind:"head|item|block", ref, position:[x,y,z] (entity − block-centre),
 * matrix:[16], animation:{period,frames:[[16],…]}|null, tag }}.
 */
final class DisplayCapture {

    private static final int MAX_PERIOD = 2400;   // tick cap for loop detection (2 min)
    private static final int MAX_FRAMES = 120;
    private static final float EPS = 1.0e-3f;

    private DisplayCapture() {}

    /** Read the displays spawned for {@code type} at {@code scratch}'s block, positions relative to the
     *  block centre. When {@code animate}, animated displays get a baked keyframe track ({@code reversed}
     *  negates the tick for CCW). */
    static List<Map<String, Object>> readDisplays(CustomHeadBlock type, Location scratch, String state,
            boolean animate, boolean reversed) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!type.hasDisplayEntities()) return out;

        Location blockLoc = scratch.getBlock().getLocation();
        Location center = blockLoc.clone().add(0.5, 0.5, 0.5);
        String prefix = DisplayUtil.blockTagPrefix(type.namespace(), type.typeId(), blockLoc);

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

    /** Read one arbitrary Display entity (not owned by a custom-block type) into the docs record shape,
     *  positioned relative to {@code cellCenter}. Used for auxiliary displays the type-scoped
     *  {@link #readDisplays} misses — the chain-pulley strand (a BlockDisplay) and showcase banners
     *  (ItemDisplays). No animation is baked (static frame). Returns null for an unhandled Display type. */
    static @Nullable Map<String, Object> readRawDisplay(Display display, Location cellCenter) {
        Map<String, Object> rec = new LinkedHashMap<>();
        Location loc = display.getLocation();
        rec.put("position", new double[]{
                loc.getX() - cellCenter.getX(), loc.getY() - cellCenter.getY(), loc.getZ() - cellCenter.getZ()});
        rec.put("matrix", arr(toMatrix(display.getTransformation())));
        if (display instanceof ItemDisplay item) {
            ItemStack stack = item.getItemStack();
            if (stack != null && stack.getType() == Material.PLAYER_HEAD) {
                rec.put("kind", "head");
                rec.put("ref", textureUrl(itemTexture(stack)));
            } else {
                rec.put("kind", "item");
                rec.put("ref", stack == null ? null : stack.getType().getKey().getKey());
            }
        } else if (display instanceof BlockDisplay bd) {
            rec.put("kind", "block");
            rec.put("ref", bd.getBlock().getAsString());
        } else {
            return null;
        }
        rec.put("animation", null);
        return rec;
    }

    /** Bake a looping matrix track by sampling the real animation over its detected period. */
    static Map<String, Object> bake(DisplayAnimation anim, Matrix4f base, boolean reversed) {
        Matrix4f f0 = new Matrix4f();
        anim.apply(base, 0, f0);
        Matrix4f f1 = new Matrix4f();
        anim.apply(base, 1, f1);
        int period = 0;
        Matrix4f f = new Matrix4f();
        Matrix4f fNext = new Matrix4f();
        // Two-point check: the cycle truly repeats only when BOTH apply(t)==apply(0) AND apply(t+1)==apply(1).
        for (int t = 1; t <= MAX_PERIOD; t++) {
            anim.apply(base, t, f);
            if (approxEqual(f, f0)) {
                anim.apply(base, t + 1, fNext);
                if (approxEqual(fNext, f1)) { period = t; break; }
            }
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

    /** Resolve a base64 textures property to a skin URL (for base-head / head displays). */
    static String textureUrl(String base64) {
        if (base64 == null) return null;
        return HeadUtil.parseTexture(base64).map(HeadUtil.TextureInfo::textureUrl).orElse(null);
    }

    // ── helpers ────────────────────────────────────────────────────────────

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
}
