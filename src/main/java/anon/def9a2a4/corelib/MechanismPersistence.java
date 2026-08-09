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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Crash-safe mechanism persistence: a per-world chunk index ({@code mechanisms/<world>/chunks.yml},
 * mapping {@code "cx,cz" → [mechId...]}) plus one state file per mechanism
 * ({@code mechanisms/<world>/<mechId>.yml}). Keyed on the mechanism's own {@code mechId} (mechId-primary
 * bridge), with {@code vehicle_uuid} stored inside the file as a recovery hint.
 *
 * <p>Per-mechanism STATE files are written synchronously (recovery reads them synchronously on the main
 * thread, so an async write could race a read). The chunk INDEX — the only per-move O(all-mechanisms)
 * cost — is written on a single-thread daemon IO executor: a moving mechanism's re-save updates the index
 * in memory (O(1) keyed move) and marks its world dirty; the file is flushed off the main thread on a
 * timer / at chunk-or-world unload / at shutdown. Every index write (async and the blocking "sync"
 * variants) goes through that one executor, so there is exactly one writer thread and no file races. The
 * index lags memory, so it is validated against the per-mechanism state files (the source of truth) on
 * load. Indexing is on the pivot chunk (matching BlockShips' vehicle-chunk model); recovery does a nearby
 * entity sweep for blocks that spilled into adjacent chunks.
 */
final class MechanismPersistence {

    private final JavaPlugin plugin;
    private final File root;
    // world → "cx,cz" → mechIds
    private final Map<String, Map<String, Set<UUID>>> chunkIndex = new HashMap<>();
    // Reverse index for an O(1) keyed remove: mechId → where it currently sits in chunkIndex (world kept so
    // a cross-world move drops the OLD world's entry). Rebuilt on load; never serialized.
    private final Map<UUID, IndexLoc> indexLoc = new HashMap<>();
    // Worlds whose in-memory index changed since the last flush — flushDirtyAsync writes only these.
    private final Set<String> dirtyWorlds = new HashSet<>();
    // Single writer thread for ALL chunk-index file writes (async + the blocking sync variants).
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "defCoreLib-IO");
        t.setDaemon(true);
        return t;
    });

    private record IndexLoc(String world, String key) {}

    MechanismPersistence(JavaPlugin plugin) {
        this.plugin = plugin;
        this.root = new File(plugin.getDataFolder(), "mechanisms");
        loadChunkIndices();
    }

    private File worldDir(String world) { return new File(root, world); }
    private File stateFile(String world, UUID id) { return new File(worldDir(world), id + ".yml"); }
    private File chunkFile(String world) { return new File(worldDir(world), "chunks.yml"); }

    /** Snapshot (already done by the caller on the main thread) → write the state file (SYNC) + re-index the
     *  pivot chunk in memory (the index file is flushed off-thread on the timer/unload/shutdown cadence). */
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
        // chunk borders) MOVES its index entry via an O(1) keyed remove (indexLoc knows the old chunk), not a
        // sweep — and touches no disk here. The change reaches chunks.yml on the next dirty flush.
        removeFromChunkIndex(st.mechId);
        addToChunkIndex(st.worldName, st.mechId, cx, cz);
    }

    /** Delete a mechanism's state file + drop it from the chunk index (on real disassembly). Flushes the
     *  world's index synchronously for immediate durability. */
    void remove(String world, UUID id) {
        File f = stateFile(world, id);
        if (f.exists() && !f.delete()) plugin.getLogger().warning("mechanism persistence: cannot delete " + f);
        removeFromChunkIndex(id);
        flushWorldSync(world);
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
        String key = cx + "," + cz;
        chunkIndex.computeIfAbsent(world, k -> new HashMap<>())
            .computeIfAbsent(key, k -> new HashSet<>()).add(id);
        indexLoc.put(id, new IndexLoc(world, key));
        dirtyWorlds.add(world);
    }

    /** O(1) keyed remove via the reverse index — drops the id from the ONE chunk-set it currently sits in. */
    private void removeFromChunkIndex(UUID id) {
        IndexLoc loc = indexLoc.remove(id);
        if (loc == null) return;
        Map<String, Set<UUID>> w = chunkIndex.get(loc.world());
        if (w == null) return;
        Set<UUID> s = w.get(loc.key());
        if (s != null) {
            s.remove(id);
            if (s.isEmpty()) w.remove(loc.key());
        }
        dirtyWorlds.add(loc.world());
    }

    // ── Flush (all writes serialize on the single ioExecutor) ──────────────────────────────────────────

    /** Deep, string-ified snapshot of the given worlds' index — the immutable payload handed to the IO
     *  thread. Built on the main thread; never shares the live nested maps. A world present with an empty
     *  map truncates its chunks.yml. */
    private Map<String, Map<String, List<String>>> snapshotWorlds(Set<String> worlds) {
        Map<String, Map<String, List<String>>> snap = new HashMap<>();
        for (String world : worlds) {
            Map<String, Set<UUID>> w = chunkIndex.get(world);
            Map<String, List<String>> ws = new HashMap<>();
            if (w != null) {
                for (Map.Entry<String, Set<UUID>> e : w.entrySet()) {
                    if (e.getValue().isEmpty()) continue;
                    List<String> ids = new ArrayList<>(e.getValue().size());
                    for (UUID u : e.getValue()) ids.add(u.toString());
                    ws.put(e.getKey(), ids);
                }
            }
            snap.put(world, ws);
        }
        return snap;
    }

    /** Write a pre-built snapshot to disk (one chunks.yml per world). Runs on the IO thread. */
    private void writeWorlds(Map<String, Map<String, List<String>>> snap) {
        for (Map.Entry<String, Map<String, List<String>>> we : snap.entrySet()) {
            File dir = worldDir(we.getKey());
            if (!dir.exists() && !dir.mkdirs()) continue;
            YamlConfiguration y = new YamlConfiguration();
            for (Map.Entry<String, List<String>> e : we.getValue().entrySet()) y.set(e.getKey(), e.getValue());
            try {
                y.save(chunkFile(we.getKey()));
            } catch (IOException ex) {
                plugin.getLogger().warning("mechanism persistence: chunk index save failed for "
                    + we.getKey() + ": " + ex.getMessage());
            }
        }
    }

    /** Flush only the worlds whose index changed since the last flush, asynchronously (main-thread snapshot,
     *  off-thread write). Called on the 60s timer and at chunk-entities unload. */
    void flushDirtyAsync() {
        if (dirtyWorlds.isEmpty()) return;
        Map<String, Map<String, List<String>>> snap = snapshotWorlds(dirtyWorlds);
        dirtyWorlds.clear();
        try {
            ioExecutor.submit(() -> writeWorlds(snap));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Executor already shut down (server stopping) — flushAllSync at shutdown covers it.
        }
    }

    /** Blocking single-world flush for lifecycle/rare paths (remove, world unload): enqueues behind any
     *  pending async writes on the same thread and waits, so the file lands before returning and never
     *  races an async write. */
    void flushWorldSync(String world) {
        dirtyWorlds.remove(world);
        submitAndWait(snapshotWorlds(Set.of(world)));
    }

    /** Blocking full flush (shutdown / after a load cleanup). */
    void flushAllSync() {
        dirtyWorlds.clear();
        submitAndWait(snapshotWorlds(new HashSet<>(chunkIndex.keySet())));
    }

    private void submitAndWait(Map<String, Map<String, List<String>>> snap) {
        if (snap.isEmpty()) return;
        try {
            Future<?> f = ioExecutor.submit(() -> writeWorlds(snap));
            f.get();
        } catch (java.util.concurrent.RejectedExecutionException rex) {
            writeWorlds(snap); // executor gone (mid-shutdown) — write inline as a last resort
        } catch (ExecutionException e) {
            plugin.getLogger().warning("mechanism persistence: index flush failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void loadChunkIndices() {
        if (!root.exists()) return;
        File[] worlds = root.listFiles(File::isDirectory);
        if (worlds == null) return;
        for (File wd : worlds) {
            File cf = new File(wd, "chunks.yml");
            if (!cf.exists()) continue;
            String world = wd.getName();
            YamlConfiguration y = YamlConfiguration.loadConfiguration(cf);
            Map<String, Set<UUID>> w = chunkIndex.computeIfAbsent(world, k -> new HashMap<>());
            int stale = 0;
            for (String key : y.getKeys(false)) {
                Set<UUID> set = w.computeIfAbsent(key, k -> new HashSet<>());
                for (String s : y.getStringList(key)) {
                    UUID id;
                    try { id = UUID.fromString(s); } catch (IllegalArgumentException ignored) { continue; }
                    if (indexLoc.containsKey(id)) continue; // dedupe a pre-fix multi-chunk entry (keep first)
                    // Crash-safety: the lagging index can point at a mechanism whose state file is gone (removed
                    // after the last flush, or a crash mid-move). The per-mechanism state file is the source of
                    // truth — drop any indexed id without one.
                    if (!stateFile(world, id).exists()) { stale++; continue; }
                    set.add(id);
                    indexLoc.put(id, new IndexLoc(world, key));
                }
                if (set.isEmpty()) w.remove(key);
            }
            if (stale > 0) {
                plugin.getLogger().info("mechanism persistence: dropped " + stale + " stale chunk-index entr"
                    + (stale == 1 ? "y" : "ies") + " for world " + world + " (no state file)");
                flushWorldSync(world);
            }
        }
    }

    void shutdown() {
        flushAllSync(); // authoritative final write, enqueued behind any pending async writes (executor live)
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(5, TimeUnit.SECONDS)) ioExecutor.shutdownNow();
        } catch (InterruptedException e) {
            ioExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
