# Fluids — liquid pipes, pump, water-gated sieve, steam engine

Standing design doc for the fluid subsystem, grounded in an audit of the pipes plugin and
rotation machinery. Un-defers the tech-tree's "Fluid handling" gate with a deliberately
small model: fluids move as discrete units along the *existing* pipe chains, driven by a
rotation-powered pump. Design only — build waves at the bottom; nothing here is implemented.

Locked decisions: fluid unit = **1 bucket** · pumping **consumes** source blocks (water and
lava alike) · steam engine stack supplies **20 power** (new top of the source ladder).

## Fluid model

- `FluidType { WATER, LAVA }` — extensible enum, corelib-owned.
- **Unit = 1 bucket.** All tanks, pump cycles, and cauldrons speak whole buckets.
- **No in-pipe storage.** A pipe chain is a route, not a tank — identical to item
  transport (instant end-to-end transfer, min-throughput accumulated along the walk,
  `PipeManager.findDestination`). Nothing per-pipe persists.
- **Sources are consumed**: draining a world source block sets it to air for +1 unit.
  Water pools that vanilla self-refills (2×2 infinite pools) remain the intended cheap
  water source; lava never refills and stays scarce.
- **Liquid physics must run on both ends**: drain sets the vacated cell to air WITH
  physics (`setType(AIR, true)`) so neighbouring liquid immediately flows back in;
  fill places the source block with physics so it spreads normally. (The infinite-pool
  refill loop depends on this.) No conflict with the casing stair-shape pinning — that
  protection is `UPDATE_KNOWN_SHAPE` on the casing's own placements, not a physics
  suppressor.
- Cauldrons hold exactly 1 unit when full. Partial water cauldrons (levels 1–2) are not
  drainable; filling always produces a full cauldron. Lava cauldrons are naturally
  all-or-nothing.

## Reuse map (verified file refs)

- **Pipe chain walk + endpoints** — `pipes/.../PipeManager.java` (`findDestination`
  ~:665, cached `CachedPath`, two-phase peek/commit transfer ~:508); endpoints resolve
  through `corelib/container/ContainerAdapterRegistry.java` (ordered adapters, locked-
  container guard, `register()` before vanilla fallback). The fluid system mirrors this
  wholesale.
- **Iron pipes already exist** — `pipes/src/main/resources/pipes.yml:126-169` (+ corner
  :302-346), art in `pipes/textures/`. `PipeVariant.java` is a 4-field record with no
  material/capability field — the natural home for a `fluids:` list.
- **Cauldron unit-drain precedent** — `CoreLibPlugin.onItemSpawnCauldron` (~:849-914):
  `Levelled` level check + decrement + splash effects.
- **Water sensing** — `RotationBlocks.flowSignal` (~:399): `world.getFluidData` +
  `Fluid.WATER/FLOWING_WATER`, chunk-load-guarded.
- **Fuel burning** — `EngineFuelManager.java`: in-memory tick counters, PDC persist on
  chunk unload (`mech:fuel_ticks`), `consumeOneFuelItem`/`fuelTicksFor` shared helpers.
- **Dynamic SOURCE toggle** — `overlayEngine` (`RotationBlocks.java` ~:454-503): the
  removeNode+addNode SOURCE↔TRANSMITTER discipline in lockstep with display state.
- **Vertical-stack "multiblock-lite"** — fixed-neighbor conventions (`hostContainer`
  behind, `ejectOutputs` below, `onNeighborChange` revalidation). No frame detection
  needed for the steam stack.
