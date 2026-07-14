package anon.def9a2a4.corelib;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Directional;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dev-only inverse of {@link ShowcaseBuilder}: serialize the anchor + its glued blocks into a
 * {@code showcases.yml} entry, written to disk for a developer to fold into the resource file. Reads back
 * each block exactly as ShowcaseBuilder would have placed it (custom id + placement facing; vanilla
 * material + block-data + physics). {@code activate}/{@code expect}/text fields are left as safe
 * placeholders for the developer to fill in.
 */
final class ShowcaseExporter {

    private ShowcaseExporter() {}

    /** Serialize {@code anchor} (origin = [0,0,0]) plus {@code glued} into {@code out}; returns the count
     *  of blocks written. */
    static int export(String id, Anchor anchor, List<Block> glued, CustomBlockRegistry registry, Path out)
            throws IOException {
        Block origin = anchor.originBlock();
        // Anchor cell first, then every glued cell — deduped by world position, air skipped.
        Map<String, Block> cells = new LinkedHashMap<>();
        cells.put(posKey(origin), origin);
        if (glued != null) for (Block b : glued) cells.putIfAbsent(posKey(b), b);

        List<Map<String, Object>> blocks = new ArrayList<>();
        List<Map<String, Object>> vanilla = new ArrayList<>();
        for (Block b : cells.values()) {
            if (b.getType().isAir()) continue;
            List<Integer> at = List.of(b.getX() - origin.getX(), b.getY() - origin.getY(),
                    b.getZ() - origin.getZ());
            CustomHeadBlock type = registry.getTypeFromBlock(b);
            Map<String, Object> rec = new LinkedHashMap<>();
            if (type != null) {
                rec.put("id", type.fullId());
                rec.put("facing", reverseFacing(b));
                rec.put("at", at);
                blocks.add(rec);
            } else {
                rec.put("block", b.getType().name());
                rec.put("at", at);
                String data = bracketState(b);
                if (data != null) rec.put("data", data);
                if (b.getType() == Material.WATER || b.getType() == Material.LAVA) rec.put("physics", true);
                vanilla.add(rec);
            }
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("name", id);
        entry.put("blurb", "");
        entry.put("description", "");
        entry.put("blocks", blocks);
        if (!vanilla.isEmpty()) entry.put("vanilla", vanilla);
        entry.put("activate", "passive");   // developer promotes to fuel/pulse if the machine needs it
        entry.put("expect", new ArrayList<>());

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("showcases", new ArrayList<>(List.of(entry)));

        Files.createDirectories(out.toAbsolutePath().getParent());
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        Files.writeString(tmp, yaml.saveToString(), StandardCharsets.UTF_8);
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return blocks.size() + vanilla.size();
    }

    /** Filesystem-safe {@code <id>.yml} name for one exported showcase, so each id lands in its own file
     *  (and can't escape the export directory). Lower-cased; any run of non-{@code [a-z0-9_-]} chars —
     *  including path separators and dots — collapses to a single underscore; empty falls back. */
    static String fileNameFor(String id) {
        String slug = (id == null ? "" : id).trim().toLowerCase(java.util.Locale.ROOT);
        slug = slug.replaceAll("[^a-z0-9_-]+", "_");   // unsafe chars (incl. '/', '.') → '_'
        slug = slug.replaceAll("^[_-]+|[_-]+$", "");   // trim leading/trailing separators
        if (slug.isEmpty()) slug = "showcase";
        return slug + ".yml";
    }

    private static String posKey(Block b) {
        return b.getX() + "," + b.getY() + "," + b.getZ();
    }

    /** Inverse of {@code ShowcaseBuilder.placeHead}: floor head → {@code down}; wall head → the opposite
     *  of the head's facing (the clicked face the YAML {@code facing} records). */
    private static String reverseFacing(Block b) {
        if (b.getType() == Material.PLAYER_WALL_HEAD && b.getBlockData() instanceof Directional d) {
            return d.getFacing().getOppositeFace().name().toLowerCase();
        }
        return "down";
    }

    /** The {@code [state=…]} slice of {@code getAsString()} for the VanillaSpec {@code data} field, or
     *  null for a plain block. */
    private static String bracketState(Block b) {
        String s = b.getBlockData().getAsString();
        int i = s.indexOf('[');
        return i < 0 ? null : s.substring(i);
    }
}
