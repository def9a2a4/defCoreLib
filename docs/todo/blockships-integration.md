# BlockShips Integration Plan

## Context

DefCoreLib's mechanism system (Mechanism, BasicMechanism, MechanismRegistry) already implements the core patterns BlockShips uses: passenger entity chains (vehicle → parent BlockDisplay → child displays), shulker collision proxies on marker ArmorStands, JOML Matrix4f transforms with delta-yaw rotation, and three-tier disassembly. The hard math works.

However, the mechanism system was built around CustomHeadBlock as its only block type. BlockShips uses vanilla blocks, custom textures via skull profiles, per-ship persistence, incremental entity recovery across chunk loads, health/damage, multi-seat support, and buoyancy physics. Migrating BlockShips to use DefCoreLib as its core means DefCoreLib must support all of these without requiring CustomHeadBlock.

This plan has four phases, each shippable independently. After Phase 4, BlockShips can replace its internal entity management (ShipInstance display/collider spawning, transform math, entity tagging) with DefCoreLib's Mechanism API.

---

## Phase 1: Decouple from CustomHeadBlock

**Goal:** MechanismRegistry and BasicMechanism work with arbitrary block types, not just custom head blocks.

### Problem

Assembly and disassembly are tightly coupled to CustomBlockRegistry/CustomHeadBlock:

**MechanismRegistry.assembleCore():**
- Line 105: `registry.getTypeFromBlock(block)` — detect custom block
- Lines 107-118: `chb.fullId()`, `resolveDisplayEntities()`, `resolveParticles()`, `chb.storage()`, `registry.loadInventoryFromPDC()` — snapshot custom state
- Line 119: `registry.onBlockRemoved(block, chb)` — cleanup
- Lines 164-170: `registry.getType()`, `chb.resolveTexture()` — spawn primary display

**BasicMechanism.setBlockState():**
- Lines 171-185: `registry.getType()`, `type.resolveTexture()`, `type.resolveParticles()`, `type.resolveDisplayEntities()`

**BasicMechanism.placeBlock() (disassembly):**
- Lines 241-252: `registry.getType()`, `registry.markBlock()`, `registry.readPower()`, `registry.applyConfig()`, `registry.restoreBlock()`

**BasicMechanism.dropBlockAsItem():**
- Line 258: `registry.getType()`, `type.createItem()`

### Solution: Extract `BlockSnapshotProvider` interface

```java
public interface BlockSnapshotProvider {
    /** Snapshot a world block for mechanism assembly. Returns null to use default vanilla snapshot. */
    @Nullable BlockSnapshot snapshot(Block block);

    /** Restore a block to the world during disassembly. */
    void restore(Block target, BlockSnapshot snapshot, float snappedYaw);

    /** Create an item drop for a block that can't be placed (solid block collision). */
    ItemStack createDrop(BlockSnapshot snapshot);

    /** Spawn the primary display entity for this block. Returns null to use default BlockDisplay. */
    @Nullable Display spawnPrimaryDisplay(Location loc, BlockSnapshot snapshot,
                                          UUID mechId, int blockIdx);

    /** Update displays when block state changes in-place. */
    void updateState(Display primaryDisplay, BlockSnapshot snapshot, String newState);
}
```

`BlockSnapshot` extends `MechanismBlockData` or replaces it — carries everything needed for both rendering and restoration, without referencing CustomHeadBlock.

**Default implementation:** `CustomBlockSnapshotProvider` wraps the existing CustomBlockRegistry calls. This is what DoorDemo and MinecartShipManager continue to use.

**BlockShips implementation:** `ShipBlockSnapshotProvider` uses vanilla BlockData for displays, no PDC, no custom state. Restoration places vanilla blocks. Drop creates vanilla items (or ship wheel for the vehicle block).

### Changes

