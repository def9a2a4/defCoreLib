package anon.def9a2a4.corelib;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Crash-safe mechanism persistence: one state file per mechanism ({@code mechanisms/<world>/<mechId>.yml}),
 * keyed on the mechanism's own {@code mechId} (mechId-primary bridge), with {@code vehicle_uuid} stored
 * inside the file as a recovery hint. The state files are the sole source of truth.
 *
 * <p>There is no persisted chunk index. Recovery discovers what to rebuild by scanning a loaded chunk's
 * entities for their {@code corelib:mech:<id>} scoreboard tags (see
 * {@link MechanismRegistry#recoverMechanismsInChunk}) — so a lost or stale index can never strand a
 * mechanism. The only in-memory bookkeeping is {@link #persistedIds}, a per-world set of the mechIds that
 * have a state file, seeded eagerly at construction by listing each world's directory (filenames are the
 * UUIDs — no YAML parse). It backs {@link #hasMetadata} (the orphan-sweep guard and the
 * {@code hasPersistedState} migration probe) as a pure in-memory lookup, so those pay no per-entity
 * {@code File.exists} on the chunk-streaming hot path, and it answers "does defCoreLib already own this id?"
 * for a mechanism whose pivot chunk has not loaded yet.
 *
 * <p>All bookkeeping is on the main thread (the maps are unsynchronized by design). Every file write and
 * delete runs on ONE writer thread, in submission order: {@link #save}/{@link #remove} submit and wait (their
 * synchronous contract is unchanged), only the world-save re-snapshot ({@link #saveAsync}) is fire-and-forget.
 * Because the queue is FIFO, an async write can never land after a later save or delete of the same id.
 * Recovery reads synchronously on the main thread; the atomic rename means it never sees a half-written file.
 */
final class MechanismPersistence {

    private final Logger log;
    private final File root;
    // world → mechIds that have a state file on disk. Seeded eagerly at construction; maintained in
    // save()/remove(). Main-thread only. Backs hasMetadata without touching the filesystem.
    private final Map<String, Set<UUID>> persistedIds = new HashMap<>();
    // Once-per-file guard for the corrupt-state warning: load() re-runs on every nearby chunk load and the
    // bad file is deliberately never culled (see remove()), so an unguarded warning floods the console.
    private final Set<String> corruptWarned = new HashSet<>();
    // world:id → lastModified of a file whose CONTENTS were read and found not to be a valid state. load()
    // skips the re-parse while the file is unchanged. Only content failures are cached: a read that failed
    // with an IOException (a disk blip) is not, so the next chunk load retries it as before.
    private final Map<String, Long> corruptMtimes = new HashMap<>();
    // world → (mechId → queued delete). A disassembled mechanism's file must outlive the chunk write that
    // stores its landed blocks: until then a crash restores the region with the entities still in it and the
    // blocks NOT placed, and only the file lets recovery re-adopt the ship (without it the entities are reaped
    // as orphans → the whole structure is lost). Paper's WorldSaveEvent proves nothing here: its autosave
    // (ServerLevel.saveIncrementally) writes NO chunks — Moonrise writes each dirty chunk on its own timer
    // (chunks.auto-save-interval, defaulting to bukkit.yml ticks-per.autosave) — and under /save-off no chunk
    // is written at all while the event keeps firing. Neither interval is API, so the delete waits for BOTH
    // DELETE_AFTER_TICKS since removal and DELETE_AFTER_SAVES autosave-on events (the latter covers a raised
    // ticks-per.autosave: three events span at least two periods); a save-off event restarts both. Not
    // persisted: a crash leaves queued files behind (inert without their entities); /stop deletes them.
    private final Map<String, Map<UUID, PendingDelete>> pendingDeletes = new HashMap<>();
    /** 2× Paper's default chunk autosave interval (6000 ticks) — margin for the per-tick chunk-save cap. A
     *  per-world chunks.auto-save-interval raised above bukkit's ticks-per.autosave is not covered. */
    static final long DELETE_AFTER_TICKS = 12_000;
    static final int DELETE_AFTER_SAVES = 3;

    private static final class PendingDelete {
        long since;
        int saves;
        PendingDelete(long since) { this.since = since; }
    }

    // The single writer thread — see the class doc. Daemon: a missed close() never blocks JVM exit.
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "defCoreLib-mechanism-persistence");
        t.setDaemon(true);
        return t;
    });
    // mechId → the YAML last handed to the writer, so saveAsync skips an unchanged snapshot. Main thread
    // writes it; the writer only drops a FAILED write's entry (so the next world save retries it).
    private final Map<UUID, String> lastWritten = new ConcurrentHashMap<>();

    MechanismPersistence(JavaPlugin plugin) {
        this(plugin.getDataFolder(), plugin.getLogger());
    }

    /** Plugin-free constructor — the plugin only ever supplied these two (also what the unit tests use). */
    MechanismPersistence(File dataFolder, Logger log) {
        this.log = log;
        this.root = new File(dataFolder, "mechanisms");
        loadPersistedIds();
    }

    private File worldDir(String world) { return new File(root, world); }
    File stateFile(String world, UUID id) { return new File(worldDir(world), id + ".yml"); }

    /** Snapshot (already done by the caller on the main thread) → write the state file (SYNC) and record the
     *  id as persisted. Written atomically (tmp sibling + atomic rename) so a crash mid-write can never leave a
     *  truncated file — a truncated file would read as corrupt and pause the mechanism's recovery until an
     *  operator intervened. */
    void save(MechanismState st) {
        String data = serialize(st);
        ordered(() -> writeFile(st.worldName, st.mechId, data));
        lastWritten.put(st.mechId, data);
        persistedIds.computeIfAbsent(st.worldName, k -> new HashSet<>()).add(st.mechId);
        corruptMtimes.remove(st.worldName + ":" + st.mechId);
        Map<UUID, PendingDelete> pending = pendingDeletes.get(st.worldName);
        if (pending != null) pending.remove(st.mechId);   // re-persisted before its queued delete: keep the file
    }

    /** The world-save re-snapshot of a LIVE persisted mechanism: serialized here (main thread), written on the
     *  writer thread without waiting, and skipped when identical to the last write. A failed write is only
     *  logged — the previous file stays. The id is already persisted (persist() wrote it synchronously). */
    void saveAsync(MechanismState st) {
        String data = serialize(st);
        UUID id = st.mechId;
        if (data.equals(lastWritten.get(id))) return;
        lastWritten.put(id, data);
        String world = st.worldName;
        corruptMtimes.remove(world + ":" + id);
        submit(() -> {
            try {
                writeFile(world, id, data);
            } catch (RuntimeException e) {
                lastWritten.remove(id, data);
                log.warning("Mechanism " + id + " failed to persist on world save (" + e.getMessage()
                    + "); its previous state file is kept");
            }
        });
    }

    private static String serialize(MechanismState st) {
        YamlConfiguration y = new YamlConfiguration();
        st.write(y);
        return y.saveToString();
    }

    /** Writer thread (or inline after {@link #close}): tmp sibling + atomic rename. */
    private void writeFile(String world, UUID id, String data) {
        File dir = worldDir(world);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new UncheckedIOException(new IOException("mechanism persistence: cannot create " + dir));
        }
        File target = stateFile(world, id);
        File tmp = new File(dir, id + ".yml.tmp");
        try {
            Files.writeString(tmp.toPath(), data, StandardCharsets.UTF_8);
            try {
                Files.move(tmp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException atomicUnsupported) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Thrown, not logged: every caller has a fallback for a failed save (an assembly rolls back,
            // shutdown/unload disassemble instead) and a swallowed failure skipped all of them. The target (if
            // any) is untouched and still correctly reflected in persistedIds.
            if (tmp.exists() && !tmp.delete()) tmp.deleteOnExit();
            throw new UncheckedIOException("mechanism persistence: save failed for " + id, e);
        }
    }

    private void deleteFile(File f) {
        if (f.exists() && !f.delete()) log.warning("mechanism persistence: cannot delete " + f);
    }

    /** Run on the writer thread behind everything already queued, and wait; its exception is rethrown here. */
    private void ordered(Runnable task) {
        if (writer.isShutdown()) { task.run(); return; }   // after close(): the queue is drained, run inline
        try {
            writer.submit(task).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException("mechanism persistence: interrupted", e));
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            if (e.getCause() instanceof Error err) throw err;
            throw new UncheckedIOException(new IOException(e.getCause()));
        }
    }

    private void submit(Runnable task) {
        if (writer.isShutdown()) task.run();
        else writer.execute(task);
    }

    /** Drain the queued writes and stop the writer — plugin disable, after the final sync saves. Later
     *  calls (none expected) run inline. */
    void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warning("mechanism persistence: queued state writes did not finish within 30s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Delete a mechanism's state file + drop it from the persisted-id set. Real disassembly only — a
     *  corrupt/unreadable file is deliberately NEVER removed (see the recovery pass in MechanismRegistry:
     *  the file's existence is what shields the mechanism's entities from the orphan sweep). */
    void remove(String world, UUID id) {
        File f = stateFile(world, id);
        ordered(() -> deleteFile(f));
        forget(world, id);
        Map<UUID, PendingDelete> pending = pendingDeletes.get(world);
        if (pending != null) pending.remove(id);
    }

    /** {@link #remove} for a real disassembly: the id stops being persisted NOW (hasMetadata answers false, so
     *  the orphan sweep and the migration probe behave exactly as after remove), but the file itself is only
     *  deleted once the chunks holding its landed blocks have surely been written — see {@link #pendingDeletes}.
     *  {@code nowTick} is the server tick ({@code Bukkit.getCurrentTick()}). */
    void removeAfterSave(String world, UUID id, long nowTick) {
        removeAfterSave(world, id, nowTick, null);
    }

    void removeAfterSave(String world, UUID id, long nowTick, @Nullable String teardown) {
        forget(world, id);
        if (teardown != null) {
            File f = stateFile(world, id);
            ordered(() -> {
                if (!f.exists()) return;
                YamlConfiguration y = new YamlConfiguration();
                try {
                    y.load(f);
                } catch (IOException | InvalidConfigurationException e) {
                    return; // unreadable: leave it as it is; recovery will not touch a file it cannot read
                }
                // First mark wins: a teardown that threw after landing re-runs onMechanismRemoved from its error
                // path, and turning "landed" into "destroyed" would make a crash discard the landed blocks.
                if (y.contains(MechanismState.TEARDOWN_KEY)) return;
                y.set(MechanismState.TEARDOWN_KEY, teardown);
                try {
                    writeFile(world, id, y.saveToString());
                } catch (UncheckedIOException e) {
                    // Called from inside a teardown, which must finish. Unmarked, a crash revives it as before.
                    log.warning("mechanism persistence: could not mark " + id + " as " + teardown + ": " + e.getMessage());
                }
            });
        }
        pendingDeletes.computeIfAbsent(world, k -> new HashMap<>()).put(id, new PendingDelete(nowTick));
    }

    /** A WorldSaveEvent for {@code world}. With autosave on ({@code World#isAutoSave}) count it and delete the
     *  files that are due; with it off (/save-off) nothing has been written since, so restart their wait. */
    void onWorldSaved(String world, boolean autoSave, long nowTick) {
        Map<UUID, PendingDelete> pending = pendingDeletes.get(world);
        if (pending == null) return;
        List<File> due = new ArrayList<>();
        pending.entrySet().removeIf(e -> {
            PendingDelete d = e.getValue();
            if (!autoSave) { d.since = nowTick; d.saves = 0; return false; }
            if (++d.saves < DELETE_AFTER_SAVES || nowTick - d.since < DELETE_AFTER_TICKS) return false;
            due.add(stateFile(world, e.getKey()));
            return true;
        });
        if (pending.isEmpty()) pendingDeletes.remove(world);
        if (!due.isEmpty()) ordered(() -> due.forEach(this::deleteFile));
    }

    /** Delete every queued file now — server stop only, see {@code MechanismRegistry.shutdown}. The stop save
     *  writes every world even under /save-off (MinecraftServer.stopServer clears noSave before saveAllChunks). */
    void deletePending() {
        List<File> due = new ArrayList<>();
        pendingDeletes.forEach((world, ids) -> ids.keySet().forEach(id -> due.add(stateFile(world, id))));
        pendingDeletes.clear();
        if (!due.isEmpty()) ordered(() -> due.forEach(this::deleteFile));
    }

    private void forget(String world, UUID id) {
        lastWritten.remove(id);
        corruptMtimes.remove(world + ":" + id);
        Set<UUID> ids = persistedIds.get(world);
        if (ids != null) {
            ids.remove(id);
            if (ids.isEmpty()) persistedIds.remove(world);
        }
    }

    /** Whether a state file exists for this id — an in-memory lookup, no filesystem stat. */
    boolean hasMetadata(String world, UUID id) {
        Set<UUID> ids = persistedIds.get(world);
        return ids != null && ids.contains(id);
    }

    @Nullable MechanismState load(String world, UUID id) {
        File f = stateFile(world, id);
        if (!f.exists()) return null;
        String key = world + ":" + id;
        long mtime = f.lastModified();
        Long badMtime = corruptMtimes.get(key);
        if (badMtime != null && badMtime == mtime) return null;   // known-bad contents, unchanged since
        // Not YamlConfiguration.loadConfiguration: it folds an IOException into an empty config, which would
        // make a disk blip indistinguishable from a corrupt file (and cache it as one).
        YamlConfiguration y = new YamlConfiguration();
        MechanismState st;
        try {
            y.load(f);
            st = MechanismState.read(y);
        } catch (IOException e) {
            if (corruptWarned.add("io:" + key)) {
                log.warning("mechanism persistence: could not read state file " + f + " (" + e.getMessage()
                    + ") — this mechanism's recovery is paused and will be retried on the next chunk load.");
            }
            return null;
        } catch (InvalidConfigurationException e) {
            st = null;
        }
        if (st != null) {
            corruptMtimes.remove(key);
            return st;
        }
        corruptMtimes.put(key, mtime);
        if (corruptWarned.add(key)) {
            log.warning("mechanism persistence: corrupt state file " + f
                + " — this mechanism's recovery is paused and nothing will be deleted while the file exists."
                + " Restore or repair the file by hand; it is re-read once it changes, on the next chunk load"
                + " near the mechanism. To give the mechanism up instead, move the file aside and restart —"
                + " the shield is an in-memory set seeded at startup, so removing the file alone does not lift"
                + " it until then.");
        }
        return null;
    }

    /** Seed {@link #persistedIds} from disk once, at construction — a filename-only directory walk (each
     *  {@code <id>.yml} name is the UUID, so no YAML is parsed). This must run eagerly here rather than lazily
     *  as chunks load: {@code hasPersistedState} probes ids whose pivot chunk has not loaded yet. Any leftover
     *  {@code chunks.yml} from the retired persisted-index subsystem is inert now and deleted on sight; a
     *  {@code <id>.yml.tmp} left by a crash between save()'s tmp-write and its rename is likewise swept. */
    private void loadPersistedIds() {
        if (!root.exists()) return;
        File[] worlds = root.listFiles(File::isDirectory);
        if (worlds == null) return;
        for (File wd : worlds) {
            File[] files = wd.listFiles();
            if (files == null) continue;
            String world = wd.getName();
            Set<UUID> ids = persistedIds.computeIfAbsent(world, k -> new HashSet<>());
            for (File f : files) {
                String name = f.getName();
                if (name.equals("chunks.yml") || name.endsWith(".yml.tmp")) {
                    if (!f.delete()) log.warning("mechanism persistence: cannot delete stale " + f);
                    continue;
                }
                if (!name.endsWith(".yml")) continue;
                try {
                    ids.add(UUID.fromString(name.substring(0, name.length() - 4)));
                } catch (IllegalArgumentException ignored) {
                    // not a mechanism state file — skip
                }
            }
            if (ids.isEmpty()) persistedIds.remove(world);
        }
    }
}
