package anon.def9a2a4.corelib;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

    /** A disassembly unshields the id at once but keeps the file until the world has saved twice (the event
     *  fires before the chunks are written), so a crash in between can still recover the ship. */
    @Test
    void removeAfterSaveDeletesOnTheSecondWorldSave() {
        MechanismState st = MechanismStateTest.sample();
        p.save(st);
        p.removeAfterSave(st.worldName, st.mechId);
        assertFalse(p.hasMetadata(st.worldName, st.mechId));
        p.onWorldSaved("some_other_world");
        p.onWorldSaved(st.worldName);
        assertTrue(stateFile(st).exists());
        p.onWorldSaved(st.worldName);
        assertFalse(stateFile(st).exists());
    }

    @Test
    void aSaveCancelsTheQueuedDelete() {
        MechanismState st = MechanismStateTest.sample();
        p.save(st);
        p.removeAfterSave(st.worldName, st.mechId);
        p.save(st);
        p.onWorldSaved(st.worldName);
        p.onWorldSaved(st.worldName);
        assertTrue(stateFile(st).exists());
        assertTrue(p.hasMetadata(st.worldName, st.mechId));
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
