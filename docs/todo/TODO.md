# DefCoreLib TODO

# Main

- refine allowed blocks in doors/rotators
- mechanisms: storage blocks not persistent, no reload persistence, etc. will be mostly handled by blockships integration
- texture fixes for dril/screw, gears, shafts, others
- recipe book categories: most rotation stuff goes in redstone, vertical slabs go in building blocks
- chains -- long-distance connect two "chain wheels"
- migrate plugins:
  - redstone displays (should be trivial)
  - pipes
  - headsmith
  - blockships
  - yeetables?
  - ropes?
- separate rotation stuff into it's own plugin
- minecart stuff:
  - chaining minecarts
  - blast funace minecart
  - copper rails?
  - faster tier of rails?
  - quad junctions?


## Detail docs

- [animations-mechanisms.md](animations-mechanisms.md) — animations ✅ done; moveable mechanisms built but buggy
- [blockships-integration.md](blockships-integration.md) — BlockShips integration plan (4 phases, not started)
- [display-system-refactor.md](display-system-refactor.md) — display elegance refactor; Phases 1/2 done, 2b blocked, 3 deferred
- [minecarts.md](minecarts.md) — minecart mechanism off-center pivot bug (open) + 7 deferred issues
- [pipes.md](pipes.md) — Pipes → CoreLib migration plan
- [redstone-cables.md](redstone-cables.md) — redstone-cables demo plugin plan
- [rotation-mechanisms.md](rotation-mechanisms.md) — Reverser → Rotator → Glue: network-driven doors/drawbridges bridging rotation-power to mechanisms (3 phases, not started)
- [rotation-power.md](rotation-power.md) — rotational power network; Phase 1 done, Phase 2 (directionality) in progress
- [vertical-slabs.md](vertical-slabs.md) — vertical-slabs demo plugin plan (additive BlockDisplay support)

## Ready to implement

- [ ] **[next]** **Batched recipe reload** — `registerRecipesBatched(batchSize, onComplete)` via
  BukkitRunnable. Process N recipes/tick to avoid lag with HeadSmith's ~3000 heads. Registration is
  currently synchronous (`CustomBlockRegistry.registerRecipes`). Unblocks the HeadSmith migration.
- [ ] **Off-center pivot rotation fix** — delta-tracked snapped pivot; 4 code changes to
  `MechanismRegistry` + `BasicMechanism` + cross-world guard. Fully designed in
  [minecarts.md](minecarts.md) (Changes 1-4). Prerequisite for mechanism stabilization.

## Consumer plugins (open)

- [ ] **VerticalSlabs** — vertical slabs via the defcorelib system (wall heads + rotated BlockDisplay
  entities). Needs additive `BlockDisplayEntityConfig` in CoreLib. See [vertical-slabs.md](vertical-slabs.md).
- [ ] **Redstone cables** — reuse Pipes code; a cable that transmits redstone power instantly without
  changing power level. See [redstone-cables.md](redstone-cables.md).
- [ ] **Create-type mod** — rotational power for drills, grindstones, doors; minecarts with build
  blocks. Rotation Phase 1 is shipped (see [rotation-power.md](rotation-power.md)); mechanisms are
  built-but-buggy (see below). *Design-open: how to glue blocks to a mechanism — enchanted slimeball
  "slime glue" vs. particle display?*

## Future systems

- [ ] **Moveable mechanisms** — *core built* (`Mechanism`/`MechanismRegistry`/`BasicMechanism`, door +
  minecart demos), but **not done**: off-center pivot rotation bug open ([minecarts.md](minecarts.md)),
  particle ticking unimplemented (`MechanismRegistry` ~line 340), BlockShips pre-Phase-1 bugs
  ([blockships-integration.md](blockships-integration.md)). Stabilize before calling complete.
- [ ] **Custom blocks in mechanisms** — DefCoreLib custom blocks (gears, windmills, etc.) on minecart
  mechanisms. `assembleCore` already handles custom blocks when present (snapshots type/state/displays/
  particles/inventory, re-spawns display entities with animations), but `MinecartShipManager`'s
  material allow-list flood fill blocks `PLAYER_HEAD`. Becomes natural when glue replaces flood-fill
  ([rotation-mechanisms.md](rotation-mechanisms.md) Phase 3) — glue selects by anchor-owned offsets,
  not material. Needs testing: custom state round-trip, animation playback on assembled mechanism.
  Includes the sub-case of `blockDisplayEntities` (e.g. vertical slabs) which need a parallel loop
  in `MechanismRegistry` (~30 lines).
