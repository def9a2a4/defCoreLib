# Rotation Power System (Basic Create Mod)

## Context
Adding a rotation power network to defCoreLib — power sources (windmill, water wheel, engine), transmission (shaft, gear, gearbox), and consumers (drill, grindstone). Hybrid architecture: `RotationNetwork` manages graph/power, blocks are `CustomHeadBlock` for visuals.

## Architecture

```
CoreLibPlugin.onEnable()
  ├─ CustomBlockRegistry (existing)
  ├─ RotationNetwork (new — graph, BFS, power math)
  └─ RotationBlocks.register(registry, network)

Place block → markBlock → onChunkLoadCallback → network.addNode → recalculate
  → BFS finds sources → setState("spinning_x") + applyConfig → animation starts
Break block → network.removeNode → recalculate → affected nodes stop
Chunk unload → batch removeNode → single recalculate
Chunk load → onChunkLoadCallback → addNode → recalculate
```

## Design decisions

- **Same package, not subpackage.** MechanismRegistry already lives in `anon.def9a2a4.corelib`. Follow this pattern. Eliminates all visibility issues.
- **Axis in state name:** `{status}_{axis}` — `idle_x`, `spinning_y`, `locked_z`. Derived from placement face.
- **Power model:** Additive units. BFS connected component, sum supply/demand. `supply >= demand` → powered.
- **State updates:** Direct `setState()` + `applyConfig()`. No new Trigger variant.
- **Persistence:** Rebuild from chunk scan. Axis encoded in state name, persists in PDC.
- **Windmill = passive source:** Always spins, never receives state updates from network. BFS discovers it as a source.
- **Visuals:** Custom skull textures for display entities (3D head models via HeadUtil).

## Prerequisites: corelib changes

### A. Add `onInteract` escape hatch to `CustomHeadBlock`
Engine (add fuel) and grindstone (grind items) need custom interact handling.

```java
// New field + accessor + builder method:
private final @Nullable BiFunction<Block, PlayerInteractEvent, Boolean> onInteract;
```

In `CoreLibPlugin.onPlayerInteract()`, invoke AFTER interactSound, BEFORE GUI/storage/transition. If returns `true`, framework calls `event.setCancelled(true)` and returns.

### B. Fix placement path to mirror `restoreBlock` tracking
After `applyConfig()` in deferred placement task:
```java
if (type.onTick() != null && type.tickInterval() != null) {
    registry.trackTick(block, type);
}
if (type.onChunkLoadCallback() != null) {
    type.onChunkLoadCallback().accept(block, state);
}
```

## New files (all in `anon.def9a2a4.corelib`)

### 1. `RotationNetwork.java` (~300 lines)

```java
public class RotationNetwork {
    private final CustomBlockRegistry registry;
    private final JavaPlugin plugin;

    public enum Axis { X, Y, Z }
    public enum NodeRole { SOURCE, TRANSMITTER, CONSUMER }

    record RotationNode(LocationKey key, String blockTypeId, Axis axis,
                        NodeRole role, int powerUnits, boolean gearLike)
    record NetworkState(int supply, int demand) {
        boolean powered() { return supply >= demand; }
    }

    Map<LocationKey, RotationNode> nodes = new HashMap<>();
    Map<LocationKey, Integer> nodeNetworkId = new HashMap<>();
    Map<Integer, Set<LocationKey>> networkMembers = new HashMap<>(); // reverse index
    Map<Integer, NetworkState> networks = new HashMap<>();
    int nextNetworkId = 0;
}
```

**LocationKey → Block:**
```java
private @Nullable Block toBlock(LocationKey key) {
    World world = Bukkit.getWorld(key.worldId());
    return world != null ? world.getBlockAt(key.x(), key.y(), key.z()) : null;
}
```

**Connection rules:**
- Along-axis: 2 neighbors along axis direction. Neighbor must share same axis and not be locked.
- Perpendicular (gear-to-gear only): 4 neighbors in perpendicular plane. Both must be `gearLike`, have different axes, not locked.
- Locked gearbox: excluded from all connections.

**Recalculation (handles both add and remove):**
```
recalculate(changed):
  if changed has old network:
    dirty = all members of old network
    clear old network from indexes
  else (freshly added):
    dirty = {changed} + members of any adjacent networks
    clear those networks from indexes
  remove non-existent nodes from dirty

  for each unassigned node in dirty:
    BFS via getConnections() → new component
    sum supply/demand
    assign network ID to all members

  for each node whose powered state changed:
    if role != SOURCE:
      setState(powered ? "spinning_" : "idle_" + axis)
      applyConfig()
```