| File | Change |
|------|--------|
| `MechanismRegistry.java` | Add `BlockSnapshotProvider` parameter to `assembleMechanism()` overloads. Default to `CustomBlockSnapshotProvider` (backward compat). Replace all `registry.getType*()` calls in `assembleCore()` with provider calls. |
| `BasicMechanism.java` | Store `BlockSnapshotProvider` instead of `CustomBlockRegistry`. Replace `registry.*` calls in `placeBlock()`, `dropBlockAsItem()`, `setBlockState()` with provider calls. |
| `MechanismBlockData.java` | Add optional `Object providerData` field — provider-specific opaque state (CustomHeadBlock stores customTypeId/customState; BlockShips stores model part index). |
| `Mechanism.java` | No change. Consumer-facing interface stays clean. |
| `DoorDemo.java` | No change (uses default provider). |
| `MinecartShipManager.java` | No change (uses default provider). |

### Backward compatibility

Existing two-arg `assembleMechanism()` overloads keep working — they internally use `CustomBlockSnapshotProvider`. New overloads add `BlockSnapshotProvider` parameter.

---

## Phase 2: Persistence & Entity Recovery

**Goal:** Mechanisms survive server restarts and chunk unload/reload cycles.

### Current state

- `MechanismSerializer` interface exists but is never called for save/restore
- Both demo consumers pass `null` serializer
- `cleanupOrphanedEntities()` removes stale entities — but never tries to recover them
- Entities are `setPersistent(true)` and tagged, so they survive restarts as orphans

### BlockShips' proven pattern (to adopt)

BlockShips uses per-world YAML with a chunk index:
```
worlds/
  world_name/
    chunks.yml          # "x,z" → [uuid1, uuid2, ...]
    mechanisms/
      {uuid}.yml        # Position, yaw, type, block list, consumer data
```

Key design decisions from BlockShips:
1. **Snapshot on main thread, write async** — single-threaded `ExecutorService` serializes I/O
2. **Entity recovery is incremental** — entities may arrive across multiple chunk loads
3. **Expected entity count** stored in metadata — recovery is "complete" when count matches
4. **Chunk index** enables O(1) lookup: "which mechanisms overlap this chunk?"

### Implementation

#### A. `MechanismPersistence` class (new)

```java
public class MechanismPersistence {
    private final JavaPlugin plugin;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, Map<String, Set<UUID>>> chunkIndices; // world → "x,z" → mechIds

    // Save: snapshot YAML on main thread, write async
    void saveMechanism(BasicMechanism mech) { ... }
    void saveMechanismAsync(BasicMechanism mech) { ... }

    // Load: read async, construct on main thread
    CompletableFuture<MechanismState> loadMechanismAsync(String world, UUID mechId) { ... }

    // Chunk index
    Set<UUID> getMechanismsInChunk(String world, int chunkX, int chunkZ) { ... }
    void updateChunkIndex(String world, UUID mechId, Set<String> oldChunks, Set<String> newChunks) { ... }
    void saveChunkIndices() { ... }
    void loadChunkIndices() { ... }

    void shutdown() { ioExecutor.shutdown(); }
}
```

#### B. `MechanismState` record (serialized form)

```java
record MechanismState(
    UUID id,
    String type,
    String worldName,
    double x, double y, double z,
    float yaw,
    float assemblyYaw,
    float rideOffset,
    boolean ownsVehicle,
    List<BlockSnapshot> blocks,     // Serialized block data
    int expectedEntityCount,        // For recovery validation
    @Nullable ConfigurationSection consumerData  // From MechanismSerializer.save()
) {}
```

#### C. YAML format

```yaml
id: "uuid"
type: "demo:door"
world: "world"
position: {x: 100.5, y: 64.0, z: -200.5}
yaw: 90.0
assembly_yaw: 0.0
ride_offset: 1.975
owns_vehicle: true
entity_count: 15
blocks:
  - material: OAK_PLANKS
    local_transform: [1,0,0,1, 0,1,0,0, 0,0,1,0, 0,0,0,1]  # 16 floats
    has_collision: true
    custom_type: null
    custom_state: null
    storage: null                   # or Base64 serialized items (BlockShips pattern)
  - material: PLAYER_HEAD
    custom_type: "demo:door_controller"
    custom_state: "open"
    # ... etc
consumer:
  # MechanismSerializer.save() writes here
  door_state: "opening"
  target_yaw: 90
```

#### D. Entity recovery flow

Adopt BlockShips' incremental recovery pattern:

