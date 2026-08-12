# DefCoreLib roadmap

Re-grounded 2026-08-12 against current code + git (a 3-agent audit; the old version of this file was ~1 month stale and
listed as "planned/future" many things that had already shipped). This is the single source of truth — do not keep a
copy in agent memory. Line/commit refs are indicative; the code has moved well past most docs' cited lines.

## Complete (verified in code + git — do not re-open)
- **Core custom blocks:** data-driven CustomHeadBlock (states, redstone, scaling), display entities by tag, YAML block
  defs (BlockLoader), recipes (shaped/shapeless/stonecutter + head-input GUI), state transitions, light blocks,
  orientation-aware particles, chunk scan + self-healing, lifecycle callbacks, placement restrictions, commands, all
  event handlers.
- **Sounds** — place/break/interact SoundConfig + YAML + handlers (`f343882`).
- **Advancement-based recipe unlocking** — per-block unlock advancement, batched discover/undiscover (`f343882`).
- **Storage** — PDC-backed inventory: open/load/save/unload + drop-on-break.
- **Batched recipe reload** — shipped in HeadSmith (50 recipes/tick); defCoreLib itself synchronous by design (few recipes).
- **Animated display entities** — oscillation-param system (`DisplayAnimation`: rotate/bob/pulse/compose, `3b94141`).
- **DynLight integration** — carts AND moving mechanisms (`DynLightTags`, `040c038`).
- **Rotation-block elegance Phase 1** — named-texture `@alias` registry; plus `copy_from` state inheritance (`61b8e8a`).
- **Mechanism & glue track:** MechanismRegistry/BasicMechanism (displays + shulker colliders), doors, rotators
  (drawbridges), mechanism minecarts (A-block geometry, freeze fix, rename), rotation-power network (`RotationNetwork` +
  `RotationSolver`), gearboxes/casings/ratchets, **glue** (anchor-owned selection, sticky families, public external-anchor
  API), **hoist/crane** (`ChainHoistManager`, `HoistAnchor`), **inertial mass**, **mechanism persistence + entity-tag
  recovery**, **public live-rotation API** (`MechanismRotationSolvedEvent`).
- **Extendable pistons** — BUILT beyond the design-doc MVP (glued payload; spin-driven), `ExtendablePistonManager`.
- **Power-chain transmission** — `ChainPulley` + `RotationNetwork` distance-edge (closed-loop model; diverges from the
  doc's mutual-link `chain_wheel` design, same end effect).
- **Ship propulsion** — propellers (3 tiers), thruster, reaction wheel; banner-tier sail power.
- **BlockShips ↔ defCoreLib cutover** (2026-08): native→delegated migration, native-engine deletion, chunk-index removal,
  bare-shaft → `WAXED_COPPER_CHAIN` (paper-api bumped to 1.21.11; old CHAIN shafts migrate on chunk load).
- **Fluids** — package scaffold present (`corelib/fluid/`, 8 classes) — see "still actionable" for the unfinished part.
- **Module split** — bbanners / vslab / rsd / railbound / pipes / headsmith; vertical slabs; pipes migration.

## Still actionable

### Real gaps / correctness
- **WorldGuard protection-awareness — NOT STARTED.** No protection layer; every mechanism/glue/ship world-write bypasses
  region protection. Real grief gap. (`mechanism/protection-awareness.md`)
- **Asymmetric minecart curve-jam.** A turning cart doesn't rotate its payload — rotation is deliberately frozen
  (`BasicMechanism` turn logic); needs a cumulative-turn accumulator. Most user-visible mechanism bug.
  (`mechanism/deferred-from-glue-landing-round.md §2`)
- **Chain-pulley open items** — lock-gate phantom demand (correctness), reload strand duplication, wheel-spin direction
  cue. (`mechanism/chain-pulley.md`)

### Features (designed / partial)
- **Power-chains ski-lift** — cart-on-chain gondola (`ChainRideManager`) is DESIGN-ONLY; transmission half is done.
  (`mechanism/power-chains.md §E`)
- **Minecart trains** — coupling/chaining not built (`mechanism/minecarts-v2.md`).
- **Mixer** — not started, no code (`mechanism/mixer.md`).
- **Fluids** — package scaffolded; per-wave work needs audit/finish (`mechanism/fluids.md`).
- **Tech-tree remaining gates** — sieve/steam/etc.; foundation (machines/press/fan/drill + MachineRecipes) shipped
  (`mechanism/tech-tree.md`).

### Dev-ergonomics refactors (no user-facing change)
- **Rotation elegance Phase 3** — per-orientation quaternions still hand-computed in `rotation-blocks.yml`; loader should
  compose a canonical Y-frame across axes. NOT DONE.
- **Rotation elegance Phase 2 sugar** — `copy_from`+`animation` works, but spin axis is still hand-declared (no
  `{like/spin}` shorthand). PARTIAL.

### Small cleanups
- Remove dead `MechanismSerializer.onRecoveryComplete` seam (defined, called nowhere).
- C9: `RotationNetwork` size-cap is silent — add a warning (trivial).
- Cosmetic deferrals: mid-stroke display pop (§4), owned-vehicle destroy hole (§3), non-sticky hoist shear (§5).

### Intentionally deferred (design decision — leave unless revisited)
Reverser/clutch redstone while riding, chain-pulley edges aboard, water-wheel as a mobile source (windmills already are),
and unifying static `RotationNetwork` onto `RotationSolver` — all explicitly inert-on-mechanism by design
(`RotationSolver.java:19-39`).

## Housekeeping — stale docs to archive
These describe already-shipped work and can be archived/deleted: `chain-shaft.md` (approach abandoned — shafts use
stairs/copper), `minecarts.md`, `rotation-mechanisms.md`, `blockships-integration.md`, `persistence.md`,
`animations-mechanisms.md`, `display-system-refactor.md`, and top-level `TODO.md`.
`mechanism/rotation-and-mechanism-fixes.md` (the old "live list") is ~90% done — only C9/C10, F5, and the deferred-doc
items remain.

## Infrastructure
- Test on live server · Git push + CI · Two-JAR distribution (slim + bundled) for consumer plugins.
