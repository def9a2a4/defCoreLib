# Docs review findings (3 agents: generator/build, frontend, Java exporter)

> Reviewed the docs pipeline. Note the user has since reworked the exporter layout into quadrants
> (demo / rotation / slabs / windmill) — layout findings are superseded; correctness findings stand.

## Blockers
1. **Committed docs are currently dead.** `docs/data/items.json` has **0 `placedVariants`**;
   `docs/data/display-spec.json` is missing (the good 13.6 MB export sits unused in
   `.temp/display-spec.json`). `make docs-fast` / `uv run` **silently zero `placedVariants`** when the
   spec is absent (`generate_catalog.py` merge). Fix: commit `display-spec.json` as source-of-truth
   input (like `extras.yml`); make the merge **preserve/fail-loud** instead of zeroing; assert
   `items.json` ends with non-empty placed data when the spec is non-empty.
2. **Model-origin mismatch (needs in-game check).** Web meshes are **center-origin**
   (`blockmodel.js` `−0.5`, `skullMesh` `−0.25` seat) but `de.matrix` is the raw read-back
   `Display.getTransformation()` in MC's **corner-origin** space → half-block offset + wrong rotation
   pivot for placed displays. Verify against the in-game grid; fix by building meshes corner-origin
   **or** inserting a `+0.5` compensation between the `position` parent and the mesh.

