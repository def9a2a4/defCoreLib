# Vertical Slabs — Implementation Plan

## Context

Vertical slabs (half-blocks placed on N/S/E/W faces) as a defCoreLib addon plugin.
Uses BlockDisplay entities for 3D block visuals and the existing YAML-driven
CustomHeadBlock system for placement, drops, recipes, and persistence.

**Core problem**: defCoreLib's `DisplayEntityConfig` only supports `ItemDisplay` entities.
Materials like `OAK_PLANKS` rendered via ItemDisplay show as flat 2D item sprites.
Vertical slabs need `BlockDisplay` entities which render actual 3D block textures at
any scale.

**Design decision**: Add a **separate** `BlockDisplayEntityConfig` record rather than
making `DisplayEntityConfig.displayItem` nullable. This keeps the existing record,
builder, state config, mechanism registry, and all consumer code completely untouched.
No NPE risks, no constructor signature changes, no downstream breakage.

> **Rejected alternative**: an earlier draft (formerly the repo-root `TODO-vertical-slabs.md`)
> proposed making `DisplayEntityConfig.displayItem` `@Nullable` and adding a `blockData` field to
> the existing record in-place. Rejected: it changes the record's constructor signature and risks
> an NPE/CCE in `MechanismRegistry` (~line 178-187) where every config is assumed to be an
> ItemDisplay. The separate-record approach above supersedes it.

---

## Part 1: defCoreLib — Add BlockDisplay support (additive only)

No existing code is modified. All changes are new fields, new methods, new parsing.

### 1a. New `BlockDisplayEntityConfig` record

**File**: `CustomHeadBlock.java` — add after `DisplayEntityConfig` (line 105)

```java
/** A BlockDisplay entity attached to the block.
 *  Renders actual 3D block geometry at any scale via BlockDisplay.
 *  Parsed from the {@code block_data} key in YAML display_entities. */
public record BlockDisplayEntityConfig(
        org.bukkit.block.data.BlockData blockData,
        Transformation transform,
        @Nullable String tagSuffix,
        @Nullable DisplayAnimation animation,
        int interpolationDuration,
        float wallOffset
) {}
```

### 1b. New fields in `CustomHeadBlock`

Mirror the existing `displayEntities` pattern with a parallel `blockDisplayEntities` list.

**Fields** (after line 233 `private final List<DisplayEntityConfig> displayEntities;`):
```java
private final List<BlockDisplayEntityConfig> blockDisplayEntities;
```

**Constructor** (after line 289 `this.displayEntities = List.copyOf(b.displayEntities);`):
```java
this.blockDisplayEntities = List.copyOf(b.blockDisplayEntities);
```

**Cached check** (update line 321 `_hasDisplayEntities`):
```java
this._hasDisplayEntities = !displayEntities.isEmpty() || !blockDisplayEntities.isEmpty()
    || states.values().stream().anyMatch(s -> s.displayEntities() != null || s.blockDisplayEntities() != null);
```

**Accessor** (after line 342):
```java
public List<BlockDisplayEntityConfig> blockDisplayEntities() { return blockDisplayEntities; }
```

**Resolver** (after `resolveDisplayEntities` at line 451-460):
```java
public List<BlockDisplayEntityConfig> resolveBlockDisplayEntities(@Nullable String state) {
    if (state != null) {
        StateConfig sc = states.get(state);
        if (sc != null) {
            if (sc.clearDisplayEntities()) return List.of();
            if (sc.blockDisplayEntities() != null) return sc.blockDisplayEntities();
        }
    }
    return blockDisplayEntities;
}
```

### 1c. Update `StateConfig` record

**File**: `CustomHeadBlock.java` line 205-214

Add `blockDisplayEntities` field:
```java
public record StateConfig(
        @Nullable String texture,
        @Nullable Map<BlockFace, String> directionalTextures,
        @Nullable LightConfig light,
        @Nullable ParticleConfig particles,
        @Nullable List<DisplayEntityConfig> displayEntities,
        @Nullable List<BlockDisplayEntityConfig> blockDisplayEntities,  // NEW
        boolean clearLight,
        boolean clearParticles,
        boolean clearDisplayEntities
) {}
```

Update every `new StateConfig(...)` call to pass the new field (null for existing code).

### 1d. Update Builder and StateBuilder

