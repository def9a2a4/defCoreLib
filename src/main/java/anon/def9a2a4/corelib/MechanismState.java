package anon.def9a2a4.corelib;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Serializable snapshot of an assembled mechanism, for crash-safe persistence and recovery.
 *
 * <p>Captures everything geometry-related needed to re-land the blocks (pivot, axis, live yaw, per-block
 * local offset, block data, collision, custom identity/state, storage, glue, config PDC) plus a
 * {@code vehicleUuid} recovery hint. Also captured: riding banner attachments ({@code banners}), a
 * reverted-bare-shaft flag ({@code wasBare}), the carried throttle level ({@code throttleLevel}), the
 * floor-head transit yaw ({@code floorYaw}), and the facing-resolved display-transform overrides
 * ({@code displayXf}). The <em>static</em> display/particle configs are NOT stored — they are re-derived
 * from {@code customType}+{@code customState} via the registry on recovery; only the neighbour-resolved
 * transform overrides ride along in {@code displayXf}. Written as one YAML file per mechanism by
 * {@link MechanismPersistence}.
 */
final class MechanismState {

    UUID mechId;
    String type;
    String worldName;
    double px, py, pz;              // block-centered pivot at save time
    float axisX, axisY, axisZ;      // rotation axis (Y for doors/carts, X/Z for drawbridges)
    float currentYaw;               // live rotation at save time — recovery snaps this to 90° to land
    float rideOffset;
    boolean ownsVehicle;
    boolean driven;                 // restored onto the mechanism so a driven (consumer-positioned) body keeps
                                    // skipping updateFromVehicle after recovery. NOT a recovery-mode switch —
                                    // recovery is always a live entity rebind (see MechanismRegistry.recoverOne).
    boolean blockFree;              // P7.B: a model-assembled mechanism (prefab ship) with no world blocks to
                                    // restore — teardown is destroy() semantics; some BlockRecs have null blockData.
    int entityCount;                // total persistent entities at save time (vehicle+parent+displays+banners+
                                    // colliders×2) — a recovery-completeness check (BlockShips' entity_count).
    @Nullable UUID vehicleUuid;     // recovery hint (owned marker ArmorStand, or external cart/ship vehicle)
    final List<BlockRec> blocks = new ArrayList<>();

    static final class BlockRec {
        @Nullable String blockData;     // null for a block-free / standalone display part (P7.B)
        float[] localTransform = new float[16]; // JOML Matrix4f column-major
        boolean colEnabled;
        float colSize, colOffX, colOffY, colOffZ;
        @Nullable String customType;
        @Nullable String customState;
        boolean spinReversed;
        boolean hasWallFacing;
        float wfX, wfY, wfZ;
        boolean hasFloorYaw;            // floor-head transit yaw (radians) — parity with wallFacing above
        float floorYaw;
        boolean ghost;
        boolean wasBare;                // a bare shaft reverted to an encased head for capture (re-bared on landing)
        int throttleLevel = -1;         // captured throttle 0-15 level (chunk-PDC, not tile) or -1 if not a throttle
        byte @Nullable [] storage;      // ItemStack[] serialized (Base64 in YAML)
        @Nullable String storageType;   // InventoryType name — preserves the container GUI shape on recovery
        @Nullable String storageTitle;  // GUI title for a named block-free (prefab cargo) storage part
        int @Nullable [] glueOffsets;
        byte @Nullable [] configPdc;    // tile PDC bytes (Base64 in YAML)
        @Nullable Map<String, Object> blockEntity; // BlockSnapshotProvider decorated state (YAML-safe map)
        // Riding banner attachments (vanilla flag/bed/block + BetterBanners large/huge), in list order —
        // one YAML-safe map per BannerAttachment: {item: Base64 bytes, face: String, xf: 14 doubles
        // (translation xyz, leftRot xyzw, scale xyz, rightRot xyzw), anchor: 3 doubles}. Reconstructed into
        // MechanismBlockData.banners in rebuildBlocks. Order MUST be preserved (pairs with banner_k displays).
        @Nullable List<Map<String, Object>> banners;
        // Facing-RESOLVED item-display transforms for resolver-driven blocks (dynamo head, boiler/burner
        // shell, pump body, dispenser eye, piston head, cart rail) — recovery rebuilds the STATIC configs
        // without a live block, so the resolver output is lost. One map per resolved display: {i: index,
        // xf: 14 doubles}. Only emitted for blocks whose type has a displayTransformResolver.
        @Nullable List<Map<String, Object>> displayXf;
    }