## Major
3. **WebGL leak on variant switch** (`placed3d.js` teardown only `renderer.dispose()`): also need
   `controls.dispose()`, `renderer.forceContextLoss()`, `domElement.remove()`, dispose
   geometries/materials; mind the shared `texCache` (don't dispose shared textures). Risks the
   ~16-context limit when toggling variants.
4. **Stale/incomplete `models-manifest.json`** → banner & waxed-copper slabs render as grey
   `fallbackBox`. Always (re)write the manifest (incl. `--no-assets`); add a **`waxed_*`→unwaxed**
   model alias; ensure the bundled `white_banner` model is actually emitted. (Re-running `make docs`
   refreshes most of this.)
5. **Animation gaps (exporter):** `animatedSiblingOr` only maps `idle→spinning`, so **`running_*`
   engines never animate**; period auto-detect false-loops at `t=1` for very slow / `speed:0`
   rotates (bakes a static frame); "idle" variant is misleading for always-animated blocks (emit it
   only when a genuinely static state exists). Animation base for `displayTransformResolver` blocks
   should bake from `cfg.transform()` not the read-back matrix (latent — only Pipes uses resolvers).

## Minor
6. Dead code in `generate_catalog.py`: delete `placed_display_entities`, `state_display_entities`,
   `parse_display_entity`, `parse_transform`, `parse_animation` (keep `canonical_block`,
   `block_id_from_ref`, `summarize_trigger`).
7. `download()` not atomic (truncated PNG poisons cache) → `.tmp`+`os.replace`. Wrap bundled-model
   `json.loads` in try/except. Top/`double` slab states strip to bottom-half (latent).
8. Frontend: cap `setPixelRatio` at 2; `|| []` guards on `lore`/`recipes`; unify missing-skin
   failure UX; consolidate duplicate skin loaders (`head-icon.js` vs `head3d.js`); `period ||
   frames.length` fallback has wrong units (latent). `blockmodel.js` ignores element/face `rotation`
   (no current model needs it).
9. Exporter: gate on `ServerLoadEvent.LoadType.STARTUP`; suffix→config keying can collide for
   multiple null-suffix displays; windmill blades export the default item (no blade PDC seeded).

## Fix plan (careful)

Order: **A** (restores the committed docs) → **B** (verify against the in-game grid) → **C** → **D** → **E**.
A/C/D/E are independent; only B needs visual confirmation. Current exporter is the user's quadrant
layout (`exportType`/`buildVariant`/`animatedSiblingOr`/`hasAnimation` unchanged — D applies cleanly).

**Scope (state this in the docs/UI, not a bug):** the 3D placed view renders ONE state's *geometry*
only — light, particles, glow, and the other states (lit candle, redstone power 0–15, alarm `panic`)
are not in 3D; they live in the 2D "states" section. Decide+document which state the placed view
picks for `redstone`-`LEVEL` blocks (e.g. the default/unpowered texture).

### A. Pipeline integrity — `scripts/generate_catalog.py` (+ commit)  [revised per critique]
- **A1 commit the spec + fail-loud** (no preserve-merge — it masks renamed/emptied exports and is
  dead once the spec is committed). `display-spec.json` is a committed source input (like
  `extras.yml`). In `main`: spec **present** → merge by `fullId`, print `matched X/len(spec)`, **warn**
  on spec keys with no item; spec **absent** → `sys.exit(1)` ("run `make docs`"). Never silently zero.
  Also check **completeness**: warn/fail when a YAML block that *should* have placed data has **no spec
  entry** (a partial/stale export that silently dropped, e.g., all rotation blocks, otherwise passes
  `matched>0`). Cheapest signal: per-namespace `expected vs matched` counts.
- **A2 DROP** ("manifest from disk, always" was a blocker): `docs/assets/` is gitignored, so on a
  fresh clone / `--no-assets` every `.exists()` is false → it would clobber the committed manifest to
  all-`false`. `vendor_models` already writes a correct manifest from what it vendored (incl. the
  bundled-banner fallback); the stale committed manifest is fixed by **re-running `make docs`**. Leave
  `--no-assets` NOT touching the manifest.
- **A3 DROP**: vanilla *does* ship `block/waxed_*_slab.json` (they resolve to elements) — the grey
  waxed slabs were the A2 staleness, not a flatten miss.
- **A4 atomic `download()`**: `dest+".tmp"` then `os.replace`.
- **A5**: wrap the bundled-model `json.loads` in try/except → warn + `manifest[id]=False`.
- **A6 delete dead code**: `placed_display_entities`, `state_display_entities`,
  `parse_display_entity`, `parse_transform`, `parse_animation` — **`canonical_block` sits inside that
  range; preserve it** (and `block_id_from_ref`, `summarize_trigger`, `parse_ingredient`).
- **A7 commit** after the user's full `make docs`: `docs/data/{display-spec.json,items.json,
  models-manifest.json}` + `scripts/models/white_banner.json`.

### B. Model origin — `docs/util/blockmodel.js`, `docs/util/placed3d.js`  [verified; +fallbackBox]
- **Confirmed by YAML** (not assumed): `block_display_test` BlockDisplay (diamond, `scale 0.5`,
  `translation [0,0.5,0]` — only +Y) ⇒ **BlockDisplay = corner-origin [0,1]³**; millstone ItemDisplays
  (`SMOOTH_STONE_SLAB`, `[0,0,0]`/`[0,0.5,0]`, stacked) ⇒ **ItemDisplay = centered**. `buildElement`
  subtracts a **constant** 0.5 = a uniform model-box shift (the critique's per-element worry is unfounded).
- **B1** `buildBlockMesh(id, { centered = true })` → in `buildElement`, subtract 0.5 only when
  `centered` (`centered:false` ⇒ corner-origin).
- **B2** `placed3d.buildDisplayObject`: `kind:"block"` → `centered:false`; `kind:"item"` → centered.
- **B3** make `fallbackBox` honor the block origin (offset for `kind:"block"`) so missing-model block
  displays aren't half a block off.
- **B4 heads — not yet handled.** `skullMesh` only floor-seats (y −0.5..0); two head cases are wrong:
  (a) **head display entities** (drill rod, gear disc, crystal — ItemDisplays of `PLAYER_HEAD`,
  `kind:"head"`) need the same centered-vs-corner reckoning as `kind:"item"` (an ItemDisplay of a
  head likely centers its model), or rods/discs sit offset; (b) **wall-mounted base heads**
  (`PLAYER_WALL_HEAD`) protrude/center on the wall — wall-variant base heads render wrong with a
  floor seat. Add per-facing skull orientation/seat, and pin the seat from a head's read-back matrix,
  not by eye.
- **B5 banner scale unverified.** The bundled `white_banner` geometry/UVs were authored from
  `ModelBanner` dims, **not** matched to the windmill-blade ItemDisplay transform (an ItemDisplay of a
  banner applies an item-model transform the read-back `matrix` doesn't include). Tune the model
  scale/orientation against the grid (windmill quadrant).
- **B6 verify** on the grid: `block_display_test` sits in the lower `+x+z` corner lifted +0.5Y;
  millstone = two flush-stacked slabs; drill rod / gear disc / crystal align; wall drill's base head
  sits on the wall; windmill blades read as banners at the right size.

### C. WebGL lifecycle — `placed3d.js`, `head3d.js`, `item.js`
- **C1** teardown for `renderPlaced` + `render3DHead` (keep `alive=false` **first**):
  `controls.dispose(); scene.traverse(o=>{o.geometry?.dispose(); const m=o.material;
  Array.isArray(m)?m.forEach(x=>x?.dispose()):m?.dispose();}); renderer.dispose();
  renderer.forceContextLoss(); renderer.domElement.remove();` (do **not** dispose cached textures).
- **C2** `setPixelRatio(Math.min(devicePixelRatio,2))` **before** `setSize` (current order is reversed).
- **C3** wire the in-hand teardown — `addViewer` discards the `render3DHead` teardown, so in-hand
  viewers leak a context per detail-page navigation; track + call returned teardowns.

### D. Animation enumeration — `DisplayExporter.java`  [revised: generator + real period bug]
- **D1 static↔animated pair, not a one-way rename.** Per placement state `P`, resolve BOTH by axis
  suffix (strip/swap `idle_`/`spinning_`/`running_`): `staticState` = the `idle*` sibling (or `P` if
  `!hasAnimation(P)`, else null); `animState` = the `spinning*`/`running*` sibling (or `P` if
  `hasAnimation(P)`, else null). Fixes **generator** (placement=`spinning_y` but `idle_y` exists) and
  **engine** (`running_*`); always-animated blocks get `staticState=null`.
- **D2 emit**: `staticState!=null` → idle variant (`animate=false`); `animState!=null` → spinning
  (`animate=true`) + reversed. ⇒ always-animated → spin/reversed only; shaft → idle/spin/reversed;
  non-animated → one static variant.
- **D3 fix the REAL period bug** (not t=2): matrix-equality aliases to **half** period for `bob`/`pulse`
  (sin=0 at period/2). Use a **two-point** check — precompute `f1=apply(base,1)`; accept `period=t`
  only when `approxEqual(apply(base,t),f0)` **and** `approxEqual(apply(base,t+1),f1)`. (No `speed:0`
  rotates exist; the old t=1 concern was moot.) Also ensure baked `frames[0]` equals the static
  read-back `matrix` (it's the `t=0` sample; `Math.round` + `reversed` can drift it → a one-frame
  seam jump) — assert/verify.
- **D4** gate `onServerLoad` on `ServerLoadEvent.LoadType.STARTUP` **before** `done=true`.

### E. Guards — `docs/util/render.js` + `docs/util/catalog.js`
- Guard `recipes`/`lore` at `render.js:64,135` **and `catalog.js`** — `recipesHtml` runs per catalog
  card, so a missing `recipes` throws and kills the whole grid (highest blast radius). `placed3d.js`
  is already fully guarded.

### Deferred (latent)
`displayTransformResolver` animation base (Pipes only), windmill blade PDC, top/`double` slab
states, null-suffix display collisions, duplicate skin loaders, `pulsing_orb` `fast` state unexported.

### Verification
1. `uv run scripts/generate_catalog.py` with the committed `display-spec.json` → `items.json` keeps
   placedVariants; absent spec → hard error (not silent zero); dead-code removal leaves no broken refs.
2. User `make docs` → `display-spec.json` copied + committed; banner renders as a banner; waxed slabs
   textured (not grey).
3. Serve → block displays aligned (B4), generator/engine show idle+spinning+reversed, no fake idle on
   crystal/orb, bob/pulse loop at full period; toggle variants ~20× → no WebGL-context warning.
4. `make docs KEEP_ALIVE=1` → eyeball the grid vs the docs.

---

# Plan: ground-truth display export via headless read-back (round 5)

## Context

The docs' 3D "placed" view must match in-game exactly. You chose the **headless server read-back**
approach: build the plugin, boot a headless Paper world, place each block, and read back the
**actual spawned Display entities** — so transforms, `wall_offset`, the base head, and (later)
runtime resolvers are ground-truth, never re-derived. One command regenerates everything; new blocks
are picked up automatically; it extends to resolver/world-dependent plugins (Pipes) later.

Two concerns you raised, now resolved:
- **Powering / spinning states:** `CustomBlockRegistry.applyConfig(block, type, state, power)` is
  **public** (`CustomBlockRegistry.java:515`) and spawns the displays for *any* named state directly.
  So the exporter asks for `spinning_*`/`running_*` states by name — **no redstone, no
  orientation-dependent power block**.
- **Which entity is which:** `DisplayUtil.findByTag(loc, blockTagPrefix(ns,id,loc), r)`
  (`DisplayUtil.java:79`) returns exactly the displays for that block — no ambiguity.
- **Animations (snapshot won't do):** each display's `DisplayEntityConfig.animation()` is the
  real `DisplayAnimation` lambda; the exporter **bakes a keyframe track** by sampling
  `animation.apply(base, tick)` over the animation's period. The browser just plays the track back
  (no JS animation math, no drift). A small structured `AnimationSpec` is added so the exporter
  knows the loop period/type; the lambda itself does the sampling.

## A. Plugin

> **⚠️ Historical design below — the `AnimationSpec` refactor was NOT built.** The implemented
> exporter samples the existing `DisplayAnimation` lambda and **auto-detects** the loop period (see
> "Implementation status" + fix-plan D3). Do not implement `AnimationSpec`/`Animations.fromSpec`;
> the period bug is fixed in D3 instead.

- ~~**`AnimationSpec`** record `{type, axis, speed, amplitude, period, minScale, maxScale, radius,
  layers[]}`: `BlockLoader.parseAnimation` (`BlockLoader.java:641-678`) builds it; `Animations.fromSpec`
  builds the runtime lambda (today's behavior preserved). `DisplayEntityConfig`/
  `BlockDisplayEntityConfig` carry the spec next to the lambda.~~ (Superseded — auto-detect.)
- **`DisplayExporter`** on `ServerLoadEvent` (after all plugins enable), gated by
  `-Ddefcorelib.export=<path>`, in a flat/void world with the spawn chunk preloaded. For each
  `registry.allTypes()`:
  - Variants = floor/default (if allowed) + one per allowed wall facing
    (`PlacementConfig.allowedFaces` / `placementStateMap()`); for each facing the placement state,
    plus its animated counterpart state(s) so a spin toggle works.
  - Per variant+state: place `PLAYER_HEAD`/`PLAYER_WALL_HEAD` (+facing) at a scratch loc,
    `markBlock`, `applyConfig(block, type, state, 0)`, then `findByTag` the spawned displays.
  - Per display, emit `{ kind: head|item|block, ref: textureUrl|material|blockdata,
    position:[x,y,z] (entityLoc − blockCenter, captures wall_offset), matrix:[16]
    (getTransformationMatrix), animation: bakedTrack|null, tag }`. Animated displays bake a matrix
    track from the config lambda over the spec's period.
  - Base head: resolved `texture` per variant (rendered as a skull by the frontend); omit when the
    block uses `item_material` (slab placeholder is invisible).
  - Clear scratch entities between iterations. Serialize `display-spec.json`; `Bukkit.shutdown()`.

## B. One command (`make docs`)
`gradle shadowJar` → copy jar (and any dependent plugin jars present) into `server/plugins/` →
boot Paper headless with `-Ddefcorelib.export=.temp/display-spec.json` (unique/`server-port=0`,
void world) → **hard-fail if the JSON is missing/empty**, with a timeout to kill hangs and an
atomic temp+rename write → `uv run scripts/generate_catalog.py` consumes `display-spec.json` for the
placed/variant data and keeps doing recipes + texture/model vendoring → `items.json`.

## B2. Keep-alive inspect mode — make it joinable (export already works)
The export runs and writes all 97 types; the blocker is joining the keep-alive test-server:
- `test-server/server.properties`: `enforce-secure-profile=false` (offline clients are otherwise
  rejected), `gamemode=creative`, `allow-flight=true`. Also have `make docs` write a baseline
  `server.properties` when `test-server/` is first created so a fresh clone is joinable too.
- `DisplayExporter` keep-alive: set the world spawn to the grid (`world.setSpawnLocation(...)` near
  the first cell, a couple blocks up) and make it viewable (`time set day`, clear weather,
  `doDaylightCycle`/`doWeatherCycle` off) so the player spawns *at* the blocks in daylight — no
  `/tp`/op needed (which offline players don't have).
- Join `localhost:25575`; client must be Minecraft **1.21.11** to match the server.

## C. Front-end
- **`placed3d.js`:** consume `placedVariants`; set each display's base `matrix[16]` directly, parent
  it by `position` (so the offset is outside any rotation); **play the baked keyframe track**
  (decompose → lerp position/scale, slerp quaternion) instead of analytic animation. Drop the JS
  animation formulas.
- **`head3d.js`:** render the base head as the vanilla **skull** model (8×8×8 half-block, floor-seated
  / wall-mounted per facing), not a full cube.
- **Banner:** bundled `white_banner` `elements` model (from canonical `ModelBanner` dims: flag
  20×40×1, pole 2×42×2, crossbar 20×2×2; UVs on `entity/banner_base.png` flag `(0,0)`/pole `(44,0)`/
  crossbar `(0,42)`) rendered by the existing `blockmodel.js`; generator falls back to it for
  `builtin/entity` materials and vendors the texture.
- **Variant toggle (`item.js` + `catalog.css`):** selector (Floor / Wall N·E·S·W) + animate toggle;
  default to the animated state so motion shows.

## Reliability (from the critiques)
- `ServerLoadEvent` (dependents registered); void/flat world + preloaded spawn chunk so
  `world.spawn` works; `server-port=0`/unique to avoid the port-bind crash already in `server/`;
  atomic JSON write; `make docs` hard-fails on missing/empty output; hard timeout kills a hung boot.
- Exporter is dead code unless `-Ddefcorelib.export` is set (acceptable in-jar for now; could move to
  a dev source set later). Document the paper-api (1.21.8) vs server jar (1.21.11, gitignored)
  pinning so a fresh clone knows it needs the jar.

## Files
- **Add (Java):** `DisplayExporter.java` (+ `ServerLoadEvent` listener in `CoreLibPlugin`).
  *(No `AnimationSpec`/`Animations.fromSpec` — superseded by auto-detect.)*
- **Modify (Java):** `CoreLibPlugin.java` (hook). *(No `BlockLoader`/`CustomHeadBlock` changes —
  the exporter reads the existing config + lambda.)*
- **Modify:** `Makefile` (`docs` target), `scripts/generate_catalog.py` (consume `display-spec.json`;
  keep recipes/vendoring; `builtin/entity` banner fallback), `docs/util/placed3d.js`,
  `docs/util/head3d.js`, `docs/util/item.js`, `docs/util/catalog.css`, `docs/README.md`.
- **Add:** `scripts/models/white_banner.json`. **Generated:** `docs/data/display-spec.json`,
  `docs/data/items.json`.

## Reuse
- `CustomBlockRegistry.applyConfig` / `getRegistry` / `getSkullFacing`; `DisplayUtil.findByTag` +
  tag helpers; `HeadUtil.parseTexture` (profile base64 → URL); round-3 renderers
  (`head3d.js`, `blockmodel.js`, `placed3d.js`, the `item.js` viewer scaffolding).

## Implementation status (round 5)
- **Done:** `DisplayExporter.java` (grid placement via `applyConfig`, read-back, auto-detected
  keyframe baking — no AnimationSpec refactor needed) + `CoreLibPlugin` hook; plugin compiles.
  `Makefile` single `docs` target (headless on port 25575; `KEEP_ALIVE=1` → grid + keep-alive for
  in-game inspection) + `docs-fast`; separate `test-server/` (gitignored, won't touch playtest
  `server/`). `generate_catalog.py` consumes `docs/data/display-spec.json` → `placedVariants`,
  with the `builtin/entity` banner fallback; `scripts/models/white_banner.json` authored.
- **Frontend done:** `placed3d.js` (keyframe playback + position parent), `head3d.js` (`skullMesh`
  half-block), `item.js` + `catalog.css` (variant `<select>`), README updated.

## Round-5 follow-up: in-game grid layout + idle/spinning/reversed variants
In-game inspection feedback — all changes in `DisplayExporter.java` (frontend already renders
whatever variants/frames the export emits):
1. **y = -58** (superflat surface), not y=100.
2. **Spacing:** lay blocks much further apart (no display overlap); a block's own variants stay close.
3. **Sections by namespace:** one section per part — **demo**, then **rotation**, then
   **verticalslabs LAST** — each a compact 2D grid of block-clusters separated by a gap (+ logged
   section marker).
4. **idle / spinning / reversed:** currently only the spinning state is emitted. Per placedOn facing
   emit **idle** (placement state, no animation) + **spinning** (animated sibling, +t) + **reversed**
   (same displays, animation baked with **negated ticks** — matches `CustomBlockRegistry.java:844`
   `if (reversed) tickAge = -tickAge`). Non-animated blocks emit just the placement variant(s).
   Labels: `Floor · spinning`, `Wall N · reversed`, etc. `bake(anim, base, reversed)` negates t.
5. **States together:** a block's variants cluster adjacently in the grid (and already share one
   dropdown in the docs).

## Round-5 follow-up v2: quadrant layout + wall-orientation bug
From the next round of in-game inspection. All in `DisplayExporter.java` except where noted:

1. **Only floor variants show (BUG).** Blocks like shaft/gear/clutch/engine define orientations via
   `placement_state_map` (DOWN/N/S/E/W → idle_y/idle_z/idle_x) but have **no** `allowed_faces`, so
   `placedOnFaces` falls back to `[DOWN]`. **Fix:** enumerate `allowedFaces ∪ placementStateMap
   .keySet()` (→ `[DOWN]` only if both empty). Now wall gears/shafts/windmills appear.
2. **y = -60** (two lower).
3. **Quadrant layout** (replaces the linear sections): four quadrants around the origin, each a
   grid, offset off the center axes so they don't collide:
   - **demo** → (+x, +z), **rotation** (non-windmill) → (−x, +z), **verticalslabs** → (+x, −z),
   - **windmills** → (−x, −z) — the "large display quadrant" for big/multi-block assemblies (future).
   Block-per-row within a quadrant (a block's variants along the quadrant's x; blocks step along z).
4. **Spacing:** everything **4 blocks apart**; the **windmill** quadrant **12 blocks apart**.
   Windmill detection: `rotation` namespace + id contains `windmill`.
5. **World gamerules** (keep-alive `setupViewing` + Makefile baseline `server.properties`): disable
   **structure generation** (`generate-structures=false`), **weather** (`DO_WEATHER_CYCLE=false`,
   clear), and **day/night** (`DO_DAYLIGHT_CYCLE=false`, set day) — plus the already-set peaceful /
   no-mob-spawning / creative / flight / offline / spawn-at-grid.

## Round-5 follow-up v3: CW / CCW / stopped per facing (in-game)
Each facing should show 3 cells — **stopped / CW / CCW**. Observed: the generator's 3 all spin the
same; drill/fan's 3 don't spin. Root cause (verified): drill/fan are rotation-network **consumers**,
and `RotationNetwork.updateBlockState` (RotationNetwork.java:593-626) resets unpowered consumers to
`idle_*` on recalc (fires when the player loads chunks); generators are **sources** (skipped) so they
keep spinning — but all 3 use the same spinning state. Fix in `DisplayExporter.java`, no power network:

1. **Idle↔spin pair from the suffix:** from the placement state strip the `idle_`/`spinning_` prefix
   to get the suffix; **stopped** = `idle_<suffix>`, **CW/CCW** = `spinning_<suffix>` (fallback when a
   name is missing). Fixes the generator (placement state is `spinning_y`, so stopped must use `idle_y`).
2. **Skip `markBlock`** for inspection blocks → they never join the rotation network → `updateBlockState`
   can't reset them, so forced `spinning_*` persists in-game; `applyConfig` still spawns + animates
   without PDC (fixes drill/fan not spinning).
3. **`registry.setAnimationDirection(LocationKey.of(block), SpinDirection.CCW)`** on the reversed cell
   (CustomBlockRegistry.java:851) → it spins backwards in-game; no effect on the JSON (read at tick 0,
   baked ±t separately).
4. **Force-load** each cell's chunk in keep-alive so animations don't freeze off-screen.
JSON baking is unchanged (stopped → no track, CW → +t, CCW → −t).

## Verification
1. `make docs` runs end-to-end as one command (build → headless export → vendor → `items.json`) and
   hard-fails if the export JSON is absent/empty.
2. Serve and check against in-game (ground-truth now): drill `wall_offset`, millstone two stacked
   smooth-stone slabs, windmill rotating white **banner** with correct pivot, water_wheel, slab
   per-facing variant toggle, spinning↔idle toggle, spinning_crystal / pulsing_orb animations.
3. Robustness: add a new config block → it appears with variants automatically, zero per-block code.
4. Deferred: resolver/world-dependent plugins (Pipes) need representative-neighbor placement — the
   same harness extends to them in a later phase.