**Builder** (after line 560):
```java
private final List<BlockDisplayEntityConfig> blockDisplayEntities = new ArrayList<>();
```

Builder method (after line 611):
```java
public Builder blockDisplayEntities(List<BlockDisplayEntityConfig> configs) {
    this.blockDisplayEntities.addAll(configs); return this;
}
```

`toBuilder()` (after line 516):
```java
b.blockDisplayEntities.addAll(blockDisplayEntities);
```

**StateBuilder** (after line 744):
```java
private @Nullable List<BlockDisplayEntityConfig> blockDisplayEntities;
```

StateBuilder method (after line 755):
```java
public StateBuilder blockDisplayEntities(List<BlockDisplayEntityConfig> configs) {
    this.blockDisplayEntities = configs; return this;
}
```

StateBuilder.build() — pass `blockDisplayEntities` to the new `StateConfig` constructor.

### 1e. Update `BlockLoader.parseDisplayEntities()`

**File**: `BlockLoader.java` — the existing `parseDisplayEntities()` at line 409-456.

Split `block_data` entries into a separate list. Add a new `parseBlockDisplayEntities()`
method, or handle inline. The cleanest approach: in the existing loop, when `block_data`
is present, collect into a separate list and return both via a helper record.

**Option A** (simpler): Add a parallel `parseBlockDisplayEntities()` that filters the
same `display_entities` YAML list for entries with `block_data`:

```java
private static List<CustomHeadBlock.BlockDisplayEntityConfig> parseBlockDisplayEntities(
        List<Map<?, ?>> list) {
    List<CustomHeadBlock.BlockDisplayEntityConfig> result = new ArrayList<>();
    for (Map<?, ?> map : list) {
        Object blockDataObj = map.get("block_data");
        if (blockDataObj == null) continue;  // skip ItemDisplay entries

        BlockData blockData = Bukkit.createBlockData(String.valueOf(blockDataObj));
        String tag = map.get("tag") != null ? String.valueOf(map.get("tag")) : null;
        Transformation transform = parseTransform(map);  // extract existing transform parsing

        DisplayAnimation animation = null;
        Object animObj = map.get("animation");
        if (animObj instanceof Map<?, ?> animMap) {
            animation = parseAnimation(animMap);
        }

        int interpolation = toInt((Object) map.get("interpolation"), 2);
        float wallOffset = (float) toDouble((Object) map.get("wall_offset"), 0);

        result.add(new CustomHeadBlock.BlockDisplayEntityConfig(
                blockData, transform, tag, animation, interpolation, wallOffset));
    }
    return result;
}
```

Update the existing `parseDisplayEntities()` to skip `block_data` entries:
```java
// At the top of the loop, before the texture/material resolution:
if (map.get("block_data") != null) continue;  // handled by parseBlockDisplayEntities
```

Update the error message (line 423) to say `'texture', 'material', or 'block_data'`
even though block_data entries are skipped — for clarity when none are present.

**Call sites** — wherever `parseDisplayEntities()` is called, also call
`parseBlockDisplayEntities()` and pass to the builder:

Line 164-166 (base config):
```java
if (displayList != null) {
    b.displayEntities(parseDisplayEntities(sec.getMapList("display_entities"), textures));
    b.blockDisplayEntities(parseBlockDisplayEntities(sec.getMapList("display_entities")));
}
```

Line 328-331 (state overrides in `parseStateOverrides`):
```java
if (displayList != null) {
    sb.displayEntities(parseDisplayEntities(sec.getMapList("display_entities"), textures));
    sb.blockDisplayEntities(parseBlockDisplayEntities(sec.getMapList("display_entities")));
}
```

**Refactor note**: The transform parsing code (lines 428-456) is duplicated between
`parseDisplayEntities` and the new method. Extract a `parseTransform(Map<?, ?> map)`
helper to avoid duplication.

### 1f. Update `CustomBlockRegistry.applyConfig()`

**File**: `CustomBlockRegistry.java` line 447-483

After the existing ItemDisplay loop (line 453-477), add a second loop for BlockDisplay:

```java
// Block display entities (new — runs after ItemDisplay loop)
List<CustomHeadBlock.BlockDisplayEntityConfig> blockDisplays = type.resolveBlockDisplayEntities(state);
if (blockDisplays != null && !blockDisplays.isEmpty()) {
    for (var bdc : blockDisplays) {
        String tag = DisplayUtil.blockTag(type.namespace(), type.typeId(),
                block.getLocation(), bdc.tagSuffix());
        org.bukkit.Location spawnBase = block.getLocation();
        if (bdc.wallOffset() != 0 && block.getType() == Material.PLAYER_WALL_HEAD
                && block.getBlockData() instanceof Directional wallDir) {
            Vector wallFacing = wallDir.getFacing().getDirection();
            spawnBase = spawnBase.clone().subtract(wallFacing.multiply(bdc.wallOffset()));
        }
        Matrix4f matrix = transformToMatrix(bdc.transform());
        var display = DisplayUtil.spawnBlock(spawnBase, bdc.blockData(), matrix, tag);
        if (bdc.interpolationDuration() != 0) {
            display.setInterpolationDuration(bdc.interpolationDuration());
        }
        if (bdc.animation() != null) {
            if (anims == null) anims = new ArrayList<>();
            anims.add(new AnimationTracked(display, bdc.animation(),
                    Bukkit.getServer().getCurrentTick(),
                    transformToMatrix(bdc.transform())));
        }
    }
}
```

**Removal** — already handled. `DisplayUtil.removeByTag()` (line 450) matches on the
`Display` superclass, which covers both `ItemDisplay` and `BlockDisplay`. The tag prefix
is the same. No change needed.

**`trackAnimations()`** (line 700-737) — needs a parallel loop for block display configs
to re-register animations on chunk load. Same pattern as above but matching by tag.

### 1g. What is NOT changed

- `DisplayEntityConfig` record — untouched
- `MechanismRegistry` — untouched (it only iterates `displayEntityConfigs`)
- `MechanismBlockData` — untouched
- `CoreLibPlugin` event handlers — untouched
- `DisplayUtil` — already has `spawnBlock()` (line 58-68), no changes needed
- All consumer plugins (Pipes, BlockShips, etc.) — untouched

---

## Part 2: Demo block

**File**: `defCoreLib/src/main/resources/demo-blocks.yml`

Add a test block to verify BlockDisplay works before building the addon:

```yaml
  # ─── N. Block Display Test ──────────────────────────────────────────
  # Features: block_data display entity (BlockDisplay).
  block_display_test:
    name: "&aBlock Display Test"
    lore: ["&7Tests BlockDisplay entity rendering"]
    texture: "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzNmYjdjOTJmZTE2NDlkNzZjNGFkOTc5ZWZjZDJjNDYwYjJlOTBiMjMyMGEyMzNlZjg1MTMzZGQ1NmJlZDg2YSJ9fX0="
    drops: self
    display_entities:
      - block_data: "minecraft:diamond_block"
        tag: overlay
        transform:
          translation: [0, 0.5, 0]
          scale: [0.5, 0.5, 0.5]
```

**Verify**: Place the block, confirm a small 3D diamond block renders floating above
the skull. Break it, confirm the BlockDisplay entity is cleaned up.

---

## Part 3: VerticalSlabs addon project

### Directory structure

```
VerticalSlabs/
  build.gradle.kts
  settings.gradle.kts
  Makefile
  src/main/java/anon/def9a2a4/verticalslabs/
    VerticalSlabsPlugin.java
  src/main/resources/
    plugin.yml
    slabs.yml
```

Create at `/home/miv/projects/minecraft/VerticalSlabs/`.

### `settings.gradle.kts`

```kotlin
rootProject.name = "VerticalSlabs"
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
        archiveBaseName.set("VerticalSlabs")
        manifest {
            attributes["paperweight-mappings-namespace"] = "mojang"
        }
    }

    shadowJar {
        archiveClassifier.set("")
        archiveBaseName.set("VerticalSlabs")
    }
}
```

### `plugin.yml`

```yaml
name: VerticalSlabs
main: anon.def9a2a4.verticalslabs.VerticalSlabsPlugin
version: ${version}
api-version: 1.21
author: You
description: Vertical slab variants for all wood and stone types
depend: [DefCoreLib]

commands:
  verticalslabs:
    description: Vertical Slabs admin commands
    usage: /verticalslabs <give|list>
    permission: verticalslabs.admin

permissions:
  verticalslabs.admin:
    description: Allows access to all VerticalSlabs commands
    default: op
```

