# Design: Animations + Mechanisms (v3)

## Part 1: Display Entity Animations

### API

```java
// New file: DisplayAnimation.java
@FunctionalInterface
public interface DisplayAnimation {
    /** Apply animation to a base transform. tickAge = ticks since animation started.
     *  Mutates output (not base). */
    void apply(Matrix4f base, long tickAge, Matrix4f output);
}

// Factory methods (same pattern as Triggers class)
public final class Animations {
    public static DisplayAnimation rotate(Vector3f axis, float degreesPerTick) { ... }
    public static DisplayAnimation bob(float amplitude, int periodTicks) { ... }
    public static DisplayAnimation pulse(float minScale, float maxScale, int periodTicks) { ... }
    public static DisplayAnimation orbit(float radius, int periodTicks, Vector3f axis) { ... }
    public static DisplayAnimation compose(DisplayAnimation... layers) { ... }
}
```

Animation is an imperative transform: `(base, tickAge) → writes output`. Works identically in static and mechanism mode — just receives a different base matrix. The animation doesn't know which mode it's in.

### Data model changes

```java
// Extend existing DisplayEntityConfig:
record DisplayEntityConfig(
    String itemTexture,
    Transformation transform,
    @Nullable String tagSuffix,
    @Nullable DisplayAnimation animation,  // NEW
    int interpolationDuration              // NEW: default 2
) {}
```

### YAML format

```yaml
display_entities:
  - texture: "crystal_base64..."
    tag: crystal
    transform: { translation: [0, 0.5, 0], scale: [0.5, 0.5, 0.5] }
    animation:
      type: compose
      layers:
        - { type: rotate, axis: [0, 1, 0], speed: 3.0 }
        - { type: bob, amplitude: 0.1, period: 40 }
    interpolation: 4
```

### Registry changes

- All animation tracking lives in `CustomBlockRegistry` (alongside existing tracking maps — `LocationKey` is private)
- New tracking map: `Map<LocationKey, List<AnimationTracked>> animationTracked`
- `AnimationTracked`: display entity reference, animation, startTick, base Matrix4f
- New tick method: `tickAnimations()` — every tick, evaluate animation, call `display.setTransformationMatrix(result)` (confirmed working in Paper via BlockShips)
- `DisplayUtil.spawn()`: if animation present, set `interpolationDuration` at spawn
- On block removal: `onBlockRemoved()` must also clean `animationTracked`
- On chunk unload: clean `animationTracked` entries (same `removeIf` pattern as other maps)
- On chunk load: find existing display entities via `DisplayUtil.findByTag()`, re-register in `animationTracked` with fresh `startTick` (phase reset is fine for periodic animations like rotate/bob/pulse)

Note: display entities persist naturally via Minecraft entity NBT (`setPersistent(true)`). Only the plugin's tracking map needs restoration on chunk load.

### Files
- New: `DisplayAnimation.java` (~100 lines: interface + Animations factory)
- Modified: `CustomHeadBlock.java` (DisplayEntityConfig fields)
- Modified: `BlockLoader.java` (parse animation config via recursive `parseAnimation(Map)`)
- Modified: `CustomBlockRegistry.java` (animationTracked map, tickAnimations, cleanup in onBlockRemoved + onChunkUnload, re-registration in onChunkLoad/restoreBlock)
- Modified: `DisplayUtil.java` (interpolation params at spawn)

---

## Part 2: Mechanism System

### Design principles

1. **CoreLib creates the full passenger chain** (vehicle → parent → displays) by default, matching BlockShips' battle-tested approach
2. **Consumer moves the mechanism via `move()`/`rotate()`** — one call moves displays AND colliders
3. **Animations and particles tick independently** of consumer movement calls
4. **Custom head blocks inside mechanisms keep working** — state transitions, particles, animations — via a **separate mechanism-aware code path** (no world Skull exists)
5. **Persistence via YAML** with consumer hook for extra data
6. **Shulker collision is axis-aligned** — colliders only match visuals at 90° increments (inherent Minecraft limitation, same as BlockShips)

### Core API

