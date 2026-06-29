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

> **Note**: All line numbers in this plan are approximate and may have drifted since
> the plan was written. Verify against current source during implementation.

### 1a. New `DisplayTransformResolver` interface

**File**: `CustomHeadBlock.java` — add after the `StateChangeHandler` interface

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

### 1e. New `stateResolver` callback + `playerHeadStates`

Pipes has placement logic that can't be expressed with `placementStateMap`:
- Regular pipes face AWAY from placement surface; corner pipes face TOWARD
- Corner pipes cancel UP-facing placement
- Vertical pipes need PLAYER_HEAD block type and locked rotation

**Important**: The stateResolver must NOT mutate block type directly (calling
`block.setType()` inside the callback destroys the tile entity before `markBlock`
can write PDC). Instead, states that require PLAYER_HEAD are declared declaratively
via `playerHeadStates` on the builder, and CoreLib performs the mutation after
state resolution.

**File**: `CustomHeadBlock.java`

```java
@FunctionalInterface
public interface StateResolver {
    /**
     * @param event the placement event
     * @return resolved state string, or null to cancel placement
     */
    @Nullable String resolve(BlockPlaceEvent event);
}

// Fields:
private final @Nullable StateResolver stateResolver;
private final Set<String> playerHeadStates;

// Constructor:
this.stateResolver = b.stateResolver;
this.playerHeadStates = b.playerHeadStates;

// Accessors:
public @Nullable StateResolver stateResolver() { return stateResolver; }
public Set<String> playerHeadStates() { return playerHeadStates; }

// Builder:
private @Nullable StateResolver stateResolver;
private Set<String> playerHeadStates = Set.of();
public Builder stateResolver(StateResolver resolver) {
    this.stateResolver = resolver; return this;
}
public Builder playerHeadStates(String... states) {
    this.playerHeadStates = Set.of(states); return this;
}
```

### 1g. Wire resolver into `CustomBlockRegistry.applyConfig()`

**File**: `CustomBlockRegistry.java`, inside `applyConfig()` (line numbers approximate,
verify during implementation)

The existing display entity spawn loop uses `for (var dec : displays)` with no index
variable. **Convert to an indexed loop** to provide `displayIndex` to the resolver:

```java
// CHANGE: for (var dec : displays) → indexed loop
for (int i = 0; i < displays.size(); i++) {
    var dec = displays.get(i);

    // INSERT before the spawn call:
    Transformation transform = dec.transform();
    if (type.displayTransformResolver() != null) {
        Transformation resolved = type.displayTransformResolver()
                .resolve(block, state, dec, i);
        if (resolved != null) transform = resolved;
    }

    // ... existing spawn code uses `transform` instead of `dec.transform()` ...
}
```

This preserves the existing `displayItemResolver` check, wallOffset logic, animation
tracking, and `interpolationDuration`. Only the loop header changes and the transform
variable is extracted.

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

**File**: `CoreLibPlugin.java`, inside `onBlockPhysics()` handler (the `BlockPhysicsEvent`
listener that iterates cardinal faces and dispatches `onNeighborChange`)

After the existing `onNeighborChange` dispatch within the neighbor loop, add:

