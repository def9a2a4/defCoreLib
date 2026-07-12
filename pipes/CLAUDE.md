# Pipes Plugin - Developer Documentation

Technical documentation for developers working on the Pipes plugin.

## Build Instructions

```bash
make build          # Build the plugin JAR to bin/
make clean          # Clean build artifacts
make server         # Build, copy to server/plugins/, and start server
```

**Make targets:**
| Target | Description |
|--------|-------------|
| `build` | Build shadowJar and copy to `bin/Pipes.jar` |
| `clean` | Clean Gradle build and `bin/` directory |
| `server-plugin-copy` | Copy JAR to `server/plugins/` |
| `server-clear-plugin-data` | Remove `server/plugins/Pipes/` config data |
| `server-start` | Start the test server |
| `server` | Build + copy + start (full dev workflow) |

## File Structure

```
Pipes/
├── src/main/java/anon/def9a2a4/pipes/
│   ├── PipesPlugin.java            - Main plugin class, commands, item creation
│   ├── PipeManager.java            - Core pipe logic, item transfer, display entities
│   ├── PipeListener.java           - Event handlers for placement/breaking/chunks
│   ├── PipeTags.java               - Scoreboard tag encoding for persistence
│   ├── BehaviorType.java           - Enum: REGULAR, CORNER
│   ├── PipeVariant.java            - Variant definition (rates, textures, etc.)
│   ├── VariantRegistry.java        - Loads and manages variants from config
│   ├── RecipeManager.java          - Crafting recipe registration
│   ├── RecipeDefinition.java       - Shaped recipe data model
│   ├── ConversionRecipeDefinition.java - Shapeless recipe data model
│   ├── RecipeUnlockListener.java   - Advancement-based recipe unlocking
│   ├── CauldronConversionListener.java - Cauldron item conversion
│   ├── ConversionRecipeCraftListener.java - Handles conversion recipe crafting
│   └── config/
│       ├── PipeConfig.java         - Global config parser
│       └── DisplayConfig.java      - Texture and display settings
├── src/main/resources/
│   ├── plugin.yml                  - Plugin metadata
│   ├── config.yml                  - User configuration
│   └── display.yml                 - Internal display settings
├── docs/assets/                    - Documentation images
└── textures/                       - Texture design files
```

## Class Responsibilities

### PipesPlugin.java
Main plugin class handling initialization and lifecycle.

**Key Responsibilities:**
- Plugin enable/disable lifecycle
- Config loading (config.yml and display.yml)
- Item creation with custom textures (player heads)
- Command handling: `/pipes reload|give|recipes|cleanup|info|delete_all`
- Permission checking and tab completion

**Key Methods:**
- `onEnable()` - Initializes configs, loads items, registers listeners, scans for existing pipes
- `onDisable()` - Shuts down PipeManager
- `onCommand()` - Handles all subcommands
- `loadConfigs()` - Loads config.yml and display.yml
- `loadItems()` - Creates ItemStack objects for all pipe variants
- `createPipeItem()` - Creates player head items with base64 texture data

### PipeManager.java
Core pipe system handling transfers, display entities, and persistence.

**Key Data Structure:**
```java
record PipeData(BlockFace facing, List<UUID> displayEntityIds, PipeVariant variant)
```

**Key Responsibilities:**
- Tracks all active pipes via `Map<Location, PipeData>`
- Item transfer between containers on scheduled task
- Spawns and manages ItemDisplay entities for visual representation
- Calculates 3D transformations for pipe visuals
- Scans for existing pipes on server start (persistence)
- Handles chunk loading/unloading

**Key Methods:**
- `registerPipe()` / `unregisterPipe()` - Pipe lifecycle
- `transferAllPipes()` - Main transfer loop checking time intervals
- `transferItems()` - Extract from source, follow pipe chain, deposit at destination
- `findDestination()` - Recursive pathfinding through pipe networks
- `spawnDisplayEntities()` - Create ItemDisplay entities
- `calculateTransformation()` / `calculateCornerTransformation()` - 3D positioning math
- `scanChunk()` / `scanForExistingPipes()` - Restore pipes after restart

### PipeListener.java
Event handler for pipe placement, breaking, and chunk events.

**Events Handled:**
- `BlockPlaceEvent` - Creates pipe with orientation, spawns displays
- `BlockBreakEvent` - Removes pipe, drops item, updates adjacent
- `ChunkLoadEvent` - Scans chunk for existing pipes
- `ChunkUnloadEvent` - Unloads pipes from memory
- `EntityExplodeEvent` / `BlockExplodeEvent` - Handle explosions
- `BlockPistonExtendEvent` / `BlockPistonRetractEvent` - Cancel piston pushing
- `BlockDestroyEvent` - Handle physics/command-based destruction
- `BlockBurnEvent` - Handle fire destruction