```java
// Mechanism.java — the handle returned by assembly
public interface Mechanism {
    UUID id();
    String type();             // consumer-provided: "blockships:ship", "doors:iron_door"
    Location pivot();
    int blockCount();

    // --- Movement (moves displays + colliders together) ---
    void move(Location position, float yaw, float pitch);
    void teleport(Location position);
    void rotate(float yaw, float pitch);

    // --- Per-block state (for custom head blocks) ---
    MechanismBlockData getBlock(int index);
    @Nullable String getBlockState(int index);
    void setBlockState(int index, String state);

    // --- Animation support ---
    void applyAnimation(int blockIndex, Matrix4f baseTransform, long tickAge, Matrix4f output);

    // --- Storage (containers inside mechanisms) ---
    @Nullable Inventory getStorage(int blockIndex);

    // --- Lifecycle ---
    void disassemble();        // restore blocks to world, resume static tracking
    void destroy();            // remove all entities without restoring blocks

    // --- Entity access (for advanced consumers) ---
    ArmorStand vehicle();
    List<Display> displays();
    List<ColliderPair> colliders();

    // --- Re-parenting (e.g., attach to a minecart) ---
    void setVehicle(Entity newVehicle);
}

// Block snapshot — state is mutable (transitions), rest is final
record MechanismBlockData(
    BlockData blockData,
    Matrix4f localTransform,
    boolean hasCollision,
    float collisionScale,
    @Nullable String customTypeId,
    String customState,                    // mutable via setBlockState()
    @Nullable DisplayAnimation animation,
    @Nullable ParticleConfig particles,
    @Nullable Inventory storage            // NEW: snapshotted container contents
) {}

// Carrier + shulker pair for collision
record ColliderPair(ArmorStand carrier, Shulker shulker, int blockIndex) {}
```

### How `move()` works internally

```java
void move(Location position, float yaw, float pitch) {
    // 1. Teleport vehicle — must use TeleportCompat pattern!
    //    Pre-1.21.9: entity.teleport() silently fails with passengers.
    //    Eject passengers, teleport, re-add. (BlockShips TeleportCompat.java:75-101)
    TeleportCompat.teleport(vehicle, position);

    // 2. Compute rotation matrix from yaw + pitch
    Matrix4f rotation = computeRotation(yaw, pitch);
    this.currentTransform = rotation;

    // 3. Reposition all colliders (NOT passengers — independent carriers)
    for (ColliderPair cp : colliders) {
        Vector3f worldOffset = rotation.transformPosition(
            blocks.get(cp.blockIndex).localTransform.getTranslation(new Vector3f()));
        TeleportCompat.teleport(cp.carrier,
            position.clone().add(worldOffset.x, worldOffset.y, worldOffset.z));
    }
}
```

`currentTransform` initialized to identity matrix in constructor (not null — avoids NPE before first `move()` call).

### Mechanism state transitions (separate from static applyConfig)

Static blocks use `applyConfig()` which calls `HeadUtil.applyTexture(block, ...)` — requires a Skull in the world. Mechanism blocks have no world Skull. State transitions in mechanisms use a **separate code path**:

```java
void setBlockState(int index, String newState) {
    MechanismBlockData mb = blocks.get(index);
    if (mb.customTypeId() == null) return;
    CustomHeadBlock type = registry.getType(mb.customTypeId());

    // 1. Update state in MechanismBlockData (not PDC — no skull exists)
    mb.customState = newState;

    // 2. Update display entity IN-PLACE (do NOT remove+respawn — breaks passenger chain)
    Display display = displays.get(index);
    if (display instanceof ItemDisplay id) {
        String texture = type.resolveTexture(newState, 0, null);
        id.setItemStack(HeadUtil.createHead(texture, 1));
    }

    // 3. Update particle config for this block
    ParticleConfig pc = type.resolveParticles(newState);
    mb.particles = pc;  // mechanism tick will use updated config

    // 4. Update animation config
    // Animation resolves per-state from DisplayEntityConfig
    List<DisplayEntityConfig> decs = type.resolveDisplayEntities(newState);
    // ... update animation reference if changed

    // No light blocks (no static world position)
    // No skull texture (no skull)
    // No PDC writes (no PDC)
}
```