### `VerticalSlabsPlugin.java`

```java
package anon.def9a2a4.verticalslabs;

import anon.def9a2a4.corelib.BlockLoader;
import anon.def9a2a4.corelib.CoreLibPlugin;
import anon.def9a2a4.corelib.CustomBlockRegistry;
import org.bukkit.plugin.java.JavaPlugin;

public class VerticalSlabsPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        CustomBlockRegistry registry = CoreLibPlugin.getInstance().getRegistry();
        try (var stream = getResource("slabs.yml")) {
            if (stream == null) {
                getLogger().severe("slabs.yml not found in JAR");
                return;
            }
            int count = BlockLoader.load(stream, registry, getLogger());
            getLogger().info("Loaded " + count + " vertical slab types");
        } catch (Exception e) {
            getLogger().severe("Failed to load slabs: " + e.getMessage());
        }
    }
}
```

### `Makefile`

```makefile
.PHONY: build clean

build:
	./gradlew shadowJar

clean:
	./gradlew clean
```

---

## Part 4: YAML block definitions (`slabs.yml`)

### Pattern per variant

Each slab has 4 directional states via `placement_state_map`. The skull provides block
identity and collision. The BlockDisplay entity is scaled to half-depth and offset to
the correct face.

```yaml
namespace: verticalslabs

blocks:
  oak:
    name: "&fOak Vertical Slab"
    texture: "<oak-colored-base64-head-texture>"
    drops: self
    place_sound: { sound: BLOCK_WOOD_PLACE, volume: 1.0, pitch: 1.0 }
    break_sound: { sound: BLOCK_WOOD_BREAK, volume: 1.0, pitch: 1.0 }
    placement:
      allowed_faces: [NORTH, SOUTH, EAST, WEST]
      require_solid: true
    default_state: north
    placement_state_map:
      NORTH: north
      SOUTH: south
      EAST: east
      WEST: west
    states:
      north:
        display_entities:
          - block_data: "minecraft:oak_planks"
            tag: slab
            transform: { scale: [1, 1, 0.5], translation: [0, -0.5, -0.25] }
      south:
        display_entities:
          - block_data: "minecraft:oak_planks"
            tag: slab
            transform: { scale: [1, 1, 0.5], translation: [0, -0.5, 0.25] }
      east:
        display_entities:
          - block_data: "minecraft:oak_planks"
            tag: slab
            transform: { scale: [0.5, 1, 1], translation: [0.25, -0.5, 0] }
      west:
        display_entities:
          - block_data: "minecraft:oak_planks"
            tag: slab
            transform: { scale: [0.5, 1, 1], translation: [-0.25, -0.5, 0] }
    recipes:
      craft:
        shaped:
          - id: "oak_vslab_craft"
            amount: 6
            pattern: ["P", "P", "P"]
            key:
              P: { material: OAK_PLANKS }
      stonecutter:
        - id: "oak_vslab_from_planks"
          amount: 2
          input: { material: OAK_PLANKS }
```

### Transform math

BlockDisplay renders a full 1x1x1 block by default. Spawn point is block center
(0.5, 0.5, 0.5). Transforms are relative to center.

| Direction | Scale         | Translation          | What it does                          |
|-----------|---------------|----------------------|---------------------------------------|
| North     | [1, 1, 0.5]   | [0, -0.5, -0.25]    | Half-depth Z, anchor bottom, push N   |
| South     | [1, 1, 0.5]   | [0, -0.5, 0.25]     | Half-depth Z, anchor bottom, push S   |
| East      | [0.5, 1, 1]   | [0.25, -0.5, 0]     | Half-depth X, anchor bottom, push E   |
| West      | [0.5, 1, 1]   | [-0.25, -0.5, 0]    | Half-depth X, anchor bottom, push W   |

These values may need empirical tuning. BlockDisplay origin conventions can differ
from ItemDisplay. Test with oak first, then apply to all.

### Skull textures

Each variant gets a base64 head texture colored to match the material. Source from
minecraft-heads.com or similar. Use a placeholder solid-color texture for initial
testing.

### Full variant list

**Wood** (11 variants) — sound: `BLOCK_WOOD_PLACE` / `BLOCK_WOOD_BREAK`

