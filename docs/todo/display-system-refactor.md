# Display-system elegance refactor — rotation blocks

Reducing duplication in the rotation-power display-entity definitions
(`src/main/resources/rotation-blocks.yml`, ~660 lines, mostly duplicated data).
Tackled in three independent phases, one at a time.

## Background — three layers of duplication

The rotation blocks (shaft, gear, drill, windmill, water wheel, clutch, engine,
grindstone, generator) are data-driven via YAML. Three layers of redundancy:

1. **Texture duplication** — ~13 distinct head textures, but ~69 inline `texture: "eyJ0…"`
   base64 references (~250 chars each). "Cut Copper" appeared 29×, the gear disc 20×,
   the drill rod 10×.
2. **`idle_*` vs `spinning_*` states** are byte-for-byte copies differing only by an added
   `animation:` block (and sometimes `particles:`, already a separate field).
3. **Per-axis orientation transforms** repeated across 5 block types; gear discs use
   hand-computed composed quaternions (e.g. `[98.42, -0.3574, 0.3574, 0.8629]`, documented
   in YAML as `R_Z(90)×R_Y(45)`).

The consuming machinery (`BlockLoader`, `DisplayAnimation`/`Animations`, the per-tick
ticker in `CustomBlockRegistry`) is clean — all duplication lives in the YAML data and its
parser.

Decision: named texture registry referenced with an `@alias` sigil (chosen over native
YAML anchors for readability, self-documentation, and reachability from Java).

---

## Phase 1 — Named texture registry  ✅ DONE (live-server smoke test pending)

Top-level `textures:` map (alias → base64) in a block YAML; `texture` / `item_texture` /
display / directional values use `@alias`, resolved to full base64 at parse time. Values
not starting with `@` pass through unchanged (backward compatible). Unknown `@alias` throws
→ block skipped + logged.

### Done
- **`BlockLoader.java`**: `load()` reads the `textures:` section; added
  `resolveTexture(String, Map<String,String>)` helper; threaded the `textures` map through
  `parseBlock` → `parseDisplayEntities`, `parseStateOverrides`, `parseDirectionalTextures`,
  and wrapped each raw texture string in `resolveTexture(...)`.
- **`rotation-blocks.yml`**: added a 13-entry registry; replaced all 69 inline base64 refs
  with `@alias` (each line ~230 chars shorter). Old hand-written texture comment folded in.
- Compiles cleanly (only pre-existing `Sound.valueOf` deprecation warnings).

### Verification (two adversarial agents)
- **YAML transformation: PASS, fully clean.** Purely cosmetic. All 13 registry textures
  decode to valid, distinct skin JSON; all 69 `@alias` refs resolve; no mismap (notably
  drill rods correctly use `@drill_rod`, NOT `@cut_copper`); no dead/missing aliases;
  transforms / states / animations / recipes untouched.
- **`BlockLoader.java`: PASS except one missed consumption point** (below).

### ✅ Fixed — redstone textures now routed through resolveTexture
`parseRedstone()` (`BlockLoader.java`) previously read `redstone.textures.<power>` and
`redstone.texture_ranges.<range>` as raw strings, so `@alias` would not resolve there.

- [x] Added `Map<String,String> textureAliases` param to `parseRedstone` (named to avoid
      shadowing the local `Map<Integer,String> textures`); updated the call site.
- [x] Wrapped both reads in `resolveTexture(..., textureAliases)`.
- [x] `gradle compileJava` — clean (only pre-existing `Sound.valueOf` deprecation warnings).

### Live-server verification (pending — needs a running server)
- [ ] Deploy jar; `/corelib give` each rotation block; place in all orientations
      (floor + N/S/E/W walls); confirm textures render identical to before.
- [ ] Confirm spinning/animation still works.
- [ ] Negative test: set a `texture:` to `@does_not_exist`, reload, confirm the
      "Unknown texture alias" warning + block skip; revert.

---

## Phase 2 — De-dup spin states via `copy_from` + state-level `animation`  (final-reviewed, ready to implement)

Every active (`spinning_*` / `running_*`) state is a byte-for-byte copy of its `idle_*` twin
plus a rotate `animation:` on its display entities; the windmill repeats an identical
`animation:` on all 4 blades of every orientation state. Phase 2 removes this duplication and
**normalizes every spin to 3.0** (speeds had drifted: gear discs 2.0, gear floor-rod 2.0 vs
wall-rod 3.0, water wheel 1.25, grindstone 2.0, windmill 1.5, rest 3.0).

Design after four critique/review rounds. Decisive insight (two independent agents): display
entities **already** support a per-entity `animation:` field (rotate/bob/pulse/orbit/compose via
`parseAnimation`), so we do **not** invent a bespoke `spin` key — we reuse `animation` at a new
scope. Net new vocabulary: **one key (`copy_from`)**, and **no special cases**.