    void write(ConfigurationSection s) {
        s.set("id", mechId.toString());
        s.set("type", type);
        s.set("world", worldName);
        s.set("pivot", List.of(px, py, pz));
        s.set("axis", List.of((double) axisX, (double) axisY, (double) axisZ));
        s.set("yaw", (double) currentYaw);
        s.set("ride_offset", (double) rideOffset);
        s.set("owns_vehicle", ownsVehicle);
        s.set("driven", driven);
        if (blockFree) s.set("block_free", true);
        s.set("entity_count", entityCount);
        if (vehicleUuid != null) s.set("vehicle_uuid", vehicleUuid.toString());
        List<Object> blockList = new ArrayList<>(blocks.size());
        for (BlockRec b : blocks) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (b.blockData != null) m.put("data", b.blockData); // absent for a block-free / standalone part
            List<Double> lt = new ArrayList<>(16);
            for (float f : b.localTransform) lt.add((double) f);
            m.put("lt", lt);
            m.put("col", List.of(b.colEnabled, (double) b.colSize,
                (double) b.colOffX, (double) b.colOffY, (double) b.colOffZ));
            if (b.customType != null) m.put("ctype", b.customType);
            if (b.customState != null) m.put("cstate", b.customState);
            if (b.spinReversed) m.put("spin_rev", true);
            if (b.hasWallFacing) m.put("wall", List.of((double) b.wfX, (double) b.wfY, (double) b.wfZ));
            if (b.hasFloorYaw) m.put("floor_yaw", (double) b.floorYaw);
            if (b.ghost) m.put("ghost", true);
            if (b.wasBare) m.put("bare", true);
            if (b.throttleLevel >= 0) m.put("throttle", b.throttleLevel);
            if (b.storage != null) m.put("storage", Base64.getEncoder().encodeToString(b.storage));
            if (b.storageType != null) m.put("storage_type", b.storageType);
            if (b.storageTitle != null) m.put("storage_title", b.storageTitle);
            if (b.glueOffsets != null) {
                List<Integer> g = new ArrayList<>(b.glueOffsets.length);
                for (int i : b.glueOffsets) g.add(i);
                m.put("glue", g);
            }
            if (b.configPdc != null) m.put("pdc", Base64.getEncoder().encodeToString(b.configPdc));
            if (b.blockEntity != null && !b.blockEntity.isEmpty()) m.put("be", b.blockEntity);
            if (b.banners != null && !b.banners.isEmpty()) m.put("banners", b.banners);
            if (b.displayXf != null && !b.displayXf.isEmpty()) m.put("dxf", b.displayXf);
            blockList.add(m);
        }
        s.set("blocks", blockList);
    }

    static @Nullable MechanismState read(ConfigurationSection s) {
        try {
            MechanismState st = new MechanismState();
            st.mechId = UUID.fromString(s.getString("id"));
            st.type = s.getString("type");
            st.worldName = s.getString("world");
            List<Double> p = s.getDoubleList("pivot");
            st.px = p.get(0); st.py = p.get(1); st.pz = p.get(2);
            List<Double> ax = s.getDoubleList("axis");
            st.axisX = ax.get(0).floatValue(); st.axisY = ax.get(1).floatValue(); st.axisZ = ax.get(2).floatValue();
            st.currentYaw = (float) s.getDouble("yaw");
            st.rideOffset = (float) s.getDouble("ride_offset");
            st.ownsVehicle = s.getBoolean("owns_vehicle");
            st.driven = s.getBoolean("driven");
            st.blockFree = s.getBoolean("block_free");
            st.entityCount = s.getInt("entity_count");
            String vu = s.getString("vehicle_uuid");
            if (vu != null) st.vehicleUuid = UUID.fromString(vu);
            for (Map<?, ?> raw : s.getMapList("blocks")) {
                BlockRec b = new BlockRec();
                Object dataRaw = raw.get("data");
                b.blockData = dataRaw != null ? String.valueOf(dataRaw) : null; // null = block-free part
                List<?> lt = (List<?>) raw.get("lt");
                for (int i = 0; i < 16 && i < lt.size(); i++) b.localTransform[i] = ((Number) lt.get(i)).floatValue();
                List<?> col = (List<?>) raw.get("col");
                b.colEnabled = Boolean.TRUE.equals(col.get(0));
                b.colSize = ((Number) col.get(1)).floatValue();
                b.colOffX = ((Number) col.get(2)).floatValue();
                b.colOffY = ((Number) col.get(3)).floatValue();
                b.colOffZ = ((Number) col.get(4)).floatValue();
                b.customType = str(raw.get("ctype"));
                b.customState = str(raw.get("cstate"));
                b.spinReversed = Boolean.TRUE.equals(raw.get("spin_rev"));
                Object wall = raw.get("wall");
                if (wall instanceof List<?> wl && wl.size() >= 3) {
                    b.hasWallFacing = true;
                    b.wfX = ((Number) wl.get(0)).floatValue();
                    b.wfY = ((Number) wl.get(1)).floatValue();
                    b.wfZ = ((Number) wl.get(2)).floatValue();
                }
                if (raw.get("floor_yaw") instanceof Number fy) {
                    b.hasFloorYaw = true;
                    b.floorYaw = fy.floatValue();
                }
                b.ghost = Boolean.TRUE.equals(raw.get("ghost"));
                b.wasBare = Boolean.TRUE.equals(raw.get("bare"));
                b.throttleLevel = raw.get("throttle") instanceof Number tn ? tn.intValue() : -1;
                Object storage = raw.get("storage");
                if (storage instanceof String ss) b.storage = Base64.getDecoder().decode(ss);
                b.storageType = str(raw.get("storage_type"));
                b.storageTitle = str(raw.get("storage_title"));
                Object glue = raw.get("glue");
                if (glue instanceof List<?> gl) {
                    b.glueOffsets = new int[gl.size()];
                    for (int i = 0; i < gl.size(); i++) b.glueOffsets[i] = ((Number) gl.get(i)).intValue();
                }
                Object pdc = raw.get("pdc");
                if (pdc instanceof String ps) b.configPdc = Base64.getDecoder().decode(ps);
                Object be = raw.get("be");
                if (be instanceof Map<?, ?> beMap) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : beMap.entrySet()) m.put(String.valueOf(e.getKey()), e.getValue());
                    b.blockEntity = m;
                }
                Object bn = raw.get("banners");
                if (bn instanceof List<?> bnList) {
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (Object el : bnList) {
                        if (!(el instanceof Map<?, ?> em)) continue;
                        Map<String, Object> m = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> e : em.entrySet()) m.put(String.valueOf(e.getKey()), e.getValue());
                        out.add(m);
                    }
                    if (!out.isEmpty()) b.banners = out;
                }
                Object dxf = raw.get("dxf");
                if (dxf instanceof List<?> dxfList) {
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (Object el : dxfList) {
                        if (!(el instanceof Map<?, ?> em)) continue;
                        Map<String, Object> m = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> e : em.entrySet()) m.put(String.valueOf(e.getKey()), e.getValue());
                        out.add(m);
                    }
                    if (!out.isEmpty()) b.displayXf = out;
                }
                st.blocks.add(b);
            }
            return st;
        } catch (Exception e) {
            return null; // corrupt/partial file — recovery skips it (caller logs)
        }
    }

    private static @Nullable String str(@Nullable Object o) { return o == null ? null : String.valueOf(o); }
}