```java
// Inside the existing loop over CARDINAL_FACES in onBlockPhysics():
if (type != null && type.onNeighborChange() != null) {
    type.onNeighborChange().accept(neighbor, face.getOppositeFace());
}
// ADD THIS — re-resolve dynamic display transforms:
if (type != null && type.displayTransformResolver() != null) {
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
if (type.stateResolver() != null) {
    resolvedState = type.stateResolver().resolve(event);
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

// Apply block type override BEFORE markBlock (so tile entity exists for PDC write)
// playerHeadStates is declarative — states listed here auto-trigger WALL_HEAD → HEAD
if (type.playerHeadStates().contains(resolvedState)
        && block.getType() == Material.PLAYER_WALL_HEAD) {
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

**REPLACE** the existing `onChunkLoadCallback` call (lines 290-292), not add alongside
it. The `onBlockPlaced` callback fires during placement; `onChunkLoadCallback` fires
only during actual chunk restoration in `restoreBlock()`. Without this change, types
that set both callbacks would have both fire during placement (double registration).

```java
// REPLACE lines 290-292 (the existing onChunkLoadCallback call):
if (type.onBlockPlaced() != null) {
    type.onBlockPlaced().accept(block, state);
} else if (type.onChunkLoadCallback() != null) {
    // Backwards compat: existing types without onBlockPlaced still get onChunkLoad
    type.onChunkLoadCallback().accept(block, state);
}
```

### 1m. Add `BlockDestroyEvent` handler

**File**: `CoreLibPlugin.java` — new handler alongside `onBlockBreak`

CoreLib currently lacks this. Paper fires `BlockDestroyEvent` for `/fill`, `/setblock`,
physics-based destruction, etc. Without it, custom blocks destroyed by commands leave
orphaned display entities.

`onBlockRemoved(Block, CustomHeadBlock)` is cleanup-only (removes tracking, display
entities, fires callback). Drops are handled upstream by each destruction type — this
handler intentionally does NOT drop items (same pattern as fire/water handlers).

```java
@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
public void onBlockDestroy(BlockDestroyEvent event) {
    Block block = event.getBlock();
    CustomHeadBlock type = registry.getTypeFromBlock(block);
    if (type == null) return;
    registry.onBlockRemoved(block, type);
}
```

### 1n. Add `breakOnPiston` flag (piston break-and-drop)

**File**: `CustomHeadBlock.java` — new boolean field

Pipes' current behavior: break the pipe, drop the item, let the piston proceed. CoreLib's
`cancelPistons` flag cancels the entire piston event (nothing moves). A new
`breakOnPiston` flag provides the alternative: break-and-drop, then let the piston proceed.

The `onBlockRemoved` callback already fires during cleanup, so no separate piston
callback is needed — Pipes' `onBlockRemoved` handler handles pipe-specific cleanup
(removing from `pipes` map, invalidating path cache, updating adjacent displays).

```java
// Field + accessor (same pattern as cancelPistons):
private final boolean breakOnPiston;
public boolean breakOnPiston() { return breakOnPiston; }

// Builder:
private boolean breakOnPiston;
public Builder breakOnPiston(boolean value) {
    this.breakOnPiston = value; return this;
}
```

**File**: `CoreLibPlugin.java` — modify piston handlers (line numbers approximate)

Three-state behavior per block: cancel event, break-and-drop, or default (no custom handling).
Drops follow the same upstream pattern as `onBlockBreak`:

```java
public void onPistonExtend(BlockPistonExtendEvent event) {
    for (Block block : event.getBlocks()) {
        CustomHeadBlock type = registry.getTypeFromBlock(block);
        if (type == null) continue;
        if (type.cancelPistons()) {
            event.setCancelled(true);
            return;
        }
        if (type.breakOnPiston()) {
            // 1. Drop item (evaluate DropRule, same as break handler)
            String state = registry.getState(block);
            for (var rule : type.dropRules()) {
                if (rule.inState() != null && !rule.inState().equals(state)) continue;
                if (rule.isSelfDrop()) {
                    block.getWorld().dropItemNaturally(
                            block.getLocation(), type.createItem(1));
                }
                break;
            }
            // 2. Cleanup (remove tracking, display entities, fire onBlockRemoved callback)
            registry.onBlockRemoved(block, type);
            // 3. Clear block so piston can proceed
            block.setType(Material.AIR, false);
        }
    }
}
```

Same pattern for `onPistonRetract`.

### 1o. Auto-register recipes for late-registered types

**File**: `CustomBlockRegistry.java`

CoreLib calls `finalizeLoading()` → `registerRecipes()` during its own `onEnable()`,
before dependent plugins' `onEnable()` runs. Types registered after finalization
(like Pipes' block types) need their recipes registered automatically.

```java
private boolean finalized = false;

