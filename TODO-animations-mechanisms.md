# Design: Animations + Mechanisms (v4)

## Part 1: Display Entity Animations ✅ COMPLETE

Implemented and committed. Key files:
- `DisplayAnimation.java` — `@FunctionalInterface` + `Animations` factory (rotate, bob, pulse, orbit, compose)
- `CustomHeadBlock.DisplayEntityConfig` — gained `animation` + `interpolationDuration` fields
- `BlockLoader.java` — recursive `parseAnimation(Map)`, `display_entities` in state overrides
- `CustomBlockRegistry.java` — `animationTracked` map, `tickAnimations()` per tick, chunk load re-registration
- `DisplayUtil.java` — generalized to `Display` supertype, added `spawnBlock()` for BlockDisplay

Pre-multiply fix applied to `Animations.rotate()` for rigid-body rotation of anisotropic-scale displays.

---

## Part 2: Mechanism System

### Design principles

1. **Passenger chain** (vehicle → parent → displays) matching BlockShips' battle-tested approach
2. **Consumer moves via `move()`/`rotate()`** — one call moves displays AND colliders
3. **Yaw-only** — no pitch. Simplifies disassembly (90° snap). Pitch can be added later.
4. **Animations and particles tick independently** of consumer movement calls
5. **Custom head blocks inside mechanisms keep working** via a separate code path (no world Skull)
6. **Multiple displays per block** — `List<List<Display>>`, supports `display_entities` configs
7. **No redstone inside mechanisms** — state only changes via `setBlockState()` / player interaction
8. **Shulker collision is axis-aligned** — colliders only match visuals at 90° increments
9. **Persistence deferred** — not in initial pass, but tag format + data structures ready for bolt-on

### Core API

```java
// Mechanism.java
public interface Mechanism {
    UUID id();
    String type();             // consumer-provided: "demo:door", "blockships:ship"
    Location pivot();
    int blockCount();
    float getCurrentYaw();

    // --- Movement ---
    void move(Location position, float yaw);  // teleport vehicle + rotate
    void rotate(float yaw);                   // pivot stays, only transform updates

    // --- Per-block state ---
    MechanismBlockData getBlock(int index);
    void setBlockState(int index, String state);

    // --- Storage ---
    @Nullable Inventory getStorage(int blockIndex);

    // --- Lifecycle ---
    void disassemble();        // restore blocks to world
    void destroy();            // remove all entities without restoring

    // --- Entity access ---
    ArmorStand vehicle();
    List<List<Display>> displays();   // outer = per block, inner = primary + display_entities
    List<ColliderPair> colliders();
    void setVehicle(Entity newVehicle);
}

record ColliderPair(ArmorStand carrier, Shulker shulker, int blockIndex) {}
```

### MechanismBlockData

Not a record — needs mutable fields for state transitions:

```java
public final class MechanismBlockData {
    public final BlockData blockData;
    public final Matrix4f localTransform;     // offset from pivot
    public final boolean hasCollision;
    public final float collisionScale;
    public final @Nullable String customTypeId;
    public String customState;                // mutable — setBlockState()
    public @Nullable List<DisplayEntityConfig> displayEntityConfigs;
    public @Nullable ParticleConfig particles;
    public @Nullable Inventory storage;       // deep-copied ItemStack[] at assembly
}
```

### Yaw sign convention

**JOML vs Minecraft yaw:** JOML `rotateY(+angle)` is CCW from above (right-hand rule).
Minecraft positive yaw is CW from above. Must negate:

```java
Matrix4f rot = new Matrix4f().rotateY((float) Math.toRadians(-yaw));
```

BlockShips does the same negation (ShipInstance.java:1347).

### Entity chain (from BlockShips)

```
ArmorStand vehicle (invisible, yaw frozen at 0°, at pivot)
  └─ BlockDisplay parent (AIR, invisible, coordinate anchor)
      ├─ Display block[0] primary (BlockDisplay for vanilla, ItemDisplay for custom head)
      ├─ Display block[0] extra[0] (from display_entities config, if any)
      ├─ Display block[1] primary
      └─ ...
```