1. **On chunk load** (`EntitiesLoadEvent`):
   - Query chunk index for mechanisms in this chunk
   - For each mechanism not already active:
     - Load metadata async
     - On completion (main thread): create `RecoveringMechanism` holder
     - Scan chunk entities for matching tags
     - Track found vs expected entity count

2. **`RecoveringMechanism`** tracks partial state:
   ```java
   class RecoveringMechanism {
       final MechanismState state;
       Entity vehicle;
       BlockDisplay parent;
       final Map<Integer, List<Display>> displays = new HashMap<>();
       final Map<Integer, ColliderPair> colliders = new HashMap<>();
       int foundCount = 0;
       int expectedCount;

       boolean isComplete() { return foundCount >= expectedCount; }
   }
   ```

3. **On recovery complete:**
   - Construct `BasicMechanism` from recovered entities + state
   - Call `MechanismSerializer.onRecoveryComplete(mech)`
   - Register in `activeMechanisms`
   - Resume ticking

4. **Fallback:** If entities missing after 30 seconds (all relevant chunks loaded), either:
   - Re-spawn missing entities from state (if metadata available)
   - Or disassemble (restore blocks) and log warning

#### E. Chunk overlap tracking

Each mechanism computes which chunks its blocks overlap:

```java
Set<String> computeOverlappingChunks(Mechanism mech) {
    Set<String> chunks = new HashSet<>();
    Matrix4f rot = new Matrix4f().rotateY(-mech.getCurrentYaw());
    for (int i = 0; i < mech.blockCount(); i++) {
        Vector3f worldOff = rot.transformPosition(
            mech.getBlock(i).localTransform.getTranslation(new Vector3f()), new Vector3f());
        int cx = ((int)Math.floor(mech.pivot().getX() + worldOff.x)) >> 4;
        int cz = ((int)Math.floor(mech.pivot().getZ() + worldOff.z)) >> 4;
        chunks.add(cx + "," + cz);
    }
    return chunks;
}
```

On mechanism rotation/movement that crosses chunk boundaries: update chunk index.

#### F. Integration with existing code

| File | Change |
|------|--------|
| `MechanismRegistry.java` | Add `MechanismPersistence` field. After assembly, call `persistence.saveMechanism()`. On `onMechanismRemoved()`, call `persistence.removeMechanism()`. Replace `cleanupOrphanedEntities()` with recovery logic. |
| `BasicMechanism.java` | After `rotate()`/`move()`, check chunk boundary crossings. |
| `CoreLibPlugin.java` | In `onEntitiesLoad()`: trigger recovery instead of just cleanup. |
| `MechanismSerializer.java` | No interface change — `save()` and `restore()` are already defined. |

#### G. Save triggers

Following BlockShips:
- **On disassemble:** Remove metadata file + chunk index entry
- **On shutdown:** Save all active mechanisms synchronously
- **Periodic:** Every 5 minutes, save dirty mechanisms async (for crash safety)
- **On chunk unload:** If all of a mechanism's chunks are unloading, save + suspend

---

## Phase 3: Health, Damage & Seats

**Goal:** Mechanisms can take damage, regenerate health, and have mountable seats.

### Health system

BlockShips tracks health on the ArmorStand vehicle:
```java
vehicle.getAttribute(GENERIC_MAX_HEALTH).setBaseValue(maxHealth);
```

