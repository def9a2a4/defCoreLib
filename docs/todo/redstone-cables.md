# Redstone Cables — Implementation Plan

## Context

Redstone cables transmit redstone power **instantly** across any distance **without
power decay** (vanilla dust loses 1 level per block). Cables auto-connect in all 6
directions (omnidirectional). For v1, cables only interact with other CoreLib custom
blocks — vanilla redstone output (converters) comes later.

Written as a standalone plugin in this repo, to be extracted later. Reuses network
patterns from Pipes (register/unregister, cache invalidation, chunk scanning) but the
network model is fundamentally different: Pipes are directional chains, cables are
undirected graphs.

**Core challenge**: Player heads can't natively output redstone power. Instead of
hacking hidden redstone blocks or NMS, cables use a new CoreLib API
(`externalPowerOverride`) to inject power directly into adjacent custom blocks'
redstone tracking system. CoreLib blocks adjacent to powered cables see the cable's
power level as if it were vanilla redstone.

---

## Part 1: defCoreLib — Add external power override API

One new method pair on `CustomBlockRegistry`. No existing behavior changes.

### 1a. New `externalPowerOverrides` map

**File**: `CustomBlockRegistry.java` — add after `animationTracked` (line 148)

```java
private final Map<LocationKey, Integer> externalPowerOverrides = new HashMap<>();
```

### 1b. Public API methods

**File**: `CustomBlockRegistry.java` — add after `trackTick()` (line 618)

```java
/**
 * Allow external plugins (e.g. redstone cables) to override the effective power
 * level for a custom block. The override takes precedence over readPower().
 * Takes effect on the next redstone tick (within 2 game ticks).
 */
public void externalPowerOverride(Block block, int power) {
    externalPowerOverrides.put(LocationKey.of(block), Math.clamp(power, 0, 15));
}

public void clearExternalPowerOverride(Block block) {
    externalPowerOverrides.remove(LocationKey.of(block));
}
```

### 1c. Wire into `tickRedstone()`

**File**: `CustomBlockRegistry.java` line 575-606

Change the power read line (line 581):
```java
// Before:
int newPower = readPower(block, type);

// After:
LocationKey key = LocationKey.of(block);
Integer override = externalPowerOverrides.get(key);
int newPower = override != null ? override : readPower(block, type);
```

Note: `key` is already computed on entry to the loop — reuse it. The existing variable
`entry` is a `RedstoneTracked` which has `block` and `type` but not a precomputed key.
The fastest fix: compute `LocationKey key = LocationKey.of(entry.block)` at the top of
the loop body (it's just 3 ints, negligible cost vs the `getBlockPower()` call it
replaces when overridden).

### 1d. Cleanup on block removal

**File**: `CustomBlockRegistry.java` line 487-510 (`onBlockRemoved`)

Add after `animationTracked.remove(key)` (line 500):
```java
externalPowerOverrides.remove(key);
```

### 1e. What is NOT changed

- `readPower()` — unchanged, still used as fallback when no override exists
- `tickRedstone()` scheduling (every 2 ticks) — unchanged
- All existing redstone-sensitive blocks — unchanged behavior unless overridden
- `RedstoneTracked` record — unchanged

**Total: ~15 lines across 1 file.**

---

## Part 2: Plugin project structure

```
RedstoneCables/
├── build.gradle.kts
├── settings.gradle.kts
├── Makefile
├── src/main/resources/
│   ├── plugin.yml
│   └── config.yml
└── src/main/java/anon/def9a2a4/cables/
    ├── CablesPlugin.java         # Main plugin, commands, block registration
    ├── CableManager.java         # Network graph, power propagation, tick loop
    ├── CableNetwork.java         # Connected component of cables
    └── CableListener.java        # Place/break/chunk/physics event handlers
```

Create at `/home/miv/projects/minecraft/RedstoneCables/`.

### `settings.gradle.kts`

```kotlin
rootProject.name = "RedstoneCables"
```

### `build.gradle.kts`

