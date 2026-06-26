# Migrate Pipes to defCoreLib — Implementation Plan

## Context

Pipes and defCoreLib duplicate significant infrastructure: both manage player-head
custom blocks with ItemDisplay entities, handle chunk scanning/restoration, event
processing (place, break, explosions, pistons, fire), and item/recipe creation.

The migration makes Pipes depend on CoreLib for all general block infrastructure.
Pipes shrinks to pipe-specific logic only: item transfer, path caching, container
adapters, and neighbor-aware display transforms.

**The core design challenge**: Pipes' display entities have *dynamic* transforms that
recalculate whenever an adjacent block changes — the pipe visual stretches or retracts
to connect to containers, other pipes, or terminate in air. CoreLib's display entities
currently use *static* transforms defined in YAML. The solution is a new
`DisplayTransformResolver` callback in CoreLib that lets consumers provide custom
transform logic while CoreLib manages the lifecycle.

---

## Part 1: defCoreLib — Add `DisplayTransformResolver` support

### 1a. New `DisplayTransformResolver` interface

**File**: `CustomHeadBlock.java` — add after the `StateChangeHandler` interface (~line 267)

```java
/** Pluggable transform resolver for display entities whose transforms depend on
 *  adjacent block state. Called by CoreLib on placement, neighbor change, and
 *  chunk load. The resolver replaces the static transform from DisplayEntityConfig. */
@FunctionalInterface
public interface DisplayTransformResolver {
    /**
     * @param block         the custom block
     * @param state         the block's current state string
     * @param config        the DisplayEntityConfig being resolved
     * @param displayIndex  index within the resolved display entity list (0-based)
     * @return the computed Transformation, or null to keep the config's static transform
     */
    @Nullable Transformation resolve(Block block, String state,
                                      DisplayEntityConfig config, int displayIndex);
}
```

