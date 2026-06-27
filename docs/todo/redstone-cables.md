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

**File**: `CustomBlockRegistry.java` — add after `trackTick()` (line 616)

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
	gradle shadowJar
	mkdir -p bin
	cp build/libs/RedstoneCables.jar bin/

clean:
	gradle clean
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

import anon.def9a2a4.corelib.CustomBlockRegistry.LocationKey;
import java.util.*;

final class CableNetwork {

    private final Set<LocationKey> members = new HashSet<>();
    private int powerLevel = 0;

    private final Set<LocationKey> inputEdges = new HashSet<>();
    private final Set<LocationKey> outputEdges = new HashSet<>();

    Set<LocationKey> members() { return members; }
    int powerLevel() { return powerLevel; }
    void setPowerLevel(int level) { this.powerLevel = level; }
    Set<LocationKey> inputEdges() { return inputEdges; }
    Set<LocationKey> outputEdges() { return outputEdges; }

    void addMember(LocationKey key) { members.add(key); }
    void removeMember(LocationKey key) {
        members.remove(key);
        inputEdges.remove(key);
        outputEdges.remove(key);
    }

    boolean isEmpty() { return members.isEmpty(); }
    int size() { return members.size(); }
}
```

Uses CoreLib's immutable `LocationKey` record instead of mutable `Location`.
`removeMember` also cleans up edge sets to maintain the subset invariant.

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
private final Map<LocationKey, CableNetwork> cableToNetwork = new HashMap<>();
private final Set<CableNetwork> networks = new HashSet<>();
private BukkitTask tickTask;
```

Uses CoreLib's immutable `LocationKey` record as map key (not mutable `Location`).
No separate `Map<LocationKey, CableData>` — cables are omnidirectional with no
per-cable config. The network membership IS the cable data.

### Key methods

#### `registerCable(Block block)`

Called on cable placement / chunk load.

```java
LocationKey key = LocationKey.of(block);
if (cableToNetwork.containsKey(key)) return; // already registered

// Find which existing networks our neighbors belong to
Set<CableNetwork> neighborNets = new HashSet<>();
for (BlockFace face : ALL_FACES) {
    LocationKey nk = LocationKey.of(block.getRelative(face));
    CableNetwork net = cableToNetwork.get(nk);
    if (net != null) neighborNets.add(net);
}

CableNetwork target;
if (neighborNets.isEmpty()) {
    target = new CableNetwork();
    networks.add(target);
} else {
    Iterator<CableNetwork> it = neighborNets.iterator();
    target = it.next();
    // Merge remaining networks into target
    while (it.hasNext()) {
        CableNetwork absorbed = it.next();
        for (LocationKey m : absorbed.members()) {
            target.addMember(m);
            cableToNetwork.put(m, target);
        }
        target.inputEdges().addAll(absorbed.inputEdges());
        target.outputEdges().addAll(absorbed.outputEdges());
        networks.remove(absorbed);
    }
}

target.addMember(key);
cableToNetwork.put(key, target);
classifyEdges(key, block, target);
```

#### `unregisterCable(Block block)`

Called on cable break / chunk unload.

```java
LocationKey key = LocationKey.of(block);
CableNetwork oldNetwork = cableToNetwork.remove(key);
if (oldNetwork == null) return;

oldNetwork.removeMember(key);

if (oldNetwork.isEmpty()) {
    networks.remove(oldNetwork);
    return;
}

handleNetworkSplit(key, block, oldNetwork);
```

#### `rebuildNetworkFrom(LocationKey seed, Set<LocationKey> validMembers)` — bounded BFS

BFS is bounded to `validMembers` — NOT the global `cableToNetwork` map. This
prevents accidentally absorbing cables from adjacent but separate networks during
split-rebuild.