```kotlin
plugins {
    java
    id("com.gradleup.shadow") version "9.3.0"
}

group = "anon.def9a2a4"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    compileOnly(files("../defCoreLib/build/libs/DefCoreLib.jar"))
}

tasks {
    processResources {
        val props = mapOf("version" to version)
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    jar {
        archiveBaseName.set("RedstoneCables")
        manifest {
            attributes["paperweight-mappings-namespace"] = "mojang"
        }
    }

    shadowJar {
        archiveClassifier.set("")
        archiveBaseName.set("RedstoneCables")
    }
}
```

### `Makefile`

```makefile
.PHONY: build clean server server-plugin-copy server-start

build:
	./gradlew shadowJar
	mkdir -p bin
	cp build/libs/RedstoneCables.jar bin/

clean:
	./gradlew clean
	rm -rf bin

server-plugin-copy:
	cp bin/RedstoneCables.jar server/plugins/

server: build server-plugin-copy server-start

server-start:
	cd server && java -jar paper.jar --nogui
```

### `plugin.yml`

```yaml
name: RedstoneCables
main: anon.def9a2a4.cables.CablesPlugin
version: ${version}
api-version: 1.21
author: You
description: Instant lossless redstone signal transmission via cable networks
depend: [DefCoreLib]

commands:
  cables:
    description: Redstone Cables admin commands
    usage: /cables <give|info|cleanup>
    permission: cables.admin

permissions:
  cables.admin:
    description: Allows access to all Cables commands
    default: op
```

---

## Part 3: CableNetwork — connected component data structure

A lightweight mutable object representing a connected group of cables that share
power state.

**File**: `CableNetwork.java`

```java
package anon.def9a2a4.cables;

import org.bukkit.Location;
import java.util.*;

final class CableNetwork {

    private final Set<Location> members = new HashSet<>();
    private int powerLevel = 0;

    // Edges: cables that have at least one non-cable neighbor
    // inputEdges: cable locations adjacent to a vanilla/CoreLib power source
    // outputEdges: cable locations adjacent to a CoreLib redstone-sensitive block
    private final Set<Location> inputEdges = new HashSet<>();
    private final Set<Location> outputEdges = new HashSet<>();

    Set<Location> members() { return members; }
    int powerLevel() { return powerLevel; }
    void setPowerLevel(int level) { this.powerLevel = level; }
    Set<Location> inputEdges() { return inputEdges; }
    Set<Location> outputEdges() { return outputEdges; }

    boolean isEmpty() { return members.isEmpty(); }
    int size() { return members.size(); }
}
```

**Why separate from CableManager?** Networks are rebuilt on structural changes (cable
place/break). Keeping them as objects lets the tick loop iterate networks, not
individual cables. A 100-cable straight line is 1 network with 2 edges, not 100
individual power checks.

---

## Part 4: CableManager — core logic

**File**: `CableManager.java`

Tracks all cables, manages network graph, runs power tick.

### Data structures

```java
private final Map<Location, CableNetwork> cableToNetwork = new HashMap<>();
private final Set<CableNetwork> networks = new HashSet<>();
```

No separate `Map<Location, CableData>` — cables are omnidirectional with no per-cable
config. The network membership IS the cable data.

### Key methods

#### `registerCable(Location loc)`

Called on cable placement.

1. `loc = normalizeLocation(loc)` (block-aligned, copied from Pipes'
   `PipeManager.normalizeLocation` — `loc.getBlock().getLocation()`)
2. Build or merge network:
   - Check all 6 neighbors for existing cables
   - If 0 neighbors are cables: create new single-member `CableNetwork`
   - If 1+ neighbors are cables and all in same network: add `loc` to that network
   - If neighbors span multiple networks: merge all into one, reclassify edges
3. Classify `loc`'s edges: check 6 neighbors for power sources / CoreLib blocks
4. Put `cableToNetwork.put(loc, network)`

#### `unregisterCable(Location loc)`

Called on cable break.

1. Remove `loc` from its network
2. If the network now has 0 members: remove it entirely
3. Otherwise: the break may split one network into multiple. Run BFS from each
   neighbor that was a cable. If BFS reaches all remaining members, network stays
   intact. If not, split into separate `CableNetwork` objects.