- **Plugin dependency direction** — pipes → corelib only. Two existing inversion
  patterns to choose from for the pump↔pipes handshake: registry injection
  (`MechanismConduits`, pipes registers its conduits into corelib) or events
  (`MachineEjectEvent` → pipes' `MachineEjectListener`).

## New subsystems

### 1. Fluid endpoints (corelib)

`FluidEndpointRegistry` mirroring `ContainerAdapterRegistry`: ordered adapters, first
`canHandle` match wins, locked-container guard. Interface:
`canProvide(block, fluid)` / `drain(block, fluid, n)` / `canAccept(block, fluid)` /
`fill(block, fluid, n)`. Three v1 adapters:

- **World source block** — drain consumes the source block (block → air); fill places a
  source block into the air cell at the chain end ("pumping out" into the world).
- **Cauldron** — full ↔ empty, 1 unit, water and lava.
- **Machine tank** — PDC int + fluid type on a machine head block (boiler water tank,
  sieve water tank); sizes in config. This is also the pipes-visible way to feed
  machines.

### 2. Liquid pump (`mech:pump`)

- Rotation **CONSUMER**, **power 4**, cycle ~40 ticks, **1 unit per cycle**.
- **Floor-placeable only, axis-aligned X/Z** — the chain-hoist placement pattern
  (`ChainHoistManager.resolvePlacementState`: placement yaw → `idle_x`/`idle_z`,
  `snapFloorRotation` for the skull, `healAxisState` on load). Like the hoist it is an
  **in-line consumer on its horizontal axle**: a shaft runs through it and power
  crosses to both ±axis neighbours.
- **Liquid output is the TOP** (the pipe chain starting above); **intake is the
  BOTTOM** (endpoint or downward pipe chain below — the pump sits over its liquid).
  Wrench reverses the flow (top becomes intake, bottom becomes output) — that is the
  "pump in / pump out" control.
- Handshake: pipes registers a fluid route walker into a corelib registry (preferred —
  the `MechanismConduits` pattern), so the pump asks "walk from face F, carrying fluid
  X, find the first accepting endpoint" without importing pipes code.
- Aboard moving mechanisms: mirror the capability into `MechanismConduits.Conduit`
  later (follow-up wave, not v1).

### 3. Lava gating (pipes)

- `PipeVariant` gains `fluids:` (default `[WATER]`); **iron + iron corner get
  `[WATER, LAVA]`** via pipes `config.yml`.
- The fluid route walk fails if any segment lacks the payload fluid → the pump idles.
  Same single-chokepoint shape as min-throughput accumulation.
- Item behavior of every pipe is untouched; a chain only carries fluid while a pump
  drives it.

### 4. Sieve water requirement

- `ConsumerSpec` gains an optional per-machine work-gate hook (the shared
  `processingMachineTick` stays untouched; the sieve passes a water gate).
- Sieve gets a **4-unit water tank**: pipe-fillable (machine-tank endpoint) or
  right-click with a water bucket (returns the empty bucket). Consumes **1 unit per
  ~16 pan cycles** (config). Dry → idles with a distinct cue (dry gravel sound, no
  grate spin).
- Lore + catalog notes updated; `machines/panning` advancement wiring unchanged.

### 5. Steam engine stack (burner → boiler → steam piston)

Vertical fixed-neighbor stack, validated at tick/neighbor-change — no multiblock
framework.

- **Burner** (`mech:burner`) — fuel HOPPER storage, `EngineFuelManager` reused
  verbatim, lit/unlit states, interact opens the fuel GUI. Standalone heat block
  (future alloy-furnace bellows/heat tie-in).
- **Boiler** (`mech:boiler`) — a **full block in the world: `base_block: GLASS`**,
  shell rendered with the casing/dynamo display placement verbatim
  (`translation: [0, 0.501, 0], scale: 2.006`; glass does not occlude, so the shell
  display stays lit — same reason casings ride a stair — but with a full-cube
  collider). Identity + tank counter live in chunk PDC via the display-backed
  bare-block registry (glass has no tile entity). **Floor-placeable only,
  axis-aligned X/Z** (hoist placement pattern). **8-unit water tank**, pipe-fillable +
  bucket interact. Runs only with a burning burner below; consumes **1 water unit per
  ~60 s** while running (config). **Flame particles at its base while running**.
- **Steam piston** (`mech:steam_piston`) — **floor-placeable only, axis-aligned X/Z,
  placed exactly like the chain hoist** (yaw stateResolver → `idle_x`/`idle_z`). The
  dynamic **SOURCE node: 20 power, in-line on its horizontal X/Z axle** — power exits
  along that axis to both sides, hoist-style (shafts continue the line at piston
  level). Engine-style removeNode+addNode toggle driven by the stack check (burner
  burning ∧ boiler has water ∧ stack intact). Running display: **press-style bob
  animation** on the piston-head entity (`animation: { type: bob, amplitude, period,
  phase }`) + steam particles.
- Recipes sketch: burner = blast-furnace core + copper; boiler = iron + bucket +
  bearing; steam piston = piston + bearing + iron. Advancements: `craft/` trio + a
  `structures/` capstone for first steam power.

## Config & balance

- `power:` additions — `pump: 4` (consumer), `steam_piston: 20` (source).
- Source ladder becomes: windmill 1 · water wheel 2 · large windmill 5 · engine 10 ·
  huge windmill 15 · **steam stack 20** (costs fuel AND water AND a 3-block build).
- New config knobs: pump cycle/rate, sieve tank size + water-per-N-cycles, boiler tank
  size + burn rate — `rotation-config.yml`; pipe `fluids:` capability — pipes
  `config.yml`.

## Build waves

- **Wave A — fluid core**: `FluidType`, `FluidEndpointRegistry` + 3 adapters, pump
  block, pipes `fluids:` capability + route-walker handshake, lava-on-iron
  enforcement. Independently testable: pump water cauldron → cauldron; copper pipe
  refuses lava, iron carries it.
- **Wave B — sieve water gate**: tank + bucket interact + config + dry cue.
- **Wave C — steam stack**: 3 blocks, fuel/water consumption, SOURCE toggle, displays,
  advancements.
- Per-wave verification: `make build` + test-server scenarios (cauldron→cauldron pump;
  lava refusal on copper; sieve drying out mid-run; steam stack assembly, water
  starvation, burner starvation).

## Open decisions (unresolved)

- **Pump-to-world griefing surface** — placing lava source blocks at a remote chain
  end: allow, config-gate, or restrict placement to loaded/same-chunk cells.
- **Steam stacking** — multiple steam stacks on one network add 20 each by default
  (every piston is a node); cap or embrace.
- **Fluid feedback** — dripping/flow particles along fluid-carrying pipes vs none.
- **Waterlogged blocks as sources** — v1 drains only true source blocks; waterlogged
  drain is a possible later adapter.
