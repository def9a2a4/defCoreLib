package anon.def9a2a4.corelib;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class MechanismPersistenceTest {

    @TempDir Path dataFolder;
    private final Logger log = Logger.getLogger("MechanismPersistenceTest");
    private MechanismPersistence p;

    @BeforeEach
    void setUp() {
        p = new MechanismPersistence(dataFolder.toFile(), log);
    }

    private File stateFile(MechanismState st) {
        return dataFolder.resolve("mechanisms").resolve(st.worldName).resolve(st.mechId + ".yml").toFile();
    }

    @Test
    void saveThenLoad() {
        MechanismState st = MechanismStateTest.sample();
        p.save(st);
        assertTrue(p.hasMetadata(st.worldName, st.mechId));
        MechanismState r = p.load(st.worldName, st.mechId);
        assertNotNull(r);
        assertEquals(st.mechId, r.mechId);
        assertEquals(2, r.blocks.size());
    }

    /** A failed write must reach the caller: assembly rolls back on it, shutdown and unload disassemble instead.
     *  It used to be logged and swallowed, so none of those fallbacks ever ran. */
    @Test
    void aFailedSaveThrowsAndRecordsNothing() throws Exception {
        MechanismState st = MechanismStateTest.sample();
        File worldDir = dataFolder.resolve("mechanisms").resolve(st.worldName).toFile();
        assertTrue(worldDir.getParentFile().mkdirs());
        assertTrue(worldDir.createNewFile());   // a FILE where the world's directory should be: mkdirs fails
        assertThrows(java.io.UncheckedIOException.class, () -> p.save(st));
        assertFalse(p.hasMetadata(st.worldName, st.mechId));
    }

    @Test
    void saveLeavesNoTempFile() {
        MechanismState st = MechanismStateTest.sample();
        p.save(st);
        File[] left = stateFile(st).getParentFile().listFiles((d, n) -> n.endsWith(".tmp"));
        assertNotNull(left);
        assertEquals(0, left.length);
    }

    /** A disassembly unshields the id at once but keeps the file until its landed blocks' chunks have surely
     *  been written: Paper's WorldSaveEvent writes no chunks (they autosave on their own timers), so the delete
     *  needs both enough ticks and enough autosave events. */
    @Test
    void removeAfterSaveWaitsForTicksAndSaves() {
        MechanismState st = MechanismStateTest.sample();
        p.save(st);
        long t = 1000;
        p.removeAfterSave(st.worldName, st.mechId, t);
        assertFalse(p.hasMetadata(st.worldName, st.mechId));
        p.onWorldSaved("some_other_world", true, t + 100_000);
        // Many saves, too little time (e.g. repeated /save-all): kept.
        for (int i = 0; i < 5; i++) p.onWorldSaved(st.worldName, true, t + 6_000);
        assertTrue(stateFile(st).exists());
        // Enough time but the count was already met: deleted at the first event past the tick bound.
        p.onWorldSaved(st.worldName, true, t + MechanismPersistence.DELETE_AFTER_TICKS);
        assertFalse(stateFile(st).exists());
    }

    /** A teardown replaces the state file with a record: the id stops being persisted (its entities are orphans),
     *  and after a crash the record comes back as due to be redone, with the state it was torn down in. A clean stop
     *  deletes it; an assembly from its cells marks it superseded. */
    @Test
    void aTeardownRecordSurvivesACrashButNotACleanStop() {
        MechanismState st = MechanismStateTest.sample();
        p.save(st);
        UUID w = UUID.randomUUID();
        Set<CustomBlockRegistry.LocationKey> cells = Set.of(new CustomBlockRegistry.LocationKey(w, 1, 2, 3));
        p.markTeardown(st, MechanismState.LANDED, cells, 0);
        assertFalse(p.hasMetadata(st.worldName, st.mechId));
        assertFalse(stateFile(st).exists());
        assertTrue(p.crashTeardowns(st.worldName).isEmpty());          // in session: not due

        UUID relaunch = UUID.randomUUID();
        p.supersedeTeardowns(st.worldName, relaunch, Set.of(new CustomBlockRegistry.LocationKey(w, 9, 9, 9)));
        p.supersedeTeardowns(st.worldName, relaunch, cells);

        MechanismPersistence afterCrash = new MechanismPersistence(dataFolder.toFile(), log);
        assertFalse(afterCrash.hasMetadata(st.worldName, st.mechId));
        List<MechanismPersistence.Teardown> due = afterCrash.crashTeardowns(st.worldName);
        assertEquals(1, due.size());
        assertEquals(MechanismState.LANDED, due.get(0).mode);
        assertEquals(relaunch, due.get(0).supersededBy);
        assertEquals(2, due.get(0).st.blocks.size());

        p.deletePending();                                              // a clean stop
        assertTrue(new MechanismPersistence(dataFolder.toFile(), log).crashTeardowns(st.worldName).isEmpty());
    }

    @Test
    void removeAfterSaveNeedsEnoughAutosaveEvents() {
        MechanismState st = MechanismStateTest.sample();
        p.save(st);
        p.removeAfterSave(st.worldName, st.mechId, 0);
        // A raised ticks-per.autosave: few events, each far apart — the event count gates the delete.
        for (int i = 1; i < MechanismPersistence.DELETE_AFTER_SAVES; i++) p.onWorldSaved(st.worldName, true, i * 50_000L);
        assertTrue(stateFile(st).exists());
        p.onWorldSaved(st.worldName, true, MechanismPersistence.DELETE_AFTER_SAVES * 50_000L);
        assertFalse(stateFile(st).exists());
    }

    /** Under /save-off no chunk is written, yet the event still fires: it must restart the wait, not count. */
    @Test
    void saveOffRestartsTheQueuedDelete() {
        MechanismState st = MechanismStateTest.sample();
        p.save(st);
        p.removeAfterSave(st.worldName, st.mechId, 0);
        for (int i = 1; i <= 10; i++) p.onWorldSaved(st.worldName, false, i * 6_000L);
        assertTrue(stateFile(st).exists());
        long off = 60_000;   // last save-off event
        p.onWorldSaved(st.worldName, true, off + 6_000);
        p.onWorldSaved(st.worldName, true, off + 6_000);
        p.onWorldSaved(st.worldName, true, off + 6_000);
        assertTrue(stateFile(st).exists(), "the tick bound restarted at the save-off event");
        p.onWorldSaved(st.worldName, true, off + MechanismPersistence.DELETE_AFTER_TICKS);
        assertFalse(stateFile(st).exists());
    }

    @Test
    void stopDeletesQueuedFiles() {
        MechanismState st = MechanismStateTest.sample();
        p.save(st);
        p.removeAfterSave(st.worldName, st.mechId, 0);
        p.deletePending();
        assertFalse(stateFile(st).exists());
    }

    @Test
    void aSaveCancelsTheQueuedDelete() {
        MechanismState st = MechanismStateTest.sample();
        p.save(st);
        p.removeAfterSave(st.worldName, st.mechId, 0);
        p.save(st);
        for (int i = 1; i <= 5; i++) p.onWorldSaved(st.worldName, true, i * 12_000L);
        assertTrue(stateFile(st).exists());
        assertTrue(p.hasMetadata(st.worldName, st.mechId));
    }

    /** The world-save path writes off-thread and skips an unchanged snapshot; it is ordered before later
     *  sync deletes, so it can never resurrect a removed file. */
    @Test
    void saveAsyncSkipsUnchangedAndStaysOrdered() throws Exception {
        MechanismState st = MechanismStateTest.sample();
        p.save(st);
        File f = stateFile(st);
        Files.writeString(f.toPath(), "marker: 1\n");
        p.saveAsync(st);                      // identical to the last write: skipped
        p.close();
        assertEquals("marker: 1\n", Files.readString(f.toPath()));

        MechanismPersistence q = new MechanismPersistence(dataFolder.resolve("q").toFile(), log);
        q.saveAsync(st);
        q.remove(st.worldName, st.mechId);    // queued behind the async write
        q.close();
        assertFalse(dataFolder.resolve("q").resolve("mechanisms").resolve(st.worldName)
            .resolve(st.mechId + ".yml").toFile().exists());
    }

    @Test
    void missingFileLoadsAsNull() {
        assertNull(p.load("world", UUID.randomUUID()));
        assertFalse(p.hasMetadata("world", UUID.randomUUID()));
    }

    @Test
    void startupSeedsIdsFromDiskAndSweepsLeftovers() throws Exception {
        MechanismState st = MechanismStateTest.sample();
        p.save(st);
        File dir = stateFile(st).getParentFile();
        File tmp = new File(dir, UUID.randomUUID() + ".yml.tmp");
        File chunks = new File(dir, "chunks.yml");
        Files.writeString(tmp.toPath(), "half-written");
        Files.writeString(chunks.toPath(), "old: index");

        MechanismPersistence fresh = new MechanismPersistence(dataFolder.toFile(), log);
        assertTrue(fresh.hasMetadata(st.worldName, st.mechId));
        assertFalse(tmp.exists(), "a crash-orphaned .yml.tmp is swept");
        assertFalse(chunks.exists(), "the retired chunk index is deleted");
        assertTrue(stateFile(st).exists());
    }

    @Test
    void removeDeletesTheFileAndTheShield() {
        MechanismState st = MechanismStateTest.sample();
        p.save(st);
        p.remove(st.worldName, st.mechId);
        assertFalse(stateFile(st).exists());
        assertFalse(p.hasMetadata(st.worldName, st.mechId));
        assertNull(p.load(st.worldName, st.mechId));
    }

    @Test
    void corruptFileIsNeverCulledAndKeepsTheShield() throws Exception {
        // 8d75a15: a corrupt read must not delete the file or drop the id, or the orphan sweep reaps the
        // mechanism's entities and its blocks are lost for good.
        MechanismState st = MechanismStateTest.sample();
        p.save(st);
        Files.writeString(stateFile(st).toPath(), "foo: bar\n");

        assertNull(p.load(st.worldName, st.mechId));
        assertNull(p.load(st.worldName, st.mechId));
        assertTrue(stateFile(st).exists());
        assertTrue(p.hasMetadata(st.worldName, st.mechId));
    }

    @Test
    void corruptFileIsNotReparsedUntilItChanges() throws Exception {
        MechanismState st = MechanismStateTest.sample();
        p.save(st);
        File f = stateFile(st);
        Files.writeString(f.toPath(), "foo: bar\n");
        long badMtime = f.lastModified();
        assertNull(p.load(st.worldName, st.mechId));

        // Repaired contents, but the mtime is unchanged: still served from the failed-read cache.
        Path other = dataFolder.resolve("other");
        new MechanismPersistence(other.toFile(), log).save(st);
        Files.copy(other.resolve("mechanisms").resolve(st.worldName).resolve(st.mechId + ".yml"),
            f.toPath(), StandardCopyOption.REPLACE_EXISTING);
        assertTrue(f.setLastModified(badMtime));
        assertNull(p.load(st.worldName, st.mechId), "an unchanged file is not re-parsed");

        // Touching the file (the operator's repair) makes the next load re-read it.
        assertTrue(f.setLastModified(badMtime + 5_000));
        MechanismState r = p.load(st.worldName, st.mechId);
        assertNotNull(r);
        assertEquals(st.mechId, r.mechId);
    }

    @Test
    void saveClearsAFailedRead() throws Exception {
        MechanismState st = MechanismStateTest.sample();
        p.save(st);
        File f = stateFile(st);
        Files.writeString(f.toPath(), "foo: bar\n");
        long badMtime = f.lastModified();
        assertNull(p.load(st.worldName, st.mechId));

        p.save(st);
        f.setLastModified(badMtime); // even with a colliding mtime, a fresh save is trusted
        assertNotNull(p.load(st.worldName, st.mechId));
    }

    @Test
    void invalidYamlIsTreatedAsCorrupt() throws Exception {
        MechanismState st = MechanismStateTest.sample();
        p.save(st);
        Files.writeString(stateFile(st).toPath(), "id: [unterminated\n");
        assertNull(p.load(st.worldName, st.mechId));
        assertTrue(stateFile(st).exists());
        assertTrue(p.hasMetadata(st.worldName, st.mechId));
    }

    @Test
    void aFailedDiskReadIsRetriedNotCached() throws Exception {
        // A read that throws IOException (here: the path is a directory) must not be remembered as corrupt —
        // the next load retries even though the mtime is unchanged.
        MechanismState st = MechanismStateTest.sample();
        p.save(st);
        File f = stateFile(st);
        Path saved = dataFolder.resolve("saved.yml");
        Files.move(f.toPath(), saved);
        assertTrue(f.mkdir());
        long mtime = f.lastModified();
        assertNull(p.load(st.worldName, st.mechId));

        assertTrue(f.delete());
        Files.move(saved, f.toPath());
        assertTrue(f.setLastModified(mtime));
        MechanismState r = p.load(st.worldName, st.mechId);
        assertNotNull(r, "a disk-read failure is retried on the next load");
        assertEquals(st.mechId, r.mechId);
    }
}
