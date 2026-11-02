package anon.def9a2a4.corelib;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
 * <p>All access is on the main thread (the maps are unsynchronized by design). State writes are synchronous;
 * recovery reads them synchronously on the main thread, so there is no writer thread and no file race.
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
    // world → (mechId → WorldSaveEvents still to pass before the state file may go). A disassembled
    // mechanism's file must outlive the world save that writes its landed blocks: until then a crash restores
    // the region with the entities still in it and the blocks NOT placed, and only the file lets recovery
    // re-adopt the ship (without it the entities are reaped as orphans → the whole structure is lost). The
    // event fires BEFORE chunks are written, so the first save after removal isn't proof — wait for the
    // second. Not persisted: a crash leaves queued files behind (inert without their entities); /stop deletes them.
    private final Map<String, Map<UUID, Integer>> pendingDeletes = new HashMap<>();

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
        File dir = worldDir(st.worldName);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new java.io.UncheckedIOException(new IOException("mechanism persistence: cannot create " + dir));
        }
        YamlConfiguration y = new YamlConfiguration();
        st.write(y);
        File target = stateFile(st.worldName, st.mechId);
        File tmp = new File(dir, st.mechId + ".yml.tmp");
        try {
            y.save(tmp);
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
            throw new java.io.UncheckedIOException("mechanism persistence: save failed for " + st.mechId, e);
        }
        persistedIds.computeIfAbsent(st.worldName, k -> new HashSet<>()).add(st.mechId);
        corruptMtimes.remove(st.worldName + ":" + st.mechId);
        Map<UUID, Integer> pending = pendingDeletes.get(st.worldName);
        if (pending != null) pending.remove(st.mechId);   // re-persisted before its queued delete: keep the file
    }

    /** Delete a mechanism's state file + drop it from the persisted-id set. Real disassembly only — a
     *  corrupt/unreadable file is deliberately NEVER removed (see the recovery pass in MechanismRegistry:
     *  the file's existence is what shields the mechanism's entities from the orphan sweep). */
    void remove(String world, UUID id) {
        File f = stateFile(world, id);
        if (f.exists() && !f.delete()) log.warning("mechanism persistence: cannot delete " + f);
        forget(world, id);
        Map<UUID, Integer> pending = pendingDeletes.get(world);
        if (pending != null) pending.remove(id);
    }

    /** {@link #remove} for a real disassembly: the id stops being persisted NOW (hasMetadata answers false, so
     *  the orphan sweep and the migration probe behave exactly as after remove), but the file itself is only
     *  deleted on the second {@link #onWorldSaved} — see {@link #pendingDeletes}. */
    void removeAfterSave(String world, UUID id) {
        forget(world, id);
        pendingDeletes.computeIfAbsent(world, k -> new HashMap<>()).put(id, 2);
    }

    /** A WorldSaveEvent for {@code world}: count down the queued deletes and delete the files that are due. */
    void onWorldSaved(String world) {
        Map<UUID, Integer> pending = pendingDeletes.get(world);
        if (pending == null) return;
        pending.entrySet().removeIf(e -> {
            if (e.getValue() > 1) { e.setValue(e.getValue() - 1); return false; }
            File f = stateFile(world, e.getKey());
            if (f.exists() && !f.delete()) log.warning("mechanism persistence: cannot delete " + f);
            return true;
        });
        if (pending.isEmpty()) pendingDeletes.remove(world);
    }

    /** Delete every queued file now — server stop only, see {@code MechanismRegistry.shutdown}. */
    void deletePending() {
        pendingDeletes.forEach((world, ids) -> ids.keySet().forEach(id -> {
            File f = stateFile(world, id);
            if (f.exists() && !f.delete()) log.warning("mechanism persistence: cannot delete " + f);
        }));
        pendingDeletes.clear();
    }

    private void forget(String world, UUID id) {
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
