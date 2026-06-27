# 2026-06-23 06:08

- [ ] adjust gear and drill textures to be symmetric

# DefCoreLib TODO


Demos:

- migrate pipes, redstonedisplays, headsmith
- "VerticalSlabs!11!" plugin - simple vertical slabs with the defcorelib system. wall heads with rotated slab display entities
- "redstone cables" -- reuse some code from pipes. a redstone cable that transmits redstone power instantly, without changing power level
- create-type mod (part of the core lib or no?). rotational power for drills, grindstones, and moving around doors and stuff. minecarts with build blocks on them
  - key question -- how to glue? enchanted slimeball "slime glue", particle display?


## Done

- [x] Custom player heads (HeadUtil — textures, items, skull blocks)
- [x] Data-driven CustomHeadBlock (states, redstone, triggers, scaling, recipes)
- [x] Display entities attached to blocks (spawn/find/remove by tag)
- [x] YAML-driven block definitions (BlockLoader + 13 demo blocks)
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
- [x] All event handlers (place, break, interact, explosions, pistons, fire, water, physics, creative pick, crafting validation, stonecutter, inventory close)
- [x] 7 rounds of review — all known bugs fixed

## Ready to implement

- [ ] **Batched recipe reload** — `registerRecipesBatched(batchSize, onComplete)` via BukkitRunnable. Process N recipes/tick to avoid lag with HeadSmith's ~3000 heads.

## Future systems (see TODO-animations-mechanisms.md for detailed design)

- [ ] **Animated display entities** — rotate, bob, pulse, orbit, compose. Data-driven via YAML. `DisplayAnimation` functional interface + `Animations` factory. Tick-driven with interpolation. ~150 lines.
- [ ] **Moveable mechanisms** — BlockShips-style: blocks → display entities + shulker colliders. Movement, collision, disassembly. `Mechanism` interface + `BasicMechanism` impl. Persistence via YAML + chunk index. ~850 lines.
- [ ] **DynLight integration** — soft dependency, custom blocks declare dynamic light via DynLight API.
- [ ] **Connected/multi-block structures** — generalize Ropes' vertical chain pattern. Shared lifecycle across linked blocks.

## Banner subsystem follow-ups (from 2026-06 deep review)

- [ ] **Windmill banner tiering** *(reminder — design later)* — large windmill craftable **only**
  with large banners; add a **huge windmill** tier craftable with huge banners. Today any banner
  works as a blade and a tiered banner bakes its tier PDC + auto-name into the sail lore
  ("Large White Banner") — strip the tier PDC/auto-name from captured blades when this is built.
- [ ] **Unified event dispatcher + caching (F3)** — ~42 `@EventHandler`s across 4 `Listener`
  classes; 11 event types handled by 2+ classes (break/explode/piston/flow/etc.). Route through one
  dispatcher with a shared per-event cache + unified chunk/location index so hot handlers
  (BlockFromTo, piston, physics) short-circuit O(1). **Hard constraints (don't build naively):**
  keep multiple handlers per *actually-used* priority bucket (MONITOR cleanup must run after other
  plugins' cancels — don't collapse to one priority); preserve per-callback `ignoreCancelled`; the
  index **must** include banner-entity chunks (banner displays are entities, never in `chunkHints`,
  else false-negative "empty" → missed cleanup); memoize only pure reads in a stack-scoped
  EventContext (re-entrancy via `skull.update()`); the real win is extending existing fast-paths
  (`isNeighborReactive`/`isCustomBlock`) to BlockFromTo + the banner radius-scan. Folds in the
  deferred banner per-chunk perf gate.
- [ ] **Remove banner reload tooling** — `reloadConfig` / `/defcorelib reloadbanners` and the
  `banner-config.yml` load path are dev-only; delete once positions are finalized. (Known minor
  bugs while it lives: reload swaps flag front/back faces, can CCE on a stale bed cast, and skips
  large wall/standing banners — not worth fixing given planned removal.)

## Infrastructure

- [ ] Push to GitHub, verify CI workflows
- [ ] Migrate RedstoneDisplays to CoreLib (simplest consumer, ~7 files)
- [ ] Migrate HeadSmith to CoreLib
- [ ] Migrate Pipes to CoreLib
- [ ] Two-JAR distribution: slim (depends on CoreLib) + bundled (shadows CoreLib)
