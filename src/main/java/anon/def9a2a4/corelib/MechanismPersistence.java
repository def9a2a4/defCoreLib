package anon.def9a2a4.corelib;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Crash-safe mechanism persistence: a per-world chunk index ({@code mechanisms/<world>/chunks.yml},
 * mapping {@code "cx,cz" → [mechId...]}) plus one state file per mechanism
 * ({@code mechanisms/<world>/<mechId>.yml}). Keyed on the mechanism's own {@code mechId} (mechId-primary
 * bridge), with {@code vehicle_uuid} stored inside the file as a recovery hint.
 *
 * <p>Writes are synchronous for now (mechanisms are few and small); a single-thread async executor is a
 * later optimisation. Indexing is on the pivot chunk (matching BlockShips' vehicle-chunk model);
 * recovery does a nearby entity sweep for blocks that spilled into adjacent chunks.
 */
final class MechanismPersistence {

    private final JavaPlugin plugin;
    private final File root;
    // world → "cx,cz" → mechIds
    private final Map<String, Map<String, Set<UUID>>> chunkIndex = new HashMap<>();

    MechanismPersistence(JavaPlugin plugin) {
        this.plugin = plugin;
        this.root = new File(plugin.getDataFolder(), "mechanisms");
        loadChunkIndices();
    }

    private File worldDir(String world) { return new File(root, world); }
    private File stateFile(String world, UUID id) { return new File(worldDir(world), id + ".yml"); }
    private File chunkFile(String world) { return new File(worldDir(world), "chunks.yml"); }

    /** Snapshot (already done by the caller on the main thread) → write the state file + index the pivot chunk. */
    void save(MechanismState st) {
        File dir = worldDir(st.worldName);
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("mechanism persistence: cannot create " + dir);
            return;
        }
        YamlConfiguration y = new YamlConfiguration();
        st.write(y);
        try {
            y.save(stateFile(st.worldName, st.mechId));
        } catch (IOException e) {
            plugin.getLogger().warning("mechanism persistence: save failed for " + st.mechId + ": " + e.getMessage());
            return;
        }
        int cx = (int) Math.floor(st.px) >> 4;
        int cz = (int) Math.floor(st.pz) >> 4;
        // Re-index onto the CURRENT pivot chunk. A moving mechanism (a sailing ship re-saved as it crosses
        // chunk borders) must MOVE its index entry, not accumulate one per chunk it ever crossed — else the
        // index bloats and every stale chunk triggers a futile recovery sweep. Drop the old entry first
        // (removeFromChunkIndex sweeps all this world's chunks for the id), then add the new one.
        removeFromChunkIndex(st.worldName, st.mechId);
        addToChunkIndex(st.worldName, st.mechId, cx, cz);
        saveChunkIndex(st.worldName);
    }

    /** Delete a mechanism's state file + drop it from the chunk index (on real disassembly). */
    void remove(String world, UUID id) {
        File f = stateFile(world, id);
        if (f.exists() && !f.delete()) plugin.getLogger().warning("mechanism persistence: cannot delete " + f);
        removeFromChunkIndex(world, id);
        saveChunkIndex(world);
    }

    boolean hasMetadata(String world, UUID id) { return stateFile(world, id).exists(); }

    @Nullable MechanismState load(String world, UUID id) {
        File f = stateFile(world, id);
        if (!f.exists()) return null;
        MechanismState st = MechanismState.read(YamlConfiguration.loadConfiguration(f));
        if (st == null) plugin.getLogger().warning("mechanism persistence: corrupt state file " + f);
        return st;
    }

    /** Mechanism ids indexed to the given chunk (a copy — safe to iterate while recovery mutates). */
    Set<UUID> mechanismsInChunk(String world, int cx, int cz) {
        Map<String, Set<UUID>> w = chunkIndex.get(world);
        if (w == null) return Set.of();
        Set<UUID> s = w.get(cx + "," + cz);
        return s == null ? Set.of() : new HashSet<>(s);
    }

    private void addToChunkIndex(String world, UUID id, int cx, int cz) {
        chunkIndex.computeIfAbsent(world, k -> new HashMap<>())
            .computeIfAbsent(cx + "," + cz, k -> new HashSet<>()).add(id);
    }

    private void removeFromChunkIndex(String world, UUID id) {
        Map<String, Set<UUID>> w = chunkIndex.get(world);
        if (w == null) return;
        for (Set<UUID> s : w.values()) s.remove(id);
    }

    private void saveChunkIndex(String world) {
        Map<String, Set<UUID>> w = chunkIndex.get(world);
        if (w == null) return;
        File dir = worldDir(world);
        if (!dir.exists() && !dir.mkdirs()) return;
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, Set<UUID>> e : w.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            List<String> ids = new ArrayList<>(e.getValue().size());
            for (UUID u : e.getValue()) ids.add(u.toString());
            y.set(e.getKey(), ids);
        }
        try {
            y.save(chunkFile(world));
        } catch (IOException ex) {
            plugin.getLogger().warning("mechanism persistence: chunk index save failed for " + world + ": " + ex.getMessage());
        }
    }

    private void loadChunkIndices() {
        if (!root.exists()) return;
        File[] worlds = root.listFiles(File::isDirectory);
        if (worlds == null) return;
        for (File wd : worlds) {
            File cf = new File(wd, "chunks.yml");
            if (!cf.exists()) continue;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(cf);
            Map<String, Set<UUID>> w = chunkIndex.computeIfAbsent(wd.getName(), k -> new HashMap<>());
            for (String key : y.getKeys(false)) {
                Set<UUID> set = w.computeIfAbsent(key, k -> new HashSet<>());
                for (String s : y.getStringList(key)) {
                    try { set.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
                }
            }
        }
    }

    void shutdown() {
        // Writes are synchronous today, so nothing to drain. (Async executor drain lands here later.)
    }
}
