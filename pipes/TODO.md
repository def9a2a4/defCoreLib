# Pipes v0.2.0 — Roadmap

Reimplementing good ideas from the reviewed `dev` branch as clean, incremental improvements on `main`.

## Phases

- [x] **Phase 1: PDC migration** — Switch entity tags from scoreboard tags to PersistentDataContainer. Hybrid read (PDC first, scoreboard fallback) for backwards compat. PDC-only writes. Auto-migrate on chunk load.
- [x] **Phase 2: Per-world PipeManager** — One PipeManager per world via `WeakHashMap<World, PipeManager>`. New `WorldManager` for world load/unload lifecycle. Config to enable/disable pipes per world.
- [x] **Phase 3: Container adapters** — `ContainerAdapter` interface with peek/commit extraction. Implementations for vanilla containers, furnaces (vanilla hopper parity), and brewing stands (no extract during brewing). `ContainerAdapterRegistry` for lookup.
- [x] **Phase 4: Path caching + sleep/throttle** — Cache computed paths with full-invalidation on topology changes. Validate all chain members. Sleep idle pipes (empty source / full dest) using ticks. Dead-end recheck cooldown. Transfer phase offset to spread load.
- [ ] **Phase 5: Corner pipe improvements** — (deferred, different approach planned)
- [ ] **Phase 6: Oxidation system** — Config-gated copper aging. Waxing (honeycomb) and scraping (axe). Batch `tickOxidation()` with single cache eviction pass. Disableable via `oxidation.enabled: false`.
- [ ] **Phase 7: Listener package reorg** — Move all listeners into `listener/` subpackage.

---

## Phase 1: PDC Migration

**Goal:** Switch from scoreboard tags to PersistentDataContainer for entity identification. Backwards compatible.

**Strategy — hybrid read, PDC-only write:**
- `getPipeTag(Entity)` — check PDC first, fall back to scoreboard tag scan
- `addPipeTag(Entity, String)` — write to PDC, remove any old scoreboard tag
- `isPipeEntity(Entity)` — check PDC first, fall back to scoreboard tag scan
- On chunk scan (`scanChunk`), auto-migrate: if entity has scoreboard tag but no PDC, write PDC + remove scoreboard tag
- Add `_head` suffix support for corner head displays (needed later in Phase 5)
- Log migration count at INFO level

**Files to modify:**
- `PipeTags.java` — rewrite API from `Set<String>` to `Entity`-based, add PDC key, keep scoreboard fallback
- `PipeManager.java` — update all callers (spawn, scan, cleanup, remove) to use new `Entity`-based API

**Why PDC over scoreboard tags:**
- O(1) namespaced key lookup vs O(n) string iteration
- No collision risk with other plugins
- Modern Bukkit best practice

---

## Phase 2: Per-world PipeManager

**Goal:** Isolate pipe state per world for multi-world servers.

**Changes:**
- `PipesPlugin`: replace `private PipeManager pipeManager` with `WeakHashMap<World, PipeManager>`
- New `WorldManager.java`: listens for `WorldLoadEvent`/`WorldUnloadEvent`, creates/shuts down per-world PipeManagers
- `PipeManager` constructor takes `(PipesPlugin, World)`, stores world reference
- `PipeListener`, commands route to correct manager via `pipeManagers.get(location.getWorld())`
- Chunk scan scoped to the manager's world
- Config to enable/disable pipes per world (allowlist or blocklist mode)
- `/pipes reload` re-evaluates world filters + re-resolves stale `PipeVariant` references via `reloadVariants()`

### Per-world config (`config.yml`)
```yaml
worlds:
  mode: allowlist  # "allowlist" or "blocklist"
  list:
    - world
    - world_the_end
```
- `allowlist` mode: pipes only work in listed worlds
- `blocklist` mode: pipes work everywhere except listed worlds
- Default: no filtering (all worlds enabled)
- `WorldManager` checks config before creating a PipeManager for a world
- Pipe placement blocked in disabled worlds (cancel `BlockPlaceEvent` with message)

**Files to modify:**
- `PipesPlugin.java` — WeakHashMap, routing helpers
- `PipeManager.java` — constructor, world-bound tasks, remove world params from methods
- `PipeListener.java` — route events to correct manager, block placement in disabled worlds
- `PipeConfig.java` — parse world filter config
- New `WorldManager.java`

---

## Phase 3: Container Adapters

**Goal:** Decouple inventory logic from PipeManager. Enable proper furnace/brewing stand handling.

**Interface — `adapter/ContainerAdapter.java`:**
- `canReceive(Block)`, `insert(Block, ItemStack)`, `peekExtract(Block, int)`, `commitExtract(Block, ItemStack)`, `hasItems(Block)`
- Static helpers: `tryInsertSlot(Inventory, int, ItemStack)`, `removeFromSlots(Inventory, ItemStack, int, int)`

**Implementations:**
- `VanillaContainerAdapter` — wraps `Container.getInventory().addItem()` for chests, hoppers, etc.
- `FurnaceContainerAdapter` — fuel slot only tops up existing stacks (vanilla hopper parity), extracts only from result slot
- `BrewingStandContainerAdapter` — no extraction during active brewing, routes bottles/ingredients/fuel to correct slots

