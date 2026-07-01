# BlockShips Integration Plan

> **Verified against current source 2026-07-01.** All major claims confirmed (serializer never called;
> demo consumers pass `null`; custom-block storage captured but not restored — Known Issue #7; colliders
> repositioned without `Math.round`; BlockShips health/seats/recovery patterns as described). Two content
> updates this pass: the **yaw framing was corrected** (both engines already delta-rotate — see the Context
> bullet and the Phase-4 yaw section) and a **core-ownership boundary** was made explicit (below).
> Caveat: cited line numbers are `~`-approximate and drift by 1–2 as code changes; treat them as anchors,
> re-grep before editing. Uncertainties are flagged inline with ⚠️.

## Context

### The three goals

1. **One copy of the heavy path.** There should be a single engine for "convert a set of blocks → display
   entities + colliders, then move/rotate them," shared by rotators, minecarts, AND BlockShips. Today
   BlockShips duplicates this (~1,400+ lines of `ShipInstance`/`DisplayShip`/persistence). The end state:
   BlockShips **delegates** to DefCoreLib's `Mechanism` engine and holds **zero** copies of spawn/transform/
   recovery.
2. **DefCoreLib custom blocks (and their display entities) work on ships.** Custom head blocks (gears,
   windmills) with animated display entities work on doors/rotators today; they must keep working when built
   into a ship — rendered by the *same* engine, not re-implemented.
3. **Persistence for DefCoreLib mechanisms** (doors, minecarts, rotators) — for DefCoreLib's own sake
   (survive `/stop`+restart and chunk reload), independent of BlockShips.

### Where things actually stand

DefCoreLib's mechanism system (`Mechanism`, `BasicMechanism`, `MechanismRegistry`) already implements the
core patterns: passenger entity chains (vehicle → parent BlockDisplay → child displays), shulker collision
proxies on marker ArmorStands, JOML `Matrix4f` transforms, and three-tier disassembly. It **already moves
vanilla blocks** — the oak-plank door is the proof; `CustomHeadBlock` handling is an optional
`if (customTypeId != null)` branch, not a requirement. So the task is **not** "make vanilla work."

The two engines are **complementary** (same author): DefCoreLib leads on multi-axis rotation, custom-block
state machines, and the rotation-power network; BlockShips leads on persistence, incremental chunk recovery,
health/damage, and seats. Integration is currently **unstarted** (no dependency either direction). So most of
Phases 2–3 is *porting BlockShips' proven systems into the Mechanism core* (it's construction, not just
"decoupling"); the BlockShips line-count reduction is the *final* payoff.