**Batch chunk unload:**
```
removeNodesInChunk(worldId, chunkX, chunkZ):
  collect affected nodes and network IDs
  remove nodes from map
  single rebuildFromDirty() on remaining members of affected networks
```

### 2. `RotationBlocks.java` (~450 lines)

Static `register(CustomBlockRegistry, RotationNetwork)`. Namespace: `"rotation"`.

| Block | Role | Power | States | Tick | Special |
|-------|------|-------|--------|------|---------|
| windmill | SOURCE | 1 | spinning_{x,y,z} only | - | Passive source. Always spins. |
| water_wheel | SOURCE | 2 | idle/spinning per axis | - | onNeighborChange checks adjacent water |
| engine | SOURCE | 5 | idle/running per axis | 20 | onInteract: add fuel. onTick: consume fuel |
| shaft | TRANSMITTER | 0 | idle/spinning per axis | - | Display: skull rod texture |
| gear | TRANSMITTER | 0 | idle/spinning per axis | - | gearLike=true. Display: skull gear texture |
| gearbox | TRANSMITTER | 0 | idle/spinning/locked per axis | - | onNeighborChange checks redstone |
| drill | CONSUMER | 1 | idle/spinning per axis | 40 | onTick: breakNaturally() in facing dir |
| grindstone | CONSUMER | 1 | idle/spinning | - | Floor-only. onInteract: grind recipes |

**Common placementStateMap (all axis-aware blocks):**
```java
Map.of(DOWN, "idle_y", NORTH, "idle_z", SOUTH, "idle_z", EAST, "idle_x", WEST, "idle_x")
```

**Common callbacks (all blocks):**
- `.reactsToNeighbors(true)` + `.onNeighborChange()` → `network.recalculate()`
- `.onChunkLoad()` → `network.addNode()`
- `.onChunkUnload()` → `network.removeNode()`

**Drill facing:** stored in PDC (`NamespacedKey("rotation", "facing")`) at placement. Wall heads: `Directional.getFacing()`. Floor heads: player's cardinal facing.

**Drill blacklist:**
```java
Set.of(BEDROCK, OBSIDIAN, CRYING_OBSIDIAN, END_PORTAL_FRAME, BARRIER,
       COMMAND_BLOCK, CHAIN_COMMAND_BLOCK, REPEATING_COMMAND_BLOCK,
       STRUCTURE_BLOCK, JIGSAW, REINFORCED_DEEPSLATE)
```

**Engine fuel:**
```java
NamespacedKey FUEL_KEY = new NamespacedKey("rotation", "fuel_ticks");
// COAL: +200 ticks (10s), CHARCOAL: +160 (8s), COAL_BLOCK: +1600 (80s)
// onTick(20): decrement. At zero → idle + recalculate
// onInteract: consume fuel item, add ticks. If idle → running + recalculate
```

**Gearbox:** `onNeighborChange` checks `block.getBlockPower() > 0` → locked/unlocked + recalculate.

**Water wheel:** `onNeighborChange` checks 6 neighbors for `Material.WATER` → spinning/idle + recalculate.

### 3. `GrindRecipes.java` (~60 lines)
Loads `grind-recipes.yml` → `Map<Material, ItemStack>`.

### 4. `resources/grind-recipes.yml`
```yaml
recipes:
  - input: BONE
    output: BONE_MEAL
    amount: 3
  - input: BLAZE_ROD
    output: BLAZE_POWDER
    amount: 3
  - input: SUGAR_CANE
    output: SUGAR
    amount: 2
```

## Known bugs to fix in implementation

### Re-entrant recalculate
`skull.update()` inside `setState()` fires `BlockPhysicsEvent` → neighbor's `onNeighborChange` → nested `recalculate()` while outer is mid-iteration.

**Fix: guard flag + pending queue.**
```java
private boolean recalculating = false;
private final Set<LocationKey> pendingRecalcs = new HashSet<>();

void recalculate(LocationKey changed) {
    if (recalculating) { pendingRecalcs.add(changed); return; }
    recalculating = true;
    try {
        doRecalculate(changed);
        while (!pendingRecalcs.isEmpty()) {
            Set<LocationKey> batch = new HashSet<>(pendingRecalcs);
            pendingRecalcs.clear();
            for (LocationKey pending : batch) doRecalculate(pending);
        }
    } finally { recalculating = false; }
}
```

