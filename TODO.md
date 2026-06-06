# DefCoreLib TODO

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

## Infrastructure

- [ ] Push to GitHub, verify CI workflows
- [ ] Migrate RedstoneDisplays to CoreLib (simplest consumer, ~7 files)
- [ ] Migrate HeadSmith to CoreLib
- [ ] Migrate Pipes to CoreLib
- [ ] Two-JAR distribution: slim (depends on CoreLib) + bundled (shadows CoreLib)