4. Reclassify edges for affected networks (the broken cable's neighbors may have
   become input/output edges)
5. Clear external power overrides for any CoreLib blocks that were only reachable
   through the removed cable's network

#### `rebuildNetwork(Location seed)` — BFS flood fill

```
Queue<Location> queue = [seed]
Set<Location> visited = {}
CableNetwork network = new CableNetwork()

while queue not empty:
    loc = queue.poll()
    if loc in visited: continue
    visited.add(loc)
    network.members().add(loc)

    for each of 6 BlockFace directions:
        neighbor = loc.getRelative(face)
        neighborNorm = normalizeLocation(neighbor)

        if neighborNorm is a cable (in cableToNetwork):
            queue.add(neighborNorm)
        else:
            classifyEdge(loc, neighbor, face, network)
```

#### `classifyEdge(Location cableLoc, Block neighbor, BlockFace face, CableNetwork net)`

Determines if `cableLoc` is an input edge, output edge, or both.

```java
// Input: neighbor provides power to the cable
int power = neighbor.getBlockPower();
if (power > 0) {
    net.inputEdges().add(cableLoc);
}

// Also check if neighbor is a CoreLib custom block with redstone sensitivity
CustomBlockRegistry registry = CoreLibPlugin.getInstance().getRegistry();
CustomHeadBlock type = registry.getTypeFromBlock(neighbor);
if (type != null && type.sensitivity() != CustomHeadBlock.Sensitivity.NONE) {
    net.outputEdges().add(cableLoc);
    // This cable is ALSO an input if the CoreLib block outputs power
    // (but CoreLib blocks don't output power, so skip for v1)
}

// Vanilla power sources: redstone block, lever, button, etc.
// block.getBlockPower() already handles these
```

A cable location can be in BOTH `inputEdges` and `outputEdges` (e.g., a cable between
a lever and a redstone display).

#### `tickPower()` — called every 2 ticks

```java
for (CableNetwork network : networks) {
    // Read max power across all input edges
    int maxPower = 0;
    for (Location inputLoc : network.inputEdges()) {
        Block cableBlock = inputLoc.getBlock();
        for (BlockFace face : ALL_FACES) {
            Block neighbor = cableBlock.getRelative(face);
            Location neighborNorm = normalizeLocation(neighbor.getLocation());
            if (cableToNetwork.containsKey(neighborNorm)) continue; // skip other cables
            int power = neighbor.getBlockPower();
            maxPower = Math.max(maxPower, power);
        }
    }

    if (maxPower == network.powerLevel()) continue; // no change

    network.setPowerLevel(maxPower);

    // Update visuals for all cables in network
    updateNetworkVisuals(network);

    // Push power to output edges via CoreLib API
    CustomBlockRegistry registry = CoreLibPlugin.getInstance().getRegistry();
    for (Location outputLoc : network.outputEdges()) {
        Block cableBlock = outputLoc.getBlock();
        for (BlockFace face : ALL_FACES) {
            Block neighbor = cableBlock.getRelative(face);
            if (cableToNetwork.containsKey(normalizeLocation(neighbor.getLocation()))) continue;
            CustomHeadBlock type = registry.getTypeFromBlock(neighbor);
            if (type != null && type.sensitivity() != CustomHeadBlock.Sensitivity.NONE) {
                if (maxPower > 0) {
                    registry.externalPowerOverride(neighbor, maxPower);
                } else {
                    registry.clearExternalPowerOverride(neighbor);
                }
            }
        }
    }
}
```

**Tick scheduling**: `Bukkit.getScheduler().runTaskTimer(plugin, this::tickPower, 20, 2)`
— matches CoreLib's `tickRedstone()` interval. The 20-tick startup delay ensures
CoreLib's registry is populated.

#### `updateNetworkVisuals(CableNetwork network)`

Swap skull texture for all cables in the network:

```java
String texture = network.powerLevel() > 0 ? POWERED_TEXTURE : UNPOWERED_TEXTURE;
for (Location loc : network.members()) {
    Block block = loc.getBlock();
    if (block.getState() instanceof Skull skull) {
        HeadUtil.setTexture(skull, texture);
    }
}
```

