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

## Phase 2: Direction

### Wrench item

Copper axe with enchantment glint (`ItemMeta.setEnchantmentGlintOverride(true)`). Right-clicking
any rotation source toggles its spin direction CW↔CCW. Added to `give_demo_rotation` command.

### Source direction storage

Sources store direction in PDC: `NamespacedKey("rotation", "spin_dir")`, value `"cw"`/`"ccw"`,
default `"cw"`. Written on wrench toggle, read during BFS post-pass.

- Active sources (engine, generator, water wheel): read from skull PDC
- Passive sources (windmill): read from skull PDC during boundary scan
- Windmill needs `toBuilder()` overlay for wrench-only `onInteract`

### Direction propagation

**Rules:**
- **Sources** have stored direction (CW default, toggled by wrench)
- **Along-axis connections** (shaft-like, same axis) → PRESERVE direction
- **Same-axis gear mesh** (both gearLike, same axis, adjacent perpendicular to axis) → REVERSE
  (counter-rotate, like real meshing gears)
- **Bevel gear** (both gearLike, different axes) → PRESERVE (transmit around corner)
- **Contradictions** (same node reached via two paths with opposite directions) → network
  jammed (treat as unpowered). Naturally prevents impossible gear configurations.
- Two sources with conflicting directions (through gear topology) → jammed

**Data model additions to `RotationNetwork.java`:**
```java
public enum SpinDirection { CW, CCW;
    public SpinDirection reversed() { return this == CW ? CCW : CW; }
}

private final Map<LocationKey, SpinDirection> nodeDirection = new HashMap<>();

public @Nullable SpinDirection getDirection(LocationKey key) {
    return nodeDirection.get(key);
}
```

**BFS direction resolution (single-pass + post-pass):**
1. BFS from root, tentatively assign root = CW, propagate through edges (gears reverse)
2. Post-pass: find all sources in component (including passive from boundary scan)
3. First source = anchor. Compare computed vs stored direction → `flip` boolean
4. If flip, invert all node directions in the component
5. Check remaining sources: `(computedDir XOR flip)` must equal stored direction. Mismatch → jammed

`getConnections()` returns edge metadata (along-axis vs gear-mesh). BFS uses it to decide:
- Along-axis edge → preserve direction
- Gear-mesh edge, same axis → REVERSE (meshing teeth)
- Gear-mesh edge, different axes (bevel) → PRESERVE (corner transmission)

**`NetworkState` change:**
```java
record NetworkState(int supply, int demand, boolean jammed) {
    boolean powered() { return !jammed && supply >= demand && supply > 0; }
}
```

### Animation direction

Direction baked into animation at creation time (not runtime tickAge negation — that would
break non-rotation animations like bob/pulse). Animations are pre-parsed from YAML by
`BlockLoader.parseAnimation()`, so direction is applied via wrapping.

- `DisplayAnimation.withDirection(int mult)` returns a new animation that delegates to the
  original but multiplies tickAge by `mult`. Only affects rotate animations.
- `CustomBlockRegistry` stores `Map<LocationKey, SpinDirection> animationDirection`
- `updateBlockState()` writes direction to map. `applyConfig()` reads it and wraps the
  animation with `withDirection()` when creating `AnimationTracked`
- Cleanup in `onBlockRemoved()` and `onChunkUnload()` (match existing pattern for other maps)

### Wrench interaction (`RotationBlocks.java`)

- Check held item: copper axe with glint override → source blocks: `toggleSourceDirection()`,
  non-source blocks: `debugInteract()` (wrench doubles as diagnostic tool)
- `toggleSourceDirection()`: read PDC, flip, write PDC, `skull.update()`, recalculate
- For passive sources (windmill): `toBuilder()` overlay with wrench-only `onInteract`
- Non-wrench right-click on any rotation block → `debugInteract()` as usual
- Debug output gains direction: `"3/5 SU | Powered ✓ | CW"`

### Gearbox reverser block (deferred)

`rotation:reverser` — TRANSMITTER that reverses direction when redstone-powered, passes
through normally when unpowered. Distinct from clutch (which disconnects).

### Rotator block (deferred)

CONSUMER (4 SU) that rotates connected solid blocks 90° per redstone pulse in the network's
spin direction. CW → +90°, CCW → -90°. BFS flood fill, max 64 blocks. Reuse DoorDemo pattern.

### Implementation order

1. Wrench item creation + give command
2. `SpinDirection` enum + `nodeDirection` map in RotationNetwork
3. BFS direction tracking + post-pass anchor logic + jammed detection
4. `getDirection()` public API
5. `directionMultiplier` in `Animations.rotate()` + `animationDirection` map in CustomBlockRegistry
6. Wrench interaction in RotationBlocks (active sources)
7. Windmill `toBuilder()` overlay for wrench interaction
8. Direction in `debugInteract()` output

### Verification

1. Shaft chain from source → all spin CW (default)
2. Wrench-click source → flips to CCW, all downstream reverses visually
3. Same-axis gears adjacent → counter-rotate (CW/CCW)
4. Bevel gears (different axes) → same direction (preserve)
5. Two same-axis gear reversals → back to original direction
6. Two sources same direction through shaft → works
7. Two sources conflicting direction → jammed, everything stops
8. Add gear to resolve conflict → unjams
9. Wrench on windmill (passive source) → toggles, network updates
10. Wrench on non-source → shows debug info
11. Debug right-click shows CW/CCW
12. Chunk reload → direction rebuilt correctly from BFS + PDC

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

## Future enhancements

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
5. Clutch + redstone → downstream stops. Remove → resumes
6. Drill breaks block in facing direction every 2s
7. Drill does NOT break blocks with `drillable: false`
8. Grindstone powered + right-click bone → 3 bone meal
9. Engine + coal → runs 10s, stops, downstream stops
10. Water wheel with/without water → produces/stops
11. Network cap: 257th shaft doesn't receive power
12. Chunk unload/reload → network rebuilds correctly
13. Re-entrant recalculate doesn't corrupt state
