# Chain Shafts — shafts as self-describing CHAIN blocks (no PDC, no storage)

## Context

Make `rotation:shaft` place a real vanilla **`CHAIN`** block instead of a `PLAYER_HEAD`, so the base block reads as a thin chain hidden inside the rotating copper-rod display entity — and give it **lightning-rod place/break sounds**. The item in hand stays a head; display entities and the rotation network keep working.

See [`rotation-power.md`](rotation-power.md) for the existing shaft/network design this modifies.

### Why this is not a one-line change
Today every custom block's identity (`corelib:block_type`) and state (`corelib:state`) live in the placed **skull's** PersistentDataContainer. A `CHAIN` has no block-entity → no PDC. Options considered and rejected:
- **Invisible skull texture** — skull base skin layer renders opaque; transparent skins show solid/black. Unreliable.
- **Chunk-PDC identity store** — works, but we explicitly want **no location-based storage**.
- **Ghost blocks (client packets)** — fragile; the plugin's own `skull.update()` re-asserts the real block every state change → constant flicker + per-viewer upkeep.

### Chosen design
**A `CHAIN` block IS a shaft — self-describing, nothing stored.** Identity = "the block is `Material.CHAIN`"; axis = the chain's native `Orientable` blockdata (Minecraft persists it for free). Spinning on/off and CW/CCW are recomputed by the network solve. **Any** chain (even a player-placed decorative one) is treated as a shaft — accepted trade-off.

> Verified against source 2026-07-01 (three code-trace passes). The trade is deliberate: **zero persistence in exchange for a rewrite of the network traversal core + a few new event hooks.** This is deeper than a storage-based approach, not shallower.

## Design decisions

- **Self-describing, no nodes.** Shafts are no longer registered `RotationNetwork` nodes. Sources/consumers/gears/engines stay skull+PDC nodes. The network discovers shafts by **walking world `CHAIN` blocks along-axis during each solve**, collapsing a run of chains into one "fat edge" between two real nodes.
- **Axis from `Orientable`.** No `axisFromState` for shafts; read `((Orientable)blockData).getAxis()`. Set at placement from `placement_state_map` (DOWN→Y, N/S→Z, E/W→X).
- **State is synthesized, not stored.** `getState(chain)` → `(inAnimationTracked ? "spinning_" : "idle_") + axisSuffix`.
- **Display is unchanged machinery, re-driven.** Rod `ItemDisplay` spawned at placement via `applyConfig`; persistent + tagged; re-tracked on chunk load by scanning display entities; animated by the solve marking each discovered chain position.
- **Sounds:** lightning rods use the copper sound group → `BLOCK_COPPER_PLACE` / `BLOCK_COPPER_BREAK` (already used elsewhere).

## Work blocks (execution order)

References are `file` `symbol` (`~line`) against current source.

### A. Type plumbing (opt-in, shaft-only) — low risk, do first
- `rotation-blocks.yml` `shaft:` — add `base_block: CHAIN`; keep `placement_state_map`; add `place_sound: {sound: BLOCK_COPPER_PLACE}` / `break_sound: {sound: BLOCK_COPPER_BREAK}`. Leave `texture`/`item_texture` (item stays a head).
- `CustomHeadBlock` — add `@Nullable Material baseBlock` + `isNonSkull()`; **copy it in `toBuilder()`** (shaft is overlaid via `toBuilder()`; missing this silently reverts to skull — classic omission).
- `BlockLoader.parseBlock` (~77-86) — parse `base_block`.
- `CustomBlockRegistry` — build a `Map<Material, CustomHeadBlock>` of material-identified types (CHAIN→shaft) at registration.
- **Verify:** plugin loads; `type.baseBlock()` survives the `toBuilder()` round-trip; shaft still places as skull (placement not yet branched).