This is O(network size) but only runs on power CHANGES, not every tick.

#### `onNeighborChange(Location cableLoc)`

Called when a non-cable block adjacent to a cable changes (lever toggled, block
placed/broken). Does NOT rebuild the network structure — just reclassifies edges
and triggers a power recalculation.

```java
CableNetwork network = cableToNetwork.get(normalizeLocation(cableLoc));
if (network == null) return;

// Reclassify this cable's edges
reclassifyEdges(cableLoc, network);

// Force immediate power tick for responsiveness
tickSingleNetwork(network);
```

#### `normalizeLocation(Location loc)` — from Pipes

```java
static Location normalizeLocation(Location loc) {
    return new Location(loc.getWorld(),
        loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
}
```

### Network split detection on cable break

When a cable is removed, its former neighbors may end up in separate networks. Naive
approach: rebuild all affected networks from scratch. Optimized approach: BFS from one
neighbor; if it reaches all other cable neighbors, the network is intact. If not, the
unreached neighbors seed new networks.

```java
void handleCableBreak(Location removed, CableNetwork oldNetwork) {
    oldNetwork.members().remove(removed);

    List<Location> cableNeighbors = new ArrayList<>();
    for (BlockFace face : ALL_FACES) {
        Location n = normalizeLocation(removed.getBlock().getRelative(face).getLocation());
        if (oldNetwork.members().contains(n)) cableNeighbors.add(n);
    }

    if (cableNeighbors.isEmpty()) {
        // Isolated cable removed — delete empty network
        networks.remove(oldNetwork);
        return;
    }

    // BFS from first neighbor
    Set<Location> reached = bfs(cableNeighbors.get(0), oldNetwork.members());

    if (reached.size() == oldNetwork.members().size()) {
        // Network stays connected — just reclassify edges near the break
        reclassifyAllEdges(oldNetwork);
        return;
    }

    // Split: create new networks from unreached components
    networks.remove(oldNetwork);
    Set<Location> remaining = new HashSet<>(oldNetwork.members());

    while (!remaining.isEmpty()) {
        Location seed = remaining.iterator().next();
        CableNetwork newNet = rebuildNetwork(seed);
        // rebuildNetwork removes from remaining via visited set
        remaining.removeAll(newNet.members());
        networks.add(newNet);
        for (Location m : newNet.members()) cableToNetwork.put(m, newNet);
    }
}
```

### Max network size

Config-controlled cap (default 256). If `rebuildNetwork` BFS exceeds this, stop
growing. Log a warning. This prevents lag from accidental mega-networks.

---

## Part 5: Cable block registration via CoreLib Builder API

**File**: `CablesPlugin.java`

Cables are CoreLib custom head blocks registered programmatically. One block type
with two states (unpowered / powered) and lifecycle callbacks.

```java
@Override
public void onEnable() {
    saveDefaultConfig();

    CustomBlockRegistry registry = CoreLibPlugin.getInstance().getRegistry();
    cableManager = new CableManager(this);

    CustomHeadBlock cableType = CustomHeadBlock.builder("cables", "cable")
        .texture(UNPOWERED_TEXTURE)
        .name(Component.text("Redstone Cable", NamedTextColor.RED))
        .lore(List.of(Component.text("Transmits redstone instantly", NamedTextColor.GRAY)))
        .drops(DropRule.self())
        .cancelPistons(true)
        .reactsToNeighbors(true)
        .onNeighborChange((block, face) -> cableManager.onNeighborChange(block))
        .onBlockRemoved((block, state) -> cableManager.unregisterCable(block.getLocation()))
        .onChunkLoad((block, state) -> cableManager.registerCable(block.getLocation()))
        .onChunkUnload(block -> cableManager.onChunkUnload(block.getLocation()))
        .build();

    registry.register(cableType);
    cableManager.startTasks();
}
```