**Critical patterns:**
- **1-tick delay** for passenger mounting (`runTaskLater(plugin, 1L)`) — mounting in same tick causes issues
- **Vehicle yaw frozen at 0** — all rotation via display transforms, avoids ArmorStand jitter
- **Spawn displays at Y+2.5 offset** — prevents 1-tick flash at ground level before mounting
- **All displays get:** `setTeleportDuration(0)`, `setShadowRadius(0f)`, `setShadowStrength(0f)`,
  `setViewRange(64f)`, `setPersistent(true)`, `setGravity(false)`, `setInterpolationDuration(2)`
- **Colliders are independent** (NOT passengers) — carrier ArmorStands teleported via TeleportCompat

### How `rotate()` works

```java
void rotate(float yaw) {
    this.currentYaw = yaw;
    // Negate for JOML convention
    Matrix4f rot = new Matrix4f().rotateY((float) Math.toRadians(-yaw));
    this.currentTransform = rot;

    // Update each display group's transform
    for (int i = 0; i < blocks.size(); i++) {
        Matrix4f dm = new Matrix4f(rot).mul(blocks.get(i).localTransform);
        for (Display d : displaysPerBlock.get(i)) {
            d.setTransformationMatrix(dm);
        }
    }

    // Reposition collider carriers (independent entities)
    for (ColliderPair cp : colliders) {
        Vector3f worldOff = rot.transformPosition(
            blocks.get(cp.blockIndex()).localTransform.getTranslation(new Vector3f()),
            new Vector3f());
        TeleportCompat.teleport(cp.carrier(),
            pivot.clone().add(worldOff.x, worldOff.y, worldOff.z));
    }
}

void move(Location pos, float yaw) {
    TeleportCompat.teleport(vehicle, pos);
    this.pivot = pos;
    rotate(yaw);
}
```

`currentTransform` initialized to identity in constructor (avoids NPE before first rotate).

### Mechanism state transitions (separate from static applyConfig)

```java
void setBlockState(int index, String newState) {
    MechanismBlockData mb = blocks.get(index);
    if (mb.customTypeId == null) return;
    CustomHeadBlock type = registry.getType(mb.customTypeId);

    mb.customState = newState;

    // Update primary display (skull texture)
    Display primary = displaysPerBlock.get(index).get(0);
    if (primary instanceof ItemDisplay id) {
        String tex = type.resolveTexture(newState, 0, null);
        id.setItemStack(HeadUtil.createHead(tex, 1));
    }

    // Update configs for tick loop
    mb.particles = type.resolveParticles(newState);
    mb.displayEntityConfigs = type.resolveDisplayEntities(newState);

    // NOTE: if display entity count changes between states, additional displays
    // are NOT spawned/removed in this initial implementation. Only the primary
    // display and the config references are updated. Known limitation.
}
```

### Assembly

```java
public Mechanism assembleMechanism(String type, List<Block> blocks, Location pivot) {
    List<MechanismBlockData> blockData = new ArrayList<>();

    // 1. Snapshot each block
    for (Block block : blocks) {
        // ... snapshot BlockData, localTransform, custom state, particles,
        //     displayEntityConfigs, container inventory (deep-copy ItemStack[])
        // For custom heads: call registry.onBlockRemoved(block, chb) to clean tracking
    }

    // 2. Two-pass block removal (prevents item drops from attachables losing support)
    //    Pass 1: remove attachable blocks (banners, signs, torches, buttons, etc.)
    //    Pass 2: remove solid blocks
    //    (copy attachable detection from BlockShips BlockStructureScanner.java)

    // 3. Spawn entities
    //    - Vehicle: invisible ArmorStand at pivot, yaw=0
    //    - Parent: BlockDisplay(AIR), invisible
    //    - Per block: primary display + additional displays from display_entities config
    //      -> BlockDisplay for vanilla blocks, ItemDisplay for custom heads
    //      -> Spawn all at Y+2.5 offset to avoid ground flash
    //    - Per collision block: carrier ArmorStand + Shulker passenger
    //    - Tag everything: "corelib:mech:{uuid}:{index}:{role}"

    // 4. 1-tick delay: mount displays to parent, parent to vehicle, call rotate(0)

    // 5. Register in activeMechanisms, return mechanism
}
```

