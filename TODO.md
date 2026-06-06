# DefCoreLib TODO

## Ready to implement

- [ ] **Storage** — custom block inventories. StorageConfig record exists, needs: PDC-based persistence (BukkitObjectOutputStream), lazy load on interact, drop contents on break, InventoryCloseEvent save-back, StorageHolder class.
- [ ] **Sounds** — place/break sounds. Add SoundConfig record, builder methods, YAML parsing (`place_sound`/`break_sound`), play in onBlockPlace/onBlockBreak.
- [ ] **Advancement-based recipe unlocking** — per-block-type `unlock_advancement` field. PlayerAdvancementDoneEvent + PlayerJoinEvent listeners. discover/undiscover recipes per player. Follows Pipes' RecipeUnlockListener pattern.
- [ ] **Batched recipe reload** — registerRecipesBatched(batchSize, onComplete) via BukkitRunnable. Process N recipes/tick to avoid lag with HeadSmith's ~3000 heads.
- [ ] **YAML parsing for PlacementConfig** — runtime enforcement exists, BlockLoader doesn't parse it yet.
- [ ] **YAML parsing for directionalTextures** — resolution code exists, no YAML path.
- [ ] **YAML parsing for transition volume/pitch** — always hardcoded to 1f.

## Future systems

- [ ] **Animated display entities** — data-driven animations on custom block display entities: rotation, bobbing, pulsing, scaling. Animation configs (keyframes or simple oscillation parameters like amplitude/frequency/axis). Tick-driven interpolation. Needed for decorative blocks, machinery visuals.
- [ ] **Moveable mechanisms** — BlockShips-style system: convert a group of blocks into display entities + shulker colliders. Movement with velocity/drag/collision. Disassembly back to blocks. Core system reusable for ships, redstone doors, drawbridges, elevators. Keep ship-specific logic (engines, fuel, sails, buoyancy) in BlockShips.
- [ ] **DynLight integration** — interface hook so custom blocks can declare dynamic light emission via DynLight's existing API. Soft dependency.
- [ ] **Connected/multi-block structures** — generalize Ropes' vertical chain pattern (breaking one breaks all). Support arbitrary multi-block structures with shared lifecycle.

## Infrastructure

- [ ] Test on live Paper server
- [ ] Git push to GitHub, verify CI workflows
- [ ] Migrate RedstoneDisplays to CoreLib (simplest consumer, ~7 files)
- [ ] Two-JAR distribution: slim (depends on CoreLib) + bundled (shadows CoreLib) for consumer plugins
