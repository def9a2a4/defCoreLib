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

## Phase 1 — Named texture registry  ✅ DONE (one gap to close)

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

### ⚠️ Remaining fix — redstone textures not routed through resolveTexture
`parseRedstone()` (`BlockLoader.java:569-595`) reads `redstone.textures.<power>` (L581) and
`redstone.texture_ranges.<range>` (L591) as raw strings — these are genuine head textures
(consumed via `CustomHeadBlock.resolveRedstoneTexture` → `HeadUtil.createHead`), so an
`@alias` there would be passed literally → broken head, no error. **Impact is latent today**
(only `demo-blocks.yml` uses redstone textures, with literal base64), but the feature is
inconsistent.

- [ ] Add `Map<String,String> textures` param to `parseRedstone`; update its call site (~L199).
- [ ] **Rename the param to `textureAliases`** to avoid shadowing the existing local
      `Map<Integer,String> textures` at L579.
- [ ] Wrap both reads: `resolveTexture(texSec.getString(key), textureAliases)` (L581) and
      `resolveTexture(rangeSec.getString(key), textureAliases)` (L591).
- [ ] Re-run `gradle compileJava`.

### Live-server verification (pending — needs a running server)
- [ ] Deploy jar; `/corelib give` each rotation block; place in all orientations
      (floor + N/S/E/W walls); confirm textures render identical to before.
- [ ] Confirm spinning/animation still works.
- [ ] Negative test: set a `texture:` to `@does_not_exist`, reload, confirm the
      "Unknown texture alias" warning + block skip; revert.

---

## Phase 2 — Auto-derived spin states  (planned)

Every active (`spinning_*` / `running_*`) state is its idle state + a rotate animation
around the state's axis. Let a state inherit another state's display entities and add spin:

```yaml
spinning_y: { like: idle_y, spin: 3.0 }   # inherit idle_y displays + rotate around Y @ 3.0
```

- Loader resolves `like:` target's display entities, clones them, attaches a rotate
  animation around the declared axis at `spin:` speed (+ `interpolation`). Particles stay
  as their own field (engine/generator declare particles inline on the active state).
- Axis derivable from the state-name suffix (`_x/_y/_z`, already parsed by
  `RotationNetwork.axisFromState`) or an explicit `axis:` field.
- Eliminates every `spinning_*` duplicate (~half the remaining file) and all hand-written
  animation axes.

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