### State string operator precedence
`powered ? "spinning_" : "idle_" + axis` → `+` binds to false branch only.
**Fix:** `(powered ? "spinning_" : "idle_") + axis`

### Grindstone has no axis in state name
`state.lastIndexOf('_')` returns -1 for `"idle"` → parser returns `"idle"` as axis.
**Fix:** grindstone's `updateBlockState` must handle axis-less states. Special-case blocks where `role == CONSUMER && !state.contains("_")`.

### Drill breakNaturally bypasses events
`block.breakNaturally()` does NOT fire `BlockBreakEvent`. Custom head blocks won't get `onBlockRemoved` cleanup.
**Fix:** before breaking, check if target is custom block → call `registry.onBlockRemoved(target, type)` then `target.setType(Material.AIR)`. Only use `breakNaturally()` for vanilla blocks.

### Drill vs custom blocks: configurable per-block
Add `boolean drillable` to `CustomHeadBlock.Builder` (default `true`). Drill checks `type.drillable()` before breaking. Rotation blocks themselves set `.drillable(false)`.

### Water wheel: waterlogged blocks
`Material.WATER` check misses waterlogged blocks. Add fallback:
```java
|| (block.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged())
```

### Engine fuel: avoid excessive skull.update()
Keep fuel counter in `Map<LocationKey, Integer>` in memory. Only write to PDC on chunk unload or when fuel hits zero. Read from PDC on chunk load.

### Network size cap
Hard cap, configurable (default 256). BFS stops adding nodes after cap. Nodes beyond cap are unreachable. Config key: `rotation.max-network-size`.

## Configuration (`rotation-config.yml`)
```yaml
max-network-size: 256
drill:
  tick-interval: 40
  blacklist: [BEDROCK, OBSIDIAN, CRYING_OBSIDIAN, END_PORTAL_FRAME, BARRIER]
fuel:
  COAL: 200
  CHARCOAL: 160
  COAL_BLOCK: 1600
power:
  windmill: 1
  water_wheel: 2
  engine: 5
  drill: 1
  grindstone: 1
```

---

## Phase 2: Direction, Speed, and Rotator Block

### Direction propagation

Currently the network is an undirected power graph (powered/unpowered). Direction makes the
network feel like a REAL mechanical system — gears reverse direction, shaft side determines
which way a machine rotates.

**Rules:**
- **Sources** have inherent direction (CW by default, or from placement orientation)
- **Shafts** (along-axis connections, same axis) → preserve direction
- **Gears** (perpendicular connections, different axes, both gear-like) → REVERSE direction
- **Contradictions** (same node reached via two paths with opposite directions) → network
  jammed (treat as unpowered). This naturally prevents impossible gear configurations.

**Data model additions to `RotationNetwork.java`:**
```java
enum SpinDirection { CW, CCW;
    SpinDirection reversed() { return this == CW ? CCW : CW; }
}

// Per-node computed direction (rebuilt during BFS):
private final Map<LocationKey, SpinDirection> nodeDirection = new HashMap<>();

// Public API:
public SpinDirection getDirection(LocationKey key) {
    return nodeDirection.getOrDefault(key, SpinDirection.CW);
}
```