Two cross-cutting facts the phases must respect:
- **Composition, not replacement (goal #2):** the engine keeps its `CustomBlockRegistry` rendering path and
  *adds* a consumer provider; they compose **per block** (registry-first; provider as fallback). See Phase 1.
- **Yaw-source mismatch (Phase-4 risk) — CORRECTED 2026-07-01:** both engines already *delta-rotate*
  displays, so the old "live vs delta" framing was wrong. `BasicMechanism.updateFromVehicle` (`:385`) does
  `rotate(yaw - assemblyYaw)`, and its **non-owned/minecart path** (`:379-385`) already freezes the parent
  entity at zero yaw and rotates displays via matrices — the *same* model BlockShips uses. The real conflict
  is the **yaw source + who drives the tick**: `tickMechanisms` (`MechanismRegistry:377`) **unconditionally**
  calls `updateFromVehicle` every tick, reading the network-**quantized** `vehicle.getYaw()`, whereas
  BlockShips freezes the vehicle and drives from an **exact** internal `physics.currentYaw` (which is *why*
  it needs ProtocolLib position-sync packets). A consumer calling `move(pos, exactYaw)` today is overridden
  the same tick by `updateFromVehicle`. See the corrected Phase-4 reconciliation section.

This plan has four phases, each shippable independently. After Phase 4, BlockShips replaces its internal
entity management (`ShipInstance` display/collider spawning, transform math, entity tagging, recovery) with
DefCoreLib's Mechanism API.

### Core ownership boundary (governing principle)

The goal is to have **as much of the "core" as possible live in DefCoreLib**: mechanism orchestration,
display-entity management, collider (carrier+shulker) management, and the transform/movement that drives
them. BlockShips keeps only *domain* logic. The split:

| Concern | Owner | Note |
|---|---|---|
| Display-entity spawn / transform / recovery | **Core (DefCoreLib)** | the heavy path |
| Collider spawn / position / recovery | **Core** | the entities are core… |
| Collision **force → velocity** response | Consumer (BlockShips) | …but the force math is physics; the engine must **expose** collider read-access so `ShipCollision` can read positions (see Phase 4) |
| Movement + yaw application + anti-quantization position-sync | **Core** | see corrected Phase-4 yaw section |
| Health / seats machinery (values, mounting, HUD sync) | **Core** | |
| Physics, steering, config, customization, fuel, structure scan | Consumer | domain logic |

Everything below is consistent with this table; the Phase-4 "what stays / what migrates" tables are its
concrete expression.

---

## Phase 1: Composable per-block provider

**Goal:** Let an external consumer (BlockShips) supply its own per-block snapshot / render / restore / drop
behavior **without** removing DefCoreLib's existing CustomHeadBlock path. The engine renders each block
**registry-first** (a gear/windmill on a ship still renders + animates via the registry), falling back to
the consumer provider only for blocks the registry doesn't claim. This is what makes goal #2 work: ship
blocks and DefCoreLib custom blocks coexist in **one** mechanism.

> This is a refinement of the old "decouple from CustomHeadBlock" framing. Vanilla blocks already work; the
> point is *additive composition*, not replacing the registry.

### Where the registry/CustomHeadBlock coupling lives (current line numbers)

**`MechanismRegistry.assembleCore()`:**
- `registry.getTypeFromBlock(block)` — detect custom block (~135)
- custom-state capture: `chb.fullId()` (137), `registry.getState` (138), `resolveDisplayEntities` (139),
  `resolveParticles` (140), `isSpinReversed` (142), wall-facing (143–147), `chb.storage()`/`loadInventoryFromPDC` (148–155)
- `registry.onBlockRemoved(block, chb)` — rotation-network cleanup, AFTER all blocks are captured (~174–175)
- primary display: `registry.getType()` (209) + `chb.resolveTexture()` (211)

**`BasicMechanism.setBlockState()`** (~199–213): `registry.getType()`, `resolveTexture`, `resolveParticles`, `resolveDisplayEntities`.

**`BasicMechanism.placeBlock()` (disassembly)** (~294–303): `registry.getType()` (294), `markBlock` (300), `readPower` (301), `applyConfig` (302), `restoreBlock` (303).

**`BasicMechanism.dropBlockAsItem()`** (~314–315): `registry.getType()`, `type.createItem()`.

### What "custom blocks work on ships" actually means (honest scope)

Verified against the engine: when a DefCoreLib custom block is assembled into a mechanism, its **visual /
animated** layer works fully, but its **live behavior is frozen** for the duration of the ride.

- **Works:** display entities with a baked `animation:` (windmill blades, gears, shafts, water-wheel paddles)
  keep spinning on a moving ship from the captured `DisplayAnimation` alone (`updateAnimatedDisplays` ~391–421);
  captured spin direction (CW/CCW via `spinReversed`), texture, wall offset, colliders, and
  restore-on-disassembly with the correct state all work.
- **Frozen while assembled (inherent):** live rotation-network spin recompute, redstone-driven state
  (clutch/reverser), engine fuel, grindstone/press/drill processing, particle emission (`// TODO` ~379), and
  interaction. Assembly removes the block from the world and tears down its network node
  (`onBlockRemoved`→`removeNode`); nothing re-evaluates custom state during the ride.

So **purely-visual blocks just work; network/redstone-driven machines ride frozen** in their captured state.
That's acceptable (a ship is a rigid body, not a running rotation network); making machines live mid-ride is
out of scope (it would require per-tick state re-evaluation). **Acceptance criterion:** a ship mixing vanilla
blocks + a gear + a windmill renders all three correctly and the gear/windmill animate while the ship moves,
with no special-casing in BlockShips.

### Solution: a `BlockSnapshotProvider` that composes with the registry (registry-first)

The engine keeps its `CustomBlockRegistry` branch and **adds** an optional consumer provider. Per block, in
the one assembly loop:

1. Try the registry (`getTypeFromBlock`). If it claims the block → DefCoreLib custom block renders /
   animates / restores / drops via the existing path, **unchanged** (this is why a gear/windmill works on a
   ship).
2. Otherwise fall back to the consumer `BlockSnapshotProvider` (ship skull/banner ItemDisplays, opaque
   per-block data, vanilla restore, the wheel's custom drop).

The provider is an **additive per-block fallback — never a replacement** for the registry.

```java
public interface BlockSnapshotProvider {
    /** Snapshot a world block for mechanism assembly. Called only for blocks the registry does NOT claim. */
    @Nullable BlockSnapshot snapshot(Block block);

    /** Post-snapshot cleanup for this provider's blocks (mirrors registry.onBlockRemoved; no-op for vanilla/ships). */
    void onCaptured(Block block);

    /** Restore a block to the world during disassembly. */
    void restore(Block target, BlockSnapshot snapshot, float snappedYaw);

    /** Create an item drop for a block that can't be placed (e.g. ship wheel for the vehicle block). */
    ItemStack createDrop(BlockSnapshot snapshot);

    /** Spawn the primary display entity for this block (skull/banner ItemDisplay, or null → vanilla BlockDisplay). */
    @Nullable Display spawnPrimaryDisplay(Location loc, BlockSnapshot snapshot,
                                          UUID mechId, int blockIdx);

    /** Update displays when block state changes in-place. */
    void updateState(Display primaryDisplay, BlockSnapshot snapshot, String newState);
}
```

`BlockSnapshot` = the existing `MechanismBlockData` plus an `Object providerData` opaque field — no new
class, no `CustomHeadBlock` reference in the carried data.

**The registry path stays as the built-in (default) branch** — DoorDemo and MechanismMinecartManager are
unaffected. The provider is only consulted for non-registry blocks.

**BlockShips implementation:** `ShipBlockSnapshotProvider` handles the ship's **non-registry** blocks. Note
those are NOT "vanilla, no custom state" — BlockShips renders **skull** (PLAYER_HEAD ItemDisplay +
`skull_profile`) and **banner** (ItemDisplay + patterns) blocks and stores rich opaque per-block state (a
`rawYaml` map: `skull_profile`, `banner_patterns`, `sign_data`, `container_items`, `is_engine`,
`display_yaw`). Only the **wheel block** drops a custom item; other blocks drop/restore vanilla. Any
DefCoreLib custom head built into a ship is resolved **registry-first** and renders/animates normally — the
ship provider never sees it.

### Changes

| File | Change |
|------|--------|
| `MechanismRegistry.java` | Add an optional `BlockSnapshotProvider` parameter to `assembleMechanism()` overloads. In `assembleCore()`, keep the registry branch; **delegate to the provider only for blocks the registry doesn't claim** (registry-first composition). |
| `BasicMechanism.java` | Hold the registry **AND** the optional provider. In `placeBlock()`/`dropBlockAsItem()`/`setBlockState()`, registry-claimed blocks use the registry path; others use the provider. |
| `MechanismBlockData.java` | Add optional `Object providerData` field — provider-specific opaque state (BlockShips stores its `rawYaml`/model-part data; registry-claimed blocks keep using the typed custom fields). |
| `Mechanism.java` | No change. Consumer-facing interface stays clean. |
| `DoorDemo.java` | No change (no provider → registry path only). |
| `MechanismMinecartManager.java` | No change (no provider → registry path only). |

### Backward compatibility

Existing `assembleMechanism()` overloads keep working with **no** provider — every block goes through the
registry/vanilla path exactly as today. New overloads add an optional `BlockSnapshotProvider` that only
handles blocks the registry doesn't claim.

---

## Phase 2: Persistence & Entity Recovery

**Goal:** Mechanisms survive server restarts and chunk unload/reload cycles. **This is goal #3 — a
DefCoreLib feature in its own right** (a door/minecart/rotator must survive `/stop`+restart), not a BlockShips
favor. BlockShips is the reference implementation we borrow from.

### Current state (a DefCoreLib defect to close)

- `MechanismSerializer` interface exists but is **never called** for save/restore
- Both demo consumers pass `null` serializer
- `cleanupOrphanedEntities()` removes stale entities — but never tries to recover them
- Entities are `setPersistent(true)` and tagged, so today they survive restarts as **orphans** (a door
  reassembled before restart comes back as dead entities + lost state)

### BlockShips' proven pattern (to adopt)

BlockShips actually has **two** persistence paths; adopt the modern per-world one:
- `ShipPersistence` → a flat `ships.yml` (legacy whole-world save/load + startup orphan cleanup).
- `ShipWorldData` → the per-world, chunk-indexed path we model on:
```
worlds/
  world_name/
    chunks.yml          # "x,z" → [uuid1, uuid2, ...]
    ships/              # (DefCoreLib: mechanisms/) one file per mechanism
      {uuid}.yml        # type, yaw, entity count, block list, consumer data
```

Key design decisions from BlockShips:
1. **Snapshot on main thread, write async** — single-threaded daemon `ExecutorService` serializes I/O
2. **Entity recovery is incremental** — entities may arrive across multiple chunk loads
3. **Expected entity count** stored in metadata — recovery is "complete" when count matches
4. **Chunk index** enables O(1) lookup: "which mechanisms overlap this chunk?"
5. **Position is re-derived from the recovered vehicle entity**, not stored in the per-ship YAML — BlockShips'
   ships always own a persistent ArmorStand vehicle. ⚠️ **DefCoreLib must NOT blindly copy this:** a door
   anchors on a *world block* (no persistent entity) and a minecart can despawn, so the generic schema
   **keeps** position (see `MechanismState` below). Re-derive-from-vehicle is an *optional optimization* for
   mechanism types that have a guaranteed persistent anchor entity.

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
    double x, double y, double z,   // KEEP: required for vehicle-less mechanisms (doors anchor on a world
                                    // block). Mechanisms with a persistent anchor entity MAY re-derive it
                                    // from the entity on recovery instead (the BlockShips optimization).
    float yaw,                      // dynamic yaw at save time (cf. BlockShips `current_yaw`)
    float assemblyYaw,              // NB: which yaw value(s) to persist depends on the live-vs-frozen yaw
    float rideOffset,               //     model reconciliation (Phase 4) — revisit when that lands.
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

Adopt BlockShips' incremental recovery pattern (entities may arrive across several chunk loads):

1. **On chunk load** (`EntitiesLoadEvent`):
   - Query chunk index for mechanisms in this chunk
   - For each mechanism not already active:
     - Load metadata async
     - On completion (main thread): begin tracking recovery (holder OR in-instance — see below)
     - Scan chunk entities for matching tags
     - Track found vs expected entity count

2. **Tracking partial recovery — two valid shapes** (BlockShips uses the second):
   - **Holder class `RecoveringMechanism`** (cleaner encapsulation; good for deferred assembly):
     ```java
     class RecoveringMechanism {
         final MechanismState state;
         Entity vehicle;
         BlockDisplay parent;
         final Map<Integer, List<Display>> displays = new HashMap<>();
         final Map<Integer, ColliderPair> colliders = new HashMap<>();
         int foundCount = 0, expectedCount;
         boolean isComplete() { return foundCount >= expectedCount; }
     }
     ```
   - **In-instance (the proven BlockShips reference):** construct the mechanism entity-less via `fromState`,
     then attach entities incrementally — fields `expectedEntityCount` / `recoveryComplete` /
     `pendingCarriers` / `pendingShulkers` / `recoveredDisplayIndices` on the mechanism itself
     (`collectEntitiesFromChunk` / `tryAddEntity` / `processPendingColliders`). Carriers and shulkers count
     toward `foundCount` only once **paired**. Expected count =
     `2 (vehicle+parent) + parts + items + collisionParts*2 + seats` — a flat `foundCount++` over raw
     entities would complete recovery prematurely.

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

BlockShips stores health **on the ArmorStand vehicle's `GENERIC_MAX_HEALTH` attribute** (not a side field) —
regen runs in the tick loop (`min(current + regenPerTick, max)`, ~ShipInstance 1209) and death is checked
off `vehicle.getHealth() <= 0` (~1228) → `destroyAndDropItem`:
```java
vehicle.getAttribute(GENERIC_MAX_HEALTH).setBaseValue(maxHealth);
```
It also **mirrors the value onto each seat shulker** so the rider sees it on the vanilla health HUD, scaled
to fit (≤40 HP shown directly, else scaled to 20 hearts) — `syncSeatShulkerHealth` (~1870), setup ~805.

DefCoreLib should support this as an optional feature (not all mechanisms need health — doors don't).

#### `MechanismHealth` (new, optional component)

A thin wrapper over the vehicle's max-health attribute (so the value survives in entity NBT across chunk
reload for free) that also fans the value out to any seat shulkers for the HUD:

```java
public class MechanismHealth {
    private final LivingEntity vehicle;      // health lives on its GENERIC_MAX_HEALTH attribute
    private final double regenPerTick;       // healthRegenPerSecond / 20

    void damage(double amount) { vehicle.setHealth(max(0, vehicle.getHealth() - amount)); syncSeatHUD(); }
    void tick() { setHealth(min(vehicle.getHealth() + regenPerTick, maxHealth())); }
    boolean isDead() { return vehicle.getHealth() <= 0; }
    double fraction() { return vehicle.getHealth() / maxHealth(); }
    // syncSeatHUD(): mirror onto seat shulkers with the 40-HP/20-heart scaling
}
```

Wired into Mechanism interface as optional:
```java
@Nullable MechanismHealth health();
```

#### Damage routing (confirmed — keep)

BlockShips routes damage from shulker hits to the vehicle, verified in `DisplayShip.onShulkerDamage`
(~1487–1549): an `EntityDamageEvent` on a ship-tagged shulker is **cancelled** (shulker stays effectively
invulnerable) and the damage is applied to `vehicle.getHealth()`; at ≤0 it calls `destroyAndDropItem`.
DefCoreLib already cancels damage on `corelib:mech:*` entities in CoreLibPlugin. Change to:

1. On `EntityDamageEvent` for a mechanism shulker:
   - If mechanism has health: cancel the shulker damage, apply it to the vehicle health, check death
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

BlockShips does NOT spawn separate seat entities — a seat **is one of the block's collision shulkers**
(collidable, tagged `shipseat:{i}`). The player rides that shulker, so it already follows the mechanism via
the existing collider repositioning. Lead points work the same way: a `leadable:{i}` role on an existing
collision shulker, not a new entity.

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
// A seat marks an EXISTING collision shulker (by blockIndex) as rideable + tags it shipseat:{i}.
// Mount player via seatShulker.addPassenger(player)
```

Seats reuse the block's collision shulker (collidable — it's the same entity that gives the block its
collision), tagged for seat lookup. They are repositioned on rotate/move exactly because they ARE colliders,
so the entity-count math (`collisionParts*2`, no extra per-seat entities) stays correct.

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

BlockShips pre-allocates ~15–20 reusable Matrix4f/Vector3f fields. DefCoreLib's tick loop allocates new matrices per block per tick.

**Current (`MechanismRegistry.updateAnimatedDisplays`, ~lines 410 & 414 — two allocations per block per tick):**
```java
dec.animation().apply(BasicMechanism.transformToMatrix(dec.transform()), age, workMatrix); // alloc (410)
Matrix4f placed = new Matrix4f(mech.currentTransform()).mul(mb.localTransform);            // alloc (414)
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
| **ShipCollision** / **ShipCollisionCoordinator** | Force accumulation + ship-to-ship momentum → velocity — pure physics. **Verified 2026-07-01:** these classes only *read* collider positions (terrain force, penetration force), they don't own entities. ⚠️ **Consequence of the core-ownership boundary:** once the collider *entities* live in DefCoreLib, the engine must **expose collider read-access** on the `Mechanism` interface — e.g. `int colliderCount()` + a world-space `getColliderBox(int)` (or per-block collider position accessor). Exact shape TBD (see uncertainty note below). |
| **ShipConfig** | Per-ship-type tuning (speed, drag, turn rate) — consumer config |
| **ShipCustomization** | Wood type, balloon color, banner — visual customization |
| **ShipSteeringListener** | ProtocolLib key detection — domain-specific |
| **BlockStructureScanner** (partial) | Its `scanStructure` + weight/sail/cannon/leash detection stays. ⚠️ But its `placeBlocks`/`removeBlocks` (block snapshot/restore/drop) belong in the `ShipBlockSnapshotProvider` — NOT a clean split: the rotation utilities (`rotateBlockData`, `blockFaceToYaw`, …) are shared by both `scanStructure` (stays) and `placeBlocks` (moves). Extract rotations to a neutral util, or accept duplication. |
| **ShipWheelManager** | Fuel tracking — domain-specific |

#### What migrates to DefCoreLib

(Sizes below are from `wc -l` at the time of writing — treat as rough.)

| BlockShips Code | Lines | DefCoreLib Replacement |
|----------------|-------|----------------------|
| ShipInstance entity spawning | ~200–300 | `MechanismRegistry.assembleMechanism()` |
| ShipInstance.updateDisplayTransforms() | ~32 | `BasicMechanism.rotate()` |
| ShipInstance.updateCollisionPositions() | ~107 | `BasicMechanism.rotate()` collider section |
| ShipInstance health + seat mgmt | ~120 (61 + 58) | `MechanismHealth` + seat system |
| DisplayShip chunk recovery | ~150–200 | `MechanismPersistence` + recovery flow |
| ShipWorldData | 593 | `MechanismPersistence` |
| ShipPersistence | 403 | `MechanismPersistence` |
| ShipTags | 182 | Entity tag pattern in MechanismRegistry |
| CollisionBox | 31 | `ColliderPair` |
| DisplayInstance | 18 | Display lists in BasicMechanism |
| BlockStructureScanner.placeBlocks/removeBlocks | ~216 | `ShipBlockSnapshotProvider` (see entanglement caveat above) |
| ShipTeleportCompat | — | `TeleportCompat` (already exists in DefCoreLib) |

**Lines eliminated from BlockShips: a rough lower bound of ~1,400, and plausibly ~2,300+** once partial
removals (ShipInstance spawn/transform/recovery/health/seat) are counted. Treat as an order-of-magnitude
estimate, not a precise figure.

#### Yaw-source reconciliation (prerequisite for BlockShips delegating movement) — CORRECTED 2026-07-01

**The original "the two engines drive rotation differently" framing was wrong** and is corrected here.
Verified against current code:

- **Both delta-rotate.** `BasicMechanism.updateFromVehicle` (`:349-386`) does `rotate(yaw - assemblyYaw)`,
  not "applies the live yaw directly." Its **non-owned/minecart path** (`:379-385`) teleports the parent at
  **zero yaw** and rotates displays purely via matrices — this is *already* BlockShips' frozen-parent +
  delta-rotate model.
- **The real difference is the yaw source + the tick driver.** `tickMechanisms` (`MechanismRegistry:377`)
  **unconditionally** calls `updateFromVehicle` for every active mechanism each tick, reading the
  network-**quantized** `vehicle.getYaw()` (~1.4°/byte). BlockShips instead freezes the vehicle and drives
  displays from an **exact** internal `physics.currentYaw`, and sends ProtocolLib position-sync packets
  (~ShipInstance 1379-1469) so *other* clients see smooth motion despite the quantized entity.
- **The concrete conflict:** a consumer calling `mech.move(pos, exactYaw)` is overridden the **same tick**,
  because `updateFromVehicle` runs afterward and re-derives yaw from the frozen vehicle (→ `rotate(0)`).
  Also latent: `move()` treats yaw as **absolute** (`rotate(yaw)`, `:182-188`) while `updateFromVehicle`
  treats it as a **delta** — two inconsistent yaw conventions on the same class.

**Recommended resolution (replaces the old options a/b): a "driven / external-yaw" mechanism mode.**
For a driven mechanism, `tickMechanisms` **skips `updateFromVehicle`**; the consumer pushes exact
position + yaw each tick via `move()`/a dedicated `drive(pos, yaw)`, and the **engine owns** the
anti-quantization position-sync. This eliminates quantization at the source (the engine never reads the
entity's quantized yaw), keeps all display/collider management in core, and settles which yaw Phase 2
persists (the exact driven yaw). Doors/rotators/minecarts keep the existing vehicle-driven mode unchanged.

> ⚠️ **Uncertainty (validate before committing to this):**
> 1. **Position-sync ownership.** BlockShips' smoothness relies on ProtocolLib `ENTITY_TELEPORT` packets.
>    DefCoreLib currently has only `TeleportCompat` (a teleport abstraction) — I have **not** verified it
>    emits the same per-tick position/rotation sync BlockShips depends on. Moving position-sync into the
>    engine may require porting the ProtocolLib packet path (or an equivalent), which adds a ProtocolLib
>    dependency to the *core* — possibly undesirable. Open question: does position-sync belong in core, or
>    stay a consumer responsibility while the engine only owns the transform math?
> 2. **`move()` vs `updateFromVehicle` unification.** Reconciling the absolute-vs-delta yaw conventions may
>    affect existing `move()` callers; audit them before changing semantics.
> 3. Whether a driven mechanism should still *own* an ArmorStand vehicle at all (for health NBT + a
>    recovery anchor) or drop it — interacts with the Phase-2 persistence anchor decision.

---

## Implementation Order

### Phase 1 (Composable provider) — ~2 sessions

1. Define `BlockSnapshotProvider` interface; add `Object providerData` to `MechanismBlockData`
2. Add an optional provider parameter to `assembleMechanism()` overloads (no provider = today's behavior)
3. In `assembleCore()` + `BasicMechanism`, route **registry-first per block**, provider as fallback (do NOT
   replace the registry branch)
4. Test: DoorDemo and MechanismMinecartManager work identically (regression — they pass no provider)
5. Test (goal #2): a **mixed** mechanism with a vanilla block + a DefCoreLib gear + a windmill — all render,
   the gear/windmill animate while the mechanism moves, with a trivial test provider supplying only the
   non-registry blocks

### Phase 2 (Persistence) — ~3 sessions

1. Implement `MechanismPersistence` with YAML save/load + async I/O
2. Implement `MechanismState` serialization (blocks, transforms, consumer data)
3. Add inventory serialization (Base64 ItemStack[], following BlockShips pattern)
4. Implement chunk index (world → chunk → mechanism UUIDs)
5. Implement incremental entity recovery (in-instance fields, per the BlockShips reference — or a `RecoveringMechanism` holder)
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

## Known Issues (small / precision notes — mostly not blockers)

These are minor; verification (against current code) reclassified most of the original list. Only #5 was a
genuine dead-code item and has been **struck**.

1. **Collider grid alignment — open precision note (not the original "round at spawn").** The original
   concern (round XZ at the initial carrier spawn) is moot: at assembly yaw is 0 and offsets are integer
   from centered positions (pivot snapped in `double`, `assembleCore` ~114–126). HOWEVER, `BasicMechanism.rotate`
   repositions colliders via float `pivot.add(worldOff…)` **without** the `Math.round` that disassembly uses
   on the same calculation (with a "trig epsilon" comment, ~246). For 90° rotations this is exact; for
   non-90° angles or coordinates near the float-precision limit, colliders could drift sub-block. Confirm
   whether the rotate path needs the same rounding (or whether continuous collider motion is intended).

2. ~~**`MINECART_RIDE_OFFSET = 0f`** — needs empirical tuning in-game.~~ **Resolved:** `0f` confirmed correct in-game — not a bug (see minecarts.md "MINECART_RIDE_OFFSET").

3. **No up-front vehicle validity check (minor).** The external-vehicle overload `assembleMechanism(…, Entity, …)`
   (~93–100) doesn't check `existingVehicle.isValid()` before spawning entities. It is NOT silently broken:
   `assembleCore`'s 1-tick mount task disassembles a dead vehicle (`if (!vehicle.isValid()) { mech.disassemble(); return; }`,
   ~278) and `disassemble()` removes every entity + deregisters. The only residual is a one-tick window where
   a mechanism is registered before validity is confirmed; an up-front guard would be tidier.

4. **Particle ticking not implemented** — `// TODO` at `MechanismRegistry` ~line 379. `ParticleConfig` is captured per block but never spawned in the tick loop. (This is also why custom-machine particles — engine smoke, generator sparks — don't emit on a moving ship; see Phase 1 scope.)

5. ~~**Dead code — `perpendicularNeighbors()` in RotationNetwork.java.**~~ **Struck:** the method no longer exists (removed in the Phase-2 rotation rewrite, commit `e0447d0`); grep-empty across the repo.

6. **Disassembly grid-snap precision — residual note (not "float snapX").** The original `float snapX` is
   gone — assembly snaps in `double` and casts to `float` only at Matrix4f construction (~114–126). But
   disassembly still `Math.round`s a float `worldOffset` to place blocks back (`BasicMechanism`, ~246), so
   the integer-offset invariant is *aspirational, not enforced* at >8M coords / non-90° rotations. Likely
   fine in practice; worth confirming if huge-coordinate or non-cardinal mechanisms are ever supported.

7. **Custom-block storage not restored on disassembly (data loss) — ✅ RESOLVED (in-memory round-trip).** `BasicMechanism.placeBlock()` previously restored a custom block's type/state/config but never wrote back the captured `mb.storage` inventory — the only `setContents` lived in the `else if (… instanceof Container)` branch, unreachable for a custom head — so a custom storage block absorbed into a mechanism and placed back **came back empty**. Fixed once `rotation:millstone/press/placer/engine` gained `storage()`, which made it live. The naive one-line PDC write was **insufficient**: the machine-storage subsystem caches an open `StorageHolder` per location (`getOrCreateStorage`, populated viewer-less by pipe/tick), and `onBlockRemoved` doesn't evict it, so a direct PDC write is shadowed by a stale holder. The fix is two cache-consistent helpers on `CustomBlockRegistry` — `takeStorageSnapshot(block)` (capture: read live holder-or-PDC, deep-clone, **evict**) used by `assembleCore`, and `restoreStorageSnapshot(block, inv)` (restore: `getOrCreateStorage` → `setContents` → `saveInventoryToPDC`) called from `placeBlock` after `restoreBlock`. Every reader (tick/GUI/`dropStorage`) now agrees. **Phase-2 YAML persistence (`MechanismState.blocks[].storage`) still layers on top** for cross-restart survival; and in-flight *processing* progress (grind/cook counters) is still not captured — only inventory round-trips.

---

## Verification Plan

### Phase 1 verification
- [ ] DoorDemo open/close works identically (regression — no provider passed)
- [ ] MechanismMinecartManager assemble/disassemble works identically (regression)
- [ ] Custom head blocks in a mechanism still resolve textures/animations correctly (registry path intact)
- [ ] **Goal #2 — mixed mechanism:** vanilla block + DefCoreLib gear + windmill all render; gear/windmill
      animate while the mechanism moves; the test provider only supplies the non-registry block
- [ ] Container inventories still clone and restore correctly

### Phase 2 verification
- [ ] **DefCoreLib door** → `/stop`+restart → door recovers and still opens (goal #3, no BlockShips involved)
- [ ] **Custom-head rotator with a container** → restart → inventory intact (also closes the deferred storage
      data-loss issue, Known Issues #7)
- [ ] Minecart mechanism → restart → recovers (verify the external/persistent vehicle anchor)
- [ ] Assemble mechanism → `/stop` (clean shutdown) → restart → recovery
- [ ] Assemble mechanism → kill -9 (crash) → restart → recovery from periodic save
- [ ] Mechanism spanning 2 chunks → unload one chunk → reload → incremental recovery completes
- [ ] Two mechanisms in same chunk → both recover
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