**Why no states?** State-based texture swapping (powered/unpowered) would cause
`applyConfig()` to remove and re-spawn display entities every time power changes.
Instead, `updateNetworkVisuals()` swaps the skull texture directly via
`HeadUtil.setTexture()` — much cheaper.

**Why `reactsToNeighbors(true)`?** When a lever adjacent to a cable is toggled,
`BlockPhysicsEvent` fires. CoreLib checks `isNeighborReactive()` and calls the
`onNeighborChange` callback, which triggers edge reclassification and a power
recalculation.

### `onBlockPlaced` callback

CoreLib currently fires `onChunkLoadCallback` as a surrogate for "block placed"
(see `CoreLibPlugin.java` line 240-242). This works for cables: on placement,
`onChunkLoad` fires → `cableManager.registerCable()` runs → network is built.

If CoreLib gains a dedicated `onBlockPlaced` callback (see Pipes migration plan),
use that instead and drop the `onChunkLoad` surrogate.

### Cable item creation

Cables use CoreLib's `CustomHeadBlock.createItem()` which generates a player head
with the correct texture, name, lore, and PDC `block_type` marker.

```java
// In CablesPlugin command handler:
ItemStack cableItem = cableType.createItem(amount);
player.getInventory().addItem(cableItem);
```

### Recipe

Registered via CoreLib's builder:
```java
.shapedRecipe(new ShapedRecipeDef(
    "cable_craft", 8,
    List.of("RCR", "CRC", "RCR"),
    Map.of(
        'R', new ShapedRecipeDef.MaterialIngredient(Material.REDSTONE),
        'C', new ShapedRecipeDef.MaterialIngredient(Material.COPPER_INGOT)
    )
))
```

Yields 8 cables. Copper + redstone thematic.

---

## Part 6: CableListener — event handlers

**File**: `CableListener.java`

Most events are handled by CoreLib (placement, breaking, explosions, pistons, fire,
chunk load/unload). The lifecycle callbacks on the `CustomHeadBlock` builder handle
registration/unregistration. `CableListener` only handles cable-specific events that
CoreLib doesn't cover.

### `BlockPhysicsEvent` — neighbor power changes

CoreLib already handles this for `reactsToNeighbors` blocks and dispatches to
`onNeighborChange`. No additional listener needed.

### `BlockPlaceEvent` — adjacent non-cable block placed

When a new block is placed next to a cable, it might be a power source or a CoreLib
block. CoreLib's `onNeighborChange` fires on the cable → `cableManager.onNeighborChange`
reclassifies edges.

### What CableListener DOES handle

```java
@EventHandler
public void onBlockBreak(BlockBreakEvent event) {
    // When a non-cable block adjacent to a cable is broken, reclassify.
    // CoreLib only fires onNeighborChange for its own custom blocks,
    // but BlockPhysicsEvent covers vanilla block removals too.
    // This may not be needed — test whether BlockPhysicsEvent is sufficient.
}
```

**Likely outcome**: `CableListener` is empty or very thin. CoreLib + `reactsToNeighbors`
+ `onNeighborChange` handles the vast majority. Only add handlers if testing reveals
gaps (e.g., if vanilla block placement adjacent to a cable doesn't trigger
`BlockPhysicsEvent` on the cable).

---

## Part 7: Chunk lifecycle

### Chunk load

CoreLib's chunk hint system + `onChunkLoad` callback handles this:

1. Chunk loads → CoreLib scans for skulls with `corelib:block_type = "cables:cable"` PDC
2. For each found cable, calls `onChunkLoadCallback` → `cableManager.registerCable()`
3. `registerCable()` builds/merges the network

### Chunk unload

1. CoreLib calls `onChunkUnloadCallback` → `cableManager.onChunkUnload(loc)`
2. Remove cable from its network. If network becomes empty, remove it.
3. If network splits across chunk boundary, that's fine — remaining cables in loaded
   chunks form their own network. When the chunk reloads, they merge back.

### Cross-chunk networks

A cable network can span chunk boundaries. When one chunk unloads:
- Cables in that chunk are removed from the network
- The network may split; `handleCableBreak` logic applies to each unloaded cable
- Power recalculates for surviving sub-networks
- On chunk reload, networks re-merge naturally via `registerCable()`