**Registry — `adapter/ContainerAdapterRegistry.java`:**
- `findAdapter(Block) -> Optional<ContainerAdapter>` — checks BrewingStand > Furnace > vanilla Container

**PipeManager refactor:**
- `transferItems()`: adapter-based peek/commit extraction + insertion with partial insert handling
- `findDestination()`: adapter check instead of `instanceof Container`
- `categorizeSourceBlock()`/`categorizeDestinationBlock()`: adapter registry for "container" category

**Files created:**
- `adapter/ContainerAdapter.java`, `adapter/VanillaContainerAdapter.java`, `adapter/FurnaceContainerAdapter.java`, `adapter/BrewingStandContainerAdapter.java`, `adapter/ContainerAdapterRegistry.java`

---

## Phase 4: Path Caching + Sleep/Throttle

**Goal:** Major performance win for large pipe networks.

### Path caching (simple full-invalidation)
- `CachedPath` record: `(destination, lastPipeLocation, pipeChain, minItemsPerTransfer)`
- `pathCache: Map<Location, CachedPath>` — keyed by pipe start location
- `getOrBuildPath()` — check cache → validate → rebuild if stale
- `invalidatePathCache()` → `pathCache.clear()` — called from PipeListener on adjacent block changes, and on pipe register/unregister
- `isPathStillValid()` — validates ALL chain members still exist + destination still has adapter. Dead-end paths use `deadEndRecheckAt` cooldown to avoid rechecking every tick.
- Decided against reverse-index invalidation — full clear is simpler, pipe topology changes are rare (player actions), and phase offsets amortize rebuild cost across ticks.

### Sleep/throttle
- `sleepUntil: Map<Location, Long>` — tick-based (not millis)
- Source empty → sleep for `source-empty-ticks` (config, default 60)
- Dest full → sleep for `dest-full-ticks` (config, default 80)
- `wakeUpPipe()` — called from PipeListener `updateAdjacentPipes()` when adjacent block changes
- `deadEndRecheckAt: Map<Location, Long>` — cooldown before rechecking dead-end paths (config, default 40 ticks)

### Transfer phase offset
- Task runs every tick; each pipe hashes its location to a phase offset within its variant's interval
- `isTransferDue(currentTick, loc, intervalTicks)` — replaces millis-based `lastTransferTime`

### Config (`config.yml`)
```yaml
performance:
  sleep:
    source-empty-ticks: 60
    dest-full-ticks: 80
    end-recheck-ticks: 40
```

**Files modified:** `PipeManager.java`, `PipeListener.java`, `PipeConfig.java`, `config.yml`

---

## Phase 5: Corner Pipe Improvements (deferred)

Planning a different approach to corner junction routing than what was in the reviewed dev branch. `CachedPath.pipeChain` is already in place to support future corner junction work.

Potential scope:
- Multi-output display entities for corner junctions
- DOWN-facing head displays (`_head` tag suffix already supported in PipeTags)
- UP direction support for corner pipes
- Junction fallback routing when primary destination is full

---

## Phase 6: Oxidation System

**Goal:** Config-gated copper pipe aging that mirrors vanilla copper mechanics. Must be fully disableable.

### Mechanics
- Periodic check: iterate all loaded oxidizable pipes, roll probability per pipe
- Right-click with honeycomb → wax pipe (prevents oxidation)
- Right-click with axe → scrape (remove wax or reverse one oxidation stage)
- `convertPipeVariant()` on PipeManager — updates PipeData, skull texture, display entity textures + PDC tags
- `tickOxidation()` on PipeManager — batch conversions, single cache eviction pass

### Config (`config.yml`)
```yaml
oxidation:
  enabled: true
  check-interval-ticks: 1200
  chance-numerator: 1
  chance-denominator: 16
  transitions:
    copper_pipe: oxidized_copper_pipe
    copper_corner_pipe: oxidized_copper_corner_pipe
  wax-transitions:
    copper_pipe: waxed_copper_pipe
    copper_corner_pipe: waxed_copper_corner_pipe
```

### Performance
- Only iterates pipes once per check interval (~1 min default)
- 1/16 chance per pipe per check — negligible cost even with 1000+ pipes
- Entirely disabled via `oxidation.enabled: false`

**Files:**
- New `listener/OxidationListener.java`
- `PipeManager.java` — `tickOxidation()`, `convertPipeVariant()`
- `config.yml`, `display.yml` — new variants and textures

---

## Phase 7: Listener Package Reorg

**Goal:** Cleaner project structure as listener count grows.

**Moves:**
- `PipeListener.java` → `listener/PipeListener.java`
- `CauldronConversionListener.java` → `listener/CauldronConversionListener.java`
- `RecipeUnlockListener.java` → `listener/RecipeUnlockListener.java`
- `ConversionRecipeCraftListener.java` → `listener/ConversionRecipeCraftListener.java`

Update package declarations and imports in moved files and all referencing files.

Can be done at any point — no functional dependencies.