### Independent particle + animation ticks

Particles and animations tick on their own schedule, NOT inside `move()`. This means they keep running even when the mechanism is stationary.

```java
// In MechanismRegistry.tickMechanisms() — runs every tick
for (Mechanism mech : activeMechanisms) {
    // Particles
    for (int i = 0; i < mech.blockCount(); i++) {
        ParticleConfig pc = mech.getBlock(i).particles();
        if (pc == null) continue;
        // Use cached transform from last move() call (initialized to identity)
        Vector3f worldPos = mech.currentTransform.transformPosition(
            mech.getBlock(i).localTransform.getTranslation(new Vector3f()));
        Location loc = mech.pivot().clone().add(worldPos.x, worldPos.y, worldPos.z);
        // spawn particle at loc + oriented offset
    }

    // Animations
    for (int i = 0; i < mech.blockCount(); i++) {
        DisplayAnimation anim = mech.getBlock(i).animation();
        if (anim == null) continue;
        Matrix4f base = new Matrix4f(mech.currentTransform).mul(mech.getBlock(i).localTransform());
        anim.apply(base, tickAge, workMatrix);
        mech.displays().get(i).setTransformationMatrix(workMatrix);
    }
}
```

### Assembly

```java
public Mechanism assembleMechanism(String type, List<Block> blocks, Location pivot,
                                    @Nullable MechanismSerializer serializer) {
    List<MechanismBlockData> blockData = new ArrayList<>();

    // 1. Snapshot each block
    for (Block block : blocks) {
        BlockData bd = block.getBlockData();
        Matrix4f local = computeLocalTransform(block, pivot);

        // Check if it's a custom head block
        String customType = null, customState = null;
        DisplayAnimation animation = null;
        ParticleConfig particles = null;
        Inventory storage = null;
        CustomHeadBlock chb = getTypeFromBlock(block);
        if (chb != null) {
            customType = chb.fullId();
            customState = getState(block);
            // Read resolved config from current state
            particles = chb.resolveParticles(customState);
            // animation from resolved DisplayEntityConfig
            List<DisplayEntityConfig> decs = chb.resolveDisplayEntities(customState);
            if (decs != null && !decs.isEmpty()) {
                animation = decs.get(0).animation(); // TODO: per-display animation
            }
            // Snapshot container inventory if storage block
            if (chb.storage() != null) {
                storage = snapshotInventory(block, chb);
            }
            // Remove from ALL static tracking maps
            onBlockRemoved(block, chb);
        } else if (block.getState() instanceof Container container) {
            // Vanilla container (chest, barrel, etc.)
            storage = snapshotVanillaInventory(container);
        }

        blockData.add(new MechanismBlockData(bd, local, true, 1.0f,
            customType, customState, animation, particles, storage));
        block.setType(Material.AIR);
    }

    // 2. Spawn entities
    ArmorStand vehicle = spawnVehicle(pivot);
    Display parent = spawnInvisibleParent(pivot);
    List<Display> displays = new ArrayList<>();
    List<ColliderPair> colliders = new ArrayList<>();
    UUID mechId = UUID.randomUUID();

    for (int i = 0; i < blockData.size(); i++) {
        MechanismBlockData mb = blockData.get(i);

        // Spawn display (BlockDisplay for vanilla, ItemDisplay for custom heads)
        Display display = spawnMechanismDisplay(mb, pivot, mechId, i);
        parent.addPassenger(display);
        displays.add(display);

        // Spawn collider if needed
        if (mb.hasCollision()) {
            ArmorStand carrier = spawnCarrier(pivot, mechId, i);
            Shulker shulker = spawnColliderShulker(carrier, mb.collisionScale(), mechId, i);
            colliders.add(new ColliderPair(carrier, shulker, i));
        }
    }

    vehicle.addPassenger(parent);

    // 3. Create mechanism, register, return
    BasicMechanism mech = new BasicMechanism(mechId, type, pivot, vehicle, parent,
        displays, colliders, blockData, serializer);
    mech.currentTransform = new Matrix4f(); // identity — avoids NPE before first move()
    mechanismRegistry.put(mechId, mech);
    return mech;
}
```