This means cross-chunk networks temporarily lose connectivity at chunk boundaries,
which is acceptable — it matches vanilla redstone behavior where unloaded chunks
don't process signals.

---

## Part 8: Visual representation

### v1: Skull texture swap only

- **Unpowered**: grey/dark cable texture (base64 head texture, source from
  minecraft-heads.com — search for "cable" or "wire" themed heads)
- **Powered**: red/glowing cable texture (second base64 head)
- Swap via `HeadUtil.setTexture(skull, texture)` in `updateNetworkVisuals()`

No display entities for v1. The skull head itself IS the visual. This keeps it simple
and avoids display entity spawn/despawn overhead on power toggles.

### Future: Display entity connectors

Later iterations could add ItemDisplay entities showing cable connections in connected
directions (like how pipes show directional tubes). This would use CoreLib's
`DisplayEntityConfig` with state-dependent transforms. Not in v1 scope.

---

## Part 9: Config

**File**: `config.yml`

```yaml
# Redstone Cables configuration

# Maximum cables in a single network. Prevents lag from mega-networks.
max-network-size: 256

# Power tick interval in game ticks (2 = every 100ms, matches CoreLib redstone tick).
tick-interval: 2

# Textures (base64 encoded player head textures)
textures:
  unpowered: "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvPFVOUE9XRVJFRD4ifX19"
  powered: "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvPFBPV0VSRUQ+In19fQ=="

# Recipe (set to false to disable)
recipe:
  enabled: true
  shape: ["RCR", "CRC", "RCR"]
  ingredients:
    R: REDSTONE
    C: COPPER_INGOT
  result-amount: 8
```

---

## Part 10: Edge cases and invariants

### Power semantics

- **Network power = max of all input powers.** If a network has one input at power 12
  and another at power 7, the network runs at power 12. This matches the TODO spec
  ("without changing power level") — the strongest signal wins and propagates unchanged.

- **Zero inputs = zero power.** If all power sources are removed, the network drops to 0.

- **Self-loop immunity.** A cable never reads power from another cable in the same
  network. The `tickPower()` loop explicitly skips neighbors that are in
  `cableToNetwork`. This prevents feedback loops.

### Structural invariants

- Every cable location maps to exactly one `CableNetwork`
- Every `CableNetwork` in `networks` has at least 1 member
- `inputEdges` and `outputEdges` are subsets of `members`
- On cable place/break, networks are rebuilt synchronously (no stale references)

### Race conditions

- All cable operations run on the main server thread (Bukkit scheduler). No
  thread safety concerns.
- `tickPower()` and `registerCable()`/`unregisterCable()` never run concurrently
  because both are main-thread tasks.

### Performance

- **Tick cost**: O(networks × avg edges per network). For typical builds (small
  isolated networks), this is negligible. The max-network-size cap prevents pathological
  cases.
- **Place/break cost**: O(network size) for BFS rebuild. Bounded by
  `max-network-size`.
- **Texture swap cost**: O(network size) skull updates on power change. Only runs when
  power actually changes, not every tick.
- **Comparison to Pipes**: Pipes run `transferAllPipes()` every tick iterating all
  pipes. Cables only iterate networks (much fewer) and only touch individual cables
  on power changes.

---

## v1 scope — what's skipped

| Feature | Reason |
|---------|--------|
| Vanilla redstone output | Needs converter block (hidden redstone block or NMS). Separate feature. |
| Display entity connectors | Visual polish. Skull texture is sufficient for v1. |
| Cable variants (colors/tiers) | One cable type is enough to prove the system. |
| Comparator-level output | Would need container fill tricks. Not in scope. |
| Waterlogging | Skulls can't be waterlogged without NMS. |
| Mechanism support | Cables in mechanisms don't make physical sense. |
| Wireless / long-range | Different feature entirely. |

---

## Implementation order

1. **CoreLib**: Add `externalPowerOverride` / `clearExternalPowerOverride` to
   `CustomBlockRegistry` + wire into `tickRedstone()` + cleanup in `onBlockRemoved`
   (~15 lines, 1 file)