public void finalizeLoading() {
    // ... existing code ...
    registerRecipes();
    finalized = true;
}

public void register(CustomHeadBlock type) {
    types.put(type.fullId(), type);
    rescanLoadedChunks(type);
    if (finalized) {
        registerRecipesForType(type);  // private helper — same body as the inner loop
    }
}
```

This eliminates the need for dependent plugins to call `registerRecipes()` explicitly.
No new public API — `registerRecipesForType` is private.

### 1p. What is NOT changed

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

for (PipeVariant variant : variantRegistry.getAllVariants()) {
    String typeId = variant.getId();  // e.g. "copper_pipe", "iron_corner_pipe"
    boolean isCorner = variant.getBehaviorType() == BehaviorType.CORNER;

    DisplayConfig.TextureSet textures = displayConfig.getTextureSet(variant.getTextureSetId());

    var builder = CustomHeadBlock.builder("pipes", typeId)
        .texture(textures.getHeadTexture(BlockFace.NORTH))
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
    builder.breakOnPiston(true);           // drop item + cleanup + let piston proceed
    builder.playerHeadStates("up", "down"); // vertical pipes need PLAYER_HEAD

    // Dynamic display transform resolver
    builder.displayTransformResolver((block, state, config, displayIndex) -> {
        PipeManager mgr = getManager(block.getWorld());
        if (mgr == null) return null;
        return mgr.resolveTransform(block, state, config, displayIndex, variant);
    });

    // Shaped recipes (crafting table)
    for (var recipe : variant.getRecipes()) {
        builder.shapedRecipe(convertRecipe(recipe));
    }

    // Conversion recipes (shapeless: pipe + catalyst → different pipe)
    for (var conv : variant.getConversionRecipes()) {
        builder.shapelessRecipe(new ShapelessRecipeDef(
                conv.getKey(), conv.getResultAmount(),
                List.of(
                    new IngredientSpec(null, "pipes:" + conv.getFromVariantId(), null),
                    new IngredientSpec(conv.getCatalyst(), null, null))));
    }

    CustomHeadBlock type = builder.build();
    registry.register(type);
}

/**
 * Convert Pipes' RecipeDefinition to CoreLib's ShapedRecipeDef.
 */
private ShapedRecipeDef convertRecipe(RecipeDefinition recipe) {
    Map<Character, IngredientSpec> key = new HashMap<>();
    for (var entry : recipe.getIngredients().entrySet()) {
        key.put(entry.getKey(), new IngredientSpec(entry.getValue(), null, null));
    }
    return new ShapedRecipeDef(
            recipe.getKey(), recipe.getResultAmount(),
            List.of(recipe.getShape()), key);
}
```

**Recipe timing**: CoreLib's `register()` auto-registers recipes for types registered
after `finalizeLoading()` has run (Part 1o). No explicit `registerRecipes()` call needed.

### 2b. State definitions — regular pipes

6 states (one per facing direction). Each state defines 1 display entity.

Helper to reduce `DisplayEntityConfig` boilerplate (used by both regular and corner):

```java
private DisplayEntityConfig pipeDisplayConfig(String texture, String tagSuffix) {
    return new DisplayEntityConfig(
        HeadUtil.createHead(texture, 1), identityTransform(), tagSuffix, null, 0, 0);
}
```

