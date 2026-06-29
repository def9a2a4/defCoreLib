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

**No backwards compatibility**: this is a clean migration. Existing worlds with old
pipe data should be wiped. No legacy tag migration or chunk hint seeding is needed.

---

## Part 1: defCoreLib — New APIs

### 1a. New `DisplayTransformResolver` interface

**File**: `CustomHeadBlock.java` — add after the `StateChangeHandler` interface (~line 267)

```java
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

Pipes needs a callback after display entities are spawned, to register the pipe
in the transfer system.

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
- Vertical pipes need PLAYER_HEAD block type and locked rotation

**File**: `CustomHeadBlock.java`

```java
@FunctionalInterface
public interface StateResolver {
    /**
     * @param event the placement event
     * @return resolved state string, or null to cancel placement.
     *         If the resolver needs to change block type (e.g. PLAYER_WALL_HEAD →
     *         PLAYER_HEAD), return a BlockTypeOverride via the overloaded method instead.
     */
    @Nullable String resolve(BlockPlaceEvent event);
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

### 1f. `StateResolverResult` — handle block type changes safely

The stateResolver must NOT mutate block type directly (calling `block.setType()` inside
the callback destroys the tile entity before `markBlock` can write PDC). Instead, the
resolver returns a result object and CoreLib performs the mutation.

```java
public record StateResolverResult(String state, boolean forcePlayerHead) {
    public static StateResolverResult of(String state) { return new StateResolverResult(state, false); }
    public static StateResolverResult withPlayerHead(String state) { return new StateResolverResult(state, true); }
}
```

Update `StateResolver` to return this instead of raw String:

```java
@FunctionalInterface
public interface StateResolver {
    @Nullable StateResolverResult resolve(BlockPlaceEvent event);
}
```

### 1g. Wire resolver into `CustomBlockRegistry.applyConfig()`

**File**: `CustomBlockRegistry.java`, inside `applyConfig()` at line 360-392

The existing display entity spawn loop uses a static transform from config. Add
resolver call before spawning. **Preserve existing `displayItemResolver` logic.**

```java
// Inside the display entity spawn loop, BEFORE the spawn call:
Transformation transform = dec.transform();
if (type.displayTransformResolver() != null) {
    Transformation resolved = type.displayTransformResolver()
            .resolve(block, state, dec, i);
    if (resolved != null) transform = resolved;
}
```

This is a 4-line insertion into the existing loop, not a replacement. The existing
`displayItemResolver` check, wallOffset logic, animation tracking, and
`interpolationDuration` all stay untouched.

### 1h. `resolveDisplayTransforms()` helper

Extract the re-resolution logic to `CustomBlockRegistry` to avoid duplication:

```java
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

### 1i. Wire resolver into neighbor change handler

**File**: `CoreLibPlugin.java`, neighbor change handler (~line 888)

After the existing `onNeighborChange` dispatch, add:

```java
if (type.displayTransformResolver() != null) {
    String state = registry.getState(neighbor);
    registry.resolveDisplayTransforms(neighbor, type, state);
}
```

This is the code that Part 4c depends on — without it, `onPipeNeighborChange`'s
comment "CoreLib already re-resolved" would be wrong.

### 1j. Wire resolver into chunk load — via `EntitiesLoadEvent`

**NOT in `restoreBlock()`**. Paper loads entities asynchronously — `ChunkLoadEvent`
fires before entities are ready. `restoreBlock()` runs during `ChunkLoadEvent`, so
`findByTag()` would return empty.

Instead, add to `CoreLibPlugin.onEntitiesLoad()` (line 162):

```java
@EventHandler
public void onEntitiesLoad(EntitiesLoadEvent event) {
    // ... existing minecart/mechanism cleanup ...

    // Re-resolve dynamic display transforms now that entities are available
    Chunk chunk = event.getChunk();
    if (!registry.chunkMayHaveCustomBlocks(chunk)) return;
    for (BlockState tile : chunk.getTileEntities()) {
        if (!(tile instanceof Skull skull)) continue;
        String typeId = skull.getPersistentDataContainer()
                .get(CustomBlockRegistry.BLOCK_TYPE_KEY, PersistentDataType.STRING);
        if (typeId == null) continue;
        CustomHeadBlock type = registry.getType(typeId);
        if (type == null || type.displayTransformResolver() == null) continue;
        String state = skull.getPersistentDataContainer()
                .get(CustomBlockRegistry.STATE_KEY, PersistentDataType.STRING);
        registry.resolveDisplayTransforms(tile.getBlock(), type, state);
    }
}
```

### 1k. Wire `stateResolver` into placement handler

**File**: `CoreLibPlugin.java`, lines 242-249 (state resolution)

Replace the `placementStateMap` lookup with resolver-first logic:

```java
String resolvedState;
boolean forcePlayerHead = false;
if (type.stateResolver() != null) {
    StateResolverResult result = type.stateResolver().resolve(event);
    if (result == null) {
        event.setCancelled(true);
        return;
    }
    resolvedState = result.state();
    forcePlayerHead = result.forcePlayerHead();
} else {
    resolvedState = type.defaultState();
    var psm = type.placementStateMap();
    if (psm != null) {
        String mapped = psm.get(placedOn);
        if (mapped != null) resolvedState = mapped;
    }
}

// Apply block type override BEFORE markBlock (so tile entity exists for PDC write)
if (forcePlayerHead && block.getType() == Material.PLAYER_WALL_HEAD) {
    block.setType(Material.PLAYER_HEAD, false);
    if (block.getBlockData() instanceof Rotatable rotatable) {
        rotatable.setRotation(BlockFace.NORTH);
        block.setBlockData(rotatable, false);
    }
}
final String state = resolvedState;
```

### 1l. Wire `onBlockPlaced` into placement handler

**File**: `CoreLibPlugin.java`, inside the `runTask` lambda (line 272-292)

After `applyConfig()` and the existing redstone/tick/chunkLoad registrations, add:

```java
if (type.onBlockPlaced() != null) {
    type.onBlockPlaced().accept(block, state);
}
```

### 1m. Add `BlockDestroyEvent` handler

**File**: `CoreLibPlugin.java` — new handler alongside `onBlockBreak`

CoreLib currently lacks this. Paper fires `BlockDestroyEvent` for `/fill`, `/setblock`,
physics-based destruction, etc. Without it, custom blocks destroyed by commands leave
orphaned display entities.

```java
@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
public void onBlockDestroy(BlockDestroyEvent event) {
    Block block = event.getBlock();
    CustomHeadBlock type = registry.getTypeFromBlock(block);
    if (type == null) return;
    String state = registry.getState(block);
    registry.onBlockRemoved(block, type, state, false); // no item drop
}
```

### 1n. Add `onPistonPush` callback (piston break-and-drop)

**File**: `CustomHeadBlock.java` — new optional callback

Pipes' current behavior: break the pipe, drop the item, let the piston proceed. CoreLib's
`cancelPistons` flag cancels the entire piston event (nothing moves). Add a new callback
that fires per-block and lets the consumer decide:

```java
// Field:
private final @Nullable BiConsumer<Block, String> onPistonPush;

// Builder:
public Builder onPistonPush(BiConsumer<Block, String> handler) {
    this.onPistonPush = handler; return this;
}
```

**File**: `CoreLibPlugin.java` — modify piston handlers (lines 385-404)

```java
public void onPistonExtend(BlockPistonExtendEvent event) {
    for (Block block : event.getBlocks()) {
        CustomHeadBlock type = registry.getTypeFromBlock(block);
        if (type == null) continue;
        if (type.cancelPistons()) {
            event.setCancelled(true);
            return;
        }
        if (type.onPistonPush() != null) {
            String state = registry.getState(block);
            registry.onBlockRemoved(block, type, state, true); // drop item
            block.setType(Material.AIR, false);
        }
    }
}
```

Same pattern for `onPistonRetract`.

### 1o. What is NOT changed

- `DisplayEntityConfig` record — untouched (static transform stays as the default)
- `BlockLoader` — untouched (resolvers are set programmatically, not via YAML)
- `DisplayUtil` — untouched
- `MechanismRegistry` — untouched
- All existing demo blocks — untouched
- All existing callback fields — untouched

---

## Part 2: Pipes — Block type registration

Each pipe variant + behavior type = one CoreLib custom block type. The actual variant
IDs from `config.yml` are used directly as CoreLib type IDs:

| CoreLib type ID              | Behavior | Transfer rate |
|------------------------------|----------|---------------|
| `copper_pipe`                | REGULAR  | 1 item        |
| `copper_corner_pipe`         | CORNER   | relay         |
| `iron_pipe`                  | REGULAR  | 8 items       |
| `iron_corner_pipe`           | CORNER   | relay         |
| `gold_pipe`                  | REGULAR  | 16 items      |
| `gold_corner_pipe`           | CORNER   | relay         |
| `oxidized_copper_pipe`       | REGULAR  | 1 item        |
| `oxidized_copper_corner_pipe`| CORNER   | relay         |

### 2a. Registration in `PipesPlugin.onEnable()`

After loading `config.yml` and `display.yml`, iterate variants and build block types.

**Per-world manager lookup**: Callbacks must NOT capture a single `PipeManager` at
registration time. CoreLib block types are global, but Pipes has per-world managers.
All callbacks look up the manager from the block's world at invocation time:

```java
CustomBlockRegistry registry = CoreLibPlugin.getInstance().getRegistry();

for (PipeVariant variant : variantRegistry.getAll()) {
    String typeId = variant.getId();  // e.g. "copper_pipe", "iron_corner_pipe"
    boolean isCorner = variant.getBehaviorType() == BehaviorType.CORNER;

    var builder = CustomHeadBlock.builder("pipes", typeId)
        .texture(displayConfig.getHeadTexture(variant, "horizontal"))
        .name(variant.getDisplayName())
        .drops(CustomHeadBlock.DropRule.self());

    // Each facing direction = one state with appropriate textures
    if (isCorner) {
        buildCornerStates(builder, variant, displayConfig);
    } else {
        buildRegularStates(builder, variant, displayConfig);
    }

    // Callbacks — all use getManager(block.getWorld()) for per-world lookup
    builder.stateResolver(event ->
            resolvePipeFacing(event, variant));
    builder.onBlockPlaced((block, state) -> {
        PipeManager mgr = getManager(block.getWorld());
        if (mgr != null) mgr.onPipePlaced(block, state, variant);
    });
    builder.onBlockRemoved((block, state) -> {
        PipeManager mgr = getManager(block.getWorld());
        if (mgr != null) mgr.onPipeRemoved(block, state, variant);
    });
    builder.onNeighborChange((block, face) -> {
        PipeManager mgr = getManager(block.getWorld());
        if (mgr != null) mgr.onPipeNeighborChange(block, face, variant);
    });
    builder.onChunkLoad((block, state) -> {
        PipeManager mgr = getManager(block.getWorld());
        if (mgr != null) mgr.onPipeChunkLoad(block, state, variant);
    });
    builder.onChunkUnload(block -> {
        PipeManager mgr = getManager(block.getWorld());
        if (mgr != null) mgr.onPipeChunkUnload(block, variant);
    });
    builder.onPistonPush((block, state) -> {
        // break-and-drop, let piston proceed (current Pipes behavior)
    });

    // Dynamic display transform resolver
    builder.displayTransformResolver((block, state, config, displayIndex) -> {
        PipeManager mgr = getManager(block.getWorld());
        if (mgr == null) return null;
        return mgr.resolveTransform(block, state, config, displayIndex, variant);
    });

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

Returns `StateResolverResult` — CoreLib handles the block type mutation safely.

```java
private @Nullable StateResolverResult resolvePipeFacing(BlockPlaceEvent event,
        PipeVariant variant) {
    Block block = event.getBlockPlaced();

    // World filtering — cancel placement in disabled worlds
    if (getManager(block.getWorld()) == null) {
        event.getPlayer().sendMessage(Component.text("Pipes are not enabled in this world.")
                .color(NamedTextColor.RED));
        return null;
    }

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

    // Vertical pipes need PLAYER_HEAD — tell CoreLib to handle the conversion
    if (facing == BlockFace.UP || facing == BlockFace.DOWN) {
        return StateResolverResult.withPlayerHead(facing.name().toLowerCase());
    }
    return StateResolverResult.of(facing.name().toLowerCase());
}
```

---

## Part 3: Pipes — `DisplayTransformResolver` implementation

The existing `calculateTransformation()` method (~120 lines) and its helpers (~60 lines)
stay in PipeManager but are wrapped in the resolver interface.

### 3a. Resolver entry point

```java
Transformation resolveTransform(Block block, String state,
        CustomHeadBlock.DisplayEntityConfig config, int displayIndex,
        PipeVariant variant) {
    BlockFace facing = BlockFace.valueOf(state.toUpperCase());
    boolean isCorner = variant.getBehaviorType() == BehaviorType.CORNER;

    if (isCorner) {
        if (displayIndex == 0) {
            return calculateCornerTransformation();
        } else {
            return calculateCornerDirectionalTransformation(
                    block.getLocation(), facing);
        }
    } else {
        return calculateTransformation(block.getLocation(), facing, variant);
    }
}
```

### 3b. What stays unchanged in transform calculation

| Method | Lines | Purpose |
|--------|-------|---------|
| `calculateTransformation()` | 295-415 | Regular pipe: categorize neighbors → lookup offsets → endpoint math |
| `calculateCornerTransformation()` | 451-467 | Corner base: fixed scale/height from config |
| `calculateCornerDirectionalTransformation()` | 473-524 | Corner directional: endpoint math for destination side only |
| `categorizeSourceBlock()` | 222-249 | Classify source neighbor: pipe-continuous, chest, air, etc. |
| `categorizeDestinationBlock()` | 254-279 | Classify dest neighbor (includes "corner-pipe" category) |
| `getDirectionKey()` | 287-293 | Map facing → "side"/"up"/"down" for config lookup |
| `buildScale()` | 421-427 | Build scale vector from facing + scale factors |
| `buildTranslation()` | 429-449 | Build translation vector for each facing direction |
| `buildRotation()` | 441-449 | Build rotation for SOUTH/EAST/WEST facing |

### 3c. Neighbor categorization — use CoreLib registry

Both `categorizeSourceBlock()` and `categorizeDestinationBlock()` currently call
`getPipeData(location)` to check if a neighbor is a pipe. After migration, use
CoreLib's registry:

```java
private String categorizeSourceBlock(Block sourceBlock, BlockFace currentFacing) {
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
        if (pipeDirection == currentFacing) return "pipe-continuous";
        if (pipeDirection == currentFacing.getOppositeFace()) return "pipe-into";
        return "pipe-orthogonal";
    }

    if (isChest(sourceBlock)) return "chest";
    if (isHopper(sourceBlock)) return "hopper";
    if (ContainerAdapterRegistry.findAdapter(sourceBlock).isPresent()) return "container";
    if (sourceBlock.getType().isAir() || !sourceBlock.getType().isSolid()) return "air";
    return "block";
}
```

`categorizeDestinationBlock()` follows the same pattern but also returns `"corner-pipe"`
for non-feeding corner pipes (this category has specific adjustment values in
`display.yml`).

### 3d. `display.yml` adjustments — unchanged

The source/destination adjustment tables in `display.yml` stay as pipe-specific config.
The resolver reads them through `DisplayConfig` exactly as today.

---

## Part 4: Pipes — Callback implementations

### 4a. `onPipePlaced(Block, String, PipeVariant)`

Called by CoreLib after `applyConfig()` spawns display entities with resolved transforms.

```java
void onPipePlaced(Block block, String state, PipeVariant variant) {
    BlockFace facing = BlockFace.valueOf(state.toUpperCase());
    Location normalized = normalizeLocation(block.getLocation());

    pipes.put(normalized, new PipeData(facing, variant));
    invalidatePathCache();

    // Update adjacent pipes' transforms (a new block appeared next to them)
    // CoreLib's physics handler also fires onNeighborChange on adjacent pipes,
    // but that fires same-tick while this runs next-tick. Both resolve correctly;
    // the updateAdjacentPipeDisplays is a safety net.
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

    Bukkit.getScheduler().runTask(plugin, () ->
            updateAdjacentPipeDisplays(normalized));
}
```

### 4c. `onPipeNeighborChange(Block, BlockFace, PipeVariant)`

```java
void onPipeNeighborChange(Block block, BlockFace changedFace, PipeVariant variant) {
    // CoreLib re-resolves this block's display transform via displayTransformResolver
    // in the neighbor change handler (Part 1i). Adjacent pipes also get their own
    // onNeighborChange calls from CoreLib's physics handler.

    invalidatePathCache();
    wakeUpPipe(normalizeLocation(block.getLocation()));
}
```

### 4d. `onPipeChunkLoad(Block, String, PipeVariant)`

```java
void onPipeChunkLoad(Block block, String state, PipeVariant variant) {
    BlockFace facing = BlockFace.valueOf(state.toUpperCase());
    Location normalized = normalizeLocation(block.getLocation());
    pipes.put(normalized, new PipeData(facing, variant));

    // Display transform re-resolved by CoreLib in EntitiesLoadEvent handler
    // (Part 1j). No manual update needed here.
}
```

### 4e. `onPipeChunkUnload(Block, PipeVariant)`

```java
void onPipeChunkUnload(Block block, PipeVariant variant) {
    Location normalized = normalizeLocation(block.getLocation());
    pipes.remove(normalized);
    sleepUntil.remove(normalized);
    invalidatePathCache();  // clear cached paths that reference unloaded pipes
}
```

### 4f. `updateAdjacentPipeDisplays(Location)`

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

**Before:**
```java
public record PipeData(BlockFace facing, List<UUID> displayEntityIds, PipeVariant variant) {}
```

**After:**
```java
public record PipeData(BlockFace facing, PipeVariant variant) {}
```

CoreLib tracks display entities by tag — UUIDs are no longer needed.

---

## Part 6: Pipes — File disposition

### Files to delete entirely

| File | Reason |
|------|--------|
| `PipeListener.java` | All events handled by CoreLib + callbacks |
| `PipeTags.java` | Replaced by `DisplayUtil.blockTag()` / `blockTagPrefix()` |
| `RecipeManager.java` | Replaced by CoreLib recipe system |
| `RecipeUnlockListener.java` | Replaced by CoreLib advancement-based unlocking |
| `PickBlockListener.java` | CoreLib handles pick block via `type.createItem()` |

### Files to modify

| File | Change |
|------|--------|
| `PipesPlugin.java` | Replace item/recipe init with CoreLib block type registration. Remove `loadItems()`, `createPipeItem()`, `getHeadItemForDirection()`, `getDisplayItem()`, `getDirectionalDisplayItem()`. Keep config loading, command handling, bStats init. |
| `PipeManager.java` | Remove display spawn/remove/update/chunk-scan methods. Add callback methods + `resolveTransform()`. Modify categorize methods to use CoreLib registry. Simplify PipeData. Keep transfer + transform + path + task code (`startTasks`, `stopTasks`, `restartTasks`, `shutdown`, debug particles). |
| `CauldronConversionListener.java` | Update `plugin.getVariant(item)` to use CoreLib's `BLOCK_TYPE_KEY` PDC to identify pipe items instead of variant-specific PDC keys. Update item creation to use `registry.getType("pipes:" + typeId).createItem()`. |
| `ConversionRecipeCraftListener.java` | Same PDC key update as CauldronConversionListener. Conversion recipe registration moves here or to PipesPlugin (since RecipeManager is deleted). Keep catalyst retention logic. |

### Files that stay unchanged

| File | Why |
|------|-----|
| `WorldManager.java` | Per-world PipeManager lifecycle stays as-is |
| `VariantRegistry.java` | Loads variant definitions from config.yml |
| `PipeVariant.java` | Variant data model |
| `BehaviorType.java` | Enum |
| `RecipeDefinition.java` | Recipe data model (still used by `convertRecipe()`) |
| `ConversionRecipeDefinition.java` | Conversion recipe data model |
| `config/PipeConfig.java` | Global config parser (world filtering, etc.) |
| `config/DisplayConfig.java` | Texture and display settings |
| `adapter/*` | Container adapter system |

---

## Part 7: Commands after migration

### `/pipes reload`

Current reload does: unregister recipes, reload configs, reload items, re-register
recipes, reload variants in each PipeManager, refresh all displays, restart tasks,
re-evaluate world filters, sync recipe unlocks, reload cauldron conversions.

After migration:
1. Reload `config.yml` and `display.yml` — unchanged
2. `reloadVariants()` — still needed (replaces stale PipeVariant refs in PipeData)
3. Refresh displays — iterate all pipes, call `resolveDisplayTransforms()` per pipe
4. `restartTasks()` — unchanged (transfer intervals, debug particles)
5. Re-evaluate world filters — unchanged (WorldManager)
6. Recipes — need to investigate CoreLib's recipe API for hot-reload support
7. Cauldron conversions — unchanged

### `/pipes give`

Use `registry.getType("pipes:" + variantId).createItem()` instead of
`plugin.getPipeItem()`.

### `/pipes info`

Iterate PipeManager's pipe map — unchanged. Orphaned display count: scan for
display entities with `corelib:pipes:` tag prefix whose block location has no
skull with PDC.

### `/pipes delete_all`

Iterate pipe map, for each: call `registry.onBlockRemoved(block, type, state, false)`,
then `block.setType(AIR)`.

### `/pipes cleanup`

Scan all entities for `corelib:pipes:` tags, check if corresponding skull block
exists with PDC. Remove orphans. CoreLib has no general orphan cleanup, so this
stays pipe-specific.

### `/pipes recipes`

Move recipe tracking from RecipeManager to PipesPlugin or a lightweight holder.
Core logic: `player.discoverRecipe(key)` for each registered recipe key.

---

## Part 8: Build changes

### Pipes `build.gradle.kts`

```kotlin
dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    compileOnly(files("../defCoreLib/build/libs/DefCoreLib.jar"))
}
```

### Pipes `plugin.yml`

```yaml
depend: [DefCoreLib]
```

---

## Part 9: Known limitations

### Cross-chunk boundary transforms

When a chunk loads, pipes at chunk boundaries may have neighbors in unloaded chunks.
The resolver sees the neighbor as air and computes wrong transforms. When the neighbor
chunk later loads, it doesn't trigger re-resolution on the already-loaded pipe.

This is a pre-existing bug in Pipes (the current `scanChunk` has the same issue).
Fix (future): after chunk load, schedule a deferred pass that re-resolves border pipes.

### Explosion drop behavior change

CoreLib's explosion handler always drops items. Current Pipes uses `random.nextFloat()
< yield` for probabilistic drops. This is an acceptable behavior change (custom items
are harder to obtain).

---

## Implementation order

1. **CoreLib**: Add `DisplayTransformResolver` interface + field + builder + accessor
2. **CoreLib**: Add `StateResolverResult` record + `StateResolver` interface + field + builder
3. **CoreLib**: Add `onBlockPlaced` callback + field + builder + accessor
4. **CoreLib**: Add `onPistonPush` callback + modify piston handlers
5. **CoreLib**: Add `BlockDestroyEvent` handler
6. **CoreLib**: Add `resolveDisplayTransforms()` helper to `CustomBlockRegistry`
7. **CoreLib**: Wire resolver into `applyConfig()` (4-line insertion, preserve displayItemResolver)
8. **CoreLib**: Wire resolver into neighbor change handler
9. **CoreLib**: Wire resolver into `EntitiesLoadEvent` handler
10. **CoreLib**: Wire `stateResolver` + `forcePlayerHead` into placement handler
11. **CoreLib**: Wire `onBlockPlaced` into placement handler (after applyConfig)
12. **CoreLib**: Build and verify no regressions with existing demo blocks
13. **Pipes**: Add CoreLib dependency to build
14. **Pipes**: Create block type registration (Part 2)
15. **Pipes**: Implement `DisplayTransformResolver` wrapper (Part 3)
16. **Pipes**: Implement callbacks (Part 4)
17. **Pipes**: Simplify `PipeData` record (Part 5)
18. **Pipes**: Delete redundant code, update remaining files (Part 6)
19. **Pipes**: Update commands (Part 7)
20. **Pipes**: Build and test end-to-end

---

## Files modified (defCoreLib)

| File | Change |
|------|--------|
| `CustomHeadBlock.java` | New `DisplayTransformResolver`, `StateResolver`, `StateResolverResult`, `onBlockPlaced`, `onPistonPush`. Fields, constructor params, accessors, builder methods. (~60 lines) |
| `CustomBlockRegistry.java` | New `resolveDisplayTransforms()` helper. 4-line insertion in `applyConfig()` for resolver call. (~30 lines) |
| `CoreLibPlugin.java` | `stateResolver` + `forcePlayerHead` in placement handler. `onBlockPlaced` after applyConfig. Resolver call in neighbor change handler. Resolver call in `EntitiesLoadEvent`. `BlockDestroyEvent` handler. Modified piston handlers for `onPistonPush`. (~50 lines) |

## Files modified (Pipes)

| File | Change |
|------|--------|
| `build.gradle.kts` | Add CoreLib dependency |
| `plugin.yml` | Add `depend: [DefCoreLib]` |
| `PipesPlugin.java` | Replace item/recipe init with CoreLib block type registration. Keep config, commands, bStats. |
| `PipeManager.java` | Remove display/chunk code, add callbacks + resolver. Modify categorize methods. Simplify PipeData. Keep transfer, transform, path, tasks. |
| `CauldronConversionListener.java` | Update item identification to use CoreLib PDC key |
| `ConversionRecipeCraftListener.java` | Update item identification, absorb conversion recipe registration |

## Files deleted (Pipes)

| File | Reason |
|------|--------|
| `PipeListener.java` | All events handled by CoreLib |
| `PipeTags.java` | Replaced by DisplayUtil tags |
| `RecipeManager.java` | CoreLib recipe system |
| `RecipeUnlockListener.java` | CoreLib advancement unlocking |
| `PickBlockListener.java` | CoreLib pick block handler |

---

## Verification

1. Build both plugins, deploy to test server
2. Place each pipe variant (copper/iron/gold/oxidized × regular/corner) in all 6 directions — verify head texture + display entity spawn
3. Verify display transforms update when placing/breaking adjacent blocks:
   - Pipe → air (retracted end)
   - Pipe → chest (extended into chest)
   - Pipe → hopper (extended into hopper)
   - Pipe → pipe (continuous connection)
   - Pipe → corner pipe (connected, verify "corner-pipe" category)
4. Verify corner pipes show both display entities with correct transforms
5. Verify item transfer still works through pipe networks (source → chain → destination)
6. Restart server → verify pipes restore correctly from CoreLib chunk scanning
7. Verify display transforms re-resolve after chunk entity loading (EntitiesLoadEvent)
8. Break pipes → verify display cleanup and correct item drops
9. Test explosions → verify custom blocks cleaned up
10. Test pistons → verify pipe breaks, drops item, piston proceeds
11. Test `/fill` over pipes → verify BlockDestroyEvent cleanup
12. Test fire on pipes → verify burn handler
13. Test crafting recipes, cauldron conversion
14. Verify world filtering (place pipe in disabled world → cancelled with message)
15. Run `/pipes reload` → verify display refresh, config reload
16. Run `/pipes give`, `/pipes info`, `/pipes cleanup`, `/pipes delete_all`
17. Verify debug particles toggle works