| ID         | block_data                | Recipe input    |
|------------|---------------------------|-----------------|
| oak        | minecraft:oak_planks      | OAK_PLANKS      |
| spruce     | minecraft:spruce_planks   | SPRUCE_PLANKS   |
| birch      | minecraft:birch_planks    | BIRCH_PLANKS    |
| jungle     | minecraft:jungle_planks   | JUNGLE_PLANKS   |
| acacia     | minecraft:acacia_planks   | ACACIA_PLANKS   |
| dark_oak   | minecraft:dark_oak_planks | DARK_OAK_PLANKS |
| mangrove   | minecraft:mangrove_planks | MANGROVE_PLANKS |
| cherry     | minecraft:cherry_planks   | CHERRY_PLANKS   |
| bamboo     | minecraft:bamboo_planks   | BAMBOO_PLANKS   |
| crimson    | minecraft:crimson_planks  | CRIMSON_PLANKS  |
| warped     | minecraft:warped_planks   | WARPED_PLANKS   |

**Stone** (30+ variants) — sound: `BLOCK_STONE_PLACE` / `BLOCK_STONE_BREAK` unless noted

| ID                         | block_data                           | Recipe input                 | Sound override            |
|----------------------------|--------------------------------------|------------------------------|---------------------------|
| stone                      | minecraft:stone                      | STONE                        |                           |
| cobblestone                | minecraft:cobblestone                | COBBLESTONE                  |                           |
| mossy_cobblestone           | minecraft:mossy_cobblestone          | MOSSY_COBBLESTONE            |                           |
| smooth_stone               | minecraft:smooth_stone               | SMOOTH_STONE                 |                           |
| stone_brick                | minecraft:stone_bricks               | STONE_BRICKS                 |                           |
| mossy_stone_brick           | minecraft:mossy_stone_bricks         | MOSSY_STONE_BRICKS           |                           |
| granite                    | minecraft:granite                    | GRANITE                      |                           |
| polished_granite            | minecraft:polished_granite           | POLISHED_GRANITE             |                           |
| diorite                    | minecraft:diorite                    | DIORITE                      |                           |
| polished_diorite            | minecraft:polished_diorite           | POLISHED_DIORITE             |                           |
| andesite                   | minecraft:andesite                   | ANDESITE                     |                           |
| polished_andesite           | minecraft:polished_andesite          | POLISHED_ANDESITE            |                           |
| sandstone                  | minecraft:sandstone                  | SANDSTONE                    |                           |
| smooth_sandstone            | minecraft:smooth_sandstone           | SMOOTH_SANDSTONE             |                           |
| red_sandstone              | minecraft:red_sandstone              | RED_SANDSTONE                |                           |
| smooth_red_sandstone        | minecraft:smooth_red_sandstone       | SMOOTH_RED_SANDSTONE         |                           |
| brick                      | minecraft:bricks                     | BRICKS                       |                           |
| prismarine                 | minecraft:prismarine                 | PRISMARINE                   |                           |
| prismarine_brick            | minecraft:prismarine_bricks          | PRISMARINE_BRICKS            |                           |
| dark_prismarine             | minecraft:dark_prismarine            | DARK_PRISMARINE              |                           |
| nether_brick               | minecraft:nether_bricks              | NETHER_BRICKS                | BLOCK_NETHER_BRICKS_*     |
| red_nether_brick            | minecraft:red_nether_bricks          | RED_NETHER_BRICKS            | BLOCK_NETHER_BRICKS_*     |
| quartz                     | minecraft:quartz_block               | QUARTZ_BLOCK                 |                           |
| smooth_quartz              | minecraft:smooth_quartz              | SMOOTH_QUARTZ                |                           |
| purpur                     | minecraft:purpur_block               | PURPUR_BLOCK                 |                           |
| end_stone_brick             | minecraft:end_stone_bricks           | END_STONE_BRICKS             |                           |
| blackstone                 | minecraft:blackstone                 | BLACKSTONE                   |                           |
| polished_blackstone         | minecraft:polished_blackstone        | POLISHED_BLACKSTONE          |                           |
| polished_blackstone_brick   | minecraft:polished_blackstone_bricks | POLISHED_BLACKSTONE_BRICKS   |                           |
| cut_copper                 | minecraft:cut_copper                 | CUT_COPPER                   | BLOCK_COPPER_*            |
| cobbled_deepslate           | minecraft:cobbled_deepslate          | COBBLED_DEEPSLATE            | BLOCK_DEEPSLATE_*         |
| polished_deepslate          | minecraft:polished_deepslate         | POLISHED_DEEPSLATE           | BLOCK_DEEPSLATE_*         |
| deepslate_brick             | minecraft:deepslate_bricks           | DEEPSLATE_BRICKS             | BLOCK_DEEPSLATE_BRICKS_*  |
| deepslate_tile              | minecraft:deepslate_tiles            | DEEPSLATE_TILES              | BLOCK_DEEPSLATE_TILES_*   |
| mud_brick                  | minecraft:mud_bricks                 | MUD_BRICKS                   | BLOCK_MUD_BRICKS_*        |
| tuff                       | minecraft:tuff                       | TUFF                         | BLOCK_TUFF_*              |
| polished_tuff               | minecraft:polished_tuff              | POLISHED_TUFF                | BLOCK_TUFF_*              |
| tuff_brick                 | minecraft:tuff_bricks                | TUFF_BRICKS                  | BLOCK_TUFF_*              |

