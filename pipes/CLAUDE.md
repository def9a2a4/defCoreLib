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
├── PipeVariant.java                - Record: id, behaviorType, transferIntervalTicks, itemsPerTransfer
├── BehaviorType.java               - Enum: REGULAR, CORNER
├── VariantRegistry.java            - Loads variant config from config.yml
├── RecipeManager.java              - Shapeless conversion recipe registration
├── ConversionRecipeCraftListener.java - Prevents catalyst consumption in conversion crafts
├── MachineEjectListener.java       - Handles machine block eject events
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

## Transfer System

Transfer runs on a per-variant configurable interval (default: 10 ticks = 0.5 sec):

1. **Regular pipes only:** Check block opposite facing direction (source)
2. If source is a container with items, extract up to `itemsPerTransfer` items
3. Follow pipe's facing direction to find destination via recursive pathfinding
4. Deposit into destination container, or drop on ground if no valid endpoint
5. Sleep timers reduce tick cost when source is empty or destination is full

## Persistence

CoreLib handles persistence via PDC tags on player head blocks (`corelib:block_type → "pipes:copper_pipe"`). On chunk load, CoreLib fires the `onChunkLoad` callback registered by `PipeBlockRegistrar`, which re-registers the pipe in `PipeManager`'s in-memory map. Display entities are managed entirely by CoreLib.

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
- Filter pipes
- Glass window pipes (show items inside)