```java
private void buildRegularStates(CustomHeadBlock.Builder builder,
        PipeVariant variant, DisplayConfig displayConfig) {
    DisplayConfig.TextureSet textures = displayConfig.getTextureSet(variant.getTextureSetId());

    String hHead = textures.getHeadTexture(BlockFace.NORTH);
    var hConfig = pipeDisplayConfig(textures.getItemDisplayTexture(BlockFace.NORTH), "main");

    for (String dir : List.of("north", "south", "east", "west")) {
        builder.state(dir, s -> s
                .texture(hHead)
                .displayEntities(List.of(hConfig)));
    }

    builder.state("up", s -> s
            .texture(textures.getHeadTexture(BlockFace.UP))
            .displayEntities(List.of(
                    pipeDisplayConfig(textures.getItemDisplayTexture(BlockFace.UP), "main"))));

    builder.state("down", s -> s
            .texture(textures.getHeadTexture(BlockFace.DOWN))
            .displayEntities(List.of(
                    pipeDisplayConfig(textures.getItemDisplayTexture(BlockFace.DOWN), "main"))));

    builder.defaultState("north");
}
```

The display entities use `identityTransform()` as a placeholder — the
`displayTransformResolver` overrides the transform immediately after spawning.

### 2c. State definitions — corner pipes

5 states (no UP). Each state defines 2 display entities (base + directional).

```java
private void buildCornerStates(CustomHeadBlock.Builder builder,
        PipeVariant variant, DisplayConfig displayConfig) {
    DisplayConfig.TextureSet textures = displayConfig.getTextureSet(variant.getTextureSetId());

    var hBase = pipeDisplayConfig(textures.getItemDisplayTexture(BlockFace.NORTH), "base");
    var hDir = pipeDisplayConfig(textures.getDirectionalDisplayTexture(BlockFace.NORTH), "dir");

    for (String dir : List.of("north", "south", "east", "west")) {
        builder.state(dir, s -> s
                .texture(textures.getHeadTexture(BlockFace.NORTH))
                .displayEntities(List.of(hBase, hDir)));
    }

    builder.state("down", s -> s
            .texture(textures.getHeadTexture(BlockFace.DOWN))
            .displayEntities(List.of(
                    hBase,
                    pipeDisplayConfig(textures.getDirectionalDisplayTexture(BlockFace.DOWN), "dir"))));

    builder.defaultState("north");
}
```

### 2d. `stateResolver` implementation

Returns state string, or null to cancel. Block type conversion for vertical pipes
is handled declaratively via `playerHeadStates("up", "down")` on the builder (Part 2a).

```java
private @Nullable String resolvePipeFacing(BlockPlaceEvent event,
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

    return facing.name().toLowerCase();
}

private BlockFace getPlayerFacing(float yaw) {
    yaw = ((yaw % 360) + 360) % 360;
    if (yaw >= 315 || yaw < 45) return BlockFace.SOUTH;
    if (yaw < 135) return BlockFace.WEST;
    if (yaw < 225) return BlockFace.NORTH;
    return BlockFace.EAST;
}
```

### 2e. Item identification helpers

After deleting `loadItems()` and the item cache, Pipes needs CoreLib-based replacements
for item identification (used by CauldronConversionListener and ConversionRecipeCraftListener).

```java
boolean isPipeItem(ItemStack item) {
    return getVariantFromItem(item) != null;
}

@Nullable PipeVariant getVariantFromItem(ItemStack item) {
    if (item == null || item.getType() != Material.PLAYER_HEAD) return null;
    if (!(item.getItemMeta() instanceof SkullMeta meta)) return null;
    String typeId = meta.getPersistentDataContainer()
            .get(CustomBlockRegistry.BLOCK_TYPE_KEY, PersistentDataType.STRING);
    if (typeId == null || !typeId.startsWith("pipes:")) return null;
    String variantId = typeId.substring("pipes:".length());
    return variantRegistry.getVariant(variantId);
}
```

### 2f. Conversion recipe catalyst tracking

`RecipeManager` is deleted, but `ConversionRecipeCraftListener` needs to know which
recipes are conversion recipes and what their catalyst material is. Track this on
`PipesPlugin` during block registration:

```java
// Field on PipesPlugin:
private final Map<NamespacedKey, Material> conversionCatalysts = new HashMap<>();

// Populated during block registration (Part 2a), after builder.shapelessRecipe():
for (var conv : variant.getConversionRecipes()) {
    String prefix = "pipes_" + typeId + "_";
    NamespacedKey key = new NamespacedKey(this, prefix + conv.getKey());
    conversionCatalysts.put(key, conv.getCatalyst());
}

// Accessors (used by ConversionRecipeCraftListener):
boolean isConversionRecipe(NamespacedKey key) {
    return conversionCatalysts.containsKey(key);
}

@Nullable Material getConversionCatalyst(NamespacedKey key) {
    return conversionCatalysts.get(key);
}
```

---

## Part 3: Pipes — `DisplayTransformResolver` implementation

The existing `calculateTransformation()` method (~120 lines) and its helpers (~60 lines)
stay in PipeManager but are wrapped in the resolver interface. The resolver closure
in Part 2a captures `PipeManager` via `getManager()`, giving access to PipeManager's
`displayConfig` field — the same `DisplayConfig` instance used today for scale factors,
offsets, and adjustment tables.

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

### 3c. Neighbor categorization — use CoreLib registry + enum

**Only `categorizeSourceBlock()` and `categorizeDestinationBlock()` switch to CoreLib
registry** — they need to identify arbitrary custom blocks as neighbors. The transfer
system (`findDestination()`, `transferItems()`, `getPipeData()`) keeps using the local
`pipes` map for chain-following. This is a performance-critical hot path (~300-500
hashmap lookups/tick) where O(1) map access beats block access + PDC reads per hop.

Both categorize methods currently call `getPipeData(location)` and return raw strings.
After migration, use CoreLib's registry and return a `NeighborCategory` enum for
compile-time safety:

```java
enum NeighborCategory {
    PIPE_CONTINUOUS("pipe-continuous"),
    PIPE_INTO("pipe-into"),
    PIPE_ORTHOGONAL("pipe-orthogonal"),
    CORNER_INTO("corner-into"),
    CORNER_PIPE("corner-pipe"),   // destination only
    CHEST("chest"),
    HOPPER("hopper"),
    CONTAINER("container"),
    AIR("air"),
    BLOCK("block");

    private final String configKey;
    NeighborCategory(String configKey) { this.configKey = configKey; }
    public String configKey() { return configKey; }
}
```

`DisplayConfig.getSourceAdjustment()` / `getDestinationAdjustment()` take
`NeighborCategory` and use `.configKey()` internally for the config map lookup.

Shared non-pipe classification tail (extracted from both methods):

```java
private NeighborCategory categorizeNonPipeBlock(Block block) {
    if (isChest(block)) return NeighborCategory.CHEST;
    if (isHopper(block)) return NeighborCategory.HOPPER;
    if (ContainerAdapterRegistry.findAdapter(block).isPresent()) return NeighborCategory.CONTAINER;
    if (block.getType().isAir() || !block.getType().isSolid()) return NeighborCategory.AIR;
    return NeighborCategory.BLOCK;
}
```

Source categorization:

```java
private NeighborCategory categorizeSourceBlock(Block sourceBlock, BlockFace currentFacing) {
    CustomHeadBlock type = CoreLibPlugin.getInstance().getRegistry()
            .getTypeFromBlock(sourceBlock);
    if (type != null && type.namespace().equals("pipes")) {
        String pipeState = CoreLibPlugin.getInstance().getRegistry()
                .getState(sourceBlock);
        if (pipeState == null) return NeighborCategory.BLOCK;
        BlockFace pipeDirection = BlockFace.valueOf(pipeState.toUpperCase());
        boolean isCorner = type.typeId().endsWith("corner_pipe");

        if (isCorner) {
            if (pipeDirection == currentFacing.getOppositeFace()) {
                return NeighborCategory.CORNER_INTO;
            }
            return NeighborCategory.BLOCK;
        }
        if (pipeDirection == currentFacing) return NeighborCategory.PIPE_CONTINUOUS;
        if (pipeDirection == currentFacing.getOppositeFace()) return NeighborCategory.PIPE_INTO;
        return NeighborCategory.PIPE_ORTHOGONAL;
    }

    return categorizeNonPipeBlock(sourceBlock);
}
```

