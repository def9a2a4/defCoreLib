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

    // --- Read-only entity access ---
    boolean hasCollision(int blockIndex);
    Display primaryDisplay(int blockIndex);
}

// ColliderPair is package-private — consumers use hasCollision(int) on the interface.
// BasicMechanism exposes vehicle(), displays(), colliders(), setVehicle() directly
// for advanced consumers who downcast.
```

### MechanismBlockData

Not a record — needs mutable fields for state transitions.
Mutable fields are package-private with public getters. Only `BasicMechanism.setBlockState()` mutates.

```java
public final class MechanismBlockData {
    public final BlockData blockData;
    public final Matrix4f localTransform;     // offset from pivot
    public final boolean hasCollision;
    public final float collisionScale;
    public final @Nullable String customTypeId;
    String customState;                       // pkg-private, mutable via setBlockState()
    @Nullable List<DisplayEntityConfig> displayEntityConfigs;
    @Nullable ParticleConfig particles;
    @Nullable Inventory storage;

    public String customState() { return customState; }
    public @Nullable List<DisplayEntityConfig> displayEntityConfigs() { return displayEntityConfigs; }
    public @Nullable ParticleConfig particles() { return particles; }
    public @Nullable Inventory storage() { return storage; }
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
    checkMainThread();
    this.currentYaw = yaw;
    // Negate for JOML convention
    Matrix4f rot = new Matrix4f().rotateY((float) Math.toRadians(-yaw));
    this.currentTransform = rot;

