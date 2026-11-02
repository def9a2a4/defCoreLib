package anon.def9a2a4.corelib;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Round-trips through real YAML text, as recovery reads it back from disk. */
class MechanismStateTest {

    static MechanismState sample() {
        MechanismState st = new MechanismState();
        st.mechId = UUID.randomUUID();
        st.type = "mech:chain_hoist";
        st.worldName = "world";
        st.px = 10.5; st.py = 64; st.pz = -3.5;
        st.axisX = 0; st.axisY = 1; st.axisZ = 0;
        st.currentYaw = 90f;
        st.rideOffset = 0.25f;
        st.ownsVehicle = true;
        st.driven = true;
        st.blockFree = true;
        st.entityCount = 7;
        st.vehicleUuid = UUID.randomUUID();
        st.hasPivotDelta = true;
        st.dpx = 0.5; st.dpy = -1; st.dpz = 0;

        MechanismState.BlockRec b = new MechanismState.BlockRec();
        b.blockData = "minecraft:chain[axis=y]";
        for (int i = 0; i < 16; i++) b.localTransform[i] = i * 0.5f;
        b.colEnabled = true; b.colSize = 1f; b.colOffX = 0.1f; b.colOffY = -0.5f; b.colOffZ = 0.2f;
        b.customType = "mech:propeller";
        b.customState = "idle_ccw_x";
        b.spinReversed = true;
        b.hasWallFacing = true; b.wfX = 1; b.wfY = 0; b.wfZ = 0;
        b.hasFloorYaw = true; b.floorYaw = 1.5f;
        b.ghost = true;
        b.wasBare = true;
        b.throttleLevel = 12;
        b.storage = new byte[] {1, 2, 3, 127, -128};
        b.storageType = "CHEST";
        b.storageTitle = "Cargo";
        b.storageTitleJson = "{\"text\":\"Cargo\",\"color\":\"red\"}";
        b.glueOffsets = new int[] {1, -2, 3};
        b.configPdc = new byte[] {9, 8, 7};
        Map<String, Object> be = new LinkedHashMap<>();
        be.put("lines", List.of("a", "b"));
        b.blockEntity = be;
        Map<String, Object> banner = new LinkedHashMap<>();
        banner.put("face", "NORTH");
        b.banners = List.of(banner);
        Map<String, Object> dxf = new LinkedHashMap<>();
        dxf.put("i", 2);
        b.displayXf = List.of(dxf);
        st.blocks.add(b);

        MechanismState.BlockRec free = new MechanismState.BlockRec(); // block-free part: no data
        st.blocks.add(free);
        return st;
    }

    static MechanismState roundTrip(MechanismState st) throws Exception {
        YamlConfiguration out = new YamlConfiguration();
        st.write(out);
        YamlConfiguration in = new YamlConfiguration();
        in.loadFromString(out.saveToString());
        return MechanismState.read(in);
    }

    @Test
    void everyFieldSurvivesARoundTrip() throws Exception {
        MechanismState st = sample();
        MechanismState r = roundTrip(st);
        assertNotNull(r);
        assertEquals(st.mechId, r.mechId);
        assertEquals(st.type, r.type);
        assertEquals(st.worldName, r.worldName);
        assertEquals(st.px, r.px); assertEquals(st.py, r.py); assertEquals(st.pz, r.pz);
        assertEquals(st.axisY, r.axisY);
        assertEquals(st.currentYaw, r.currentYaw);
        assertEquals(st.rideOffset, r.rideOffset);
        assertTrue(r.ownsVehicle);
        assertTrue(r.driven);
        assertTrue(r.blockFree);
        assertEquals(7, r.entityCount);
        assertEquals(st.vehicleUuid, r.vehicleUuid);
        assertTrue(r.hasPivotDelta);
        assertEquals(0.5, r.dpx); assertEquals(-1, r.dpy); assertEquals(0, r.dpz);

        assertEquals(2, r.blocks.size());
        MechanismState.BlockRec a = st.blocks.get(0), b = r.blocks.get(0);
        assertEquals(a.blockData, b.blockData);
        assertArrayEquals(a.localTransform, b.localTransform);
        assertTrue(b.colEnabled);
        assertEquals(a.colSize, b.colSize); assertEquals(a.colOffY, b.colOffY); assertEquals(a.colOffZ, b.colOffZ);
        assertEquals(a.customType, b.customType);
        assertEquals(a.customState, b.customState);
        assertTrue(b.spinReversed);
        assertTrue(b.hasWallFacing); assertEquals(1f, b.wfX);
        assertTrue(b.hasFloorYaw); assertEquals(1.5f, b.floorYaw);
        assertTrue(b.ghost);
        assertTrue(b.wasBare);
        assertEquals(12, b.throttleLevel);
        assertArrayEquals(a.storage, b.storage);
        assertEquals("CHEST", b.storageType);
        assertEquals("Cargo", b.storageTitle);
        assertEquals(a.storageTitleJson, b.storageTitleJson);
        assertArrayEquals(a.glueOffsets, b.glueOffsets);
        assertArrayEquals(a.configPdc, b.configPdc);
        assertEquals(a.blockEntity, b.blockEntity);
        assertEquals(a.banners, b.banners);
        assertEquals(2, ((Number) b.displayXf.get(0).get("i")).intValue());

        MechanismState.BlockRec free = r.blocks.get(1);
        assertNull(free.blockData);
        assertEquals(-1, free.throttleLevel);
        assertFalse(free.ghost);
        assertNull(free.storage);
        assertNull(free.glueOffsets);
    }

    @Test
    void blockFreeIsWrittenEvenWhenFalse() {
        // A dropped key reads back as false, which would paste a prefab hull into the world.
        MechanismState st = sample();
        st.blockFree = false;
        YamlConfiguration out = new YamlConfiguration();
        st.write(out);
        assertTrue(out.contains("block_free"));
    }

    @Test
    void fileWithoutPivotDeltaReadsAsAbsent() throws Exception {
        MechanismState st = sample();
        st.hasPivotDelta = false;
        MechanismState r = roundTrip(st);
        assertNotNull(r);
        assertFalse(r.hasPivotDelta);
    }

    @Test
    void corruptStateReadsAsNull() throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString("foo: bar\n");
        assertNull(MechanismState.read(y));

        y = new YamlConfiguration();
        y.loadFromString("id: not-a-uuid\n");
        assertNull(MechanismState.read(y));
    }

    @Test
    void recoveryHalfCoversTheFarthestPartPlusSlack() {
        MechanismState st = new MechanismState();
        MechanismState.BlockRec b = new MechanismState.BlockRec();
        b.localTransform[12] = 3; b.localTransform[13] = 4; b.localTransform[14] = 0; // |offset| = 5
        st.blocks.add(b);
        assertEquals(21.0, st.recoveryHalf(), 1e-9);
        assertEquals(3, st.recoveryChunkRadius()); // ceil(21/16) + 1
    }
}
