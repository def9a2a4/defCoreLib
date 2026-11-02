package anon.def9a2a4.corelib;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Carried frames/paintings survive the state file, and a bad entry can't take the file down with it. */
class CarriedEntityTest {

    static AttachedEntities.CarriedEntity frame() {
        return new AttachedEntities.CarriedEntity(AttachedEntities.KIND_FRAME, UUID.randomUUID(),
            new byte[] {1, 2, 3, -1}, new byte[] {4, 5}, 0.25, -0.5, 0.46875, 0, 0, 1, 3, true, 0, 0);
    }

    @Test
    void mapRoundTrip() {
        AttachedEntities.CarriedEntity a = frame();
        AttachedEntities.CarriedEntity b = AttachedEntities.CarriedEntity.fromMap(a.toMap());
        assertEquals(a.kind(), b.kind());
        assertEquals(a.uuid(), b.uuid());
        assertArrayEquals(a.nbt(), b.nbt());
        assertArrayEquals(a.drops(), b.drops());
        assertEquals(a.ox(), b.ox()); assertEquals(a.oy(), b.oy()); assertEquals(a.oz(), b.oz());
        assertEquals(1, b.fz());
        assertEquals(3, b.rotation());
        assertTrue(b.fixed());
    }

    @Test
    void paintingKeepsItsSize() {
        AttachedEntities.CarriedEntity p = new AttachedEntities.CarriedEntity(AttachedEntities.KIND_PAINTING,
            UUID.randomUUID(), new byte[] {9}, null, 0, 0.5, 0, 1, 0, 0, 0, false, 4, 2);
        AttachedEntities.CarriedEntity r = AttachedEntities.CarriedEntity.fromMap(p.toMap());
        assertEquals(4, r.artW());
        assertEquals(2, r.artH());
        assertNull(r.drops());
    }

    @Test
    void survivesTheStateFileAndABadEntryDoesNotCorruptIt() throws Exception {
        MechanismState st = MechanismStateTest.sample();
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("kind", "item_frame");
        bad.put("uuid", "not-a-uuid");
        st.blocks.get(0).attached = List.of(frame().toMap(), bad);

        MechanismState r = MechanismStateTest.roundTrip(st);
        assertNotNull(r, "a malformed carried entry must not make the whole file read as corrupt");
        List<Map<String, Object>> back = r.blocks.get(0).attached;
        assertEquals(2, back.size());
        assertDoesNotThrow(() -> AttachedEntities.CarriedEntity.fromMap(back.get(0)));
        assertThrows(RuntimeException.class, () -> AttachedEntities.CarriedEntity.fromMap(back.get(1)));
        assertEquals("not-a-uuid", back.get(1).get("uuid"), "the bad entry is kept verbatim");
    }

    @Test
    void carriedTagRoundTrips() {
        UUID id = UUID.randomUUID();
        String tag = AttachedEntities.carriedTag(id, 12, -0.125, 0.5, 0.0);
        assertTrue(tag.startsWith(AttachedEntities.CARRIED_TAG));
        assertFalse(tag.startsWith("corelib:mech:"), "recovery must never mistake it for a mechanism role");
    }
}
