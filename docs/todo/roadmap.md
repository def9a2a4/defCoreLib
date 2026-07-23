---
name: DefCoreLib feature roadmap
description: Tracked TODO list of remaining features and future work for the defCoreLib plugin core library
type: project
originSessionId: 2bc279e0-d887-474b-95a2-e8918ac16902
---
## Completed
- Custom player heads (HeadUtil — textures, items, skull blocks)
- Data-driven CustomHeadBlock with states, redstone (NONE/BINARY/LEVEL, DIRECT/EXTENDED), extensible triggers, scaling
- Display entities attached to blocks (spawn/find/remove by tag)
- YAML-driven block definitions (BlockLoader + 10 demo blocks)
- Recipe system (shaped, shapeless, stonecutter with custom GUI for head inputs)
- State transitions with sounds and particles
- Light blocks tied to state
- Orientation-aware particles
- Chunk scan with hint YAML + self-healing
- Lifecycle callbacks (onChunkLoad, onChunkUnload, onTick, onNeighborChange)
- Placement restrictions (PlacementConfig)
- Directional texture resolution
- Commands (/corelib give|list)
- All event handlers (place, break, interact, explosions, pistons, fire, water, physics, creative pick, crafting validation)
- 5 rounds of review — all known bugs fixed

## Remaining features (ready to implement)
- **Storage** — StorageConfig record exists but no inventory logic. PDC-based persistence, lazy load, drop on break.
- **Sounds** — place/break sounds. Add SoundConfig record + YAML parsing + play in event handlers.
- **Advancement-based recipe unlocking** — per-block-type unlock advancement. Follows Pipes' RecipeUnlockListener pattern.
- **Batched recipe reload** — BukkitRunnable that processes N recipes/tick for HeadSmith's 3000 heads.

## Rotation-block display-entity elegance pass (phased)
rotation-blocks.yml had 3 layers of duplication. Fixing one phase at a time.
- **Phase 1 — named texture registry (DONE).** Top-level `textures:` map in the YAML; `texture`/`item_texture`/display/directional values use `@alias`. Resolved in BlockLoader.resolveTexture() at parse time (unknown alias → block skipped with warning). Collapsed 69 inline base64 refs → 13 defs. Live-server placement verification still pending.
- **Phase 2 — auto-derived spin states (planned).** `spinning_*` states are idle + a rotate animation; let a state inherit another's display_entities and add spin around a declared axis (e.g. `spinning_y: { like: idle_y, spin: 3.0 }`).
- **Phase 3 — axis-orientation composition (planned).** Define parts in a canonical floor/Y frame; loader composes per-axis orientation rotation, removing hand-computed quaternions like [98.42,-0.357,...]. Translations stay explicit (wall variants pull parts to the wall).

## Mechanism & glue track (as of 2026-06-29 — see docs/todos/mechanism/rotation-and-mechanism-fixes.md for the live list; all mechanism design docs now live under defCoreLib/docs/todos/mechanism/)
The "BlockShips-style moveable mechanisms" future system below is now largely BUILT: MechanismRegistry/
BasicMechanism (displays + shulker colliders), doors, rotators (drawbridges), mechanism minecarts, and the
rotation-power network. Done in this track:
- **Block A** geometry (A1 minecart pivot, A2 drawbridge rotation origin) — verified in-game.
- **Block C** rotation-power network cleanup (C1–C4 etc.).
- **Block D — Glue (DONE, committed):** anchor-owned persisted block selection replacing same-material
  flood-fill for doors+rotators. Commits 881fd65 (FloodFill/Faces extract), 18ce491 (Anchor/BlockAnchor/
  GlueManager + rebind-on-disassembly seam in BasicMechanism), d32ce9b (slime-glue authoring UX), f7db17b
  (door+rotator retrofit). Offsets stored as INTEGER_ARRAY in skull PDC; resolveStructure→assemble;
  rebind-on-disassembly tracks rotated rest positions. **In-game parity testing still pending.**
Remaining: **Block E** (BlockShips integration onto defCoreLib — incl. EntityAnchor + minecart glue, gated
on E's persistence/recovery), **Block F** (polish/config). [[DefCoreLib feature roadmap]]

## Future systems (not yet designed)
- **Animated display entities** — display entities on custom blocks that animate (rotate, bob, pulse, etc). Data-driven animation configs (keyframes or simple oscillation params). Needed for decorative blocks.
- **BlockShips-style moveable mechanisms** — convert a group of blocks into display entities + shulker colliders. Handle movement, collision detection, and disassembly. Core system reusable for ships, redstone doors, drawbridges, elevators. *(Largely built — see Mechanism & glue track above.)*
- **DynLight integration** — interface hook so custom blocks can declare dynamic light emission via DynLight's API. *(Done for carts — a lit blast-furnace cart emits level-5 dynamic light via a scoreboard-tag integration; see CartConfig `dynamic-lights`.)*
- **Connected/multi-block structures** — Ropes' vertical chain pattern (breaking one breaks all). Generic multi-block structure support.
- **Power chains** — long-distance rotational link between two "chain wheel" nodes; requires a complete (mutual) loop; minecarts ride a powered chain like a ski lift. Designed: defCoreLib/docs/todos/mechanism/power-chains.md (chain = a graph edge-at-a-distance injected into RotationNetwork; ski-lift reuses EntityAnchor glue + updateFromVehicle).
- **Extendable pistons** — Create-style multiblock (piston core + shaft poles + separate head) that translates the head + glued payload several blocks. Designed: defCoreLib/docs/todos/mechanism/extendable-pistons.md (translation via BasicMechanism.move() — no matrix rewrite; new axis-directed multiblock validator; payload via GlueManager/BlockAnchor).

## Known follow-ups
- **1.21.9+ CHAIN→IRON_CHAIN rename breaks bare shafts** (as of 2026-07-14): the bare-shaft code
  hardcodes `Material.CHAIN` (~6 sites: CustomBlockRegistry getTypeFromBlock/isChainShaft/
  restoreChainShaftsInChunk, CoreLibPlugin onFluidFlowIntoCustomHead, RotationBlocks
  makeShaftBare/toggle) while ChainPulley already resolves `IRON_CHAIN`/`CHAIN` by name
  (resolveChain()). The playtest server jar is already 1.21.11 — reuse the resolveChain pattern.
- **/reload / hot-enable gap** (pre-existing): chunks already loaded at plugin enable get no
  ChunkLoadEvent/EntitiesLoadEvent, and rescanLoadedChunks scans tile entities only — bare chain
  shafts in such chunks stay unresolved until a chunk reload. Optional: run chunkRestorers over
  world.getLoadedChunks() for hinted chunks after registration finalizes.

## Infrastructure
- Test on live server
- Git push to GitHub + CI
- Migrate RedstoneDisplays (simplest existing plugin)
- Two-JAR distribution (slim + bundled) for consumer plugins