```java
Queue<LocationKey> queue = new ArrayDeque<>();
Set<LocationKey> visited = new HashSet<>();
CableNetwork network = new CableNetwork();

queue.add(seed);
while (!queue.isEmpty()) {
    LocationKey loc = queue.poll();
    if (!visited.add(loc)) continue;
    if (network.size() >= maxNetworkSize) {
        plugin.getLogger().warning("Network at " + loc + " exceeds max size " + maxNetworkSize);
        break;
    }

    network.addMember(loc);
    Block block = loc.toBlock(world);

    for (BlockFace face : ALL_FACES) {
        Block neighbor = block.getRelative(face);
        LocationKey nk = LocationKey.of(neighbor);

        if (validMembers.contains(nk)) {
            queue.add(nk);
        } else {
            classifyEdge(loc, block, neighbor, face, network);
        }
    }
}
return network;
```

`LocationKey.toBlock(World)` is a helper: `world.getBlockAt(x, y, z)`.

#### `classifyEdge(LocationKey cableKey, Block cableBlock, Block neighbor, BlockFace face, CableNetwork net)`

Determines if `cableKey` is an input edge, output edge, or both.

```java
// Input: does the cable RECEIVE power from this face?
// getBlockPower(face) returns the power level the cable block receives FROM
// the given face direction. This is the correct API — neighbor.getBlockPower()
// would return the power the NEIGHBOR receives, which is wrong (a lever
// receives 0 power but outputs 15).
int power = cableBlock.getBlockPower(face);
if (power > 0) {
    net.inputEdges().add(cableKey);
}

// Output: is the neighbor a CoreLib custom block that accepts redstone?
CustomBlockRegistry registry = CoreLibPlugin.getInstance().getRegistry();
CustomHeadBlock type = registry.getTypeFromBlock(neighbor);
if (type != null && type.sensitivity() != CustomHeadBlock.Sensitivity.NONE) {
    net.outputEdges().add(cableKey);
}
```

A cable location can be in BOTH `inputEdges` and `outputEdges` (e.g., a cable between
a lever and a redstone display).

#### `tickPower()` — called every 2 ticks

```java
// Iterate a copy — tickPower can trigger cascading physics events that cause
// structural changes (register/unregister), which would ConcurrentModificationException
for (CableNetwork network : new ArrayList<>(networks)) {
    if (!networks.contains(network)) continue; // stale from concurrent structural change

    // Read max power across all input edges
    int maxPower = 0;
    for (LocationKey inputKey : network.inputEdges()) {
        Block cableBlock = inputKey.toBlock(world);
        for (BlockFace face : ALL_FACES) {
            LocationKey nk = LocationKey.of(cableBlock.getRelative(face));
            if (cableToNetwork.containsKey(nk)) continue; // skip other cables
            // Read power the cable receives FROM this face
            int power = cableBlock.getBlockPower(face);
            maxPower = Math.max(maxPower, power);
        }
    }

    if (maxPower == network.powerLevel()) continue; // no change

    network.setPowerLevel(maxPower);
    updateNetworkVisuals(network);
    pushPowerToOutputs(network, maxPower);
}
```

#### `pushPowerToOutputs(CableNetwork network, int power)`

```java
CustomBlockRegistry registry = CoreLibPlugin.getInstance().getRegistry();
for (LocationKey outputKey : network.outputEdges()) {
    Block cableBlock = outputKey.toBlock(world);
    for (BlockFace face : ALL_FACES) {
        Block neighbor = cableBlock.getRelative(face);
        if (cableToNetwork.containsKey(LocationKey.of(neighbor))) continue;
        CustomHeadBlock type = registry.getTypeFromBlock(neighbor);
        if (type != null && type.sensitivity() != CustomHeadBlock.Sensitivity.NONE) {
            if (power > 0) {
                registry.externalPowerOverride(neighbor, power);
            } else {
                registry.clearExternalPowerOverride(neighbor);
            }
        }
    }
}
```

**Tick scheduling**: `tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickPower, 2, 2)`
— matches CoreLib's `tickRedstone()` interval. Store the `BukkitTask` for cancellation
in `shutdown()`.

#### `updateNetworkVisuals(CableNetwork network)`

Swap skull texture for all cables in the network:

```java
String texture = network.powerLevel() > 0 ? poweredTexture : unpoweredTexture;
for (LocationKey key : network.members()) {
    Block block = key.toBlock(world);
    HeadUtil.applyTexture(block, texture);
}
```

`HeadUtil.applyTexture(Block, String)` handles the Skull cast and `skull.update()`
internally.