**Final-review correction:** a `display_entities` entry is now EITHER an item display
(`texture`/`material` → `ItemStack`) OR a block display (`block_data` → `BlockData`). One
`display_entities` map-list parses into **two** partitioned config lists — `parseDisplayEntities`
skips `block_data` entries (BlockLoader.java:447), `parseBlockDisplayEntities` skips non-`block_data`
(:482). Phase 2 must handle **both** lists symmetrically (latent for rotation blocks, which use
none today, but `copy_from`/`animation` are generic loader features and demo-blocks uses `block_data`).

### How spin geometry works
The rotation pivot is **always the block center** (the display's anchor); you specify only the
axis *direction*. `Animations.rotate` pre-multiplies `R × base`, so an entity translated onto
the axis spins in place (gear discs pulled to the wall), while an off-axis entity orbits the
center (windmill blades sweep). There is no separate pivot field.

### YAML keys (handled in `BlockLoader`)
- **`copy_from: <stateName>`** — inherit a sibling state's raw `display_entities` verbatim
  (single-level, same-block). Other keys (`particles`, `light`, …) parse as today.
- **State-level `animation: { … }`** — parsed by the **existing** `parseAnimation` (any type:
  rotate/bob/pulse/orbit/compose). Applied to every resolved entity that has **no animation of
  its own**.
- **Per-entity `animation: none`** — opt out; that entity stays static.

`copy_from` (entity list) and `animation` (what animates) are orthogonal — three combos cover
everything, no special cases:

```yaml
# shaft / gear / drill / engine / generator / water_wheel — each active state → one line
spinning_x: { copy_from: idle_x, animation: { type: rotate, axis: [1, 0, 0], speed: 3.0 } }
running_y:  { copy_from: idle_y, animation: { type: rotate, axis: [0, 1, 0], speed: 3.0 }, particles: { … } }

# windmill — inline blades (no idle twin), one state animation; no copy_from
floor: { display_entities: [ …4 blades, no per-blade animation… ], animation: { type: rotate, axis: [0, 1, 0], speed: 3.0 } }

# grindstone — NOT a special case anymore: base opts out, top inherits the state animation
spinning:
  copy_from: idle          # idle's base_slab carries `animation: none`; top_slab carries nothing
  animation: { type: rotate, axis: [0, 1, 0], speed: 3.0 }
```

### Semantics (pinned down)
1. **Fill-only.** State-level `animation` applies only to entities with no animation of their
   own. Detection per source map: key **absent** → fill; key is a **map** (own animation) → keep
   it; key is the string **`none`** → stay static. No overwrite, no implicit compose. After
   `parseDisplayEntities`, both *absent* and *none* yield a `null` animation, so the **raw map**
   must be consulted to tell them apart.
2. **`copy_from` inherits raw, pre-animation entities** — from the sibling's raw
   `display_entities` map-list read out of `statesSec` (order-independent; **not** a built
   `StateConfig` — siblings may not be built yet); copies only `display_entities`.
3. A bare active state with neither key is legal and renders its own inline entities (or
   nothing) statically — e.g. generator `idle_*`.

### Validation (loud failure at load → block skipped + logged)
Only runs when `copy_from` or a state-level `animation` is present (empty states like clutch's
`spinning_x: {}` are untouched).
- `copy_from`: throw on unknown target / self / a target that itself has `copy_from` (no chains)
  / a target with no/empty `display_entities` / `copy_from` combined with own `display_entities`
  **or** `no_display_entities`. Message names block + state + bad target.
- State-level `animation` — **`parseAnimation` will NOT throw** (rotate defaults axis→[0,1,0],
  speed→1.0). So inspect the raw `animation` map directly: for `type: rotate`, require `axis` to
  be a length-3 list with **non-zero** magnitude and `speed` present; throw otherwise. Gate on
  type so a future bob/pulse isn't rejected for lacking an axis.
- **`Animations.rotate` zero-axis guard** (DisplayAnimation.java:42): a zero vector normalizes to
  NaN → invisible display every tick. Still needed because per-entity inline animations bypass
  the state-level validator. (`orbit` has the same hazard — guard both or share a helper.)

### Code changes — `BlockLoader.java` (+ a guard in `DisplayAnimation.java`)
Loader **materializes** animated entities into the state's `DisplayEntityConfig` /
`BlockDisplayEntityConfig` lists at parse time; runtime (`resolveDisplayEntities`,
`resolveBlockDisplayEntities`, ticker, `Animations.rotate`) unchanged. No `CustomHeadBlock`
change: `animation: none` (a string, not a map) already parses to a null animation — the opt-out
is detected from the raw map, no record change.
1. State loop — pass siblings + name:
   `parseStateOverrides(sb, stateSec, statesSec, stateName, textures)`.
2. `parseStateOverrides` — when `copy_from` or a state-level `animation` map is present, take the
   **materialize path** (else keep the existing texture/light/particles +
   `no_display_entities`/`display_entities` handling):
   - Resolve the raw map-list: `copy_from` target's `display_entities` (validated), else the
     state's own.
   - Parse **both** lists: `parseDisplayEntities(maps, textures)` and `parseBlockDisplayEntities(maps)`.
   - If a state-level `animation` is present: validate + `parseAnimation` once. **Partition** the
     raw maps by the `block_data` predicate (`itemMaps` = `block_data == null`, `blockMaps` =
     `block_data != null`) so each aligns 1:1 with its config list; for each config whose
     `animation()` is null **and** whose (partitioned) raw map has no `animation` key, rebuild it
     carrying the state animation. **Rebuild all six record components** (item:
     displayItem/transform/tagSuffix/animation/interpolation/wallOffset; block: blockData/…).
   - `sb.displayEntities(items)` **and** `sb.blockDisplayEntities(blocks)`. Leave
     texture/light/particles parsing intact.
3. `Animations.rotate` — zero-axis guard.

### YAML changes — `rotation-blocks.yml`
- Replace each `spinning_*` / `running_*` state in shaft, gear, drill, engine, generator,
  water_wheel with `{ copy_from: <idle>, animation: { type: rotate, axis: […], speed: 3.0 } }`
  (+ keep `particles:` for engine/generator). Gear: all six active states
  (`spinning_y/x/east_x/z/south_z`) → their `idle_*` twin; rod + both discs unify to 3.0 (current
  data drifts rod 3.0 / discs 2.0 on walls). ~110 lines + all hand-written quaternion+animation
  blocks removed.
- **Grindstone** — add `animation: none` to idle `base_slab`; both active states →
  `copy_from: idle` + state-level rotate at 3.0. (No longer hand-written.)
- **Windmills — BOTH `large_windmill` and `huge_windmill`** — strip inline per-blade
  `animation:`; add one state-level `animation: { type: rotate, axis: <state axis>, speed: 3.0 }`
  per state (floor `[0,1,0]`, N/S `[0,0,1]`, E/W `[1,0,0]`). Normalizes 1.5 → 3.0.
- **Clutch** — untouched (empty states, no display entities).

> Deliberate, user-requested **visual normalization** to 3.0 (gear discs + wall-rod, water wheel,
> grindstone, both windmills), not byte-for-byte preservation. Structure (transforms, textures,
> tags, wall offsets, which entities animate) preserved exactly.

### Verification
1. `gradle compileJava -q` — clean.
2. **Materialization check (no server)** — throwaway main loads `rotation-blocks.yml`; for each
   active/windmill state assert: each entity's item-or-blockData/transform/tag/wallOffset matches
   its source (idle or inline) entity; `interpolationDuration == 2` (relies on the parse default
   of 2); a rotate animation on every entity that should spin and **null on `animation: none`
   entities** (grindstone base); the animation equals `Animations.rotate(axis, speed)` at sampled
   ticks; **both** item and block lists populated. Remove after.
3. **Negative tests** — `copy_from` unknown / self / chained / + own `display_entities` /
   + `no_display_entities` / empty-target, and `animation` with missing/zero axis or missing
   speed, each fail to load with a clear message (only that block skipped).
4. **Live-server smoke test** — place every rotation block in all orientations; power them;
   confirm uniform 3.0 spin, grindstone base stays still, both windmills' blades spin, particles
   play. **Generator**: its `default_state`/`placement_state_map` point at `spinning_*`, so it
   must spawn already-spinning (resolution reads the raw section, so sibling order is irrelevant).

### Out of scope (future, noted so the design doesn't preclude it)
- Inherit display_entities **and add** more → a future `additional_display_entities:` key (kept
  an error for now; the error message can hint at this).

---

## Phase 3 — Axis-orientation composition  (planned, deepest)

Define each part once in a canonical floor/Y frame; loader composes the per-axis
orientation rotation automatically, removing hand-computed quaternions like
`[98.42, -0.3574, 0.3574, 0.8629]`.

- Orientation rotation is cleanly composable (the YAML comment already documents
  `disc_b LR = compose(orientation_LR, R_Y(45°))`): pre-multiply the orientation rotation
  onto each part's local rotation.
- **Translations stay explicit per orientation** — wall variants deliberately pull parts
  to the wall (e.g. disc translation `1.0` vs floor `~0`), which is NOT a rigid rotation of
  the floor layout, so it can't be auto-derived.
- Needs careful live visual re-verification (every block × every orientation).

---

## Key files
- `src/main/resources/rotation-blocks.yml` — the data
- `src/main/java/anon/def9a2a4/corelib/BlockLoader.java` — YAML → CustomHeadBlock parser
- `src/main/java/anon/def9a2a4/corelib/DisplayAnimation.java` — animation interface + `Animations`
- `src/main/java/anon/def9a2a4/corelib/CustomBlockRegistry.java` — display spawn + per-tick ticker
- `src/main/java/anon/def9a2a4/corelib/RotationBlocks.java` — Java behavior overlay (network/redstone)