The same interface covers `BlockDisplayEntityConfig` via overload or a second resolver
field. For simplicity, start with ItemDisplay only (Pipes doesn't use BlockDisplay).

### 1b. New fields in `CustomHeadBlock`

**Private field** (after `onBlockRemoved`, line 268):
```java
private final @Nullable DisplayTransformResolver displayTransformResolver;
```

**Constructor** (after `this.onBlockRemoved = b.onBlockRemoved;`, line 305):
```java
this.displayTransformResolver = b.displayTransformResolver;
```

**Accessor** (after `onBlockRemoved()`, line 360):
```java
public @Nullable DisplayTransformResolver displayTransformResolver() { return displayTransformResolver; }
```

### 1c. Builder addition

**Field** (after `onBlockRemoved` at line 527):
```java
private @Nullable DisplayTransformResolver displayTransformResolver;
```

**Method** (after `onBlockRemoved()` builder method, line 638):
```java
public Builder displayTransformResolver(DisplayTransformResolver resolver) {
    this.displayTransformResolver = resolver;
    this.reactsToNeighbors = true;  // auto-enable neighbor tracking
    return this;
}
```

Setting `reactsToNeighbors = true` ensures CoreLib's `BlockPhysicsEvent` handler will
dispatch `onNeighborChange` for this block type, which triggers transform recalculation.

### 1d. New `onBlockPlaced` callback

Pipes also needs a callback after display entities are spawned, to register the pipe
in the transfer system and compute the initial transform.

**File**: `CustomHeadBlock.java`

**Field** (after `onBlockRemoved`, line 268):
```java
private final @Nullable BiConsumer<Block, String> onBlockPlaced;
```

**Constructor, accessor, builder** — same pattern as `onBlockRemoved`:
```java
// Constructor:
this.onBlockPlaced = b.onBlockPlaced;

// Accessor:
public @Nullable BiConsumer<Block, String> onBlockPlaced() { return onBlockPlaced; }

// Builder:
private @Nullable BiConsumer<Block, String> onBlockPlaced;
public Builder onBlockPlaced(BiConsumer<Block, String> handler) {
    this.onBlockPlaced = handler; return this;
}
```

### 1e. New `stateResolver` callback

Pipes has placement logic that can't be expressed with `placementStateMap`:
- Regular pipes face AWAY from placement surface; corner pipes face TOWARD
- Corner pipes cancel UP-facing placement
- Vertical pipes force `PLAYER_HEAD` block type and lock rotation to NORTH

**File**: `CustomHeadBlock.java`

```java
/** Programmatic state determination at placement time. Replaces placementStateMap
 *  when set. Return null to cancel placement. */
@FunctionalInterface
public interface StateResolver {
    @Nullable String resolve(BlockPlaceEvent event, Block placed);
}

// Field:
private final @Nullable StateResolver stateResolver;

// Constructor:
this.stateResolver = b.stateResolver;

// Accessor:
public @Nullable StateResolver stateResolver() { return stateResolver; }

// Builder:
private @Nullable StateResolver stateResolver;
public Builder stateResolver(StateResolver resolver) {
    this.stateResolver = resolver; return this;
}
```

**Note**: `StateResolver` needs `BlockPlaceEvent` as a parameter, which means
`CustomHeadBlock.java` gains an import for `org.bukkit.event.block.BlockPlaceEvent`.
This is the only Bukkit event type imported by the model class — acceptable since
placement is inherently event-driven.

### 1f. Wire resolver into `CustomBlockRegistry.applyConfig()`

**File**: `CustomBlockRegistry.java`, inside `applyConfig()` at line 360-392

Currently, display entities are spawned with the static transform from config:
```java
var display = DisplayUtil.spawn(spawnBase, displayItem, dec.transform(), tag);
```

**Change**: If the block type has a `displayTransformResolver`, call it to get the
transform. Fall back to the config's static transform if the resolver returns null.

```java
// Display entities
if (type.hasDisplayEntities()) {
    String tagPrefix = DisplayUtil.blockTagPrefix(type.namespace(), type.typeId(), block.getLocation());
    DisplayUtil.removeByTag(block.getLocation(), tagPrefix, 1.5);
    List<CustomHeadBlock.DisplayEntityConfig> displays = type.resolveDisplayEntities(state);
    List<AnimationTracked> anims = null;
    for (int i = 0; i < displays.size(); i++) {
        var dec = displays.get(i);
        ItemStack displayItem = dec.displayItem();
        String tag = DisplayUtil.blockTag(type.namespace(), type.typeId(),
                block.getLocation(), dec.tagSuffix());
        org.bukkit.Location spawnBase = block.getLocation();
        if (dec.wallOffset() != 0 && block.getType() == Material.PLAYER_WALL_HEAD
                && block.getBlockData() instanceof Directional wallDir) {
            Vector wallFacing = wallDir.getFacing().getDirection();
            spawnBase = spawnBase.clone().subtract(wallFacing.multiply(dec.wallOffset()));
        }

        // NEW: resolve transform dynamically if resolver is set
        Transformation transform = dec.transform();
        if (type.displayTransformResolver() != null) {
            Transformation resolved = type.displayTransformResolver()
                    .resolve(block, state, dec, i);
            if (resolved != null) transform = resolved;
        }

        var display = DisplayUtil.spawn(spawnBase, displayItem, transform, tag);
        if (dec.interpolationDuration() != 0) {
            display.setInterpolationDuration(dec.interpolationDuration());
        }
        if (dec.animation() != null) {
            if (anims == null) anims = new ArrayList<>();
            anims.add(new AnimationTracked(display, dec.animation(),
                    Bukkit.getServer().getCurrentTick(),
                    transformToMatrix(dec.transform())));
        }
    }
    if (anims != null) {
        animationTracked.put(key, anims);
    } else {
        animationTracked.remove(key);
    }
}
```

### 1g. Wire resolver into neighbor change handler

**File**: `CoreLibPlugin.java`, lines 726-746 (neighbor change handling)

Currently, CoreLib dispatches `onNeighborChange` to the block type's callback. Add
automatic display transform re-resolution for blocks with a `displayTransformResolver`.

After the existing `onNeighborChange` dispatch, add:

```java
// Re-resolve display transforms for blocks with dynamic displays
if (type.displayTransformResolver() != null && type.hasDisplayEntities()) {
    String state = registry.getState(neighbor);
    List<CustomHeadBlock.DisplayEntityConfig> displays = type.resolveDisplayEntities(state);
    String tagPrefix = DisplayUtil.blockTagPrefix(
            type.namespace(), type.typeId(), neighbor.getLocation());
    List<Display> existing = DisplayUtil.findByTag(neighbor.getLocation(), tagPrefix, 1.5);

    // Match existing displays to configs by tag suffix
    for (int i = 0; i < displays.size(); i++) {
        var dec = displays.get(i);
        String fullTag = DisplayUtil.blockTag(
                type.namespace(), type.typeId(), neighbor.getLocation(), dec.tagSuffix());
        Transformation resolved = type.displayTransformResolver()
                .resolve(neighbor, state, dec, i);
        if (resolved == null) continue;

        for (Display d : existing) {
            if (d.getScoreboardTags().contains(fullTag)) {
                d.setTransformation(resolved);
                break;
            }
        }
    }
}
```

### 1h. Wire resolver into chunk load restoration

**File**: `CustomBlockRegistry.java`, inside `restoreBlock()` at line 232

After the existing chunk load restoration code (which re-registers tracking maps),
add display transform re-resolution:

```java
// Re-resolve dynamic display transforms after chunk load
if (type.displayTransformResolver() != null && type.hasDisplayEntities()) {
    List<CustomHeadBlock.DisplayEntityConfig> displays = type.resolveDisplayEntities(state);
    String tagPrefix = DisplayUtil.blockTagPrefix(type.namespace(), type.typeId(), block.getLocation());
    List<Display> existing = DisplayUtil.findByTag(block.getLocation(), tagPrefix, 1.5);

    for (int i = 0; i < displays.size(); i++) {
        var dec = displays.get(i);
        String fullTag = DisplayUtil.blockTag(
                type.namespace(), type.typeId(), block.getLocation(), dec.tagSuffix());
        Transformation resolved = type.displayTransformResolver()
                .resolve(block, state, dec, i);
        if (resolved == null) continue;

        for (Display d : existing) {
            if (d.getScoreboardTags().contains(fullTag)) {
                d.setTransformation(resolved);
                break;
            }
        }
    }
}
```

**Note**: `restoreBlock()` runs during chunk load after display entities have been
restored by the server. The entities already exist at this point — we just need to
fix their transforms.

### 1i. Wire `onBlockPlaced` into placement handler

**File**: `CoreLibPlugin.java`, lines 172-182 (inside the `runTask` lambda)

After `applyConfig()` and redstone tracking registration, add:

```java
// Notify consumer after display entities are spawned
if (type.onBlockPlaced() != null) {
    type.onBlockPlaced().accept(block, state);
}
```

### 1j. Wire `stateResolver` into placement handler

**File**: `CoreLibPlugin.java`, lines 152-159 (state resolution)

Replace the `placementStateMap` lookup with resolver-first logic:

```java
// Resolve initial state
String resolvedState;
if (type.stateResolver() != null) {
    resolvedState = type.stateResolver().resolve(event, block);
    if (resolvedState == null) {
        event.setCancelled(true);
        return;
    }
} else {
    resolvedState = type.defaultState();
    var psm = type.placementStateMap();
    if (psm != null) {
        String mapped = psm.get(placedOn);
        if (mapped != null) resolvedState = mapped;
    }
}
final String state = resolvedState;
```

### 1k. Helper: `resolveDisplayTransforms()`

The transform re-resolution logic in 1g and 1h is identical. Extract to
`CustomBlockRegistry`:

```java
/** Re-resolve display transforms for a block with a DisplayTransformResolver. */
void resolveDisplayTransforms(Block block, CustomHeadBlock type, @Nullable String state) {
    if (type.displayTransformResolver() == null || !type.hasDisplayEntities()) return;
    List<CustomHeadBlock.DisplayEntityConfig> displays = type.resolveDisplayEntities(state);
    String tagPrefix = DisplayUtil.blockTagPrefix(type.namespace(), type.typeId(), block.getLocation());
    List<Display> existing = DisplayUtil.findByTag(block.getLocation(), tagPrefix, 1.5);

    for (int i = 0; i < displays.size(); i++) {
        var dec = displays.get(i);
        String fullTag = DisplayUtil.blockTag(
                type.namespace(), type.typeId(), block.getLocation(), dec.tagSuffix());
        Transformation resolved = type.displayTransformResolver()
                .resolve(block, state, dec, i);
        if (resolved == null) continue;

        for (Display d : existing) {
            if (d.getScoreboardTags().contains(fullTag)) {
                d.setTransformation(resolved);
                break;
            }
        }
    }
}
```

Call sites:
- `CoreLibPlugin.java` neighbor change handler → `registry.resolveDisplayTransforms(neighbor, type, state)`
- `CustomBlockRegistry.restoreBlock()` → `resolveDisplayTransforms(block, type, state)`
- `CoreLibPlugin.java` `onBlockPlaced` dispatch → also call `resolveDisplayTransforms` (the resolver may depend on neighbors already present at placement time)

### 1l. What is NOT changed

- `DisplayEntityConfig` record — untouched (static transform stays as the default)
- `BlockLoader` — untouched (resolvers are set programmatically, not via YAML)
- `DisplayUtil` — untouched
- `MechanismRegistry` — untouched
- All existing demo blocks — untouched
- All existing callback fields — untouched

---

## Part 2: Pipes — Block type registration

Each pipe variant + behavior type = one CoreLib custom block type. Current variants:

| CoreLib type ID       | Variant       | Behavior | Transfer rate |
|-----------------------|---------------|----------|---------------|
| `copper_pipe`         | copper        | REGULAR  | 1 item        |
| `copper_corner`       | copper_corner | CORNER   | relay         |
| `iron_pipe`           | iron          | REGULAR  | 8 items       |
| `iron_corner`         | iron_corner   | CORNER   | relay         |
| `gold_pipe`           | gold          | REGULAR  | 16 items      |
| `gold_corner`         | gold_corner   | CORNER   | relay         |
| `oxidized_copper_pipe`| oxidized      | REGULAR  | 1 item        |
| `oxidized_copper_corner`| oxidized_corner | CORNER | relay       |

### 2a. Registration in `PipesPlugin.onEnable()`

After loading `config.yml` and `display.yml`, iterate variants and build block types:

```java
CustomBlockRegistry registry = CoreLibPlugin.getInstance().getRegistry();

for (PipeVariant variant : variantRegistry.getAll()) {
    String typeId = variant.getId();
    boolean isCorner = variant.getBehaviorType() == BehaviorType.CORNER;

    // Get textures from display.yml (per texture-set)
    String horizontalHead = displayConfig.getHeadTexture(variant, "horizontal");
    String upHead = displayConfig.getHeadTexture(variant, "up");
    String downHead = displayConfig.getHeadTexture(variant, "down");
    String horizontalDisplay = displayConfig.getDisplayTexture(variant, "horizontal");
    String upDisplay = displayConfig.getDisplayTexture(variant, "up");
    String downDisplay = displayConfig.getDisplayTexture(variant, "down");

    var builder = CustomHeadBlock.builder("pipes", typeId)
        .texture(horizontalHead)
        .name(variant.getDisplayName())
        .drops(CustomHeadBlock.DropRule.self())
        .cancelPistons(true);

    // Each facing direction = one state with appropriate textures
    if (isCorner) {
        buildCornerStates(builder, variant, displayConfig);
    } else {
        buildRegularStates(builder, variant, displayConfig);
    }

    // Pipe-specific callbacks
    PipeManager manager = getManager(/* world context */);
    builder.stateResolver((event, block) ->
            resolvePipeFacing(event, block, variant));
    builder.onBlockPlaced((block, state) ->
            manager.onPipePlaced(block, state, variant));
    builder.onBlockRemoved((block, state) ->
            manager.onPipeRemoved(block, state, variant));
    builder.onNeighborChange((block, face) ->
            manager.onPipeNeighborChange(block, face, variant));
    builder.onChunkLoad((block, state) ->
            manager.onPipeChunkLoad(block, state, variant));
    builder.onChunkUnload(block ->
            manager.onPipeChunkUnload(block, variant));

    // THE KEY PIECE: dynamic display transform resolver
    builder.displayTransformResolver((block, state, config, displayIndex) ->
            manager.resolveTransform(block, state, config, displayIndex, variant));

    // Recipes
    for (var recipe : variant.getRecipes()) {
        builder.shapedRecipe(convertRecipe(recipe, typeId));
    }

    CustomHeadBlock type = builder.build();
    registry.register(type);
}
```

### 2b. State definitions — regular pipes

6 states (one per facing direction). Each state defines 1 display entity.

```java
private void buildRegularStates(CustomHeadBlock.Builder builder,
        PipeVariant variant, DisplayConfig config) {
    // Horizontal directions share same textures
    String hHead = config.getHeadTexture(variant, "horizontal");
    String hDisplay = config.getDisplayTexture(variant, "horizontal");
    ItemStack hDisplayItem = HeadUtil.createHead(hDisplay, 1);
    var hConfig = new CustomHeadBlock.DisplayEntityConfig(
            hDisplayItem, identityTransform(), "main", null, 0, 0);

    for (String dir : List.of("north", "south", "east", "west")) {
        builder.state(dir, s -> s
                .texture(hHead)
                .displayEntities(List.of(hConfig)));
    }

    // Vertical directions have distinct textures
    String upHead = config.getHeadTexture(variant, "up");
    String upDisplay = config.getDisplayTexture(variant, "up");
    ItemStack upItem = HeadUtil.createHead(upDisplay, 1);
    builder.state("up", s -> s
            .texture(upHead)
            .displayEntities(List.of(new CustomHeadBlock.DisplayEntityConfig(
                    upItem, identityTransform(), "main", null, 0, 0))));

    String downHead = config.getHeadTexture(variant, "down");
    String downDisplay = config.getDisplayTexture(variant, "down");
    ItemStack downItem = HeadUtil.createHead(downDisplay, 1);
    builder.state("down", s -> s
            .texture(downHead)
            .displayEntities(List.of(new CustomHeadBlock.DisplayEntityConfig(
                    downItem, identityTransform(), "main", null, 0, 0))));

    builder.defaultState("north");
}
```

The display entities use `identityTransform()` as a placeholder — the
`displayTransformResolver` overrides the transform immediately after spawning.

### 2c. State definitions — corner pipes

5 states (no UP). Each state defines 2 display entities (base + directional).

```java
private void buildCornerStates(CustomHeadBlock.Builder builder,
        PipeVariant variant, DisplayConfig config) {
    String baseTexture = config.getCornerDisplayTexture(variant);
    ItemStack baseItem = HeadUtil.createHead(baseTexture, 1);

    for (String dir : List.of("north", "south", "east", "west")) {
        String dirTexture = config.getCornerDirectionalTexture(variant, "horizontal");
        ItemStack dirItem = HeadUtil.createHead(dirTexture, 1);

        var baseConfig = new CustomHeadBlock.DisplayEntityConfig(
                baseItem, identityTransform(), "base", null, 0, 0);
        var dirConfig = new CustomHeadBlock.DisplayEntityConfig(
                dirItem, identityTransform(), "dir", null, 0, 0);

        builder.state(dir, s -> s
                .texture(config.getHeadTexture(variant, "horizontal"))
                .displayEntities(List.of(baseConfig, dirConfig)));
    }

    // DOWN-facing corner
    String downDirTexture = config.getCornerDirectionalTexture(variant, "down");
    ItemStack downDirItem = HeadUtil.createHead(downDirTexture, 1);
    var downBase = new CustomHeadBlock.DisplayEntityConfig(
            baseItem, identityTransform(), "base", null, 0, 0);
    var downDir = new CustomHeadBlock.DisplayEntityConfig(
            downDirItem, identityTransform(), "dir", null, 0, 0);
    builder.state("down", s -> s
            .texture(config.getHeadTexture(variant, "down"))
            .displayEntities(List.of(downBase, downDir)));

    builder.defaultState("north");
}
```

### 2d. `stateResolver` implementation

Encodes the pipe-specific placement logic:

```java
private @Nullable String resolvePipeFacing(BlockPlaceEvent event, Block block,
        PipeVariant variant) {
    BlockFace clickedFace = event.getBlockAgainst().getFace(block);
    BlockFace facing;
    if (clickedFace != null) {
        facing = clickedFace;
    } else {
        facing = getPlayerFacing(event.getPlayer().getLocation().getYaw());
    }

    boolean isCorner = variant.getBehaviorType() == BehaviorType.CORNER;
    if (isCorner) {
        if (facing == BlockFace.DOWN) return null;  // cancel placement
        facing = facing.getOppositeFace();
    }

    // Force PLAYER_HEAD for vertical pipes
    if (facing == BlockFace.UP || facing == BlockFace.DOWN) {
        if (block.getType() == Material.PLAYER_WALL_HEAD) {
            block.setType(Material.PLAYER_HEAD, false);
        }
        if (block.getBlockData() instanceof Rotatable rotatable) {
            rotatable.setRotation(BlockFace.NORTH);
            block.setBlockData(rotatable, false);
        }
    }

    return facing.name().toLowerCase();  // "north", "south", "up", etc.
}
```

---

## Part 3: Pipes — `DisplayTransformResolver` implementation

This is the core of the migration. The existing `calculateTransformation()` method
(~120 lines) and its helpers (~60 lines) stay in PipeManager but are wrapped in the
resolver interface.

### 3a. Resolver entry point

```java
/** Called by CoreLib when a display entity needs its transform computed. */
Transformation resolveTransform(Block block, String state,
        CustomHeadBlock.DisplayEntityConfig config, int displayIndex,
        PipeVariant variant) {
    BlockFace facing = BlockFace.valueOf(state.toUpperCase());
    boolean isCorner = variant.getBehaviorType() == BehaviorType.CORNER;

    if (isCorner) {
        if (displayIndex == 0) {
            // Base display — fixed transform (no neighbor dependency)
            return calculateCornerTransformation();
        } else {
            // Directional display — depends on destination neighbor
            return calculateCornerDirectionalTransformation(
                    block.getLocation(), facing);
        }
    } else {
        return calculateTransformation(block.getLocation(), facing, variant);
    }
}
```

### 3b. What stays unchanged in transform calculation

These methods move from `PipeManager` to a new `PipeDisplayResolver` class (or stay
in PipeManager, depending on preference):

| Method | Lines | Purpose |
|--------|-------|---------|
| `calculateTransformation()` | 295-415 | Regular pipe: categorize neighbors → lookup offsets → endpoint math |
| `calculateCornerTransformation()` | 451-467 | Corner base: fixed scale/height from config |
| `calculateCornerDirectionalTransformation()` | 473-524 | Corner directional: endpoint math for destination side only |
| `categorizeSourceBlock()` | 222-249 | Classify source neighbor: pipe-continuous, chest, air, etc. |
| `categorizeDestinationBlock()` | 254-279 | Classify destination neighbor |
| `getDirectionKey()` | 287-293 | Map facing → "side"/"up"/"down" for config lookup |
| `buildScale()` | 421-427 | Build scale vector from facing + scale factors |
| `buildTranslation()` | 429-449 | Build translation vector for each facing direction |
| `buildRotation()` | 441-449 | Build rotation for SOUTH/EAST/WEST facing |

### 3c. Neighbor categorization needs PipeManager access

`categorizeSourceBlock()` and `categorizeDestinationBlock()` call
`getPipeData(location)` to check if a neighbor is a pipe and determine its facing.
After migration, this lookup uses CoreLib's registry instead:

```java
private String categorizeSourceBlock(Block sourceBlock, BlockFace currentFacing) {
    // Check if it's a pipe via CoreLib registry
    CustomHeadBlock type = CoreLibPlugin.getInstance().getRegistry()
            .getTypeFromBlock(sourceBlock);
    if (type != null && type.namespace().equals("pipes")) {
        String pipeState = CoreLibPlugin.getInstance().getRegistry()
                .getState(sourceBlock);
        if (pipeState == null) return "block";
        BlockFace pipeDirection = BlockFace.valueOf(pipeState.toUpperCase());
        boolean isCorner = type.typeId().contains("corner");

        if (isCorner) {
            if (pipeDirection == currentFacing.getOppositeFace()) {
                return "corner-into";
            }
            return "block";
        }
        // Regular pipe
        if (pipeDirection == currentFacing) return "pipe-continuous";
        if (pipeDirection == currentFacing.getOppositeFace()) return "pipe-into";
        return "pipe-orthogonal";
    }

    // Check container types (unchanged)
    if (isChest(sourceBlock)) return "chest";
    if (isHopper(sourceBlock)) return "hopper";
    if (ContainerAdapterRegistry.findAdapter(sourceBlock).isPresent()) return "container";
    if (sourceBlock.getType().isAir() || !sourceBlock.getType().isSolid()) return "air";
    return "block";
}
```

The `getPipeData(location)` call is replaced by CoreLib's
`getTypeFromBlock()` + `getState()`. The pipe's facing direction is encoded in the
state string, so no separate `PipeData` lookup is needed for categorization.

### 3d. `display.yml` adjustments — unchanged

The source/destination adjustment tables in `display.yml` stay as pipe-specific config.
The resolver reads them through `DisplayConfig` exactly as today.

```yaml
# These stay in Pipes' display.yml, NOT moved to CoreLib
adjustments:
  source:
    air: { side: 0.5, up: 0.5, down: 0.5 }
    chest: { side: 0.5625, up: 0.5, down: 0.625 }
    hopper: { side: 0.59, up: 0.75, down: 0.75 }
    # ... 9 categories × 3 directions
  destination:
    air: { side: -0.55, up: -0.75, down: -0.501 }
    # ... same structure
  global-offset:
    horizontal: { right: 0.0, up: 0.2495 }
    # ...
```

---

## Part 4: Pipes — Callback implementations

### 4a. `onPipePlaced(Block, String, PipeVariant)`

Called by CoreLib after `applyConfig()` spawns display entities.

```java
void onPipePlaced(Block block, String state, PipeVariant variant) {
    BlockFace facing = BlockFace.valueOf(state.toUpperCase());
    Location normalized = normalizeLocation(block.getLocation());

    // Register in transfer system
    pipes.put(normalized, new PipeData(facing, variant));
    invalidatePathCache();

    // Display transform is already resolved by displayTransformResolver
    // (CoreLib called it during applyConfig). No manual update needed.

    // Update adjacent pipes' transforms (a new block appeared next to them)
    updateAdjacentPipeDisplays(normalized);
}
```

### 4b. `onPipeRemoved(Block, String, PipeVariant)`

```java
void onPipeRemoved(Block block, String state, PipeVariant variant) {
    Location normalized = normalizeLocation(block.getLocation());
    pipes.remove(normalized);
    sleepUntil.remove(normalized);
    invalidatePathCache();

    // Display entity removal handled by CoreLib (removeByTag in onBlockRemoved)

    // Update adjacent pipes' transforms
    Bukkit.getScheduler().runTask(plugin, () ->
            updateAdjacentPipeDisplays(normalized));
}
```

### 4c. `onPipeNeighborChange(Block, BlockFace, PipeVariant)`

```java
void onPipeNeighborChange(Block block, BlockFace changedFace, PipeVariant variant) {
    // CoreLib already re-resolved this block's display transform via
    // displayTransformResolver in the neighbor change handler.

    // Invalidate path cache (neighbor might be a new container or pipe)
    invalidatePathCache();

    // Wake up sleeping pipes (a new container/path may be available)
    wakeUpPipe(normalizeLocation(block.getLocation()));
}
```

**Note**: The adjacent pipe display updates are handled by CoreLib's neighbor change
handler — when block A changes, CoreLib fires `onNeighborChange` on all adjacent
blocks that have `reactsToNeighbors`. Each pipe with a `displayTransformResolver`
gets its display re-resolved automatically.

### 4d. `onPipeChunkLoad(Block, String, PipeVariant)`

```java
void onPipeChunkLoad(Block block, String state, PipeVariant variant) {
    if (state == null) {
        // Legacy pipe — no CoreLib PDC. Attempt migration.
        migrateLegacyPipe(block);
        return;
    }

    BlockFace facing = BlockFace.valueOf(state.toUpperCase());
    Location normalized = normalizeLocation(block.getLocation());
    pipes.put(normalized, new PipeData(facing, variant));

    // Display transform re-resolved by CoreLib in restoreBlock() via
    // displayTransformResolver. No manual update needed.
}
```

### 4e. `onPipeChunkUnload(Block, PipeVariant)`

```java
void onPipeChunkUnload(Block block, PipeVariant variant) {
    Location normalized = normalizeLocation(block.getLocation());
    pipes.remove(normalized);
    sleepUntil.remove(normalized);
}
```

### 4f. `updateAdjacentPipeDisplays(Location)`

Helper to trigger display recalculation on pipes adjacent to a changed location.
After migration, this calls CoreLib's `resolveDisplayTransforms()`:

```java
private void updateAdjacentPipeDisplays(Location changedLoc) {
    CustomBlockRegistry registry = CoreLibPlugin.getInstance().getRegistry();
    BlockFace[] faces = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
                         BlockFace.WEST, BlockFace.UP, BlockFace.DOWN};

    for (BlockFace face : faces) {
        Block adjacent = changedLoc.getBlock().getRelative(face);
        CustomHeadBlock type = registry.getTypeFromBlock(adjacent);
        if (type != null && type.namespace().equals("pipes")) {
            String state = registry.getState(adjacent);
            registry.resolveDisplayTransforms(adjacent, type, state);
        }
    }
}
```

---

## Part 5: Pipes — Simplified `PipeData`

After migration, `PipeData` no longer needs display entity UUIDs (CoreLib tracks them).

**Before:**
```java
public record PipeData(BlockFace facing, List<UUID> displayEntityIds, PipeVariant variant) {}
```

**After:**
```java
public record PipeData(BlockFace facing, PipeVariant variant) {}
```

The `displayEntityIds` field was used for:
1. Display entity removal on unregister → now handled by CoreLib (`removeByTag`)
2. Display entity transform updates → now handled by CoreLib (`findByTag`)
3. Chunk scan restoration → now handled by CoreLib (chunk hint system)

All three are superseded.

---

## Part 6: Pipes — Code to delete

### Files to delete entirely

| File | Reason |
|------|--------|
| `PipeTags.java` | Replaced by `DisplayUtil.blockTag()` / `blockTagPrefix()` |
| `RecipeManager.java` | Replaced by CoreLib recipe system |
| `RecipeUnlockListener.java` | Replaced by CoreLib advancement-based unlocking |

### `PipeListener.java` — gut or delete

All event handlers are now handled by CoreLib:

| Handler | CoreLib replacement |
|---------|-------------------|
| `onBlockPlace` | CoreLib placement handler + `stateResolver` + `onBlockPlaced` |
| `handlePipeRemoval` | CoreLib break handler + `onBlockRemoved` |
| `EntityExplodeEvent` | CoreLib explosion handler |
| `BlockExplodeEvent` | CoreLib explosion handler |
| `BlockPistonExtendEvent` | CoreLib piston handler (with `cancelPistons: true`) |
| `BlockPistonRetractEvent` | CoreLib piston handler |
| `BlockDestroyEvent` | CoreLib destroy handler |
| `BlockBurnEvent` | CoreLib burn handler |
| `ChunkLoadEvent` | CoreLib chunk load + `onChunkLoad` callback |
| `ChunkUnloadEvent` | CoreLib chunk unload + `onChunkUnload` callback |
| `updateAdjacentPipes` | CoreLib neighbor change handler + `displayTransformResolver` |

**Keep**: `CauldronConversionListener.java` (pipe-specific cauldron feature).
**Keep**: `ConversionRecipeCraftListener.java` (pipe-specific conversion recipes).

### `PipeManager.java` — methods to remove

| Method | Lines | Replacement |
|--------|-------|-------------|
| `spawnDisplayEntities()` | 168-200 | CoreLib `applyConfig()` |
| `unregisterPipe()` display removal | 73-98 | CoreLib `onBlockRemoved` |
| `removeDisplaysByTag()` | 100-114 | CoreLib `DisplayUtil.removeByTag()` |
| `updateDisplayEntity()` | 124-141 | CoreLib `resolveDisplayTransforms()` |
| `updateCornerDisplayEntities()` | 143-166 | CoreLib `resolveDisplayTransforms()` |
| `scanChunk()` | 943-1013 | CoreLib chunk hint system |
| `scanForExistingPipes()` | 1017-1034 | CoreLib loads all chunks |
| `cleanupOrphanedDisplays()` | 787-809 | CoreLib display lifecycle |
| `refreshAllDisplays()` | 902-906 | `resolveDisplayTransforms()` per pipe |

### `PipeManager.java` — methods to keep

| Method | Lines | Why |
|--------|-------|-----|
| `calculateTransformation()` | 295-415 | Pipe-specific endpoint math |
| `calculateCornerTransformation()` | 451-467 | Corner base transform |
| `calculateCornerDirectionalTransformation()` | 473-524 | Corner directional transform |
| `categorizeSourceBlock()` | 222-249 | Neighbor classification (modified to use CoreLib registry) |
| `categorizeDestinationBlock()` | 254-279 | Neighbor classification (modified) |
| `getDirectionKey()` | 287-293 | Config key lookup |
| `buildScale/Translation/Rotation()` | 421-449 | Transform helpers |
| `transferAllPipes()` | 527-590 | Item transfer loop |
| `transferItems()` | 592-689 | Per-pipe transfer logic |
| `findDestination()` | 741-772 | Recursive pathfinding |
| `getOrBuildPath()` | 692-702 | Path caching |
| `isPathStillValid()` | 704-738 | Cache validation |
| `normalizeLocation()` | 810-815 | Location normalization |

### `PipesPlugin.java` — methods to remove

| Method | Replacement |
|--------|-------------|
| `loadItems()` | `CustomHeadBlock.createItem()` |
| `createPipeItem()` | CoreLib `HeadUtil.createHead()` |
| `getHeadItemForDirection()` | CoreLib per-state textures |
| `getDisplayItem()` | CoreLib display entity config |
| `getDirectionalDisplayItem()` | CoreLib display entity config |
| Recipe registration code | CoreLib recipe system |

---

## Part 7: Migration for existing worlds

### 7a. Problem

Existing pipes have:
- No CoreLib PDC on skulls (no `BLOCK_TYPE_KEY`)
- ItemDisplay entities tagged with PipeTags format: `{variant_id}:{x}_{y}_{z}_{facing}[_dir]`

CoreLib's chunk scanner won't find these blocks (no PDC marker). The display entities
have incompatible tags.

### 7b. Solution: migration in `onChunkLoad`

CoreLib's chunk scanner looks for skulls with PDC. For unmigrated pipes, the skull
has no PDC, so CoreLib skips it. Pipes needs its own one-time migration scan.

**Option A**: Keep a lightweight chunk load listener in Pipes that runs AFTER CoreLib's.
On chunk load, scan for ItemDisplay entities with PipeTags format. For each found:

```java
void migrateLegacyPipe(Block skullBlock) {
    // Find legacy display entities near this skull
    Location loc = skullBlock.getLocation();
    Collection<Entity> nearby = loc.getWorld().getNearbyEntities(
            loc.clone().add(0.5, 0.5, 0.5), 1.0, 1.0, 1.0,
            e -> e instanceof ItemDisplay);

    for (Entity entity : nearby) {
        String oldTag = PipeTags.getPipeTag(entity);
        if (oldTag == null || !PipeTags.matchesLocation(oldTag, loc)) continue;

        // Parse legacy tag
        String variantId = PipeTags.parseVariantId(oldTag);
        BlockFace facing = PipeTags.parseFacing(oldTag);
        boolean isDirectional = PipeTags.isDirectionalTag(oldTag);
        PipeVariant variant = variantRegistry.getVariant(variantId);
        if (variant == null || facing == null) { entity.remove(); continue; }

        // Write CoreLib PDC to skull
        String typeId = variant.getId();
        CustomHeadBlock type = registry.getType("pipes:" + typeId);
        if (type == null) continue;
        String state = facing.name().toLowerCase();
        registry.markBlock(skullBlock, type, state);

        // Re-tag display entity with CoreLib format
        String suffix = isDirectional ? "dir" : "main";
        String newTag = DisplayUtil.blockTag("pipes", typeId, loc, suffix);

        // Remove old tag, add new tag
        entity.getScoreboardTags().removeIf(t -> t.contains(variantId));
        entity.addScoreboardTag(newTag);

        break;  // one display migrated, next chunk load pass gets the rest
    }
}
```

**Option B**: Run a one-time world scan on first load after migration. Less elegant
but ensures all pipes are migrated before any are used.

**Recommendation**: Option A — lazy migration on chunk load. Keep `PipeTags.java`
parsing methods (mark `@Deprecated`) for the migration path. Remove after 2-3 versions.

### 7c. Chunk hint seeding

CoreLib skips chunks not in its hints file. Migrated pipes need their chunks added.
After `markBlock()` in the migration code, call:

```java
registry.markChunkDirty(skullBlock.getChunk());
```

This ensures CoreLib scans the chunk on subsequent loads.

---

## Part 8: Build changes

### Pipes `build.gradle.kts`

Add CoreLib as `compileOnly` dependency:

```kotlin
dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    compileOnly(files("../defCoreLib/build/libs/DefCoreLib.jar"))
}
```

### Pipes `plugin.yml`

Add dependency:

```yaml
depend: [DefCoreLib]
```

---

## Implementation order

1. **CoreLib**: Add `DisplayTransformResolver` interface + field + builder + accessor
2. **CoreLib**: Add `onBlockPlaced` callback + field + builder + accessor
3. **CoreLib**: Add `stateResolver` callback + field + builder + accessor
4. **CoreLib**: Add `resolveDisplayTransforms()` helper to `CustomBlockRegistry`
5. **CoreLib**: Wire resolver into `applyConfig()` (spawn with resolved transform)
6. **CoreLib**: Wire resolver into neighbor change handler (re-resolve on change)
7. **CoreLib**: Wire resolver into `restoreBlock()` (re-resolve on chunk load)
8. **CoreLib**: Wire `onBlockPlaced` into placement handler (after applyConfig)
9. **CoreLib**: Wire `stateResolver` into placement handler (before markBlock)
10. **CoreLib**: Build and verify no regressions with existing demo blocks
11. **Pipes**: Add CoreLib dependency to build
12. **Pipes**: Create block type registration (Part 2)
13. **Pipes**: Implement `DisplayTransformResolver` wrapper (Part 3)
14. **Pipes**: Implement callbacks (Part 4)
15. **Pipes**: Simplify `PipeData` record (Part 5)
16. **Pipes**: Delete redundant code (Part 6)
17. **Pipes**: Add legacy migration (Part 7)
18. **Pipes**: Build and test end-to-end

---

## Files modified (defCoreLib)

| File | Change |
|------|--------|
| `CustomHeadBlock.java` | New `DisplayTransformResolver` interface, `StateResolver` interface, `onBlockPlaced` callback. New fields, constructor params, accessors, builder methods for all three. (~50 lines) |
| `CustomBlockRegistry.java` | New `resolveDisplayTransforms()` helper method. Modified `applyConfig()` to call resolver. Modified `restoreBlock()` to call resolver. (~40 lines) |
| `CoreLibPlugin.java` | Modified placement handler: call `stateResolver`, call `onBlockPlaced`. Modified neighbor change handler: call `resolveDisplayTransforms`. (~25 lines) |

## Files modified (Pipes)

| File | Change |
|------|--------|
| `build.gradle.kts` | Add CoreLib dependency |
| `plugin.yml` | Add `depend: [DefCoreLib]` |
| `PipesPlugin.java` | Replace item/recipe init with CoreLib block type registration. Remove texture loading. Keep config loading and command handling. |
| `PipeManager.java` | Remove display spawn/remove/update/chunk-scan methods. Add callback methods (`onPipePlaced`, `onPipeRemoved`, etc.). Add `resolveTransform()`. Modify `categorizeSourceBlock`/`categorizeDestinationBlock` to use CoreLib registry. Simplify `PipeData` record. Keep transfer + transform + path code. |

## Files deleted (Pipes)

| File | Reason |
|------|--------|
| `PipeListener.java` | All events handled by CoreLib |
| `RecipeManager.java` | CoreLib recipe system |
| `RecipeUnlockListener.java` | CoreLib advancement unlocking |
| `PipeTags.java` | Deprecated after migration period, then deleted |

---

## Verification

1. Build both plugins, deploy to test server
2. Place each pipe variant (copper/iron/gold/oxidized × regular/corner) in all 6 directions — verify head texture + display entity spawn
3. Verify display transforms update when placing/breaking adjacent blocks:
   - Pipe → air (retracted end)
   - Pipe → chest (extended into chest)
   - Pipe → hopper (extended into hopper)
   - Pipe → pipe (continuous connection)
   - Pipe → corner pipe (connected)
4. Verify corner pipes show both display entities with correct transforms
5. Verify item transfer still works through pipe networks (source → chain → destination)
6. Restart server → verify pipes restore correctly from CoreLib chunk scanning
7. Load a world with pre-migration pipes → verify legacy tag migration works
8. Break pipes → verify display cleanup and correct item drops
9. Test explosions, pistons, fire, water on pipes → verify protection events work
10. Test crafting recipes, cauldron conversion, advancement unlocking
11. Run `/pipes reload` → verify display refresh works through CoreLib