This is O(network size) but only runs on power CHANGES, not every tick.

#### `onNeighborChange(Block cableBlock)`

Called when a non-cable block adjacent to a cable changes (lever toggled, block
placed/broken). Does NOT rebuild the network structure — just reclassifies edges
and triggers a power recalculation.

```java
LocationKey key = LocationKey.of(cableBlock);
CableNetwork network = cableToNetwork.get(key);
if (network == null) return;

reclassifyEdges(key, cableBlock, network);
tickSingleNetwork(network);
```

#### `reclassifyEdges(LocationKey key, Block cableBlock, CableNetwork network)`

```java
network.inputEdges().remove(key);
network.outputEdges().remove(key);

for (BlockFace face : ALL_FACES) {
    Block neighbor = cableBlock.getRelative(face);
    LocationKey nk = LocationKey.of(neighbor);
    if (cableToNetwork.containsKey(nk)) continue; // skip cables
    classifyEdge(key, cableBlock, neighbor, face, network);
}
```

### Network split detection on cable break

When a cable is removed, its former neighbors may end up in separate networks.
Optimized approach: BFS from one neighbor; if it reaches all remaining members,
the network is intact. If not, the unreached members seed new networks.

The BFS is always bounded to `oldNetwork.members()` — never the global
`cableToNetwork` map — to prevent accidentally absorbing adjacent but separate
networks.

```java
void handleNetworkSplit(LocationKey removed, Block removedBlock, CableNetwork oldNetwork) {
    // Find which neighbors were cables in this network
    List<LocationKey> cableNeighbors = new ArrayList<>();
    for (BlockFace face : ALL_FACES) {
        LocationKey nk = LocationKey.of(removedBlock.getRelative(face));
        if (oldNetwork.members().contains(nk)) cableNeighbors.add(nk);
    }

    if (cableNeighbors.isEmpty()) {
        // Was the only cable — network already emptied in unregisterCable
        networks.remove(oldNetwork);
        return;
    }

    // Quick check: BFS from first neighbor, bounded to remaining members
    Set<LocationKey> reached = bfs(cableNeighbors.get(0), oldNetwork.members());

    if (reached.size() == oldNetwork.members().size()) {
        // Still connected — reclassify edges near the break point
        for (LocationKey nk : cableNeighbors) {
            reclassifyEdges(nk, nk.toBlock(world), oldNetwork);
        }
        return;
    }

    // Split: rebuild all components from scratch, bounded to old member set
    networks.remove(oldNetwork);
    // Clear overrides for all output edges of the old network
    clearNetworkOverrides(oldNetwork);

    Set<LocationKey> remaining = new HashSet<>(oldNetwork.members());
    while (!remaining.isEmpty()) {
        LocationKey seed = remaining.iterator().next();
        CableNetwork newNet = rebuildNetworkFrom(seed, remaining);
        remaining.removeAll(newNet.members());
        networks.add(newNet);
        for (LocationKey m : newNet.members()) cableToNetwork.put(m, newNet);
    }
}
```

### Max network size

Config-controlled cap (default 256). Enforced in `rebuildNetworkFrom` BFS (see above).

### `isCable(LocationKey key)`

```java
boolean isCable(LocationKey key) { return cableToNetwork.containsKey(key); }
```

Used by `CableListener` to check adjacency.

### `clearNetworkOverrides(CableNetwork network)`

```java
void clearNetworkOverrides(CableNetwork network) {
    CustomBlockRegistry registry = CoreLibPlugin.getInstance().getRegistry();
    for (LocationKey outputKey : network.outputEdges()) {
        Block cableBlock = outputKey.toBlock(world);
        for (BlockFace face : ALL_FACES) {
            Block neighbor = cableBlock.getRelative(face);
            if (cableToNetwork.containsKey(LocationKey.of(neighbor))) continue;
            registry.clearExternalPowerOverride(neighbor);
        }
    }
}
```

### `shutdown()`

Called from `CablesPlugin.onDisable()`.