**BFS changes in `doRecalculate()`:**
During BFS, track direction alongside network membership. Seed first node as CW (or from
source's inherent direction). For each edge:
```
isGearConnection = both nodes are gearLike AND have different axes
neighborDir = isGearConnection ? myDir.reversed() : myDir
if neighbor already visited with opposite direction → jammed = true
```

Helper: `isGearToGearConnection(RotationNode a, RotationNode b)` — returns true if both are
gear-like AND have different axes. Along-axis connections between gears (same axis) still
preserve direction (they're acting as shaft extensions, not meshing).

Clear `nodeDirection` entries when networks are rebuilt (same as other maps).

### Speed (RPM)

MVP: all sources produce a fixed RPM (e.g., windmill=16, water_wheel=24, engine=32).
Shafts preserve RPM. Gears preserve RPM (no ratios yet — 1:1 gearing).

Speed affects:
- Display animation rate of spinning blocks (faster RPM = faster `rotate` speed parameter)
- Rotator block animation duration (higher RPM = faster 90° turn)
- Future: drill break speed, grindstone processing time

**Data model:**
```java
// Add to NetworkState:
record NetworkState(int supply, int demand, int rpm) {
    boolean powered() { return supply >= demand && supply > 0; }
}

// Public API:
public int getRPM(LocationKey key) {
    Integer netId = nodeNetworkId.get(key);
    if (netId == null) return 0;
    NetworkState state = networks.get(netId);
    return (state != null && state.powered()) ? state.rpm() : 0;
}
```

RPM computation during BFS: take the maximum RPM of all sources in the network. Or weighted
average. Or: RPM = source RPM for single-source networks, min RPM for multi-source (bottleneck).
Start with max for simplicity.

### Rotator block

A rotation CONSUMER that rotates connected blocks 90° per redstone pulse in the shaft's
spin direction.

**Block type:** `rotation:rotator`
- Role: CONSUMER, 4 SU demand
- States: `unpowered_y`, `powered_y` (and `_x`, `_z` per axis)
- Placement: same `placementStateMap` as shaft/gear
- Redstone: `Sensitivity.BINARY`, `PowerReader.DIRECT`
- Transitions: `unpowered_y → powered_y` on POWERED, `powered_y → unpowered_y` on ZERO
  (per axis — 6 transitions total)

**Behavior:**
- `onStateChanged("powered_*")`: check `network.isPowered()`, if yes → rotate 90°
  in `network.getDirection()`. If no power → ignore.
- Direction: `CW → +90°`, `CCW → -90°`
- BFS flood fill connected blocks (which materials? configurable allow list, or same as
  minecart ship? or any non-air solid block within N blocks?)
- Assemble → animate over 20 ticks (or `40 / (rpm / 16)` for speed-proportional) → disassemble
- Fire-and-forget: blocks are solid at their new position after each pulse
- The rotator block itself stays in place (like the door controller)

**Registration in `RotationBlocks.java`:**
```java
// Per axis, 2 states each:
.state("unpowered_y")
.state("powered_y")
// ... x, z variants
.transition(new Trigger.RedstonePower(PowerRange.POWERED), "unpowered_y", "powered_y")
.transition(new Trigger.RedstonePower(PowerRange.ZERO), "powered_y", "unpowered_y")
// ... per axis
.onStateChanged((block, oldState, newState) -> {
    if (!newState.startsWith("powered_")) return;
    var key = LocationKey.of(block);
    if (!network.isPowered(key)) return;
    SpinDirection dir = network.getDirection(key);
    float targetYaw = (dir == SpinDirection.CW) ? 90f : -90f;
    // BFS, assemble, animate, disassemble (same as DoorDemo.openDoor pattern)
})
.onChunkLoad((b, state) -> network.addNode(b, "rotation:rotator", axisFromState(state), CONSUMER, 4, false))
.onBlockRemoved((b, state) -> { network.removeNode(LocationKey.of(b)); cleanupRotator(b); })
```

**Connected blocks detection:**
The rotator needs to know which blocks to rotate. Options:
1. BFS flood fill of adjacent non-air blocks (like door demo with OAK_PLANKS but any solid)
2. Configurable allow list (like minecart ship)
3. Only blocks "glued" with a specific item (slime-like mechanic)

Start with option 1 (any solid block, max 64 blocks) for simplicity. The rotator itself is
excluded from the BFS.

**Animation duration:**
Base = 20 ticks. With RPM: `duration = max(5, 320 / rpm)`. At 16 RPM: 20 ticks. At 32 RPM:
10 ticks. At 64 RPM: 5 ticks. Faster networks = faster rotation.

### Implementation order

1. Add `SpinDirection` enum + direction tracking in BFS (~40 lines in RotationNetwork)
2. Add `getDirection()` + `getRPM()` public API (~15 lines)
3. Add rotator block registration in RotationBlocks (~80 lines)
4. Add rotation logic (BFS + assemble + animate + disassemble) — reuse DoorDemo pattern (~100 lines)
5. Add `rotation:rotator` to `rotation-blocks.yml` with display entity config

### Verification

1. Place rotator + shaft from north side, power shaft → redstone pulse → blocks rotate 90° CW
2. Move shaft to south side → redstone pulse → blocks rotate 90° CCW
3. Add gear between shaft and rotator → direction reverses
4. Two gears (double reversal) → same direction as no gear
5. No rotation power → redstone pulse does nothing
6. Contradictory gear paths → network jammed, rotator doesn't work
7. Fast network (engine, 32 RPM) → rotation animation is faster
8. Multiple pulses → blocks step through 90°, 180°, 270°, 360° (back to start)
9. Door demo regression: still works (doesn't use direction)

## Display entities — deferred
Placeholder skull textures with rotate animations initially. Tuned in-game after network logic works.
- **Shaft:** single small skull, rotate animation matching axis
- **Gear:** larger skull, rotate animation matching axis
- **Drill:** skull with rotate animation (spinning states only)
- **Grindstone:** two displays (base + top), top rotates around Y

## Implementation order (Phase 1)
1. Corelib prerequisites (onInteract, placement tracking fix, drillable flag)
2. RotationNetwork.java (graph + BFS + state propagation + guard + cap)
3. RotationBlocks.java — shaft + windmill only (MVP)
4. CoreLibPlugin wiring
5. Test: windmill → shaft → shaft → spinning propagates
6. Remaining blocks (gear, gearbox, water_wheel, engine, drill, grindstone)
7. GrindRecipes + YAML config
8. rotation-config.yml loading
9. Visual tuning

## Phase 2: future enhancements

### Mechanism integration (mechanical bearing)
A rotation CONSUMER block that assembles and rotates a mechanism structure. When powered, calls `MechanismRegistry.assembleMechanism()` on the structure in its facing direction, then `mechanism.rotate(yaw)` each tick. Power cost scales with mechanism block count. Bridges the rotation and mechanism systems.

### Fan
A rotation CONSUMER that pushes entities in its facing direction when powered. Uses velocity vectors on nearby entities within a cone. Could also: push items into fire for smelting, push items into water for washing (recipe system like grindstone).

### Clutch
Manual toggle version of gearbox. Right-click to connect/disconnect (no redstone). Same node type as gearbox but uses `onInteract` to toggle `locked_` state instead of `onNeighborChange` checking redstone.

### Sounds
- Ambient: spinning blocks play a quiet looping sound every N ticks (piggyback on existing `ParticleConfig` tick interval). Different per block type (gear grinding, shaft creaking, drill crunching).
- State transitions: play one-shot sound on start/stop (use `SoundConfig` in state definitions).

### Underpowered smoke feedback
When `supply < demand`, consumers show smoke particles instead of spinning. Add a `stalled_{axis}` state between idle and spinning. `stalled` state has: no rotation animation, CAMPFIRE_SIGNAL_SMOKE particles, distinct texture (slightly different tint). Network sets `stalled_` when component is underpowered, `idle_` when disconnected.

### Diagnostic tool
Right-click any rotation block with a specific item (e.g. CLOCK or custom item) → ActionBar message:
```
Network: 3/5 SU | 12 blocks | Powered ✓
```
Shows supply/demand, node count, powered status. Could also highlight connected blocks with glow effect temporarily.

### Saw (wood variant of drill)
Identical to drill mechanically but: only breaks wood-type blocks, drops planks instead of the block itself. Recipe map like grindstone: OAK_LOG→4 OAK_PLANKS, BIRCH_LOG→4 BIRCH_PLANKS, etc.

### Pump
Moves water/lava source blocks. Places a waterlogged block or source block in its facing direction, consuming from behind. Needs careful anti-grief considerations (worldguard integration, claim checks).

## Verification (Phase 1)
1. Place windmill → always spinning
2. Place shaft adjacent along same axis → starts spinning
3. Chain of 5 shafts → all spin. Break middle → downstream stops
4. Gear between perpendicular shafts → power transfers
5. Gearbox + redstone → downstream stops. Remove → resumes
6. Drill breaks block in facing direction every 2s
7. Drill does NOT break blocks with `drillable: false`
8. Grindstone powered + right-click bone → 3 bone meal
9. Engine + coal → runs 10s, stops, downstream stops
10. Water wheel with/without water → produces/stops
11. Network cap: 257th shaft doesn't receive power
12. Chunk unload/reload → network rebuilds correctly
13. Re-entrant recalculate doesn't corrupt state
7. Grindstone powered + right-click bone → 3 bone meal
8. Engine + coal → runs 10s, stops, downstream stops
9. Water wheel with/without water → produces/stops
10. Chunk unload/reload → network rebuilds correctly