### Disassembly

```java
void disassemble() {
    // 1. Snap rotation to nearest 90°
    float snappedYaw = Math.round(currentYaw / 90f) * 90f;
    Matrix4f rotation = computeRotation(snappedYaw, 0);

    // 2. Place blocks back
    for (int i = 0; i < blocks.size(); i++) {
        MechanismBlockData mb = blocks.get(i);
        Vector3f worldOffset = rotation.transformPosition(mb.localTransform().getTranslation(new Vector3f()));
        Location blockLoc = pivot.clone().add(
            Math.round(worldOffset.x), Math.round(worldOffset.y), Math.round(worldOffset.z));
        Block block = blockLoc.getBlock();

        // Place with rotated block data
        block.setBlockData(rotateBlockData(mb.blockData(), snappedYaw));

        // Restore custom head block state
        if (mb.customTypeId() != null) {
            CustomHeadBlock type = registry.getType(mb.customTypeId());
            if (type != null) {
                // markBlock + restoreBlock + applyConfig handles all 11 restoration items:
                // PDC, texture, light, display entities, redstone, particles,
                // neighbor, tick, location tracking, chunk hints
                registry.markBlock(block, type);
                if (mb.customState() != null) registry.setState(block, mb.customState());
                int power = registry.readPower(block, type);
                registry.applyConfig(block, type, mb.customState(), power);
                registry.restoreBlock(block, type, mb.customState());
                // Restore container inventory to skull PDC
                if (mb.storage() != null && type.storage() != null) {
                    registry.restoreInventoryToPDC(block, mb.storage());
                }
            }
        } else if (mb.storage() != null && block.getState() instanceof Container container) {
            // Restore vanilla container inventory
            container.getSnapshotInventory().setContents(mb.storage().getContents());
            container.update();
        }
    }

    // 3. Remove all entities
    displays.forEach(Entity::remove);
    colliders.forEach(cp -> { cp.carrier().remove(); cp.shulker().remove(); });
    parent.remove();
    vehicle.remove();

    // 4. Clean up
    mechanismRegistry.remove(id);
    deletePersistenceFile();
    if (serializer != null) serializer.onDisassemble(this);
}
```

### Player interaction

Route through `PlayerInteractEntityEvent` on collider shulkers:

```java
@EventHandler
public void onEntityInteract(PlayerInteractEntityEvent event) {
    if (!(event.getRightClicked() instanceof Shulker shulker)) return;
    ColliderRef ref = mechanismRegistry.getColliderRef(shulker);
    if (ref == null) return;

    event.setCancelled(true);
    Mechanism mech = ref.mechanism();
    int blockIndex = ref.blockIndex();
    MechanismBlockData mb = mech.getBlock(blockIndex);

    // Storage access (containers inside mechanisms)
    if (mb.storage() != null) {
        event.getPlayer().openInventory(mb.storage());
        return;
    }

    // Custom block state transitions
    String typeId = mb.customTypeId();
    if (typeId == null) return;
    CustomHeadBlock type = registry.getType(typeId);
    if (type == null) return;

    // ... handle interact GUI, state transitions via mech.setBlockState()
}
```

### Entity protection

```java
@EventHandler
public void onEntityDamage(EntityDamageEvent event) {
    Entity entity = event.getEntity();
    for (String tag : entity.getScoreboardTags()) {
        if (tag.startsWith("corelib:mech:")) {
            event.setCancelled(true);
            return;
        }
    }
}
```

---

## Part 3: Mechanism Persistence

### Per-mechanism YAML file

