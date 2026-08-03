# Pipes Plugin - Developer Documentation

## Build Instructions

```bash
make build          # Build the plugin JAR to bin/
make clean          # Clean build artifacts
make server         # Build, copy to server/plugins/, and start server
```

| Target | Description |
|--------|-------------|
| `build` | Build shadowJar and copy to `bin/Pipes.jar` |
| `clean` | Clean Gradle build and `bin/` directory |
| `server-plugin-copy` | Copy JAR to `server/plugins/` |
| `server-clear-plugin-data` | Remove `server/plugins/Pipes/` config data |
| `server-start` | Start the test server |
| `server` | Build + copy + start (full dev workflow) |

## Architecture

Pipes uses CoreLib's **overlay pattern**: static block definitions (textures, recipes, drops, display entities) are declared in `pipes.yml` and loaded by CoreLib's `BlockLoader`. `PipeBlockRegistrar` then overlays runtime callbacks (placement, removal, chunk load/unload, neighbor changes, display transforms) onto those definitions via `toBuilder()`.

CoreLib owns:
- Block registration, PDC persistence (`corelib:block_type`), item creation
- Display entity lifecycle (spawn, despawn, chunk tracking)
- Recipe registration (shaped recipes from pipes.yml)
- Cauldron conversions
- Per-namespace world filtering

Pipes owns:
- Pipe transfer logic and pathfinding
- Display transform calculations (positioning display entities based on adjacent blocks)
- Variant-specific behavior (REGULAR vs CORNER)
- Conversion recipes (shapeless, registered by RecipeManager)

## File Structure

```
pipes/src/main/java/anon/def9a2a4/pipes/
├── PipesPlugin.java                - Main plugin class, commands, config loading
├── PipeManager.java                - Core pipe logic: transfers, pathfinding, transforms
├── PipeBlockRegistrar.java         - CoreLib overlay: callbacks for all 8 variants
├── WorldManager.java               - Per-world PipeManager lifecycle
├── PipeVariant.java                - Record: id, behaviorType, transfer settings, fluids, filter (FilterSpec)
├── BehaviorType.java               - Enum: REGULAR, CORNER
├── VariantRegistry.java            - Loads variant config from config.yml
├── RecipeManager.java              - Shapeless conversion recipe registration
├── ConversionRecipeCraftListener.java - Prevents catalyst consumption in conversion crafts
├── MachineEjectListener.java       - Handles machine block eject events
├── PipeFilterStore.java            - Filter pipe per-block state (items + mode flags) in block PDC
├── FilterHolder.java               - InventoryHolder for the filter config GUI
├── FilterGui.java                  - Filter config GUI: slots + toggle buttons, click/close listener
└── config/
    ├── PipeConfig.java             - Parses debug, world filter, performance settings
    └── DisplayConfig.java          - Parses display transform tuning values
```

```
pipes/src/main/resources/
├── plugin.yml      - Plugin metadata and permissions
├── pipes.yml       - Block definitions for CoreLib (textures, recipes, drops, states)
├── config.yml      - Variant behavior/transfer settings, cauldron/conversion recipes
└── display.yml     - Display entity transform tuning values
```

## Pipe Behavior Types

### REGULAR Pipes
- Face **away** from the block they were placed against
- Actively pull items from the block behind them (source)
- Can face any direction including UP and DOWN
- `playerHeadStates`: "up", "down" (floor/ceiling → PLAYER_HEAD)

### CORNER Pipes
- Face **toward** the block they were placed against (inverted)
- Never pull items — only relay items pushed into them
- Cannot face UP (placement returns null → cancelled)
- `playerHeadStates`: "down" only
- Have TWO display entities (main + directional indicator)

### Filter Pipes
REGULAR pipes (a non-null `FilterSpec` on the variant) that gate items to **only the types the player
configures**. A filter pipe gates **anywhere on a chain, not only against the source** — the transport
model is bufferless and atomic per tick, so an extractor applies the AND of every filter pipe on its
resolved path (`CachedPath.filterPipes`, collected in `findDestination`); non-matching items are simply
never pulled and stay in the source, which is observably identical to items being blocked mid-run.
Filters in series therefore AND together. This is a **gate, not a router** — the single-facing model has
no junctions, so a mid-chain filter can only stop items, never divert them; sorting stays the source-side
pattern (several filter pipes on one chest, each feeding a different destination). Visually/placement-wise
**identical to the plain pipe of that tier** (reuse the same textures/states in `pipes.yml`).