### PipeTags.java
Utility for persistence via scoreboard tags.

**Tag Format:**
```
pipe:{variant_id}:{x}_{y}_{z}_{facing}
pipe:{variant_id}:{x}_{y}_{z}_{facing}_dir  (for corner pipe directional display)
```

**Key Methods:**
- `createTag()` / `createDirectionalTag()` - Create tags for entities
- `parseLocation()`, `parseFacing()`, `parseVariantId()` - Parse tag data
- `isPipeEntity()` - Check if entity has pipe tag

### VariantRegistry.java
Loads and manages pipe variants from config.yml.

**Key Methods:**
- `loadFromConfig()` - Load all variants
- `parseVariant()` - Parse single variant config
- `parseRecipes()` - Extract recipe definitions
- `getVariant()` / `getVariantByKey()` - Lookups

### RecipeManager.java
Registers and manages crafting recipes.

**Key Methods:**
- `registerRecipes()` - Register shaped + conversion recipes
- `registerShapedRecipes()` - Create ShapedRecipe for each variant
- `registerConversionRecipes()` - Create ShapelessRecipe for conversions
- `discoverAllRecipes()` / `undiscoverAllRecipes()` - Player recipe books

## Architecture Overview

```
PipesPlugin (Main)
├── Loads -> PipeConfig, DisplayConfig
├── Manages -> VariantRegistry (all variants)
├── Manages -> PipeManager (all pipes)
├── Manages -> RecipeManager (recipes)
├── Registers -> PipeListener (block events)
├── Registers -> RecipeUnlockListener (advancement events)
├── Registers -> CauldronConversionListener (item conversion)
└── Registers -> ConversionRecipeCraftListener (crafting)

PipeManager
├── Uses -> PipeVariant (transfer rates, behavior)
├── Uses -> DisplayConfig (texture & transformation settings)
├── Tracks -> Map<Location, PipeData> (all active pipes)
├── Spawns -> ItemDisplay entities (with scoreboard tags)
└── Uses -> PipeTags (for entity persistence)
```

## Pipe Behavior Types

### REGULAR Pipes
- Face **away** from the block they were placed against
- Actively pull items from the block behind them (source)
- Can face any direction including UP and DOWN

### CORNER Pipes
- Face **toward** the block they were placed against
- Never pull items - only relay items pushed into them
- Cannot face UP (DOWN-facing is blocked)
- Have TWO display entities (main + directional indicator)

## Transfer System

Transfer runs on a configurable interval per variant (default: 10 ticks = 0.5 sec):

1. **Regular pipes only:** Check block opposite facing direction (source)
2. If source is a container with items, extract items
3. Follow pipe's facing direction to find destination:
   - If another pipe → push items into that pipe
   - If container → deposit items
   - If no valid destination → drop items on ground
4. Transfer respects minimum items-per-transfer along entire chain

**Supported containers:** Any block implementing `Container` interface.

## Visual System

Each pipe uses:
- **Player head block** placed at pipe location (with custom texture)
- **ItemDisplay entity** for extended visual representation
- Corner pipes have **two displays** (main + directional)

Display entities use complex 3D transformations that account for:
- Pipe facing direction
- Adjacent containers/pipes
- Block-specific adjustments (defined in display.yml)

## Persistence System

Pipes persist across server restarts via scoreboard tags on ItemDisplay entities:

1. Player head block remains in the world
2. ItemDisplay entity has scoreboard tag encoding location, facing, variant
3. On chunk load, plugin scans for entities with pipe tags
4. Re-registers pipes from tag data

**Tag format:** `pipe:{variant_id}:{x}_{y}_{z}_{facing}[_dir]`

## Recipe System

### Shaped Recipes
Standard crafting table recipes defined per variant in config.yml:
```yaml
recipes:
  - shape: ["CCC", " B ", "CCC"]
    ingredients:
      C: COPPER_INGOT
      B: COPPER_BLOCK
    result-amount: 8
```

### Conversion Recipes (Shapeless)
Transform one pipe variant to another using a catalyst:
```yaml
conversion-recipes:
  - from: copper_pipe
    to: oxidized_copper_pipe
    catalyst: WATER_BUCKET
    result-amount: 1
```

### Cauldron Conversion
Throw pipes into water cauldrons to convert them:
```yaml
cauldron-conversions:
  copper_pipe: oxidized_copper_pipe
```

### Advancement Unlocking
Recipes can be locked until an advancement is completed:
```yaml
recipes:
  unlock-advancement: "minecraft:story/smelt_iron"
```

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