### Disassembly — three-tier placement

```java
void disassemble() {
    float snappedYaw = Math.round(currentYaw / 90f) * 90f;
    Matrix4f rotation = new Matrix4f().rotateY((float) Math.toRadians(-snappedYaw));

    for (int i = 0; i < blocks.size(); i++) {
        MechanismBlockData mb = blocks.get(i);
        Vector3f worldOffset = rotation.transformPosition(
            mb.localTransform.getTranslation(new Vector3f()), new Vector3f());
        Location blockLoc = pivot.clone().add(
            Math.round(worldOffset.x), Math.round(worldOffset.y), Math.round(worldOffset.z));
        Block target = blockLoc.getBlock();

        if (target.getType().isAir() || target.getType() == Material.WATER
                || target.getType() == Material.LAVA) {
            // AIR / liquid: place directly
            placeBlock(target, mb, snappedYaw);
        } else if (FragileBlocks.isFragile(target.getType())) {
            // FRAGILE (grass, flowers, leaves, etc.): break world block, place ours
            target.breakNaturally();  // drops the fragile block's items
            placeBlock(target, mb, snappedYaw);
        } else {
            // SOLID: world block wins — play small explosion, drop mechanism block as item
            target.getWorld().spawnParticle(Particle.EXPLOSION,
                blockLoc.clone().add(0.5, 0.5, 0.5), 1);
            dropBlockAsItem(blockLoc, mb);
        }
    }

    // Remove all entities
    for (var displayGroup : displaysPerBlock) displayGroup.forEach(Entity::remove);
    colliders.forEach(cp -> { cp.carrier().remove(); cp.shulker().remove(); });
    parent.remove();
    vehicle.remove();

    mechanismRegistry.remove(id);
}

private void placeBlock(Block target, MechanismBlockData mb, float snappedYaw) {
    target.setBlockData(rotateBlockData(mb.blockData, snappedYaw));
    if (mb.customTypeId != null) {
        CustomHeadBlock type = registry.getType(mb.customTypeId);
        if (type != null) {
            registry.markBlock(target, type);
            if (mb.customState != null) registry.setState(target, mb.customState);
            int power = registry.readPower(target, type);
            registry.applyConfig(target, type, mb.customState, power);
            registry.restoreBlock(target, type, mb.customState);
            if (mb.storage != null) registry.restoreInventoryToPDC(target, mb.storage);
        }
    } else if (mb.storage != null && target.getState() instanceof Container c) {
        c.getSnapshotInventory().setContents(mb.storage.getContents());
        c.update();
    }
}
```

### FragileBlocks

Copy from BlockShips `FragileBlocks.java`. Static `Set<Material>` containing ~80 materials:
grasses, flowers, crops, mushrooms, leaves, saplings, snow, cobweb, fire, lily pad, sugar cane,
cactus, bamboo, vines, etc. Static `isFragile(Material)` check.

### `rotateBlockData()`

Copy from BlockShips `BlockStructureScanner.java:104-170`. Handles:
- **Directional** (stairs, chests, furnaces) — rotate facing
- **Orientable** (logs, pillars) — swap X↔Z axis on 90/270°
- **Rotatable** (floor heads, banners, signs) — 16-step rotation (22.5° per step)
- **MultipleFacing** (fences, walls) — rotate all connected faces

For the door demo (OAK_PLANKS), rotation is a no-op. But needed for general mechanisms.

### Independent particle + animation ticks