```java
void shutdown() {
    if (tickTask != null) tickTask.cancel();
    // Clear all power overrides so CoreLib blocks don't stay permanently powered
    for (CableNetwork net : networks) {
        clearNetworkOverrides(net);
    }
    cableToNetwork.clear();
    networks.clear();
    pendingUnloads.clear();
}
```

---

## Part 5: Cable block registration via CoreLib Builder API

**File**: `CablesPlugin.java`

Cables are CoreLib custom head blocks registered programmatically. One block type
with two states (unpowered / powered) and lifecycle callbacks.

```java
@Override
public void onEnable() {
    saveDefaultConfig();
    String unpoweredTexture = getConfig().getString("textures.unpowered");
    String poweredTexture = getConfig().getString("textures.powered");
    int maxNetworkSize = getConfig().getInt("max-network-size", 256);

    CustomBlockRegistry registry = CoreLibPlugin.getInstance().getRegistry();
    cableManager = new CableManager(this, unpoweredTexture, poweredTexture, maxNetworkSize);

    CustomHeadBlock cableType = CustomHeadBlock.builder("cables", "cable")
        .texture(unpoweredTexture)
        .name(Component.text("Redstone Cable", NamedTextColor.RED))
        .lore(List.of(Component.text("Transmits redstone instantly", NamedTextColor.GRAY)))
        .drops(DropRule.self())
        .cancelPistons(true)
        .reactsToNeighbors(true)
        .onNeighborChange((block, face) -> cableManager.onNeighborChange(block))
        .onBlockRemoved((block, state) -> cableManager.unregisterCable(block))
        .onChunkLoad((block, state) -> cableManager.registerCable(block))
        .onChunkUnload(block -> cableManager.onChunkUnload(block))
        .shapedRecipe(new CustomHeadBlock.ShapedRecipeDef(
            "cable_craft", 8,
            List.of("RCR", "CRC", "RCR"),
            Map.of(
                'R', new CustomHeadBlock.IngredientSpec(Material.REDSTONE, null),
                'C', new CustomHeadBlock.IngredientSpec(Material.COPPER_INGOT, null)
            )
        ))
        .build();

    registry.register(cableType);
    cableManager.startTasks();

    getServer().getPluginManager().registerEvents(new CableListener(cableManager), this);
}

@Override
public void onDisable() {
    cableManager.shutdown();
}
```

**Why no states?** State-based texture swapping (powered/unpowered) would cause
`applyConfig()` to remove and re-spawn display entities every time power changes.
Instead, `updateNetworkVisuals()` swaps the skull texture directly via
`HeadUtil.applyTexture()` — much cheaper.

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

Registered via CoreLib's builder (see `onEnable()` above). Uses
`CustomHeadBlock.IngredientSpec(Material, blockId)` — pass `null` for blockId when
using vanilla materials. Yields 8 cables. Copper + redstone thematic.

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

Pistons moving blocks adjacent to cables don't reliably fire `BlockPhysicsEvent` on
the cable. `cancelPistons(true)` only prevents the cable itself from being pushed —
if a piston pushes a lever away from a cable, the cable's edge classification goes
stale. CableListener catches this:

```java
@EventHandler(ignoreCancelled = true)
public void onPistonExtend(BlockPistonExtendEvent event) {
    reclassifyIfAdjacentToCable(event.getBlocks());
}

@EventHandler(ignoreCancelled = true)
public void onPistonRetract(BlockPistonRetractEvent event) {
    reclassifyIfAdjacentToCable(event.getBlocks());
}

private void reclassifyIfAdjacentToCable(List<Block> movedBlocks) {
    Set<LocationKey> cablesToReclassify = new HashSet<>();
    for (Block moved : movedBlocks) {
        for (BlockFace face : CableManager.ALL_FACES) {
            LocationKey nk = LocationKey.of(moved.getRelative(face));
            if (cableManager.isCable(nk)) cablesToReclassify.add(nk);
        }
    }
    for (LocationKey key : cablesToReclassify) {
        cableManager.onNeighborChange(key.toBlock(movedBlocks.get(0).getWorld()));
    }
}
```

All other events (placement, breaking, explosions, fire, chunk load/unload) are
covered by CoreLib + `reactsToNeighbors` + `onNeighborChange`.

---

## Part 7: Chunk lifecycle