`plugins/DefCoreLib/mechanisms/{world}/{uuid}.yml`
```yaml
id: "550e8400-..."
type: "blockships:custom_ship"
pivot: [100.5, 64.0, 200.5]
yaw: 90.0
pitch: 0.0
entity_count: 47
blocks:
  - blockdata: "minecraft:oak_log[axis=y]"
    transform: [1,0,0,-1, 0,1,0,0, 0,0,1,0, 0,0,0,1]
    collision: true
    collision_scale: 1.0
    custom_type: null
    custom_state: null
  - blockdata: "minecraft:player_head"
    transform: [1,0,0,0, 0,1,0,1, 0,0,1,0, 0,0,0,1]
    collision: true
    collision_scale: 1.0
    custom_type: "headsmith:candle"
    custom_state: "lit"
inventories:
  5: "base64item1|base64item2||base64item4"
```

Container inventories serialized via `ItemStack.serializeAsBytes()` → Base64, pipe-delimited per slot (matching BlockShips format).

### Chunk index

`plugins/DefCoreLib/mechanisms/{world}/chunks.yml`

Tracks **vehicle chunk only** (not multi-chunk). When vehicle chunk loads, recovery starts. Other chunks recovered incrementally as they load.

```yaml
chunks:
  "6,12": ["uuid1"]
  "8,3": ["uuid2", "uuid3"]
```

Updated when mechanism moves across chunk boundaries via `move()`.

### I/O executor

Single-threaded executor (`Executors.newSingleThreadExecutor()`) serializes all writes — prevents race conditions from concurrent mechanism movements updating `chunks.yml`. Same pattern as BlockShips' `ShipWorldData`.

### Consumer serialization

```java
public interface MechanismSerializer {
    /** Serialize consumer-specific data into the YAML section. */
    void save(Mechanism mech, ConfigurationSection section);

    /** Restore consumer-specific data from the YAML section. */
    void restore(Mechanism mech, ConfigurationSection section);

    /** Called when all entities recovered after server restart. */
    void onRecoveryComplete(Mechanism mech);

    /** Called when mechanism is disassembled. */
    default void onDisassemble(Mechanism mech) {}
}
```

Uses `ConfigurationSection` instead of `Map<String, Object>` — type-safe, supports `getItemStack()`, `getString()`, etc.

### Late-binding for missing consumer plugins

If a mechanism's consumer plugin isn't loaded yet (e.g., BlockShips loads after CoreLib):

1. CoreLib loads YAML, recovers entities, creates mechanism in `pendingMechanisms` map keyed by type string
2. When consumer calls `registry.registerMechanismType("blockships:ship", serializer)`, CoreLib hands over all pending mechanisms
3. Consumer calls `serializer.restore()` and `serializer.onRecoveryComplete()` on each

Note: recovery completion (entity count match) is deferred until a serializer is registered — avoids firing `onRecoveryComplete` with no serializer.

### Save timing

- **Periodic:** async snapshot (main thread) → write (I/O executor), same pattern as chunk hints
- **On shutdown:** synchronous save all (await executor termination)
- **On vehicle chunk unload:** save mechanism state

### Recovery sequence

1. **Plugin enable:** load all chunk indices
2. **Chunk load:** check index → load YAML async → scan chunk entities by tag
3. **Fallback:** `getNearbyEntities(vehicleLoc, 32, 32, 32)` for cross-chunk entity drift (BlockShips two-pass pattern)
4. **Incremental:** pending carrier/shulker maps for cross-chunk pairing
5. **Complete:** entity count matches AND serializer registered → call serializer.onRecoveryComplete()

Entity tag format: `corelib:mech:{uuid}:{blockIndex}:{role}` — roles: display, carrier, collider, vehicle, parent

---

## Part 4: DisplayUtil generalization

Currently `DisplayUtil` only handles `ItemDisplay`. Mechanisms need `BlockDisplay` too.

### Changes

```java
// Generalize findByTag and removeByTag to Display supertype:
public static List<Display> findByTag(Location blockLoc, String tagPrefix, double radius)
public static int removeByTag(Location blockLoc, String tagPrefix, double radius)

// Add BlockDisplay spawn method:
public static BlockDisplay spawnBlock(Location loc, BlockData data,
                                       Matrix4f transform, String scoreboardTag)

// Existing ItemDisplay spawn stays as-is
```