DefCoreLib should support this as an optional feature (not all mechanisms need health — doors don't).

#### `MechanismHealth` (new, optional component)

```java
public class MechanismHealth {
    private final double maxHealth;
    private final double regenPerTick;       // healthRegenPerSecond / 20
    private double currentHealth;

    void damage(double amount) { ... }
    void tick() { currentHealth = Math.min(currentHealth + regenPerTick, maxHealth); }
    boolean isDead() { return currentHealth <= 0; }
    double fraction() { return currentHealth / maxHealth; }
}
```

Wired into Mechanism interface as optional:
```java
@Nullable MechanismHealth health();
```

#### Damage routing

BlockShips routes damage from shulker hits to the vehicle. DefCoreLib already cancels damage on `corelib:mech:*` entities in CoreLibPlugin. Change to:

1. On `EntityDamageEvent` for a mechanism shulker:
   - If mechanism has health: apply damage, check death
   - If no health: cancel (current behavior)
2. On death: call consumer callback (BlockShips drops ship wheel; door does nothing)

#### `MechanismDeathHandler` callback

```java
@FunctionalInterface
public interface MechanismDeathHandler {
    void onDeath(Mechanism mech, @Nullable Entity attacker);
}
```

Passed to `assembleMechanism()` as optional parameter. Called when health reaches zero. Default: `disassemble()`.

### Seat system

BlockShips uses shulkers as seat mount points. Players ride the shulker, which is positioned at the seat location.

#### `SeatConfig` record

```java
public record SeatConfig(
    Vector3f offset,        // Relative to mechanism pivot
    int blockIndex,         // Which block this seat belongs to
    boolean isDriver        // Driver receives steering input
) {}
```

#### Integration

```java
// In assembleMechanism():
List<SeatConfig> seats = ...;  // Provided by consumer
// For each seat: spawn Shulker at offset position (like colliders but rideable)
// Mount player via seatShulker.addPassenger(player)
```

Seat shulkers are managed like colliders but with `setCollidable(false)` (no physics — just mount points). Repositioned on rotate/move like colliders.

#### Mechanism interface additions

```java
int seatCount();
boolean occupySeat(int seatIndex, Player player);
void freeSeat(int seatIndex);
@Nullable Player getSeatOccupant(int seatIndex);
boolean isDriverSeat(int seatIndex);
```

### Changes

| File | Change |
|------|--------|
| `Mechanism.java` | Add `health()`, seat methods |
| `BasicMechanism.java` | Add `MechanismHealth` field (nullable), `List<SeatPair>` for seats, seat repositioning in `rotate()` |
| `MechanismRegistry.java` | New assembly overload accepting `MechanismConfig` (health, seats, death handler). Damage routing in tick or via event delegation. |
| `CoreLibPlugin.java` | Change shulker damage handler: route to mechanism health if present |

---

## Phase 4: Performance & BlockShips Migration API

**Goal:** Production-ready performance for 50+ concurrent mechanisms, and a clean migration surface for BlockShips.

### Performance (from BlockShips patterns)

#### A. Object pooling in tick loop

BlockShips pre-allocates ~15 reusable Matrix4f/Vector3f fields. DefCoreLib's tick loop allocates new matrices per block per tick.

**Current (MechanismRegistry.tickMechanisms line 329):**
```java
Matrix4f base = new Matrix4f(mech.currentTransform())  // allocation
    .mul(mb.localTransform)
    .mul(BasicMechanism.transformToMatrix(dec.transform()));  // allocation inside
```

**Fix:** Pre-allocate work matrices on MechanismRegistry:
```java
private final Matrix4f workBase = new Matrix4f();
private final Matrix4f workDec = new Matrix4f();
private final Vector3f workVec = new Vector3f();
```

Also in BasicMechanism.rotate(): pre-allocate `workRot`, `workDm`, `workBdm`, `workOff`.

#### B. Throttled integrity checks

BlockShips checks passenger chain integrity every 20 ticks, not every tick. Add to MechanismRegistry tick:

```java
if (currentTick % 20 == 0) {
    for (BasicMechanism mech : activeMechanisms.values()) {
        mech.validatePassengerChain(); // re-mount if broken
    }
}
```

#### C. Configurable interpolation duration

BlockShips uses 1-4 tick interpolation. DefCoreLib hardcodes 2. Make configurable per mechanism (higher = smoother but laggier for fast movement).

#### D. View range optimization

BlockShips uses 64f view range. For large fleets, consider distance-based LOD (reduce extra display count for distant mechanisms).

### BlockShips Migration API

The concrete surface BlockShips code would call:

```java
// In BlockShips' ShipInstance constructor (replaces ~200 lines of entity spawning):
Mechanism mech = CoreLibPlugin.getInstance().getMechanismRegistry()
    .assembleMechanism(MechanismConfig.builder()
        .type("blockships:" + shipType)
        .blocks(scannedBlocks)
        .pivot(spawnLocation)
        .vehicle(armorStandVehicle)
        .rideOffset(ARMORSTAND_RIDE_OFFSET)
        .snapshotProvider(new ShipBlockSnapshotProvider(model))
        .serializer(new ShipSerializer())
        .health(model.maxHealth(), model.healthRegenPerSecond())
        .deathHandler((m, attacker) -> destroyAndDropWheel(m))
        .seats(model.seats().stream()
            .map(s -> new SeatConfig(s.offset(), s.blockIndex(), s.isDriver()))
            .toList())
        .interpolationDuration(2)
        .build());

// In ShipInstance.tick() (replaces ~100 lines of transform math):
// Physics computes new position + yaw
mech.move(newPosition, physicsYaw);
// Health regen is automatic
// Display transforms are automatic
// Collider repositioning is automatic

// In DisplayShip chunk handler (replaces ~150 lines of recovery):
// Automatic — MechanismRegistry handles recovery on EntitiesLoadEvent

// In ShipInstance.destroyAndDropItem() (replaces ~50 lines):
mech.disassemble();  // or mech.destroy() if blocks shouldn't restore
```

#### What stays in BlockShips (not migrated to DefCoreLib)

| System | Why it stays |
|--------|-------------|
| **ShipPhysics** | Velocity, acceleration, buoyancy, airship controls — domain-specific, not generalizable |
| **ShipCollision** | Force accumulation, entity push — tightly coupled to physics |
| **ShipConfig** | Per-ship-type tuning (speed, drag, turn rate) — consumer config |
| **ShipCustomization** | Wood type, balloon color, banner — visual customization |
| **ShipSteeringListener** | ProtocolLib key detection — domain-specific |
| **BlockStructureScanner** | Weight calculation, sail detection — domain-specific |
| **ShipWheelManager** | Fuel tracking — domain-specific |

#### What migrates to DefCoreLib

| BlockShips Code | DefCoreLib Replacement |
|----------------|----------------------|
| ShipInstance entity spawning (~200 lines) | `MechanismRegistry.assembleMechanism()` |
| ShipInstance.updateDisplayTransforms() (~80 lines) | `BasicMechanism.rotate()` |
| ShipInstance.updateCollisionPositions() (~60 lines) | `BasicMechanism.rotate()` collider section |
| DisplayShip chunk recovery (~150 lines) | `MechanismPersistence` + recovery flow |
| ShipWorldData (~250 lines) | `MechanismPersistence` |
| ShipPersistence (~410 lines) | `MechanismPersistence` |
| ShipTags (~183 lines) | Entity tag pattern in MechanismRegistry |
| CollisionBox (~32 lines) | `ColliderPair` |
| DisplayInstance (~18 lines) | Display lists in BasicMechanism |
| Health tracking in ShipInstance (~30 lines) | `MechanismHealth` |
| Seat management in ShipInstance (~40 lines) | Seat system in BasicMechanism |
| ShipTeleportCompat | `TeleportCompat` (already exists) |

**Estimated lines eliminated from BlockShips: ~1,400**

---

## Implementation Order

### Phase 1 (Decouple) — ~2 sessions

1. Define `BlockSnapshotProvider` interface and `BlockSnapshot` class
2. Implement `CustomBlockSnapshotProvider` wrapping existing registry calls
3. Add provider parameter to `assembleMechanism()` overloads (backward-compat defaults)
4. Refactor `BasicMechanism` to use provider instead of direct registry calls
5. Refactor `MechanismRegistry.assembleCore()` to use provider
6. Test: DoorDemo and MinecartShipManager work identically (regression)
7. Test: Create a `VanillaBlockSnapshotProvider` that uses only BlockDisplay — assemble a vanilla mechanism without any CustomHeadBlock dependency

### Phase 2 (Persistence) — ~3 sessions

1. Implement `MechanismPersistence` with YAML save/load + async I/O
2. Implement `MechanismState` serialization (blocks, transforms, consumer data)
3. Add inventory serialization (Base64 ItemStack[], following BlockShips pattern)
4. Implement chunk index (world → chunk → mechanism UUIDs)
5. Implement `RecoveringMechanism` for incremental entity recovery
6. Wire into `MechanismRegistry`: save on assembly, remove on disassemble, recover on chunk load
7. Replace `cleanupOrphanedEntities()` with recovery-then-cleanup
8. Add periodic save (every 5 minutes) + save-on-shutdown
9. Test: assemble mechanism, restart server, mechanism recovers
10. Test: mechanism spanning 2 chunks, unload one, reload — mechanism recovers
11. Test: mechanism with container inventory — inventory persists

### Phase 3 (Health & Seats) — ~2 sessions

1. Implement `MechanismHealth` component
2. Add health to `Mechanism` interface (nullable)
3. Wire damage routing: shulker hit → mechanism health
4. Add `MechanismDeathHandler` callback
5. Add health regen in tick loop
6. Implement seat shulkers (spawn, position, mount/dismount)
7. Add seat methods to `Mechanism` interface
8. Test: assemble mechanism with health, damage it, watch regen, kill it
9. Test: mount player on seat, rotate mechanism, player follows

### Phase 4 (Performance & API) — ~2 sessions

1. Object pooling in BasicMechanism.rotate() and MechanismRegistry.tickMechanisms()
2. Throttled passenger chain validation (every 20 ticks)
3. Configurable interpolation duration
4. Create `MechanismConfig` builder for clean assembly API
5. Write `ShipBlockSnapshotProvider` stub (BlockShips side)
6. Integration test: assemble 50 mechanisms, verify tick time
7. Document migration guide for BlockShips

---

## Known Bugs to Fix (Pre-Phase 1)

These should be fixed before starting integration work:

1. **Collider initial position rounding** — `MechanismRegistry.assembleCore()` line 195: carrier spawns at `pivot + localTransform.getTranslation()` without rounding. Should `Math.round()` XZ offsets for grid alignment.

2. **`MINECART_RIDE_OFFSET = 0f`** — needs empirical tuning in-game. Displays may be mispositioned vertically on minecarts.

3. **No vehicle validity check at assembly** — `assembleMechanism(existingVehicle)` doesn't verify vehicle is valid/alive. Dead entity silently produces broken mechanism.

4. **Particle ticking not implemented** — `TODO` at MechanismRegistry line 340. `ParticleConfig` is stored but never spawned.

5. **Dead code** — `perpendicularNeighbors()` in RotationNetwork.java (lines 397-415) is never called.

---

## Verification Plan

### Phase 1 verification
- [ ] DoorDemo open/close works identically (regression)
- [ ] MinecartShipManager assemble/disassemble works identically (regression)
- [ ] New `VanillaBlockSnapshotProvider` assembles vanilla blocks without CustomHeadBlock
- [ ] Custom head blocks in mechanism still resolve textures correctly
- [ ] Container inventories still clone and restore correctly

### Phase 2 verification
- [ ] Assemble mechanism → restart server → mechanism entities + state recover
- [ ] Assemble mechanism → `/stop` (clean shutdown) → restart → recovery
- [ ] Assemble mechanism → kill -9 (crash) → restart → recovery from periodic save
- [ ] Mechanism spanning 2 chunks → unload one chunk → reload → recovery
- [ ] Two mechanisms in same chunk → both recover
- [ ] Mechanism with container inventory → restart → inventory intact
- [ ] Disassemble → metadata file removed → no orphan entities
- [ ] Consumer data (MechanismSerializer) round-trips through save/restore

### Phase 3 verification
- [ ] Assemble mechanism with 100 HP → hit shulker → health decreases
- [ ] Health reaches 0 → MechanismDeathHandler called
- [ ] Health regens at configured rate
- [ ] Mechanism without health → shulker damage cancelled (current behavior)
- [ ] Mount player to seat → rotate mechanism → player follows
- [ ] Dismount player → player teleported to safe position
- [ ] Multiple seats → correct seat assignment

### Phase 4 verification
- [ ] 50 concurrent mechanisms → server maintains 20 TPS
- [ ] No GC spikes from mechanism tick (check with /spark or timings)
- [ ] Passenger chain breaks → auto-repairs within 20 ticks
- [ ] BlockShips ShipInstance can construct a Mechanism via the API