```java
void tickMechanisms() {
    long currentTick = Bukkit.getServer().getCurrentTick();
    for (BasicMechanism mech : activeMechanisms.values()) {
        for (int i = 0; i < mech.blockCount(); i++) {
            // Skip invalid entities (chunk unloaded, etc.)
            List<Display> displays = mech.displaysPerBlock.get(i);
            if (displays.isEmpty() || !displays.get(0).isValid()) continue;

            MechanismBlockData mb = mech.blocks.get(i);

            // Particles
            if (mb.particles != null) {
                Vector3f worldPos = mech.currentTransform.transformPosition(
                    mb.localTransform.getTranslation(new Vector3f()), new Vector3f());
                Location loc = mech.pivot().clone().add(worldPos.x, worldPos.y, worldPos.z);
                // spawn particle at loc (respecting interval, count, speed)
            }

            // Animations (on additional displays from display_entities config)
            if (mb.displayEntityConfigs != null) {
                for (int d = 0; d < mb.displayEntityConfigs.size(); d++) {
                    var dec = mb.displayEntityConfigs.get(d);
                    if (dec.animation() == null) continue;
                    // +1 because index 0 in displaysPerBlock is the primary display
                    int displayIdx = d + 1;
                    if (displayIdx >= displays.size()) continue;
                    Display display = displays.get(displayIdx);
                    if (!display.isValid()) continue;

                    Matrix4f base = new Matrix4f(mech.currentTransform)
                        .mul(transformToMatrix(dec.transform()));
                    long tickAge = currentTick - mech.startTick;
                    dec.animation().apply(base, tickAge, workMatrix);
                    display.setTransformationMatrix(workMatrix);
                }
            }
        }
    }
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

    // Storage
    if (mb.storage != null) {
        event.getPlayer().openInventory(mb.storage);
        return;
    }

    // Custom block state transitions
    if (mb.customTypeId == null) return;
    CustomHeadBlock type = registry.getType(mb.customTypeId);
    if (type == null) return;
    // ... handle interact GUI, state transitions via mech.setBlockState()
}
```

### Entity protection

```java
@EventHandler
public void onEntityDamage(EntityDamageEvent event) {
    for (String tag : event.getEntity().getScoreboardTags()) {
        if (tag.startsWith("corelib:mech:")) {
            event.setCancelled(true);
            return;
        }
    }
}
```

### Required API changes

**`CustomHeadBlock.java`** — two new callbacks:
```java
private final @Nullable BiConsumer<Block, String> onStateChanged;    // (block, newState)
private final @Nullable Consumer<Block> onBlockRemoved;              // (block)
// + builder methods + accessors
```

**`CustomBlockRegistry.java`** — visibility + callback wiring:
- `restoreBlock()`: private → **public**
- `applyConfig()`: package-private → **public**
- `onBlockRemoved()`: package-private → **public**
- At end of `transitionState()`: invoke `type.onStateChanged()` if non-null
- At start of `onBlockRemoved()`: invoke `type.onBlockRemoved()` callback if non-null

---

## Part 3: Mechanism Persistence (DEFERRED)

Not implemented in initial pass. Structure code for easy bolt-on later:

- **Tag format** on all entities from day one: `corelib:mech:{uuid}:{blockIndex}:{role}`
  (roles: `vehicle`, `parent`, `display`, `carrier`, `collider`)
- **`MechanismSerializer` interface** exists from the start (consumers can register early)
- **`BasicMechanism`** stores all data needed for serialization (blocks, pivot, yaw, type)

When persistence is added later, copy from BlockShips:
- Per-mechanism YAML at `mechanisms/{world}/{uuid}.yml`
- Chunk index at `mechanisms/{world}/chunks.yml`
- Inventory serialization: `ItemStack.serializeAsBytes()` → Base64, pipe-delimited per slot
- Single-threaded I/O executor for writes
- Two-pass entity recovery: chunk entities by tag, then `getNearbyEntities(loc, 32, 32, 32)` fallback
- Late-binding: `pendingMechanisms` map for consumer plugins not yet loaded

---

## Part 4: DisplayUtil generalization ✅ COMPLETE

Already generalized to `Display` supertype. `spawnBlock()` added for BlockDisplay.

---

## Part 5: Door Demo

### Block type

Custom head block `demo:door_controller` registered from `DoorDemo.java` inside defCoreLib:

```java
CustomHeadBlock.builder("demo", "door_controller")
    .name(Component.text("Door Controller"))
    .texture("...hinge_texture...")
    .drops(DropRule.self())
    .defaultState("closed")
    .states(Map.of("closed", StateConfig.empty(), "open", StateConfig.empty()))
    .redstone(new RedstoneConfig(Sensitivity.BINARY, PowerReader.EXTENDED, Map.of(), Map.of()))
    .transitions(/* closed→open on power 1-15, open→closed on power 0 */)
    .onStateChanged((block, newState) -> {
        if ("open".equals(newState)) openDoor(block);
        else closeDoor(block);
    })
    .onBlockRemoved(block -> cleanupDoor(block))
    .build();
```

### BFS flood fill

6-direction cardinal only (use `CARDINAL_FACES` constant, NOT `BlockFace.values()`).
Seeds from head's 6 neighbors, BFS through `OAK_PLANKS`, max 256 blocks, head excluded.

```java
private static final BlockFace[] CARDINAL_FACES = {
    BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST,
    BlockFace.UP, BlockFace.DOWN
};

List<Block> floodFill(Block origin, Material target, int maxBlocks) {
    Set<Block> visited = new HashSet<>();
    Queue<Block> queue = new ArrayDeque<>();
    visited.add(origin); // exclude head itself
    for (BlockFace face : CARDINAL_FACES) queue.add(origin.getRelative(face));
    List<Block> result = new ArrayList<>();
    while (!queue.isEmpty() && result.size() < maxBlocks) {
        Block b = queue.poll();
        if (!visited.add(b) || b.getType() != target) continue;
        result.add(b);
        for (BlockFace face : CARDINAL_FACES) queue.add(b.getRelative(face));
    }
    return result;
}
```

### Pivot

`head.getLocation().add(0.5, 0, 0.5)` — block center in XZ, floor Y. Preserves grid alignment
at 90° snaps (±0.5 local offsets rotate to ±0.5 → integer positions).

### Smooth rotation

20-tick `runTaskTimer`. Final tick forces exact target angle (90° or 0°).
Picks up from `mech.getCurrentYaw()` to handle mid-animation interrupts.

```java
void openDoor(Block head) {
    LocationKey key = LocationKey.of(head);
    cancelExistingTask(key);  // cancel any in-progress animation

    List<Block> planks = floodFill(head, Material.OAK_PLANKS, 256);
    if (planks.isEmpty()) return;  // no mechanism created, state stays "open" harmlessly

    Mechanism mech = mechanismRegistry.assembleMechanism("demo:door", planks,
        head.getLocation().add(0.5, 0, 0.5));
    activeDoors.put(key, mech);

    float startYaw = mech.getCurrentYaw();
    float targetYaw = 90f;
    int duration = 20;
    BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
        int tick = 0;
        public void run() {
            tick++;
            float yaw = (tick >= duration) ? targetYaw
                : startYaw + (targetYaw - startYaw) * ((float) tick / duration);
            mech.rotate(yaw);
            if (tick >= duration) activeTasks.remove(key).cancel();
        }
    }, 0, 1);
    activeTasks.put(key, task);
}

void closeDoor(Block head) {
    LocationKey key = LocationKey.of(head);
    cancelExistingTask(key);

    Mechanism mech = activeDoors.get(key);
    if (mech == null) return;  // null guard: no mechanism to close

    float startYaw = mech.getCurrentYaw();
    float targetYaw = 0f;
    int duration = 20;
    BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
        int tick = 0;
        public void run() {
            tick++;
            float yaw = (tick >= duration) ? targetYaw
                : startYaw + (targetYaw - startYaw) * ((float) tick / duration);
            mech.rotate(yaw);
            if (tick >= duration) {
                activeTasks.remove(key).cancel();
                mech.disassemble();
                activeDoors.remove(key);
            }
        }
    }, 0, 1);
    activeTasks.put(key, task);
}

void cleanupDoor(Block head) {
    LocationKey key = LocationKey.of(head);
    cancelExistingTask(key);
    Mechanism mech = activeDoors.remove(key);
    if (mech != null) mech.disassemble();
}

void cancelExistingTask(LocationKey key) {
    BukkitTask task = activeTasks.remove(key);
    if (task != null) task.cancel();
}
```