2. **Plugin**: Create `RedstoneCables/` project skeleton (build.gradle.kts,
   settings.gradle.kts, Makefile, plugin.yml, config.yml)
3. **Plugin**: Write `CableNetwork.java` — data structure
4. **Plugin**: Write `CableManager.java` — register/unregister, BFS network rebuild,
   edge classification, power tick, visual updates, chunk lifecycle
5. **Plugin**: Write `CablesPlugin.java` — block type registration via Builder,
   commands, lifecycle
6. **Plugin**: Write `CableListener.java` — any gap handlers not covered by CoreLib
   callbacks
7. **Test**: Build both JARs, test on server
8. **Texture**: Find/create cable head textures (powered + unpowered)

---

## Key files modified

### defCoreLib (Part 1 only)

| File | Change |
|------|--------|
| `CustomBlockRegistry.java` | +`externalPowerOverrides` map, +`externalPowerOverride()`/`clearExternalPowerOverride()`, wire into `tickRedstone()`, cleanup in `onBlockRemoved` |

### RedstoneCables (all new)

| File | Purpose |
|------|---------|
| `build.gradle.kts` | Gradle build config with CoreLib dependency |
| `settings.gradle.kts` | Project name |
| `Makefile` | Build shortcuts |
| `plugin.yml` | Plugin metadata, depends on DefCoreLib |
| `config.yml` | Textures, recipe, max network size |
| `CablesPlugin.java` | Main plugin, CoreLib block registration, commands |
| `CableManager.java` | Network graph, power propagation, tick loop |
| `CableNetwork.java` | Connected component data structure |
| `CableListener.java` | Gap event handlers (likely minimal) |

---

## Verification

1. **Build**: `cd defCoreLib && make build`, `cd RedstoneCables && make build` — both
   produce JARs without errors
2. **Place cables**: `/cables give` → place cable items → verify head blocks appear
3. **Network formation**: Place 5 cables in a line → internal state should show 1
   network with 5 members
4. **Network split**: Break middle cable → should produce 2 networks of 2 each
5. **Power input**: Place lever adjacent to cable, toggle → cable network power changes,
   textures swap to powered
6. **Power propagation**: Place `redstone_display` (from CoreLib demo blocks) adjacent
   to far end of cable chain → toggle lever at other end → display responds to power
   level
7. **No decay**: Power level at the output end matches the input end exactly (e.g.,
   lever = 15 at input → 15 at output). Test with a comparator source at lower levels.
8. **Instant**: No visible delay between lever toggle and output response (both happen
   within 2 ticks)
9. **Chunk reload**: Unload chunk containing cables (fly far away, return) → cables
   restore and networks reform
10. **Max network size**: Place >256 cables → verify log warning and network stops growing
11. **Multiple inputs**: Two levers into same network → power = max of both
12. **Power removal**: Remove all levers → network power drops to 0, textures revert,
    CoreLib blocks receive power 0

---

## Patterns reused from Pipes

| Pattern | Pipes source | Cables adaptation |
|---------|-------------|-------------------|
| `normalizeLocation()` | `PipeManager.java` | Identical |
| Register/unregister lifecycle | `PipeManager.registerPipe()`/`unregisterPipe()` | Same shape, no facing/variant |
| Cache invalidation on structural change | `PipeManager.invalidatePathCache()` | Full network rebuild instead of path cache clear |
| Chunk scan on load | `PipeListener.onChunkLoad()` → `PipeManager.scanChunk()` | Delegated to CoreLib's chunk hint system + `onChunkLoad` callback |
| Chunk unload cleanup | `PipeListener.onChunkUnload()` | Same pattern via `onChunkUnload` callback |
| Phase-offset ticking | `PipeManager.isTransferDue()` | Not needed — iterate networks, not individual blocks |
| Scoreboard tag persistence | `PipeTags.java` | Not needed — CoreLib PDC on skulls handles persistence |
| Project structure | `Pipes/build.gradle.kts`, `Makefile`, `plugin.yml` | Near-identical, adjusted names and dependencies |
