# Rotation Tech Tree — remaining roadmap

Standing design doc for the unbuilt parts of the rotation tech tree, grounded in an
infrastructure audit of the current codebase. Not a build order yet — it captures what's
left, what reuses existing code, what needs new subsystems, and the open decisions.

## Shipped foundation (reuse these)
- **Machines:** millstone (formerly grindstone), extractor press, fan, drill — all via the shared
  `overlayConsumerMachine(ConsumerSpec)` + `processingMachineTick` in `RotationBlocks.java`.
- **`MachineRecipes`** — generic loader; **already supports `chance` + multi-output + custom-item
  outputs** (so the sieve needs no new recipe code).
- **Custom-item layer** — `CustomHeadBlock` with `item_material` + `placeable: false`; potions
  (`PotionConfig`). Existing items: juices/seed_oil, bearing, wrench.
- **Storage system** — `StorageHolder` + `storage: { layout }` + PDC-persisted inventories
  (`CustomBlockRegistry` load/saveInventoryToPDC). Backs any machine inventory or the comparator
  fill-level trick.
- **Recipe registration** — shaped/shapeless/stonecutter from YAML; `RecipeChoice.ExactChoice` for
  custom-item ingredients (`choiceForBlock`); banner-tier result-swap precedent
  (`CoreLibPlugin.captureBannerIngredients`).
- **Sources** — `network.registerPassiveSource(...)` (windmills) and the engine's dynamic
  SOURCE↔TRANSMITTER toggle (`overlayEngine`).
- **World/scan precedents** — `drillTick` (target block in facing, break stages, drops, particles),
  `fanTick` (BoundingBox + `getNearbyEntities`), `FloodFill.component`, the `onBlockPlaced`
  callback, `hostContainer`/`readFacing`/`storeFacingIfAbsent`.
- **Catalog** — machine recipes per-machine + produced-by reverse index (`generate_catalog.py`,
  `docs/util/render.js`).

## New subsystems needed (the gates)
1. **Multiblock frame DETECTION** — none exists (showcases only *place* authored structures). Build
   via `onBlockPlaced` + `FloodFill`/neighbor scan. Gates the Alloy Furnace.
2. **Vanilla blast-furnace I/O** — read/write a `BLAST_FURNACE` tile inventory. Small, new.
3. **Storage-backed processing inventory** — reuse the Storage system as a machine's input/output
   (avoids a bespoke GUI framework). For the alloy furnace (2-input) + deployer/placer.
4. **Comparator / SU output** — none. Use the container-fill trick (fill a Storage inventory to
   N ∝ SU; a vanilla comparator reads it). For gauges/sensors.
5. **Deployer item-use** — no Bukkit "use item on block" API; per-item-type handlers
   (till/plant/bonemeal/shear), tool durability via `Damageable`.
6. **Alloy tools/armor** — extend the custom-item layer with enchantments + `ArmorTrim` (+ optional
   attribute components); recipes via ExactChoice.
7. **Fluid handling** — large, none. Gates freezer/pump. **Deferred.**

## Dependency-ordered waves

### Wave 1 — cheap (reuse press/source patterns, no new subsystem)
- **Sieve** (sifter + washer merged): `overlayConsumerMachine` + `sieve-recipes.yml` with
  chance/multi-output (gravel → flint / iron dust / **tin dust** …). Near-free.
- **Hand Crank** (engine-style transient SOURCE on right-click) + **Creative Motor** (constant
  SOURCE via passive/`onChunkLoad` addNode). Easy.
- **Sawmill** (container-processing machine, logs → 6 planks …) — simple form like the press; the
  stonecutter-look / multiblock is optional later polish.
- **Dusts → ingots:** dusts as custom-head items; tin from the sieve, iron/copper from millstone
  ore-grind recipes; dust → ingot via a vanilla FurnaceRecipe (ExactChoice). Ingots are reskins
  (the layer already supports it).

### Wave 2 — materials capstone (the one ambitious build)
- **Multiblock Alloy Furnace** → **Bronze** (copper + tin) & **Steel** (iron + coal). New: frame
  detection (tuff-brick shell + embedded vanilla blast furnace + **Bellows** on top) + vanilla
  furnace I/O + a Storage-backed 2-input processing loop (the bellows is the powered gate). Mostly
  *composes* FloodFill + Storage + a processing tick.
- **Alloy tools/armor** (layer extension: enchants + trims). Bronze ≈ slight upgrade; Steel ≈ above
  iron.

### Wave 3 — world automation (shared area-effect pattern from drill/fan scans)
- **Sprinkler** (area crop-growth boost), **Auto-Harvester** (break + replant mature crops),
  **Auto-Breeder** (feed/breed penned animals; only the fanTick entity-scan precedent exists).

### Wave 4 — utility / IO / sensors
- **Deployer** (use held item in front), **Block Placer** (inverse drill; vanilla blocks trivial,
  custom via `markBlock`), **Gauges** (SU → comparator via container-fill; reads `getNetworkStats`).

### Deferred
Freezer / Pump (need fluids) · tier-2 gears/shafts (skip for now) · Chains (network change; tracked
in TODO.md).

## Open decisions (unresolved)
- **Sequencing:** Wave 1 cheap wins first (recommended — sets up the dust → alloy economy) vs
  jumping to the alloy capstone vs world-automation vs utility/sensors.
- **Alloy furnace form:** full multiblock + Storage UI (matches the stated vision; needs detection)
  vs a single custom block (simpler, loses the build-it feel).
- **Fluids:** keep deferring (recommended) vs build the subsystem now (unlocks freezer/pump/smeltery
  fluids).

## Key file references
`RotationBlocks.java` (`overlayConsumerMachine`, `processingMachineTick`, `drillTick`, `fanTick`,
`overlayEngine` source toggle, `hostContainer`/`readFacing`) · `MachineRecipes.java` (chance /
multi-output `roll`) · `CustomBlockRegistry.java` (storage open/persist, recipe registration +
`choiceForBlock`) · `FloodFill.java` · `CoreLibPlugin.java` (`onBlockPlace`, banner tier-swap) ·
`RotationNetwork.java` (`registerPassiveSource`, `getNetworkStats`, `isPowered`) ·
`ShowcaseBuilder.java` (placement-only — NOT detection).
