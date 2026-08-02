# Mixer — automated flour + water → dough (vertical multiblock)

## Context

The flour→dough→bread chain has a mechanization gap. The millstone floods flour fast
(batch-8, 20-tick cycle), but the middle step — dough — is a **hand crafting-table craft**
(3 `mech:flour` + a water bucket, shapeless, in [custom-items.yml](../../../src/main/resources/custom-items.yml)),
and bread is a slow vanilla smelt. So the "pipeline" can't be automated end-to-end: the fast
mechanized front and the valuable furnace end are joined only by a bench.

The **mixer** closes that gap without removing the bench recipe. It is a vertical multiblock — a
spinning **mixer** block sitting on a custom **mixing cauldron** that holds water — which consumes
`mech:flour` (from the mixer's own storage) + water (from the cauldron below) and produces
`mech:dough`. The bench craft stays as the hand path; the mixer is the *automated* path.

Two prerequisites drove the design:
1. Machine recipes match inputs **by vanilla `Material` only** today, so a custom flour input needs
   a small, general extension to the recipe engine (below).
2. Dough can't eject **down** the way every other processing machine does — the cauldron is there.

See [tech-tree.md](tech-tree.md) for the wider machine roadmap, [fluids.md](fluids.md) for the tank
subsystem this reuses, and [rotation-power.md](rotation-power.md) for the power network.

## Goal & geometry

Vertical stack, controller on top (modeled exactly on the steam stack, where the top
`steam_piston` walks `getRelative(DOWN)` to validate and drive the boiler/burner —
[`steamPistonTick`/`steamStatusReadout`, RotationBlocks.java:1257-1349](../../../src/main/java/anon/def9a2a4/corelib/RotationBlocks.java#L1257)):

```
  shaft            (drives the mixer — axis Y, exactly like millstone/press/sieve)
  mech:mixer       (CONSUMER + controller: validates cauldron, holds flour, mixes, ejects dough)
  mech:mixing_cauldron   (bare water tank: bucket- or pipe-fillable, drained by the mixer)
```

A Y-axis consumer directly under a top shaft is the canonical case (`mechanism: sieve {kind: consumer, axis: y}`,
[rotation-config.yml:155-158](../../../src/main/resources/rotation-config.yml#L155)); the cauldron below is a
non-rotation node and is simply ignored by the network graph, so no power passes "through" it.

## Enabling change — custom-item recipe inputs (make it extensible)

The shared recipe engine keys inputs on `Material`: `Recipe(Material input, …)` and
`match(Material)` ([MachineRecipes.java:42,103](../../../src/main/java/anon/def9a2a4/corelib/MachineRecipes.java#L42)),
and `processingEffect` matches/counts/consumes by `it.getType()`
([RotationBlocks.java:1652](../../../src/main/java/anon/def9a2a4/corelib/RotationBlocks.java#L1652)). `mech:flour`
is a custom `SUGAR`-based item, so it can only be an **output** today — a recipe keyed on `SUGAR`
would (wrongly) also mix raw sugar.

Extend the engine generically so **any** custom item can be an input:

- Give `Recipe`'s input the same material-or-custom duality `Output` already has
  ([line 41](../../../src/main/java/anon/def9a2a4/corelib/MachineRecipes.java#L41)) — add
  `@Nullable String inputCustomId`, `Material input` nullable for custom recipes.
- In `load()`, parse a `:`-bearing `input:` as a custom id (the exact trick `parseOutput` uses at
  [line 91](../../../src/main/java/anon/def9a2a4/corelib/MachineRecipes.java#L91)); keep a
  `Map<String,Recipe> byCustomInput` beside `byInput`.
- Add `@Nullable Recipe match(ItemStack)` that resolves the stack's id with the existing static
  helper **`CustomBlockRegistry.getItemTypeId(stack)`**
  ([line 345](../../../src/main/java/anon/def9a2a4/corelib/CustomBlockRegistry.java#L345)): custom-id hit →
  custom recipe, else fall back to `byInput.get(stack.getType())`.
- In `processingEffect`, switch to `recipes.match(it)` and add identity-aware count/consume helpers
  (`countOfCustom` / `removeCustom`) that select by `getItemTypeId == inputCustomId` for custom
  recipes; the material path stays byte-for-byte the same.

This is purely additive — millstone/press/sieve (all material inputs) are unchanged.
**Regression to guard:** raw `SUGAR` must remain un-mixable; only `mech:flour` mixes.

## Custom cauldron block (`mech:mixing_cauldron`)

A bare-tank custom block — the first real user of the chunk-PDC cell tank path documented at
[FluidTanks.java:19-24](../../../src/main/java/anon/def9a2a4/corelib/fluid/FluidTanks.java#L19).

- `overlayMixingCauldron`: `FluidTanks.registerTank("mech:mixing_cauldron", FluidType.WATER, config.mixerCauldronUnits)`
  (capacity ~3 buckets).
- Fill two ways, both free of new plumbing: reuse `tankBucketInteract("Mixing Cauldron", b, event)`
  ([RotationBlocks.java:1374](../../../src/main/java/anon/def9a2a4/corelib/RotationBlocks.java#L1374)) for water-bucket
  right-click + empty-hand level readout; and any registered tank is automatically a
  `MachineTankEndpoint`, so liquid pipes and the pump fill it with no extra code.
- **Mandatory:** call `FluidTanks.clear(b)` in `.onBlockRemoved` — chunk-PDC cells need an explicit
  wipe (doc at [FluidTanks.java:79-80](../../../src/main/java/anon/def9a2a4/corelib/fluid/FluidTanks.java#L79); boiler
  example at [1197-1200](../../../src/main/java/anon/def9a2a4/corelib/RotationBlocks.java#L1197)).
- **Avoid `physical_material: CAULDRON`** so the vanilla `CauldronEndpoint` doesn't claim/convert it;
  use a neutral `base_block` with a cauldron-shaped display entity.

## Mixer block (`mech:mixer`) — controller + consumer

- Block def modeled on `sieve:` ([rotation-blocks.yml:2309-2349](../../../src/main/resources/rotation-blocks.yml#L2309)):
  `texture: "@machine_base"`, optional `@mixer_item` skin, `storage: { layout: HOPPER }` (flour in),
  `idle`/`spinning` states (paddle display rotating axis Y, ~speed 3), placement + shaped craft recipe.
- `overlayMixer` — a `ConsumerSpec` with axis `b → RotationNetwork.Axis.Y` and power from config
  ([overlaySieve template, 799-837](../../../src/main/java/anon/def9a2a4/corelib/RotationBlocks.java#L799)). Each tick:
  1. Re-validate the cauldron below via `isType(registry, below, "mech:mixing_cauldron")` (the steam
     stack re-checks `stackOk` every tick, [line 1301](../../../src/main/java/anon/def9a2a4/corelib/RotationBlocks.java#L1301)).
  2. If the cauldron is missing or `FluidTanks.units(below) <= 0` → idle (+ a dry cue like
     `sieveDryCue`).
  3. Mix via `processingEffect(...)` with the custom eject lambda below; flour comes from
     `registry.getOrCreateStorage(mixer)`, matched by the extended custom-input engine.
  4. Consume 1 water unit from the cauldron every `water-per-cycles` mixes:
     `FluidTanks.takeUnit(below)`, using a per-location counter like `sievePansSinceDrink`.
- Config:
  - `mechanism: mixer { kind: consumer, axis: y }`
  - `power: mixer: 6`
  - `mixer: { tick-interval: 30, max-batch: 1, water-per-cycles: 4 }` (between mill's 20 and sieve's
    40; ~1 bucket per 4 dough)
  - `RotationConfig` fields + parse block mirroring the sieve's
    ([RotationConfig.java:31-34,169-174](../../../src/main/java/anon/def9a2a4/corelib/RotationConfig.java#L31)).

## Dough output routing (DOWN is blocked)

`ejectOutputs` hard-wires the destination to `getRelative(BlockFace.DOWN)`
([RotationBlocks.java:1520-1551](../../../src/main/java/anon/def9a2a4/corelib/RotationBlocks.java#L1520)) — now the
cauldron. Generalize it to take a `BlockFace` (every existing caller passes `DOWN`, unchanged) and
have the mixer eject to a horizontal face; the `MachineEjectEvent` already carries any direction
([MachineEjectEvent.java:34,41-43](../../../src/main/java/anon/def9a2a4/corelib/MachineEjectEvent.java#L34)), and the
`DEFAULT` branch drops the dough if there's no container. (Alternative considered: deposit into the
mixer's own storage for hopper/pipe pull, suction-hopper style — rejected because it mixes flour-in
and dough-out in one inventory.)

## Recipe file + plugin wiring

- New `src/main/resources/mixer-recipes.yml`:
  ```yaml
  recipes:
    - { input: mech:flour, input_amount: 3, output: mech:dough, amount: 1 }
  ```
- Load in [CoreLibPlugin.java:129-156](../../../src/main/java/anon/def9a2a4/corelib/CoreLibPlugin.java#L129) (clone the
  sieve block), and thread `mixerRecipes` into **both** `RotationBlocks.register(...)` and
  `new MechanismRotationDriver(...)` so the mixer also works while riding a mechanism.
- Extend `RotationBlocks.register(...)` ([line 149](../../../src/main/java/anon/def9a2a4/corelib/RotationBlocks.java#L149))
  with `mixerRecipes`; call `overlayMixingCauldron(...)` and `overlayMixer(...)` near the sieve
  registration ([line 175](../../../src/main/java/anon/def9a2a4/corelib/RotationBlocks.java#L175)).

## Bench Crafter compatibility (separate ask — verify, no code unless it fails)

Vanilla now has the automated Crafter, and the bench dough recipe should work in it and leave the
empty bucket. Investigation shows this needs **no plugin code**:
- Dough is a real Bukkit `ShapelessRecipe` (registered in
  `CustomBlockRegistry.registerRecipesForType`), so it's Crafter-eligible; flour is matched by
  `RecipeChoice.ExactChoice`, the water bucket by plain material.
- "Keeps the bucket" is pure vanilla crafting-remainder (`WATER_BUCKET` → `BUCKET`) with no plugin
  involvement, so it does not depend on a player inventory and survives a Crafter.

Verify on a live server: (a) a Crafter fed 3 flour + 1 water bucket selects `mech_dough_from_flour`
and yields dough; (b) the empty bucket lands back in the Crafter grid slot and is pipe-removable;
(c) `recipesGatedOff("mech")` is false so the recipe registers at all
([CustomBlockRegistry.java:2147](../../../src/main/java/anon/def9a2a4/corelib/CustomBlockRegistry.java#L2147)).
**Contingency only if the Crafter won't select the `ExactChoice` recipe:** add a `CrafterCraftEvent`
shim (none exists today — the codebase has zero Crafter handling). The mixer is the primary
automation path regardless.

## Advancements, docs, changelog

- Advancements (mech datapack, `data/mech/advancement/`): `craft/mixer` (+ the cauldron, or fold it
  into the mixer craft node) and `machines/mixing` ("mix dough in a Mixer"), parented under the
  existing `machines/dough` chain. Wire grants in
  [MechAdvancements.java](../../../mech/src/main/java/anon/def9a2a4/mech/MechAdvancements.java) with the same
  `machineType` gating the sieve/millstone use
  ([262-269](../../../mech/src/main/java/anon/def9a2a4/mech/MechAdvancements.java#L262)) so a mixer payout only
  grants mixer nodes.
- Add Mixer + Mixing Cauldron entries to [docs/readmes/mech.md](../../readmes/mech.md) next to the
  Sieve; changelog entries per the per-plugin convention (machine content under `mech`).
- Optional: a mixer showcase build in [showcases.yml](../../../src/main/resources/showcases.yml).

## Balance

Keeps the current ratio: 3 flour → 1 dough, i.e. 1.5 wheat → 1 bread (unchanged from the bench
path). The automated path is kept fair vs. hand-crafting by its water cost (an ingredient, gated by
the cauldron buffer and pipe refill) and a slower 30-tick, batch-1 cycle — the mixer trades
throughput and a water supply for full automation.

## Implementation checklist

- [ ] Extend `MachineRecipes` for custom-item inputs + `match(ItemStack)`; identity-aware
      count/consume in `processingEffect`. Guard: raw `SUGAR` stays un-mixable; mill/press/sieve
      unaffected.
- [ ] `mech:mixing_cauldron` block + `overlayMixingCauldron` (tank register, bucket interact,
      `FluidTanks.clear` on removal) + capacity config.
- [ ] `mech:mixer` block + `overlayMixer` controller (cauldron validation, water metering, custom
      face eject) + mechanism/power/tick config + `RotationConfig` fields.
- [ ] Generalize `ejectOutputs` to a `BlockFace` param (existing callers pass `DOWN`).
- [ ] `mixer-recipes.yml` + load/thread in `CoreLibPlugin` → `RotationBlocks.register` +
      `MechanismRotationDriver`.
- [ ] Advancements + readme + changelog (+ optional showcase).
- [ ] Verify bench dough in a vanilla Crafter (bucket returns); contingency `CrafterCraftEvent` only
      if it fails.