- **Filter items are real and consumed** — dropping an item into a filter slot takes it from the
  player; `PipeFilterStore` persists it (+ the mode flags) to the head block's PDC, and
  `PipeBlockRegistrar.onBlockRemoved` drops the stored items back when the pipe is broken.
- **Config GUI** (`FilterGui`, opened via the variant's `onInteract`): a 54-slot double chest; the
  first `FilterSpec.slots` slots hold filter items, the rest of the top rows are locked filler, and the
  bottom row holds tier-gated toggle buttons (white/black dye for whitelist⇄blacklist; a similar/exact
  head pair for material⇄exact matching). Edits refresh `PipeManager`'s per-block filter cache.
- **Extraction hook:** `PipeManager.transferItems` builds a chain-wide predicate via `buildChainFilter`
  (AND of `getFilter(loc)` for each pipe in `path.filterPipes`) and passes it to the predicate-aware
  `ContainerAdapter.peekExtract(block, max, accept)` (CoreLib) so it scans past non-matching slots.
  `FilterData.test` = (type/`isSimilar` match) XOR blacklist; an **empty whitelist blocks everything**
  (an unconfigured mid-chain filter halts its line until configured).
- **Machine push-down:** `deliverFromAbove` applies the same chain predicate and **STALLs** the whole
  push if any pushed item is rejected (the eject is atomic/binary — no partial delivery), rather than
  leaking past the filter. Route machine output through a chest to gate it.
- **Filter edits:** `refreshFilter` only updates the filter cache (path geometry is unchanged); `FilterGui`
  calls `PipeManager.wakeAll()` on GUI **close** to wake extractors that slept while fully blocked.
- **Tiers** (config.yml `variants:` → `filter:` section):

  | Variant             | slots | allow-blacklist-toggle | allow-exact-toggle | items/transfer |
  |---------------------|:-----:|:----------------------:|:------------------:|:--------------:|
  | `copper_filter_pipe`| 5     | false                  | false              | 4              |
  | `iron_filter_pipe`  | 9     | true                   | false              | 8              |
  | `gold_filter_pipe`  | 18    | true                   | true               | 16             |

- **Not yet:** filtering aboard moving mechanisms (registered as plain conduits); a planned extra
  rotation-dependent display entity to visually distinguish filter pipes.

## Transfer System

Transfer runs on a per-variant configurable interval (default: 10 ticks = 0.5 sec):

1. **Regular pipes only:** Check block opposite facing direction (source)
2. If source is a container with items, extract up to `itemsPerTransfer` items
3. Follow pipe's facing direction to find destination via recursive pathfinding
4. Deposit into destination container, or drop on ground if no valid endpoint
5. Sleep timers reduce tick cost when source is empty or destination is full

## Persistence

CoreLib handles persistence via PDC tags on player head blocks (`corelib:block_type → "pipes:copper_pipe"`). On chunk load, CoreLib fires the `onChunkLoad` callback registered by `PipeBlockRegistrar`, which re-registers the pipe in `PipeManager`'s in-memory map. Display entities are managed entirely by CoreLib.

### Legacy migration (standalone Pipes ≤ v0.2.0)

`LegacyPipeMigrator` adopts pipes from the old standalone plugin (identity on `ItemDisplay`s via the `pipe:tag` PDC / `pipe:` scoreboard tag, no block PDC) into the CoreLib format on `EntitiesLoadEvent`, per-world catch-up sweeps, and `/pipes migrate`. It also removes stray legacy displays and plugs a foreign-orphan detector into CoreLib so `/defcorelib cleanorphans` sees them. Sunset: delete the class + its wiring (PipesPlugin, WorldManager, plugin.yml) in v0.4.0 once servers report zero migrations.

## Config Files

- **pipes.yml** (in JAR, not user-editable): Block definitions — textures, shaped recipes, drops, display entity config. Loaded by `BlockLoader`.
- **config.yml** (user-editable): Variant behavior/transfer tuning, world filter, performance settings, cauldron conversions, shapeless conversion recipes.
- **display.yml** (in JAR): Display entity transform tuning values (scales, offsets, endpoint adjustments).

## Development TODOs

- Fix velocity offset from downward pipes
- Add "valve" pipe to enable/disable flow
- Determine behavior for pistons pushing pipes

### Future Ideas
- Dispenser pipes
- Warp pipes (teleport entities)
- Dyed pipes
- Glass window pipes (show items inside)
