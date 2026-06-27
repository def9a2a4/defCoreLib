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

## Phase 2 — Auto-derived spin states (normalized speed)  (planned, detailed)

Every active (`spinning_*` / `running_*`) state is a byte-for-byte copy of its `idle_*` twin
plus an `animation: { type: rotate, axis, speed }` on (most of) its display entities; the
windmill repeats an identical `animation:` on all 4 blades of every orientation state. Phase 2
removes this with small YAML sugar, **auto-derives the rotation axis**, and **normalizes every
spin to a single configurable default speed (3.0)** (per decision — speeds had drifted: gear
discs 2.0, gear floor-rod 2.0 vs wall-rod 3.0, water wheel 1.25, grindstone 2.0, windmill 1.5,
rest 3.0).

Design refined by two adversarial critiques: normalized speed kills the per-entity-speed map
and its silent-missing-tag footgun; axis derivation is pinned to the existing last-underscore
convention; the interpolation default is locked.

### YAML sugar (handled in `BlockLoader`)
- **`spin_speed: <n>`** — optional top-level per-file default rotation speed, **default 3.0**.
  The single configurable knob.
- **`like: <stateName>`** — inherit a sibling state's `display_entities` verbatim
  (single-level, same-block).
- **`spin: true | <number>`** on a state — rotate all that state's display entities (inherited
  via `like` or listed inline) around the derived axis. `true` → use `spin_speed`; a number →
  that speed. Entities flagged `static: true` are skipped.
- **`static: true`** on a display entity — never rotates even when its state spins
  (grindstone's fixed base slab).
- **`spin_axis: [x,y,z]`** — explicit axis override. Default: derived from the state-name
  **last-underscore** suffix (`_x`→[1,0,0], `_z`→[0,0,1], `_y`/none→[0,1,0]), mirroring
  `RotationNetwork.axisFromState` (so `spinning_east_x`→X, `spinning_south_z`→Z, grindstone
  `spinning`→Y).

`like` only sets display entities; `particles`/`light`/`texture` overrides parse as today
(engine/generator keep inline `particles:`). `like` + own `display_entities` = load error.
Axis derivation only fires when `spin` is present (opt-in), so demo-blocks / minecart-ships
are unaffected — no naming footgun.

```yaml
spin_speed: 3.0          # top level
# shaft / gear / drill / engine / generator / water_wheel: collapse each active state
spinning_x: { like: idle_x, spin: true }                     # axis [1,0,0] from "_x"
running_y:  { like: idle_y, spin: true, particles: { … } }   # engine keeps particles
# grindstone: base stays static
idle:      { display_entities: [ {…, tag: base_slab, static: true}, {…, tag: top_slab} ] }
spinning:  { like: idle, spin: true }                        # only top_slab spins
# windmill: spin WITHOUT like — dedupes 4 inline animation blocks per state
floor:     { display_entities: [ …blades w/o animation… ], spin: true, spin_axis: [0,1,0] }
```

### Code changes — all in `BlockLoader.java` (no `CustomHeadBlock` change)
Loader **materializes** inherited+animated entities into the state's `DisplayEntityConfig`
list at parse time; runtime path (`resolveDisplayEntities`, ticker, `Animations.rotate`)
unchanged.
1. `load()` — read `spin_speed` (default 3.0); thread it + `textures` into `parseBlock`.
   *(Optional: bundle into a small `LoadContext` record instead of more bare params.)*
2. State loop — `parseStateOverrides(sb, stateSec, statesSec, stateName, textures, spinSpeed)`.
3. `parseStateOverrides` — resolve `like` (validate unknown/self/chain → throw); pick display
   maps (target's or own); parse; if `spin` is `true`/number, attach rotation via `applySpin`
   at `spinAxis(sec, stateName)`. Keep texture/light/particles parsing.
4. `applySpin(entities, maps, axis, speed)` — rebuild each immutable `DisplayEntityConfig`
   with `Animations.rotate(axis, speed)` unless `maps.get(i).get("static") == true`. **Index-
   based** (not tag-based) → no tag-keyed lookup, no missing-tag footgun.
5. `spinAxis(sec, stateName)` — `spin_axis` override else last-underscore suffix → unit vector;
   local helper, comment that it mirrors `RotationNetwork.axisFromState`.

### YAML changes — `rotation-blocks.yml`
- Add `spin_speed: 3.0`.
- Replace each `spinning_*` / `running_*` block with `{ like: <idle>, spin: true }`
  (+ `particles:` for engine/generator).
- Grindstone: mark idle `base_slab` `static: true`; `spinning: { like: idle, spin: true }`.
- Windmill: strip inline `animation:` from every blade in all 5 states; add `spin: true` +
  per-state `spin_axis` (floor `[0,1,0]`, N/S `[0,0,1]`, E/W `[1,0,0]`).
- ~120+ lines and every hand-written animation axis/speed removed. **Clutch** untouched.

> Deliberate, user-requested **visual normalization** (gear discs, gear floor-rod, water
> wheel, grindstone, windmill speed up to 3.0), not byte-for-byte preservation. Structure
> (transforms, textures, tags, wall offsets, which entities animate) preserved exactly.

### Verification
1. `gradle compileJava -q` — clean.
2. **Materialization check (no server)** — throwaway main loads `rotation-blocks.yml`; assert
   per state: item/transform/tag/wallOffset match the source idle (or inline) entity;
   `interpolationDuration == 2` everywhere (locks the default-2 dependence); non-null rotate
   on exactly the non-`static` entities; axis = expected derived (spot-check `spinning_east_x`
   →X, `spinning_south_z`→Z, grindstone→Y, windmill walls→explicit); speed 3.0 everywhere.
3. **Live-server smoke test** — place all rotation blocks in all orientations; power them;
   confirm uniform spin, grindstone base still, windmill blades spin, particles play.
4. **Negative tests** — `like` unknown / self / + `display_entities` / chained each fail to
   load with a clear message (only that block skipped).

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