### Chunk load

CoreLib's chunk hint system + `onChunkLoad` callback handles this:

1. Chunk loads → CoreLib scans for skulls with `corelib:block_type = "cables:cable"` PDC
2. For each found cable, calls `onChunkLoadCallback` → `cableManager.registerCable()`
3. `registerCable()` builds/merges the network

### Chunk unload

CoreLib calls `onChunkUnloadCallback` per cable. To avoid O(cables × network_size)
cost from calling `handleNetworkSplit` one cable at a time, `onChunkUnload` collects
cables into a pending set and defers the split check:

```java
private final Set<LocationKey> pendingUnloads = new HashSet<>();

void onChunkUnload(Block block) {
    LocationKey key = LocationKey.of(block);
    CableNetwork net = cableToNetwork.remove(key);
    if (net == null) return;
    net.removeMember(key);
    if (net.isEmpty()) {
        networks.remove(net);
    } else {
        pendingUnloads.addAll(net.members());
    }
}
```

At the start of `tickPower()`, process pending unloads in bulk:

```java
if (!pendingUnloads.isEmpty()) {
    Set<CableNetwork> affected = new HashSet<>();
    for (LocationKey key : pendingUnloads) {
        CableNetwork net = cableToNetwork.get(key);
        if (net != null) affected.add(net);
    }
    pendingUnloads.clear();
    for (CableNetwork net : affected) {
        // Rebuild from scratch — members already removed
        networks.remove(net);
        clearNetworkOverrides(net);
        Set<LocationKey> remaining = new HashSet<>(net.members());
        while (!remaining.isEmpty()) {
            LocationKey seed = remaining.iterator().next();
            CableNetwork newNet = rebuildNetworkFrom(seed, remaining);
            remaining.removeAll(newNet.members());
            networks.add(newNet);
            for (LocationKey m : newNet.members()) cableToNetwork.put(m, newNet);
        }
    }
}
```

### Cross-chunk networks

A cable network can span chunk boundaries. When one chunk unloads, cables in that
chunk are removed and remaining members re-form into one or more sub-networks. On
chunk reload, `registerCable()` merges them back naturally.

Cross-chunk networks temporarily lose connectivity at chunk boundaries — acceptable,
matches vanilla redstone behavior where unloaded chunks don't process signals.

**Known limitation**: If chunk A has the lever and chunk C has the output, and the
connecting chunk B unloads, the output block in C loses power even though C is still
loaded. Power restores when B reloads.

---

## Part 8: Visual representation

### v1: Skull texture swap only

- **Unpowered**: grey/dark cable texture (base64 head texture, source from
  minecraft-heads.com — search for "cable" or "wire" themed heads)
- **Powered**: red/glowing cable texture (second base64 head)
- Swap via `HeadUtil.applyTexture(block, texture)` in `updateNetworkVisuals()`

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

- Every cable `LocationKey` maps to exactly one `CableNetwork` via `cableToNetwork`
- Every `CableNetwork` in `networks` has at least 1 member
- `inputEdges` and `outputEdges` are subsets of `members` (enforced by `removeMember`)
- On cable place/break, networks are rebuilt synchronously (no stale references)
- `unregisterCable` removes from `cableToNetwork` FIRST, before network split logic
- Network merge updates `cableToNetwork` for ALL absorbed members and removes old
  networks from `networks` — no orphaned network objects
- BFS during split is bounded to `oldNetwork.members()`, never the global
  `cableToNetwork` map — prevents cross-network absorption

### Race conditions / reentrancy

- All cable operations run on the main server thread (Bukkit scheduler). No
  thread safety concerns.
- `tickPower()` iterates a COPY of `networks` (`new ArrayList<>(networks)`) to
  tolerate structural changes from cascading physics events during texture updates.
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
13. **Adjacent network isolation**: Two parallel cable lines 1 block apart with
    different power sources → break a cable in line A → line B must be unaffected
    (no cross-network absorption)
14. **Plugin disable**: `/reload` → CoreLib blocks adjacent to cables must lose power
    override (not stay permanently powered)
15. **Piston edge case**: Place lever next to cable, push lever away with piston →
    cable network should lose power

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
