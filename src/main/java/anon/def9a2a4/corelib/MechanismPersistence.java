package anon.def9a2a4.corelib;

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

    private final JavaPlugin plugin;
    private final File root;
    // world → mechIds that have a state file on disk. Seeded eagerly at construction; maintained in
    // save()/remove(). Main-thread only. Backs hasMetadata without touching the filesystem.
    private final Map<String, Set<UUID>> persistedIds = new HashMap<>();

    MechanismPersistence(JavaPlugin plugin) {
        this.plugin = plugin;
        this.root = new File(plugin.getDataFolder(), "mechanisms");
        loadPersistedIds();
    }

    private File worldDir(String world) { return new File(root, world); }
    private File stateFile(String world, UUID id) { return new File(worldDir(world), id + ".yml"); }

    /** Snapshot (already done by the caller on the main thread) → write the state file (SYNC) and record the
     *  id as persisted. Written atomically (tmp sibling + atomic rename) so a crash mid-write can never leave a
     *  truncated file — recovery's corrupt-file cull would otherwise reap an otherwise-recoverable mechanism. */
    void save(MechanismState st) {
        File dir = worldDir(st.worldName);
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("mechanism persistence: cannot create " + dir);
            return;
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
            plugin.getLogger().warning("mechanism persistence: save failed for " + st.mechId + ": " + e.getMessage());
            if (tmp.exists() && !tmp.delete()) tmp.deleteOnExit();
            return; // target (if any) is untouched and still correctly reflected in persistedIds
        }
        persistedIds.computeIfAbsent(st.worldName, k -> new HashSet<>()).add(st.mechId);
    }

    /** Delete a mechanism's state file + drop it from the persisted-id set (on real disassembly, or when a
     *  corrupt state file is culled during recovery). */
    void remove(String world, UUID id) {
        File f = stateFile(world, id);
        if (f.exists() && !f.delete()) plugin.getLogger().warning("mechanism persistence: cannot delete " + f);
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
        MechanismState st = MechanismState.read(YamlConfiguration.loadConfiguration(f));
        if (st == null) plugin.getLogger().warning("mechanism persistence: corrupt state file " + f);
        return st;
    }

    /** Seed {@link #persistedIds} from disk once, at construction — a filename-only directory walk (each
     *  {@code <id>.yml} name is the UUID, so no YAML is parsed). This must run eagerly here rather than lazily
     *  as chunks load: {@code hasPersistedState} probes ids whose pivot chunk has not loaded yet. Any leftover
     *  {@code chunks.yml} from the retired persisted-index subsystem is inert now and deleted on sight. */
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
                if (name.equals("chunks.yml")) {
                    if (!f.delete()) plugin.getLogger().warning("mechanism persistence: cannot delete stale " + f);
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