`categorizeDestinationBlock()` follows the same pattern but returns
`NeighborCategory.CORNER_PIPE` for non-feeding corner pipes (instead of `BLOCK`).
Both methods share the pipe identification logic and delegate to
`categorizeNonPipeBlock()` for the non-pipe tail.

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

    // Adjacent pipe display transforms are re-resolved by CoreLib's
    // BlockPhysicsEvent handler (Part 1i) — no manual update needed.
}
```

### 4b. `onPipeRemoved(Block, String, PipeVariant)`

```java
void onPipeRemoved(Block block, String state, PipeVariant variant) {
    Location normalized = normalizeLocation(block.getLocation());
    pipes.remove(normalized);
    sleepUntil.remove(normalized);
    deadEndRecheckAt.remove(normalized);
    invalidatePathCache();

    // Display entity removal handled by CoreLib (removeByTag in onBlockRemoved).
    // Adjacent pipe display transforms re-resolved by CoreLib's BlockPhysicsEvent
    // handler (Part 1i) — no manual update needed.
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
    deadEndRecheckAt.remove(normalized);
    invalidatePathCache();  // clear cached paths that reference unloaded pipes
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
| `PipeManager.java` | Remove display spawn/remove/update/chunk-scan methods (`spawnDisplayEntities()`, `updateDisplayEntity()`, `updateCornerDisplayEntities()`, `removeDisplayEntities()`, `removeDisplaysByTag()`, `scanChunk()`, `scanForExistingPipes()`). Delete `cleanupOrphanedDisplays()` / `countOrphanedDisplays()` (use CoreLib's `scanOrphanedDisplays()` instead). Add callback methods + `resolveTransform()`. Add `NeighborCategory` enum + `categorizeNonPipeBlock()`. Modify `categorizeSourceBlock()` / `categorizeDestinationBlock()` to return `NeighborCategory` and use CoreLib registry. Simplify PipeData (remove UUID list). Change `registerPipe()` signature from `(Location, BlockFace, List<UUID>, PipeVariant)` to `(Location, BlockFace, PipeVariant)`. Rewrite `refreshAllDisplays()` to use CoreLib's `resolveDisplayTransforms()`. Update `reloadVariants()` to use 2-arg PipeData constructor. Keep transfer + transform + path + task code (`startTasks`, `stopTasks`, `restartTasks`, `shutdown`, `findDestination`, `transferItems`, `getPipeData`, debug particles). |
| `CauldronConversionListener.java` | Replace `plugin.getVariant(item)` → `plugin.getVariantFromItem(item)` (Part 2e). Replace `plugin.getPipeItem(toVariant)` → `CoreLibPlugin.getInstance().getRegistry().getType("pipes:" + toVariant.getId()).createItem(amount)`. Async polling pattern, water level handling, particle/sound effects all stay unchanged. |
| `ConversionRecipeCraftListener.java` | Replace `recipeManager.isConversionRecipe(key)` → `plugin.isConversionRecipe(key)` and `recipeManager.getConversionCatalyst(key)` → `plugin.getConversionCatalyst(key)` (Part 2f). Replace `plugin.isPipeItem(item)` → new `plugin.isPipeItem(item)` (Part 2e). Shift-click handling with manual matrix manipulation stays unchanged. Remove RecipeManager constructor dependency. |
| `PipeVariant.java` | Add `recipes` and `conversionRecipes` fields + getters. Recipes are populated during config loading, not accessed separately. |
| `VariantRegistry.java` | Move recipe parsing into `parseVariant()` so each `PipeVariant` holds its own recipes. The plan's registration code calls `variant.getRecipes()` / `variant.getConversionRecipes()` — these methods must exist. |

### Files that stay unchanged

| File | Why |
|------|-----|
| `WorldManager.java` | Per-world PipeManager lifecycle stays as-is. `initWorld()` still creates PipeManager and calls `startTasks()`, but remove `manager.scanForExistingPipes()` call (CoreLib handles chunk scanning via `restoreBlock()` → `onChunkLoadCallback`). |
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
2. `reloadVariants()` — still needed (replaces stale PipeVariant refs in PipeData).
   Update constructor to `new PipeData(data.facing(), fresh)` (no UUIDs).
3. Refresh displays via rewritten `refreshAllDisplays()`:
   ```java
   public void refreshAllDisplays() {
       CustomBlockRegistry registry = CoreLibPlugin.getInstance().getRegistry();
       for (Location loc : new ArrayList<>(pipes.keySet())) {
           Block block = loc.getBlock();
           CustomHeadBlock type = registry.getTypeFromBlock(block);
           if (type != null) {
               String state = registry.getState(block);
               registry.resolveDisplayTransforms(block, type, state);
           }
       }
   }
   ```
4. `restartTasks()` — unchanged (transfer intervals, debug particles)
5. Re-evaluate world filters — unchanged (WorldManager)
6. Recipes — CoreLib's `unregisterRecipes()` removes ALL recipe keys; then call
   `registerRecipes()` to re-register. Clear and rebuild `conversionCatalysts` map.
7. Sync recipe discovery — call `registry.syncRecipeDiscovery(player)` for all online players
8. Cauldron conversions — unchanged

### `/pipes give`

Use `registry.getType("pipes:" + variantId).createItem()` instead of
`plugin.getPipeItem()`.

### `/pipes info`

Iterate PipeManager's pipe map — unchanged. Orphaned display count: delegate to
CoreLib's existing `scanOrphanedDisplays()`:

```java
OrphanScanResult result = CoreLibPlugin.getInstance().getRegistry()
        .scanOrphanedDisplays(false);  // false = count only, don't remove
// Use result.orphans() for the display count
```

### `/pipes delete_all`

Rewritten `deleteAllPipes()`:

```java
public int deleteAllPipes() {
    CustomBlockRegistry registry = CoreLibPlugin.getInstance().getRegistry();
    List<Location> toRemove = new ArrayList<>(pipes.keySet());

    for (Location loc : toRemove) {
        Block block = loc.getBlock();
        CustomHeadBlock type = registry.getTypeFromBlock(block);
        if (type != null) {
            registry.onBlockRemoved(block, type);  // cleanup + fires onPipeRemoved callback
        }
        if (block.getType() == Material.PLAYER_HEAD || block.getType() == Material.PLAYER_WALL_HEAD) {
            block.setType(Material.AIR);
        }
    }

    return toRemove.size();
}
```

Note: `registry.onBlockRemoved()` fires the `onBlockRemoved` callback, which calls
`onPipeRemoved()`, which removes the entry from `pipes` map and clears sleep/cache state.
No separate `unregisterPipe()` needed.

### `/pipes cleanup`

Delegate to CoreLib's existing `scanOrphanedDisplays()` — no pipe-specific
implementation needed. CoreLib already has the full orphan scanning logic
(`CustomBlockRegistry.java:242-266`): iterates all Display entities, parses
`corelib:` tags, checks if the owning skull block still exists.

```java
OrphanScanResult result = CoreLibPlugin.getInstance().getRegistry()
        .scanOrphanedDisplays(true);  // true = remove orphans
player.sendMessage("Removed " + result.orphans() + " orphaned displays");
```

This cleans up ALL orphaned displays (any CoreLib namespace, not just pipes).
This is desirable — orphans from any consumer are equally unwanted.

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
2. **CoreLib**: Add `StateResolver` interface + `playerHeadStates` field + builder
3. **CoreLib**: Add `onBlockPlaced` callback + field + builder + accessor
4. **CoreLib**: Add `breakOnPiston` flag + modify piston handlers
5. **CoreLib**: Add `BlockDestroyEvent` handler
6. **CoreLib**: Add `resolveDisplayTransforms()` helper to `CustomBlockRegistry`
7. **CoreLib**: Add auto-register in `register()` via `finalized` flag
8. **CoreLib**: Wire resolver into `applyConfig()` (4-line insertion, preserve displayItemResolver)
9. **CoreLib**: Wire resolver into neighbor change handler
10. **CoreLib**: Wire resolver into `EntitiesLoadEvent` handler
11. **CoreLib**: Wire `stateResolver` + `playerHeadStates` into placement handler
12. **CoreLib**: Wire `onBlockPlaced` into placement handler (REPLACING `onChunkLoadCallback`)
13. **CoreLib**: Build and verify no regressions with existing demo blocks
14. **Pipes**: Add CoreLib dependency to build
15. **Pipes**: Add recipes + conversion recipes to `PipeVariant`, update `VariantRegistry`
16. **Pipes**: Create block type registration (Part 2)
17. **Pipes**: Implement `DisplayTransformResolver` wrapper (Part 3)
18. **Pipes**: Add `NeighborCategory` enum, refactor categorize methods (Part 3c)
19. **Pipes**: Implement callbacks (Part 4)
20. **Pipes**: Simplify `PipeData` record (Part 5)
21. **Pipes**: Delete redundant code, update remaining files (Part 6)
22. **Pipes**: Update commands (Part 7)
23. **Pipes**: Build and test end-to-end

---

## Files modified (defCoreLib)

| File | Change |
|------|--------|
| `CustomHeadBlock.java` | New `DisplayTransformResolver`, `StateResolver`, `playerHeadStates`, `onBlockPlaced`, `breakOnPiston`. Fields, constructor params, accessors, builder methods. (~60 lines) |
| `CustomBlockRegistry.java` | New `resolveDisplayTransforms()` helper. 4-line insertion in `applyConfig()` for resolver call. Auto-register via `finalized` flag in `register()`. (~40 lines) |
| `CoreLibPlugin.java` | `stateResolver` + `playerHeadStates` in placement handler. `onBlockPlaced` REPLACING `onChunkLoadCallback` during placement. Resolver call in `onBlockPhysics` neighbor loop. Resolver call in `EntitiesLoadEvent`. `BlockDestroyEvent` handler. Modified piston handlers for `breakOnPiston`. (~50 lines) |

## Files modified (Pipes)

| File | Change |
|------|--------|
| `build.gradle.kts` | Add CoreLib dependency |
| `plugin.yml` | Add `depend: [DefCoreLib]` |
| `PipesPlugin.java` | Replace item/recipe init with CoreLib block type registration. Add `isPipeItem()`, `getVariantFromItem()` (Part 2e), `conversionCatalysts` map + accessors (Part 2f), `getPlayerFacing()` (Part 2d), `pipeDisplayConfig()` helper (Part 2b). Keep config, commands, bStats. |
| `PipeManager.java` | Remove display/chunk/orphan code, add callbacks + resolver. Add `NeighborCategory` enum + `categorizeNonPipeBlock()`. Modify categorize methods to return enum. Simplify PipeData. Keep transfer, transform, path, tasks. |
| `PipeVariant.java` | Add `recipes` and `conversionRecipes` fields + getters. |
| `VariantRegistry.java` | Move recipe parsing into `parseVariant()`. |
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
13. Test crafting recipes — both shaped and conversion (shapeless). Verify that CoreLib's
    `onPrepareCraft` PDC validation works for shapeless conversion recipes (items can be
    placed in any crafting grid slot). Test both normal-click and shift-click conversion.
    Test cauldron conversion.
14. Verify world filtering (place pipe in disabled world → cancelled with message)
15. Run `/pipes reload` → verify display refresh, config reload
16. Run `/pipes give`, `/pipes info`, `/pipes cleanup`, `/pipes delete_all`
17. Verify debug particles toggle works
