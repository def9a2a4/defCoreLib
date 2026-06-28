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

## Prerequisites: corelib changes  ✅ DONE

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
- Gear-to-gear: all 6 adjacent faces. Both must be `gearLike`, not locked. Already-connected
  along-axis neighbors are skipped (so same-axis gears on their shared axis connect as shaft-like,
  not gear mesh — this ordering is load-bearing for Phase 2 direction reversal).
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
| drill | CONSUMER | 1 | idle/spinning per axis | 4 | Staged breaking: 10 stages × 4-tick interval = 2s per block. Shows crack animation. |
| generator | SOURCE | 1 | idle/spinning per axis | - | Inverted redstone: unpowered=spinning, powered=idle |
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

**Drill blacklist:** two-layer guard. First, `hardness < 0` rejects all indestructible blocks
(BEDROCK, BARRIER, END_PORTAL_FRAME, COMMAND_BLOCKs, STRUCTURE_BLOCK, JIGSAW). Second, explicit
blacklist for high-hardness blocks that shouldn't be drillable:
```java
Set.of(OBSIDIAN, CRYING_OBSIDIAN, SPAWNER, MOVING_PISTON, REINFORCED_DEEPSLATE)
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

## Known bugs to fix in implementation  ✅ ALL DONE

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

## Configuration (`rotation-config.yml`) — Phase 2

Currently all values are hardcoded in Java. Create config file and loading in Phase 2.

```yaml
max-network-size: 256
drill:
  tick-interval: 4
  break-stages: 10
  blacklist: [OBSIDIAN, CRYING_OBSIDIAN, SPAWNER, MOVING_PISTON, REINFORCED_DEEPSLATE]
fuel:
  COAL: 200
  CHARCOAL: 160
  COAL_BLOCK: 1600
power:
  windmill: 1
  large_windmill: 5
  huge_windmill: 15
  water_wheel: 2
  engine: 5
  generator: 1
  drill: 1
  grindstone: 1