    // Update each display group's transform
    for (int i = 0; i < blocks.size(); i++) {
        MechanismBlockData mb = blocks.get(i);
        Matrix4f dm = new Matrix4f(rot).mul(mb.localTransform);

        // Primary display (index 0): rot * localTransform
        displaysPerBlock.get(i).get(0).setTransformationMatrix(dm);

        // Additional displays (index 1+): rot * localTransform * decTransform
        // Skip animated ones — tickMechanisms() handles those each tick
        if (mb.displayEntityConfigs != null) {
            for (int d = 0; d < mb.displayEntityConfigs.size(); d++) {
                int displayIdx = d + 1;
                if (displayIdx >= displaysPerBlock.get(i).size()) break;
                var dec = mb.displayEntityConfigs.get(d);
                if (dec.animation() != null) continue; // tick loop handles animated
                Matrix4f extra = new Matrix4f(dm).mul(transformToMatrix(dec.transform()));
                displaysPerBlock.get(i).get(displayIdx).setTransformationMatrix(extra);
            }
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
public Mechanism assembleMechanism(String type, List<Block> blocks, Location pivot,
                                   @Nullable MechanismSerializer serializer) {
    UUID mechId = UUID.randomUUID();
    List<MechanismBlockData> blockData = new ArrayList<>();

    // 1. Snapshot each block
    for (Block block : blocks) {
        BlockData bd = block.getBlockData();
        Matrix4f local = new Matrix4f().translation(
            block.getX() - (float) pivot.getX(),
            block.getY() - (float) pivot.getY(),
            block.getZ() - (float) pivot.getZ());

        String customType = null, customState = null;
        List<DisplayEntityConfig> decs = null;
        ParticleConfig particles = null;
        Inventory storage = null;

        CustomHeadBlock chb = registry.getTypeFromBlock(block);
        if (chb != null) {
            customType = chb.fullId();
            customState = registry.getState(block);
            decs = chb.resolveDisplayEntities(customState);
            particles = chb.resolveParticles(customState);
            // Snapshot container inventory (deep-copy)
            if (chb.storage() != null) {
                storage = Bukkit.createInventory(null, chb.storage().layout().slots);
                registry.loadInventoryFromPDC(block, storage);
                for (int s = 0; s < storage.getSize(); s++) {
                    ItemStack item = storage.getItem(s);
                    if (item != null) storage.setItem(s, item.clone());
                }
            }
            registry.onBlockRemoved(block, chb); // clean all static tracking
        } else if (block.getState() instanceof Container c) {
            Inventory orig = c.getInventory();
            storage = Bukkit.createInventory(null, orig.getSize());
            for (int s = 0; s < orig.getSize(); s++) {
                ItemStack item = orig.getItem(s);
                if (item != null) storage.setItem(s, item.clone());
            }
        }

        blockData.add(new MechanismBlockData(bd, local, true, 1.0f,
            customType, customState, decs, particles, storage));
    }

    // 2. Two-pass block removal (prevents item drops from attachables losing support)
    //    Pass 1: remove attachable blocks (banners, signs, torches, etc.)
    for (Block b : blocks) if (FragileBlocks.isAttachable(b.getType())) b.setType(Material.AIR, false);
    //    Pass 2: remove remaining solid blocks
    for (Block b : blocks) if (b.getType() != Material.AIR) b.setType(Material.AIR, false);

    // 3. Spawn entities — all at Y+2.5 offset to avoid 1-tick ground flash
    Location spawnLoc = pivot.clone().add(0, 2.5, 0);

    ArmorStand vehicle = pivot.getWorld().spawn(pivot, ArmorStand.class, as -> {
        as.setInvisible(true); as.setGravity(false); as.setSilent(true);
        as.setPersistent(true); as.setRotation(0, 0); // yaw frozen at 0
        as.addScoreboardTag("corelib:mech:" + mechId + ":vehicle");
    });

    BlockDisplay parent = pivot.getWorld().spawn(spawnLoc, BlockDisplay.class, d -> {
        d.setBlock(Bukkit.createBlockData(Material.AIR));
        d.setTeleportDuration(0); d.setViewRange(64f);
        d.setPersistent(true); d.setGravity(false);
        d.addScoreboardTag("corelib:mech:" + mechId + ":parent");
    });

    List<List<Display>> displaysPerBlock = new ArrayList<>();
    List<ColliderPair> colliders = new ArrayList<>();

    for (int i = 0; i < blockData.size(); i++) {
        MechanismBlockData mb = blockData.get(i);
        List<Display> group = new ArrayList<>();

        // Primary display
        Display primary;
        if (mb.customTypeId != null) {
            CustomHeadBlock chbType = registry.getType(mb.customTypeId);
            String tex = chbType.resolveTexture(mb.customState, 0, null);
            primary = spawnMechDisplay(spawnLoc, HeadUtil.createHead(tex, 1), mechId, i, "display");
        } else {
            primary = spawnMechBlockDisplay(spawnLoc, mb.blockData, mechId, i, "display");
        }
        group.add(primary);

        // Additional displays from display_entities config
        if (mb.displayEntityConfigs != null) {
            for (int d = 0; d < mb.displayEntityConfigs.size(); d++) {
                var dec = mb.displayEntityConfigs.get(d);
                ItemStack item = HeadUtil.createHead(dec.itemTexture(), 1);
                Display extra = spawnMechDisplay(spawnLoc, item, mechId, i, "extra_" + d);
                if (dec.interpolationDuration() != 0)
                    extra.setInterpolationDuration(dec.interpolationDuration());
                group.add(extra);
            }
        }
        displaysPerBlock.add(group);

        // Collider: carrier ArmorStand + Shulker passenger
        if (mb.hasCollision) {
            ArmorStand carrier = pivot.getWorld().spawn(spawnLoc, ArmorStand.class, as -> {
                as.setInvisible(true); as.setGravity(false); as.setSilent(true);
                as.setSmall(true); as.setPersistent(true);
                as.addScoreboardTag("corelib:mech:" + mechId + ":" + i + ":carrier");
            });
            Shulker shulker = pivot.getWorld().spawn(spawnLoc, Shulker.class, s -> {
                s.setAI(false); s.setInvisible(true); s.setGravity(false);
                s.setSilent(true); s.setPersistent(true);
                s.addScoreboardTag("corelib:mech:" + mechId + ":" + i + ":collider");
            });
            carrier.addPassenger(shulker);
            colliders.add(new ColliderPair(carrier, shulker, i));
            colliderIndex.put(shulker.getUniqueId(), new ColliderRef(mech, i));
        }
    }

    // 4. 1-tick delay: mount passengers, set initial transforms
    BasicMechanism mech = new BasicMechanism(mechId, type, pivot, vehicle, parent,
        displaysPerBlock, colliders, blockData, registry, serializer);
    Bukkit.getScheduler().runTaskLater(plugin, () -> {
        for (var group : displaysPerBlock)
            for (Display d : group) parent.addPassenger(d);
        vehicle.addPassenger(parent);
        mech.rotate(0); // sets all transforms to identity * localTransform [* decTransform]
    }, 1L);

    activeMechanisms.put(mechId, mech);
    return mech;
}

// Helper: spawn ItemDisplay with standard mechanism properties
private ItemDisplay spawnMechDisplay(Location loc, ItemStack item,
                                      UUID mechId, int blockIdx, String role) {
    return loc.getWorld().spawn(loc, ItemDisplay.class, d -> {
        d.setItemStack(item);
        d.setTeleportDuration(0); d.setShadowRadius(0f); d.setShadowStrength(0f);
        d.setViewRange(64f); d.setPersistent(true); d.setGravity(false);
        d.setInterpolationDuration(2);
        d.addScoreboardTag("corelib:mech:" + mechId + ":" + blockIdx + ":" + role);
    });
}

// Helper: spawn BlockDisplay for vanilla blocks
private BlockDisplay spawnMechBlockDisplay(Location loc, BlockData data,
                                            UUID mechId, int blockIdx, String role) {
    return loc.getWorld().spawn(loc, BlockDisplay.class, d -> {
        d.setBlock(data);
        d.setTeleportDuration(0); d.setShadowRadius(0f); d.setShadowStrength(0f);
        d.setViewRange(64f); d.setPersistent(true); d.setGravity(false);
        d.setInterpolationDuration(2);
        d.addScoreboardTag("corelib:mech:" + mechId + ":" + blockIdx + ":" + role);
    });
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

### `dropBlockAsItem()` — used when disassembly target is obstructed by a solid block

```java
private void dropBlockAsItem(Location loc, MechanismBlockData mb) {
    ItemStack drop;
    if (mb.customTypeId != null) {
        CustomHeadBlock type = registry.getType(mb.customTypeId);
        drop = (type != null) ? type.createItem(1) : new ItemStack(mb.blockData.getMaterial());
    } else {
        drop = new ItemStack(mb.blockData.getMaterial());
    }
    loc.getWorld().dropItemNaturally(loc.clone().add(0.5, 0.5, 0.5), drop);
    // Container inventory: also drop all stored items
    if (mb.storage != null) {
        for (ItemStack item : mb.storage.getContents()) {
            if (item != null && !item.getType().isAir())
                loc.getWorld().dropItemNaturally(loc.clone().add(0.5, 0.5, 0.5), item);
        }
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

                    // Must include localTransform — without it, animated displays
                    // cluster at the pivot instead of at their respective blocks
                    Matrix4f base = new Matrix4f(mech.currentTransform)
                        .mul(mb.localTransform)
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

**`CustomHeadBlock.java`** — two new callbacks + a functional interface:
```java
@FunctionalInterface
public interface StateChangeHandler {
    void accept(Block block, String oldState, String newState);
}

private final @Nullable StateChangeHandler onStateChanged;
private final @Nullable BiConsumer<Block, String> onBlockRemoved;  // (block, lastState)
// + builder methods + accessors
```

`onStateChanged` receives both old and new state — enables delta logic (e.g., sound only on `closed→open`).
`onBlockRemoved` receives the block's last known state — enables cleanup based on what state the block was in.

**`CustomBlockRegistry.java`** — visibility + callback wiring:
- `restoreBlock()`: private → **public**
- `applyConfig()`: package-private → **public**
- `onBlockRemoved()`: package-private → **public**
- At end of `transitionState()`: invoke `type.onStateChanged().accept(block, fromState, toState)` if non-null
- At start of `onBlockRemoved()`: read state from PDC, invoke `type.onBlockRemoved().accept(block, state)` if non-null

**Thread safety:** `BasicMechanism` mutating methods (`move`, `rotate`, `setBlockState`, `disassemble`,
`destroy`) call `checkMainThread()` at entry — throws `IllegalStateException` if `!Bukkit.isPrimaryThread()`.
Read-only accessors (`id`, `pivot`, `blockCount`, `getCurrentYaw`, `getBlock`) are safe from any thread.

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

### Wiring

`DoorDemo.java` is instantiated in `CoreLibPlugin.onEnable()` with constructor injection:

```java
// CoreLibPlugin.onEnable():
MechanismRegistry mechRegistry = new MechanismRegistry(this, registry);
mechRegistry.startTasks();  // register tickMechanisms() timer
DoorDemo doorDemo = new DoorDemo(this, registry, mechRegistry);
doorDemo.register();
```

```java
// DoorDemo.java
class DoorDemo {
    private final JavaPlugin plugin;
    private final CustomBlockRegistry registry;
    private final MechanismRegistry mechRegistry;
    private final Map<LocationKey, Mechanism> activeDoors = new HashMap<>();
    private final Map<LocationKey, BukkitTask> activeTasks = new HashMap<>();

    DoorDemo(JavaPlugin plugin, CustomBlockRegistry registry, MechanismRegistry mechRegistry) {
        this.plugin = plugin;
        this.registry = registry;
        this.mechRegistry = mechRegistry;
    }

    void register() {
        registry.register(buildDoorController());
    }
    // ... openDoor, closeDoor, cleanupDoor, floodFill methods below
}
```

### Block type

```java
private CustomHeadBlock buildDoorController() {
    return CustomHeadBlock.builder("demo", "door_controller")
        .name(Component.text("Door Controller"))
        .texture("...hinge_texture...")
        .drops(DropRule.self())
        .defaultState("closed")
        .states(Map.of("closed", StateConfig.empty(), "open", StateConfig.empty()))
        .redstone(new RedstoneConfig(Sensitivity.BINARY, PowerReader.EXTENDED, Map.of(), Map.of()))
        .transitions(List.of(
            new StateTransition(new Trigger.RedstonePower(PowerRange.POWERED),
                "closed", "open", null, 1f, 1f, null, false, 0),
            new StateTransition(new Trigger.RedstonePower(PowerRange.ZERO),
                "open", "closed", null, 1f, 1f, null, false, 0)))
        .onStateChanged((block, oldState, newState) -> {
            if ("open".equals(newState)) openDoor(block);
            else if ("closed".equals(newState)) closeDoor(block);
        })
        .onBlockRemoved((block, state) -> cleanupDoor(block))
        .build();
}
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

    // Reuse existing mechanism if planks are already assembled
    // (happens when close animation is interrupted mid-rotation)
    Mechanism mech = activeDoors.get(key);
    if (mech == null) {
        List<Block> planks = floodFill(head, Material.OAK_PLANKS, 256);
        if (planks.isEmpty()) return;  // no mechanism created, state stays "open" harmlessly
        mech = mechanismRegistry.assembleMechanism("demo:door", planks,
            head.getLocation().add(0.5, 0, 0.5), null);
        activeDoors.put(key, mech);
    }

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