- [ ] **DynLight integration** — soft dependency; custom blocks declare dynamic light via DynLight API.
- [ ] **Connected/multi-block structures** — generalize Ropes' vertical chain pattern. Shared lifecycle
  across linked blocks.

## Banner subsystem follow-ups (from 2026-06 deep review)

- [ ] **Unified event dispatcher + caching (F3)** — ~42 `@EventHandler`s across 4 `Listener` classes;
  11 event types handled by 2+ classes (break/explode/piston/flow/etc.). Route through one dispatcher
  with a shared per-event cache + unified chunk/location index so hot handlers (BlockFromTo, piston,
  physics) short-circuit O(1). **Hard constraints (don't build naively):** keep multiple handlers per
  *actually-used* priority bucket (MONITOR cleanup must run after other plugins' cancels — don't
  collapse to one priority); preserve per-callback `ignoreCancelled`; the index **must** include
  banner-entity chunks (banner displays are entities, never in `chunkHints`, else false-negative
  "empty" → missed cleanup); memoize only pure reads in a stack-scoped EventContext (re-entrancy via
  `skull.update()`); the real win is extending existing fast-paths (`isNeighborReactive`/`isCustomBlock`)
  to BlockFromTo + the banner radius-scan. Folds in the deferred banner per-chunk perf gate.
- [ ] **Remove banner reload tooling** — `reloadConfig` / `/defcorelib reloadbanners` and the
  `banner-config.yml` load path are dev-only; delete once positions are finalized. (Known minor bugs
  while it lives: reload swaps flag front/back faces, can CCE on a stale bed cast, and skips large
  wall/standing banners — not worth fixing given planned removal.)

## Infrastructure

- [ ] **[next]** Push the **10 pending commits** to GitHub; confirm existing CI
  (`.github/workflows/publish.yml`, `checks.yml`) goes green. (Remote + workflows already configured.)
- [ ] Migrate RedstoneDisplays to CoreLib (simplest consumer, ~7 files — do first to validate the
  migration pattern)
- [ ] Migrate Pipes to CoreLib (see [pipes.md](pipes.md))
- [ ] Migrate HeadSmith to CoreLib (largest; gated on batched recipe reload above)
- [ ] Two-JAR distribution: slim (depends on CoreLib) + bundled (shadows CoreLib)

## Polish

- [ ] Adjust gear and drill textures to be symmetric *(art; noted 2026-06-23)*

## Done

- [x] Custom player heads (HeadUtil — textures, items, skull blocks)
- [x] Data-driven CustomHeadBlock (states, redstone, triggers, scaling, recipes)
- [x] Display entities attached to blocks (spawn/find/remove by tag)
- [x] YAML-driven block definitions (BlockLoader + demo blocks)
- [x] Recipe system (shaped, shapeless, stonecutter with custom GUI for head inputs)
- [x] State transitions with sounds, particles, and item consumption
- [x] Light blocks tied to state
- [x] Orientation-aware particles with DUST/ENTITY_EFFECT data support
- [x] Chunk scan with hint YAML + self-healing
- [x] Lifecycle callbacks (onChunkLoad, onChunkUnload, onTick, onNeighborChange)
- [x] Placement restrictions (PlacementConfig) with YAML parsing
- [x] Directional texture resolution with YAML parsing
- [x] Place/break/interact sounds with YAML parsing
- [x] Transition volume/pitch from YAML
- [x] Storage (custom inventories with multi-player, PDC persistence, multiple layouts)
- [x] Advancement-based recipe unlocking
- [x] Item consumption on state transitions (damageable item support)
- [x] Commands (/defcorelib give|give_demo|list)
- [x] All event handlers (place, break, interact, explosions, pistons, fire, water, physics, creative
  pick, crafting validation, stonecutter, inventory close)
- [x] 7 rounds of review — all known bugs fixed
- [x] **Animated display entities** — rotate, bob, pulse, orbit, compose; data-driven via YAML.
  `DisplayAnimation` interface + `Animations` factory; per-tick ticker in `CustomBlockRegistry`.
- [x] **Display system elegance refactor** Phases 1 & 2 — texture registry (`@alias`), `copy_from` +
  state-level animation. See [display-system-refactor.md](display-system-refactor.md).
- [x] **Rotational power network** Phase 1 — windmills/gears/shafts/drills/grindstones; rotation
  components. See [rotation-power.md](rotation-power.md).
- [x] **Windmill banner tiering** — normal/large/huge windmills gated by banner tier; tiered banner's
  tier PDC + auto-name stripped from captured blades (`LargeBannerRecipes.stripTier`).