---

## Implementation order

1. **DisplayUtil generalization** — BlockDisplay support, generalize to Display supertype (~20 lines changed)
2. **DisplayAnimation + Animations** — functional interface, factory methods (~100 lines)
3. **Animation YAML parsing** — BlockLoader additions, recursive parseAnimation (~30 lines)
4. **Animation tick** — animationTracked map, tickAnimations, cleanup in onBlockRemoved/onChunkUnload, re-registration in restoreBlock/onChunkLoad (~80 lines)
5. **TeleportCompat** — eject-teleport-readd utility (~30 lines)
6. **MechanismBlockData** — record with storage field (~25 lines)
7. **Mechanism interface** — API contract with storage access (~50 lines)
8. **BasicMechanism** — implementation: passenger chain, move/rotate (using TeleportCompat), collider positioning, currentTransform init to identity (~250 lines)
9. **Mechanism state transitions** — separate code path: update MechanismBlockData state + display item in-place, no skull/PDC (~60 lines)
10. **Assembly** — block snapshot (including container inventory + resolved particles/animation), entity spawn, passenger mounting (~120 lines)
11. **Disassembly** — block restore via existing markBlock/restoreBlock/applyConfig, container inventory restore, entity cleanup (~100 lines)
12. **Mechanism particle + animation ticks** — independent of move() (~60 lines)
13. **Entity protection** — EntityDamageEvent cancellation for mechanism-tagged entities (~15 lines)
14. **Mechanism interaction** — PlayerInteractEntityEvent on shulker colliders, storage access, state transitions via setBlockState (~50 lines)
15. **Mechanism persistence** — YAML save/load, inventory serialization, single-threaded I/O executor (~140 lines)
16. **Chunk index + recovery** — vehicle-chunk tracking, two-pass entity search, incremental pairing (~160 lines)
17. **Late-binding** — pending mechanism registration, deferred completion callback (~30 lines)
18. **MechanismSerializer interface** — consumer hook (~15 lines)

### New files (~750 lines)
- `DisplayAnimation.java` — interface + Animations factory
- `Mechanism.java` — interface
- `BasicMechanism.java` — implementation
- `MechanismBlockData.java` — record (with mutable state + storage)
- `MechanismSerializer.java` — consumer hook interface
- `MechanismRegistry.java` — tracking, persistence, recovery, ticks
- `TeleportCompat.java` — passenger-safe teleport utility

### Modified files
- `DisplayUtil.java` — generalize to Display, add BlockDisplay
- `CustomHeadBlock.java` — DisplayEntityConfig animation field
- `BlockLoader.java` — parse animation config
- `CustomBlockRegistry.java` — animationTracked map + tick + cleanup, assembleMechanism delegation
- `CoreLibPlugin.java` — EntityDamageEvent, PlayerInteractEntityEvent, mechanism chunk load

### Estimated total: ~1050 lines new + ~120 lines modified

## Verification

### Animations
- Demo block with rotating display → smooth rotation
- Composed bob+rotate → bobs while rotating
- Break block → animation stops, tracking cleaned
- Chunk unload + reload → animation resumes (display entity persists, tracking re-registered)

### Mechanisms
- `assembleMechanism()` → blocks disappear, displays + colliders appear as passenger chain
- `mech.move(loc, yaw, 0)` → everything moves together (TeleportCompat handles passengers)
- `mech.rotate(90, 0)` → everything rotates together
- Candle inside mechanism → toggleable via shulker click, particles at correct position
- Animated display inside mechanism → keeps animating while mechanism moves
- Container inside mechanism → openable via shulker click, items persist
- `mech.disassemble()` → blocks restored with correct state, container contents restored
- Explosion near mechanism → entities protected (damage cancelled)
- State transition in mechanism → display updated in-place (no remove+respawn)
- Server restart → entities recovered, mechanism resumes
- Ship spanning chunks → two-pass recovery + incremental pairing
- Consumer plugin loads late → pending mechanisms handed over after registration
- Attach to minecart via `mech.setVehicle()` → displays follow minecart