### B. Identity / state / placement
- `CustomBlockRegistry.getTypeFromBlock` (216-224) — if `block.getType()` is a material-identified type, return it (chains → shaft).
- `getState` (344-347) — non-skull: synthesize `axisSuffix` from `Orientable`, `spinning_`/`idle_` from `animationTracked` membership.
- `setState` (349-354) — non-skull: no-op persistence (display refreshes via the solve's `applyConfig`).
- `markBlock` (327-341) — non-skull: add to in-memory `customBlockLocations` only; no PDC.
- `CoreLibPlugin.onBlockPlace` (250-393) — shaft branch: resolve state from `placement_state_map`, `setType(CHAIN)`, set `Orientable` axis; add to `customBlockLocations`; `applyConfig` spawns the rod. Guard skull-conversion (307-319), `playerHeadStates` (338-346), and `HeadUtil.applyTexture` (`applyConfig` ~525) with `if (!type.isNonSkull())`. Place sound (363-366) already fires.
- `isOwnerPresent` (302-310) — accept chain shafts so `cleanorphans` spares their rod displays.
- Break/explosion/piston (399-524) — work via the `getTypeFromBlock` chain branch; vanilla chain drop already suppressed (`setDropItems(false)`/`it.remove()`); rod item drops (`drops: self`).
- Physics (`onPhysicsCancelForCustomSkulls` ~560) — chains float in vanilla, so pop-off protection is likely unnecessary; verify, add a `CHAIN` case only if a case fires.
- **Verify:** placed shaft is a `CHAIN` with correct axis, only the rod visible, item stays a head; break drops the rod, not a chain; place/break plays the copper sound.

### C. Network traversal rewrite — the core (highest risk)
- `RotationBlocks.overlayStandard` (47) — **remove** the `rotation:shaft` node registration.
- `RotationNetwork.checkAxisNeighbor` (565-571) — replace the direct `nodes.get` with a **chain-walk**: step along-axis while the next block is `CHAIN` with matching `Orientable` axis and chunk loaded (`toBlock` (637-645) already refuses unloaded chunks), collecting chain cells, until the next real node; emit a `Connection` carrying the chain-cell list + accumulated `reverses`.
- `getConnections` (531-563) — return per-edge chain-cell lists (enrich `Connection` (46) or a side map). Gear face-mesh branch (549-560) unchanged, but a gear's along-axis neighbor may now be a chain → the walk applies to gear nodes too.
- `doRecalculate` BFS (293-334) — inject chain cells into `members` + `dirMap` with propagated direction; **count chain cells toward `maxNetworkSize`** (77/307) so a long decorative chain can't blow the world-read budget.
- Reverser flip (537-546, `isPoweredReverser` 576-580) — carry across a collapsed chain-run edge; reversers stay run-terminating nodes.
- Display drive (412-421) — existing `setAnimationDirection` loop now also covers the injected chain positions.
- **Verify:** source → long chain run → consumer transmits; reverser mid-run flips the whole run; cutting power stills the rods.

### D. New recalc triggers (mandatory for correctness)
- **Mid-run chain edits** — new `BlockPlaceEvent`/`BlockBreakEvent` listener for `Material.CHAIN`: walk along-axis to the nearest node and `recalculate`. (Node-*adjacent* edits already recalc via `onBlockPhysics` (1108-1124) → reactive node → `recalcIfKnown`; only mid-run edits lack a trigger.)
- **Chunk-load re-walk** — new `ChunkLoadEvent` branch: recalc networks whose nodes point into the newly loaded chunk, so a chain-only chunk loading between live endpoints reconnects. (`CustomBlockRegistry.onChunkLoad` (389-412) scans only skull tile-entities and never sees chains.)
- **Verify:** extend/cut a chain mid-line updates the network; unload/reload a middle chunk of a run reconnects.

### E. Display re-track on chunk load (no storage)
- New `EntitiesLoadEvent` branch (`CoreLibPlugin` ~220-244): scan `Display` entities tagged `corelib:rotation:shaft:*` in the chunk; for each, resolve the `CHAIN` block and call `trackAnimations(block, type, synthesizedState)` (`CustomBlockRegistry` 874-921) to re-register the rod animation. Replaces the tile-entity restore path for chains. `tickAnimations` (844-855) and tag-based break/unload cleanup already work unchanged.
- **Verify:** after chunk reload / server restart, rods re-track and spin.

### F. Moving-contraption parity (glue/mechanisms)
- Capture (`MechanismRegistry` 135-166) — `getTypeFromBlock` resolves the chain; `getState` synthesizes `idle_+axis`; `getBlockData()` captures the axis; `spinReversed` default false; `wallFacing` null (chain not `Directional`).
- Re-place (`BasicMechanism.placeBlock` 290-304) — `setBlockData(rotateBlockData(bd, yaw))` rotates the `Orientable` axis (`BlockRotation` 31-36; verify X↔Z on 90°); **`rotateCustomState` needs an `Orientable` special-case** deriving `idle_/spinning_ + axis` from the landed blockdata (not an attachment face); `markBlock` no-ops (add to `customBlockLocations`); `applyConfig` respawns the rod; trigger an adjacent-network recalc after settle (shafts aren't nodes → rely on the re-walk trigger, not `addNode`).
- **Verify:** glue/mechanism-move a contraption containing a shaft incl. 90° rotation → axis rotates, rod animates through/after the move, network reconnects.

## Risks

1. **Traversal rewrite** — chain-run fat-edges, reverser flip across a run, gear along-axis through chains, `maxNetworkSize` accounting including chain cells.
2. **Trigger gaps** — mid-run edit listener + chunk-load re-walk; if missed, networks silently go stale (powered-but-still / still-but-powered) until a node event.
3. **Display re-track** — the `EntitiesLoadEvent` scan + solve member-injection; if missed, rods freeze after reload.
4. **`rotateCustomState` Orientable case** — the contraption path depends on it.
5. **Decorative chains** join adjacent networks (accepted; document for users); **breaking any chain drops a shaft item** (accepted).
6. **`toBuilder()`** must copy `base_block`.
7. **Cross-unloaded-chunk truncation** is existing behavior; the chunk-load re-walk heals it.

## Verification (end-to-end, Paper 1.21.8)

`./gradlew build`, load on a test server, then:
1. Place shaft → `CHAIN` + correct axis, only rod visible, item stays a head; copper place/break sound; break drops rod not chain.
2. Source → long chain run → consumer transmits; reverser mid-run flips the run; cut power → rods still.
3. Extend/cut a chain run mid-line (away from any node) → network updates.
4. Reload chunk + restart → chains/axes persist, rods re-track; unload/reload a middle chunk of a run → reconnects.
5. Glue/mechanism-move a contraption with a shaft incl. 90° rotation → axis rotates, rod animates, network reconnects.
6. `/defcorelib cleanorphans` spares rod displays; decorative chain against a live run joins it (expected).

Temp artifacts → `defCoreLib/.temp/` (per module CLAUDE.md).