```

---

## Phase 2: Direction

### Wrench item

Copper axe with enchantment glint (`ItemMeta.setEnchantmentGlintOverride(true)`) and a PDC tag
`NamespacedKey("rotation", "wrench")` as the real identifier (glint alone matches any enchanted
copper axe). Unbreakable (`meta.setUnbreakable(true)` + `HIDE_UNBREAKABLE` flag), display name
"Rotation Wrench". Created via `RotationBlocks.createWrench()` (not a CustomHeadBlock). Added
to `give_demo_rotation` command as a special case after the block loop.

**Crafting recipe** (shaped, registered via `Bukkit.addRecipe(ShapedRecipe)` in
`RotationBlocks.register()`):
```
C S C     C = copper ingot
C S -     S = stick
- S -
```

**Interactions:**
- Right-click source with wrench → toggle CW↔CCW, show ActionBar `"Direction: CW → CCW"`,
  play `BLOCK_COPPER_PLACE` at pitch 1.5, spawn `WAX_ON` particles
- Sneak + wrench on any rotation block → inspect only (debug info + direction, no toggle)
- Right-click non-source with wrench → `debugInteract()` (wrench doubles as diagnostic)
- Wrench on water wheel → `"Water wheels are always flexible"` in AQUA, no toggle
- Wrench on generator → same as engine (toggle CW↔CCW)

**Sneak+wrench handling in CoreLibPlugin:** The sneak bail-out in `onPlayerInteract()` returns
before `onInteract` dispatch. Fix: check `isWrench(mainHand)` inside the sneak branch — if
holding wrench AND `type.onInteract() != null`, dispatch to `type.onInteract()` and cancel event;
otherwise return as normal. This keeps non-wrench sneak behavior unchanged (place blocks). The
`onInteract` callback checks `isSneaking()` to distinguish inspect (sneak) from toggle (no sneak).

### Source direction storage

Sources store direction in PDC: `NamespacedKey("rotation", "spin_dir")`, value `"cw"`/`"ccw"`/absent.
Written on wrench toggle, read during BFS post-pass.

- **Absent = flexible:** un-wrenched sources agree with whatever direction the network computes.
  Only sources explicitly wrenched get a stored value. This prevents a default-CW windmill from
  jamming a CCW network the player intentionally set up.
- Active sources (engine, generator): read from skull PDC
- Passive sources (windmill): read from skull PDC during boundary scan
- **Water wheel:** always flexible (deferred). Direction mapping from water position to CW/CCW
  is unspecified — water wheels agree with whatever the network computes. Not wrench-toggleable.
- Windmill needs `toBuilder()` overlay for wrench-only `onInteract`

### Direction propagation

**Rules:**
- **Sources** have stored direction (CW default, toggled by wrench)
- **Along-axis connections** (shaft-like, same axis) → PRESERVE direction
- **Same-axis gear mesh** (both gearLike, same axis, adjacent perpendicular to axis) → REVERSE
  (counter-rotate, like real meshing gears). Examples: two wall gears stacked vertically (both
  X-axis, adjacent along Y), two floor gears side by side (both Y-axis, adjacent along X).
- **Bevel gear** (both gearLike, different axes) → PRESERVE (transmit around corner).
  Example: wall gear (X-axis) next to floor gear (Y-axis).
- Note: two floor gears stacked vertically (both Y-axis, adjacent along Y) connect via the
  along-axis path, not gear mesh — they share a virtual shaft and rotate the same direction.
- **Contradictions** (same node reached via two paths with opposite directions) → network
  jammed (treat as unpowered). Naturally prevents impossible gear configurations.
- Two sources with conflicting directions (through gear topology) → jammed
- **Jammed = unpowered.** `powered()` returns false, transmitters and consumers transition to
  `idle_*` states, animations stop. **Sources keep their running/spinning state** and continue
  animating (and burning fuel for engines) — `updateBlockState()` skips `role == SOURCE`. This
  creates a visible cue (sources spinning, nothing else moving) and punishes leaving jams
  unresolved.
- **Isolated passive source** (no adjacent network nodes): spins independently via its YAML
  default state — it is not part of any network and has no direction assignment. Its stored PDC
  direction (if previously wrenched) only takes effect once a network node is placed adjacent.
  Once it becomes adjacent to a network, its animation direction is set by the boundary-scan
  reversal pass above; when it disconnects, it resets to the default (CW).

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

**`getConnections()` change:**
Currently returns `List<LocationKey>`. Phase 1 already connects gears to ANY adjacent gear
regardless of axis (same-axis and bevel alike). Phase 2 changes the return type to
`List<Connection>` to classify each edge:
```java
record Connection(LocationKey neighbor, boolean reverses) {}
```
`reverses = true` for same-axis gear mesh, `false` for along-axis and bevel connections. The
`reverses` flag is computed in `getConnections()` directly: gear-to-gear with `other.axis() ==
node.axis()` → `reverses = true`, everything else → `reverses = false`. BFS uses `reverses`
without needing axis comparison at propagation time. Dedup changes to
`result.stream().anyMatch(c -> c.neighbor().equals(neighbor))`. Along-axis connections are
intentionally checked before gear-to-gear so that two same-axis gears along their shared axis
are treated as shaft-like (`reverses=false`), not gear mesh. This ordering is already present
in Phase 1 code and is load-bearing.

**BFS direction resolution (single-pass + post-pass):**
1. BFS from root, tentatively assign root = CW, propagate through edges using rules above.
   **Cycle detection:** when BFS encounters an already-visited node, check if the direction
   it would assign via the current edge matches the already-assigned direction. Mismatch →
   `jammed = true` immediately. This catches contradictions in cyclic topologies.
   The post-pass (steps 2-8) runs regardless of whether cycle detection already set
   `jammed = true` — this ensures diagnostic information (step 8) is complete.
2. Post-pass: find all sources in component. For active sources (nodes), use their BFS-computed
   direction. For passive sources (windmill, not nodes), derive direction from the adjacent
   network node's direction + edge type. **Guard:** skip passive sources in unloaded chunks
   (`world.isChunkLoaded(x >> 4, z >> 4)`). Active sources in unloaded chunks are simply
   absent from `nodes` (removed on chunk unload) — BFS can't reach them, matching Phase 1
   behavior. Their stored PDC direction is inaccessible but irrelevant since they're not in
   the graph.
3. Collect sources with explicit stored direction (non-null PDC value). Sources with absent
   direction (flexible) are skipped — they agree with whatever the BFS computed.
4. First explicit source (by LocationKey order) = anchor. Compare its effective direction vs
   stored → `flip` boolean. If no explicit sources exist, `flip = false` (network keeps
   BFS-default CW). Note: if the BFS root is the anchor source, the flip handles it naturally
   — root was tentatively CW, anchor says CCW, flip inverts everything including the root.
5. If flip, invert all node directions in the component.
6. Check remaining explicit sources: `nodeDirection.get(sourceKey)` (post-flip) must equal
   stored direction. Mismatch → jammed. **Anchor determinism:** if multiple explicit sources, pick the one with
   lowest LocationKey coordinates to ensure consistent results across rebuilds. Use inline
   `Comparator.comparing(LocationKey::worldId).thenComparingInt(LocationKey::x)
   .thenComparingInt(LocationKey::y).thenComparingInt(LocationKey::z)` — don't modify the
   LocationKey record.
7. **Passive source multi-adjacency:** a passive source may border multiple network nodes. Use
   the first adjacent node found via `CARDINAL_FACES` iteration order (deterministic). The edge
   is always along-axis (passive sources aren't gearLike), so direction = PRESERVE. On jammed
   networks this assignment is irrelevant — jammed = unpowered regardless of direction.
   **Axis validation:** during boundary scan, only count a passive source if it shares the same
   axis as the adjacent network node AND the adjacency is along that axis (matching
   `checkAxisNeighbor` rules). A Y-axis windmill next to an X-axis shaft does not connect.
   After determining the passive source's direction, call
   `registry.setAnimationDirection(passiveLoc, dir)` so its blades visually match — this is
   the only place a passive source's animation direction is set (it is not a node).
8. **Jammed diagnosis:** uses tentative BFS-assigned directions to determine each source's
   faction, even though the network is jammed (BFS assigns directions before discovering the
   contradiction via post-pass).

**`NetworkState` change:**
```java
record NetworkState(int supply, int demand, boolean jammed) {
    boolean powered() { return !jammed && supply >= demand && supply > 0; }
}
```

### Animation direction

Animations are pre-parsed from YAML by `BlockLoader.parseAnimation()` at startup, so direction
can't be injected at parse time. Instead, wrap at runtime in `applyConfig()`.

- `CustomBlockRegistry` stores `Map<LocationKey, SpinDirection> animationDirection`
- Add mutable `boolean reversed` field to `AnimationTracked` (a `private static final class`,
  not a record — all other fields stay final). In `tickAnimations()`:
  `if (tracked.reversed) tickAge = -tickAge;` before applying.
- In `applyConfig()`: after creating AnimationTracked, set `reversed` from
  `animationDirection.getOrDefault(key, CW) == CCW`.
- Single method: `registry.setAnimationDirection(key, dir)` — stores dir in map + flips
  `reversed` on any existing `AnimationTracked` entries for that key. Called once per node in
  `doRecalculate()`, BEFORE `updateBlockState()`. When `applyConfig()` spawns new entities, it
  reads the map. When state doesn't change (direction-only flip), the in-place `reversed` flip
  handles it — no entity respawn, no flicker.
- Cleanup: remove from `animationDirection` in `onBlockRemoved()` and `onChunkUnload()`
  (match existing `removeIf` pattern for `animationTracked` and other per-block maps)
- **Passive sources (windmills) also get their animation reversed.** They aren't graph
  nodes (`registerPassiveSource`, found by the boundary scan), so `doRecalculate` never
  iterates them. After the post-pass derives a passive source's direction (propagation
  step 7), call `registry.setAnimationDirection(passiveLoc, dir)` so the windmill's own
  blades reverse to match the network — otherwise downstream reverses while the windmill
  keeps spinning CW. The wrench-on-windmill path already triggers `recalculate` on the
  adjacent network, which re-derives and re-applies this. When a windmill stops being
  adjacent to any network, reset it (`setAnimationDirection(loc, CW)`) so no stale reversal
  persists. (On a jammed network this is cosmetic — direction is arbitrary — but harmless.)

### Wrench interaction (`RotationBlocks.java`)

- `isWrench(ItemStack)`: check PDC tag, NOT glint (avoids false positives from enchanted axes)
- **All rotation block `onInteract` lambdas** must check `isWrench(held)` FIRST, before any
  fuel/recipe/debug logic:
  ```java
  .onInteract((b, event) -> {
      ItemStack held = event.getPlayer().getInventory().getItemInMainHand();
      if (isWrench(held)) return wrenchInteract(b, event, network, registry);
      // ... existing fuel/recipe logic ...
  })
  ```
- `toggleSourceDirection()`: read PDC, flip (absent→CCW on first toggle, then CW↔CCW), write
  PDC, `skull.update()`, recalculate. Show feedback (ActionBar + sound + particles).
  First toggle sets CCW because the default effective direction is CW — toggling means the
  opposite.
- For passive sources (`demo:windmill`, `rotation:large_windmill`, and `rotation:huge_windmill`):
  combine with existing `overlayWindmillResolver` into a single `toBuilder()` chain that sets
  both `displayItemResolver` and wrench-only `onInteract`
- Generator (`rotation:generator`): active SOURCE, same wrench treatment as engine (toggle + PDC)
- Non-wrench right-click on any rotation block → `debugInteract()` as usual
- Debug output gains direction + jammed state: `"3/5 SU | JAMMED (2 CW, 1 CCW) | 12 blocks"`
  For jammed networks, highlight conflicting sources with `ANGRY_VILLAGER` particles (red)
  and majority-direction sources with `HAPPY_VILLAGER` (green)

### Re-entrancy with direction

Pending recalcs from state changes during BFS may target nodes in the same network that was just
rebuilt. With non-deterministic anchoring this could cause direction flicker. Fix: before
processing a pending recalc, check if `nodeNetworkId.containsKey(pending)` — if the node's
network was already rebuilt in this batch, skip it.

### Gearbox reverser block (deferred)

`rotation:reverser` — TRANSMITTER that reverses direction when redstone-powered, passes
through normally when unpowered. Distinct from clutch (which disconnects).

### Rotator block (deferred)

CONSUMER (4 SU) that rotates connected solid blocks 90° per redstone pulse in the network's
spin direction. CW → +90°, CCW → -90°. BFS flood fill, max 64 blocks. Reuse DoorDemo pattern.

### Implementation order

0. Remove dead code: `perpendicularNeighbors()` in RotationNetwork.java (never called,
   superseded by inline logic in `getConnections()`)
1. Wrench item creation + give command
2. `SpinDirection` enum + `Connection` record + `nodeDirection` map in RotationNetwork;
   update `getConnections()` return type to `List<Connection>` with `reverses` classification
3. BFS direction tracking + post-pass anchor logic + jammed detection + passive source axis
   validation in boundary scan
4. `getDirection()` public API
5. `AnimationTracked.reversed` flag + `setAnimationDirection()` + `animationDirection` map in CustomBlockRegistry
6. Wrench interaction in RotationBlocks (active sources)
7. Windmill `toBuilder()` overlay for wrench interaction (all 3: demo:windmill,
   rotation:large_windmill, rotation:huge_windmill)
7b. Apply `setAnimationDirection` to passive sources using the direction derived in the
   boundary-scan/post-pass (and reset on disconnect).
8. Direction in `debugInteract()` output
9. `rotation-config.yml` creation + loading (extract hardcoded values)

### Verification

1. Shaft chain from source → all spin CW (default)
2. Wrench-click source → flips to CCW, all downstream reverses visually (no entity flicker)
3. Same-axis gears adjacent → counter-rotate (direction reverses)
4. Bevel gears (different axes) → same direction (preserves)
5. Two same-axis gear reversals → back to original direction
6. Two sources same direction through shaft → works
7. Two sources conflicting direction → jammed, everything stops, debug shows conflict
8. Gear loop with odd reversals (cyclic contradiction) → jammed
9. Un-wrenched windmill in CCW network → flexible, no jam
10. Wrench on windmill → sets explicit direction, now can jam if conflicting
11. Wrench on water wheel → shows "water wheels are always flexible", no toggle
12. Sneak+wrench → inspect only, no toggle
13. Wrench on non-source → shows debug info
14. Debug right-click shows CW/CCW + jammed diagnosis with particles
15. Chunk reload → direction rebuilt correctly from BFS + PDC
16. Direction change while already spinning → animation swaps in-place, no respawn
17. Wrench on generator → toggles direction like engine
18. Same-axis gears along axis (stacked floor gears) → same direction (shaft-like)
19. Break one source in jammed pair → network unjams, reorients to remaining source
20. Direction through clutch → preserves (along-axis connection)
21. Jammed network + chunk reload → stays jammed with same direction data
22. Wrench flexible source to conflict with existing explicit source → triggers jam
23. Wrench direction while block is idle → reversed flag persists, applied on next spin
24. Wrench a windmill feeding a network → the windmill's **own** blades reverse, not just
    downstream blocks. Disconnect it → blades return to default CW.
25. Sneak-place a block against a shaft/gear → placement still works (`onInteract`
    non-regression; non-sneak right-click still shows debug).

### Migration

Existing Phase 1 worlds with no PDC direction data: all sources are treated as flexible
(absent = agree with network), default direction is CW. No migration step needed.

## Display entities  ✅ DONE
Defined in `rotation-blocks.yml` via the display-system refactor (Phases 1+2: `@alias` textures,
`copy_from` + state-level animation). All spin speeds normalized to 3.0.
- **Shaft:** single small skull, rotate animation matching axis
- **Gear:** rod + two discs with composed quaternion rotations, rotate animation matching axis
- **Drill:** skull with rotate animation (spinning states only)
- **Grindstone:** two displays (base + top), top rotates around Y, base opts out via `animation: none`
- **Windmills:** 4 banner blades per orientation, single state-level rotate animation
- **Engine/Generator:** rod + housing, particles on running states

## Implementation order (Phase 1)  ✅ DONE
1. ~~Corelib prerequisites (onInteract, placement tracking fix, drillable flag)~~
2. ~~RotationNetwork.java (graph + BFS + state propagation + guard + cap)~~
3. ~~RotationBlocks.java — shaft + windmill only (MVP)~~
4. ~~CoreLibPlugin wiring~~
5. ~~Test: windmill → shaft → shaft → spinning propagates~~
6. ~~Remaining blocks (gear, gearbox, water_wheel, engine, drill, grindstone, generator)~~
7. ~~GrindRecipes + YAML config~~
8. ~~rotation-config.yml loading~~ (deferred to Phase 2 — values hardcoded for now)
9. ~~Visual tuning~~

## Future enhancements

### Mechanism integration (mechanical bearing)
A rotation CONSUMER block that assembles and rotates a mechanism structure. When powered, calls `MechanismRegistry.assembleMechanism()` on the structure in its facing direction, then `mechanism.rotate(yaw)` each tick. Power cost scales with mechanism block count. Bridges the rotation and mechanism systems.

### Fan
A rotation CONSUMER that pushes entities in its facing direction when powered. Uses velocity vectors on nearby entities within a cone. Could also: push items into fire for smelting, push items into water for washing (recipe system like grindstone).

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

## Verification (Phase 1)  ✅ DONE
1. ~~Place windmill → always spinning~~
2. ~~Place shaft adjacent along same axis → starts spinning~~
3. ~~Chain of 5 shafts → all spin. Break middle → downstream stops~~
4. ~~Gear between perpendicular shafts → power transfers~~
5. ~~Clutch + redstone → downstream stops. Remove → resumes~~
6. ~~Drill breaks block in facing direction every 2s~~
7. ~~Drill does NOT break blocks with `drillable: false`~~
8. ~~Grindstone powered + right-click bone → 3 bone meal~~
9. ~~Engine + coal → runs 10s, stops, downstream stops~~
10. ~~Water wheel with/without water → produces/stops~~
11. ~~Network cap: 257th shaft doesn't receive power~~
12. ~~Chunk unload/reload → network rebuilds correctly~~
13. ~~Re-entrant recalculate doesn't corrupt state~~