### State tracking

```java
private final Map<LocationKey, Mechanism> activeDoors = new HashMap<>();
private final Map<LocationKey, BukkitTask> activeTasks = new HashMap<>();
```

---

## Implementation order

1. ~~DisplayUtil generalization~~ ✅
2. ~~DisplayAnimation + Animations~~ ✅
3. ~~Animation YAML parsing~~ ✅
4. ~~Animation tick~~ ✅
5. **`CustomHeadBlock` callbacks** — add `onStateChanged` + `onBlockRemoved` (~20 lines)
6. **`CustomBlockRegistry` visibility** — make `restoreBlock`, `applyConfig`, `onBlockRemoved` public;
   wire `onStateChanged` at end of `transitionState()`, `onBlockRemoved` callback at start of `onBlockRemoved()` (~15 lines)
7. **TeleportCompat** — eject-teleport-readd utility, version detection (~30 lines)
8. **FragileBlocks** — copy from BlockShips, static `Set<Material>` + `isFragile()` (~130 lines)
9. **`rotateBlockData()`** — copy from BlockShips, handles Directional/Orientable/Rotatable/MultipleFacing (~70 lines)
10. **MechanismBlockData** — mutable class, not record (~35 lines)
11. **Mechanism interface** + ColliderPair record (~50 lines)
12. **BasicMechanism** — rotate (negated yaw), move, setBlockState, disassemble (three-tier), destroy (~300 lines)
13. **MechanismRegistry** — assembly (two-pass removal, 1-tick mount, spawn offset), tick (isValid checks),
    collider index (~350 lines)
14. **MechanismSerializer interface** — consumer hook, stub for persistence (~15 lines)
15. **CoreLibPlugin wiring** — EntityDamageEvent protection, PlayerInteractEntityEvent on shulkers (~40 lines)
16. **DoorDemo** — block registration, BFS, rotation scheduling, task management, cleanup (~150 lines)

### New files (~1050 lines)
- `TeleportCompat.java` (~30) — passenger-safe teleport
- `FragileBlocks.java` (~130) — fragile block set (from BlockShips)
- `Mechanism.java` (~50) — public API interface
- `MechanismBlockData.java` (~35) — mutable block snapshot
- `BasicMechanism.java` (~300) — implementation
- `MechanismRegistry.java` (~350) — assembly, tracking, ticks
- `MechanismSerializer.java` (~15) — consumer hook interface
- `DoorDemo.java` (~150) — door controller demo

### Modified files
- `CustomHeadBlock.java` — `onStateChanged` + `onBlockRemoved` callbacks
- `CustomBlockRegistry.java` — public visibility, callback wiring
- `CoreLibPlugin.java` — EntityDamageEvent, PlayerInteractEntityEvent, DoorDemo wiring

## Verification

### Animations ✅
- Rotating crystal: smooth rotation
- Composed bob+rotate: bobs while rotating
- Break block: animation stops, tracking cleaned
- Chunk reload: animation resumes

### Mechanisms (door demo)
- Place door controller, surround with oak planks
- Apply redstone → planks disappear, door rotates 90° CW over 1 second
- Remove redstone → door rotates back, planks restored to world
- Shulker colliders block movement through closed door
- Rapid toggle (power on/off/on) → animation cancels cleanly, no conflicting tasks
- Break controller while open → disassemble at current yaw, planks restored
- Break controller while animating → task cancelled, disassemble immediately
- Planks obstructed while open → three-tier: air=place, fragile=break+place, solid=explosion+drop
- No planks found on power-on → state says "open" but no mechanism, power-off is harmless (null guard)
- Server restart → entities lost (persistence deferred), entities tagged for future recovery

### Known limitations (initial pass)
- `setBlockState()` does not handle display entity count changes between states
- No persistence — mechanisms lost on server restart
- No crash recovery during assembly (blocks removed before entities fully spawned)
- Shared planks between adjacent doors: first-come-first-served, no conflict detection