---

## Part 5: v1 scope — what's skipped

- **Mechanism support** — slabs in mechanisms lose their BlockDisplay visual (skull
  only). `MechanismRegistry` is untouched; it iterates `displayEntityConfigs` which
  doesn't include block displays.
- **Double slabs** — merging two opposite-face slabs into a full block (needs custom Java)
- **Waterlogging** — skulls can't be waterlogged without NMS
- **Half-block collision** — skull = full block. Proper half needs shulker entities.
- **Tool requirements** — stone slabs should need pickaxe (add via `drops` rules later)
- **Vanilla slab conversion** — stonecutter from horizontal slabs to vertical
- **Oxidation variants** — exposed/weathered/oxidized cut copper

---

## Implementation order

1. **corelib**: Add `BlockDisplayEntityConfig` record + fields/accessors/builder/resolver
2. **corelib**: Add `parseBlockDisplayEntities()` to `BlockLoader`, wire into both call sites
3. **corelib**: Add BlockDisplay spawn loop to `CustomBlockRegistry.applyConfig()`
4. **corelib**: Add demo block to `demo-blocks.yml`, build, test on server
5. **addon**: Create `VerticalSlabs/` project skeleton
6. **addon**: Write `slabs.yml` — start with oak + stone, test transforms, bulk-generate rest
7. **test**: Deploy both JARs, verify placement/breaking/recipes in-game
8. **tune**: Adjust BlockDisplay transform offsets if needed

---

## Verification

1. Build defCoreLib, deploy, `/defcorelib give_demo` — place block_display_test block,
   confirm 3D diamond block renders above skull
2. Build VerticalSlabs, deploy alongside defCoreLib
3. `/defcorelib give verticalslabs:oak` — place on wall faces, verify:
   - Correct directional placement (N/S/E/W)
   - BlockDisplay renders as half-block of correct material
   - Breaking drops the item and removes the display entity
   - Sounds match material type
4. Craft 3 oak planks in a column — produces 6 oak vertical slabs
5. Stonecutter: 1 oak planks — produces 2 oak vertical slabs
6. Spot-check other variants (stone, birch, quartz)

---

## Files modified (defCoreLib)

| File | Change |
|------|--------|
| `CustomHeadBlock.java` | New `BlockDisplayEntityConfig` record, new fields/accessors/resolver, updated `StateConfig`, updated `Builder`/`StateBuilder` |
| `BlockLoader.java` | New `parseBlockDisplayEntities()`, extract `parseTransform()` helper, wire into base + state parsing |
| `CustomBlockRegistry.java` | New BlockDisplay spawn loop in `applyConfig()`, new loop in `trackAnimations()` |
| `demo-blocks.yml` | New test block using `block_data` |

## Files created (VerticalSlabs)

| File | Purpose |
|------|---------|
| `build.gradle.kts` | Gradle build config |
| `settings.gradle.kts` | Project name |
| `Makefile` | Build shortcuts |
| `plugin.yml` | Plugin metadata |
| `VerticalSlabsPlugin.java` | Loads slabs.yml and registers with corelib |
| `slabs.yml` | All ~40 slab variant definitions |
