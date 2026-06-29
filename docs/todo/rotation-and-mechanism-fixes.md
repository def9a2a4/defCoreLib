# Rotation & Mechanism — Consolidated Remaining Work

Single source of truth for what's left across four coupled plans:
[`rotation-mechanisms.md`](rotation-mechanisms.md), [`rotation-power.md`](rotation-power.md),
[`minecarts.md`](minecarts.md), [`blockships-integration.md`](blockships-integration.md). Those
docs keep the full design rationale; this one lists only **unfinished** work, in execution order,
and flags where the plans touch the **same code** and must be coordinated.

> Verified against source 2026-06-29. Items the source docs still phrase as open but which are
> **already resolved** (so not listed below): `getNetworkStats()` keep-or-remove (kept — the Rotator
> now calls it), `MINECART_RIDE_OFFSET = 0f` (confirmed correct), `perpendicularNeighbors()` (deleted),
> the transient-demand API (implemented + folded into `getNetworkStats`), and the bevel/wrench/windmill
> doc-staleness notes (fixed).

---

## Cross-plan conflicts & coordination (read first — this drives the ordering)

1. **★ Drawbridge origin fix ↔ minecart pivot fix — SAME CODE, must be one coordinated change.**
   Both rewrite the snap/offset math in `MechanismRegistry.assembleCore()` (`snapX`/`snapZ`/`localTransform`)
   and `BasicMechanism` (`updateFromVehicle`, `rotate`, `disassemble`).
   - Minecart fix snaps **XZ to block-centre** and relies on the **integer XZ-offset invariant**
     (load-bearing for `Math.round()` in `disassemble`); Y stays bottom-corner.
   - Drawbridge fix is the "center-everything" change (centre-based `localTransform` incl. Y +
     block-centre pivot + corner-shift `(-0.5,-0.5,-0.5)`), needed so X/Z-axis rotators stop drifting ~0.5.
   - The **naïve "center-Y only" drawbridge fix is wrong** — it breaks Y-doors and the minecart path
     (adds 0.5 to an on-axis offset). → **Work Block A.**
   - **Not a hard technical dependency:** A1 can ship and be verified on its own; A2 then *builds on*
     A1's snapped pivot. The reason to do them **together** is **in-game-test risk mitigation** —
     verify the Y-door + minecart paths stay numerically identical while both edits are in flight,
     rather than re-validating the same code region twice.

2. **BlockSnapshotProvider decoupling (BlockShips Phase 1) ↔ both geometry fixes — same methods.**
   Phase 1 rewrites `assembleCore()`, `placeBlock()`, `setBlockState()`, `dropBlockAsItem()`. It will
   collide with Block A. **Sequence the geometry fixes first**, then rebase the decoupling on top —
   the source doc agrees the core must be correct before consumers migrate.

3. **Glue ↔ minecart flood-fill ↔ flood-fill truncation warning.** Glue (rotation-mechanisms Phase 3)
   explicitly retrofits `MinecartShipManager` (`resolveStructure(cart)` replacing the material
   flood-fill at ~`:330`) **and** the Rotator/DoorDemo. So glue is a **shared selection layer**, not a
   rotator-only feature. Consequence: the "flood-fill silent-truncation warning" (minecarts) is **low
   value** — glue removes that flood-fill. Don't over-invest in the warning.

4. **Duplicated items across `minecarts.md` and `blockships-integration.md`** — listed **once** here:
   vehicle-validity check, particle ticking, float-precision snap, collider XZ rounding, object pooling,
   `perpendicularNeighbors` (already deleted). Same fixes, two docs.

5. **Rotation-power network Phase 3 is an independent track** (touches `RotationNetwork` /
   `RotationBlocks`, not the mechanism layer) — it can land in parallel with everything else.

---

## Ordered remaining work

### Block A — Mechanism geometry & correctness (do together; shared core)
*Files: `MechanismRegistry.assembleCore`, `BasicMechanism.{updateFromVehicle,rotate,disassemble,ctor}`.
None of this is implemented yet (verified).*

- [ ] **A1. Minecart off-center pivot fix** (`minecarts.md` Changes 1–6, *none done*):
  snap pivot XZ in `assembleCore` (`pivot.setX/Z(snap)`); compute snap in **`double`**, cast to float
  only at `Matrix4f` build; teleport the parent in the 1-tick delay to `mech.pivot()` (not raw vehicle);
  init `previousVehicleLoc` from `vehicle.getLocation()`; **delta-track** pivot in `updateFromVehicle`
  instead of overwriting; re-snap pivot in the cross-world guard.
- [ ] **A2. ★ Drawbridge rotation origin** ("center-everything", `rotation-mechanisms.md` §2a):
  centre-based `localTransform` (incl. Y) + block-centre pivot + corner-shift `(-0.5,-0.5,-0.5)`,
  across `MechanismRegistry`, `BasicMechanism`, `DoorDemo`, `MinecartShipManager`, `RotationRotator`.
  **Must preserve A1's integer-offset invariant** and keep Y-door/minecart paths exact. Needs in-game iteration.
- [ ] **A3. Same-world large-teleport guard** in `updateFromVehicle` (`minecarts.md` Additional #8):
  if `|delta| > threshold`, re-snap + reset `previousVehicleLoc`. **Depends on A1** (only matters once
  pivot is delta-tracked).

> **Overlap with B1:** A1 rewrites the 1-tick-delay lambda, which is the *same* lambda that needs
> B1's `if (!vehicle.isValid()) { disassemble; return; }` guard — fold that part of B1 in while you're
> in A1.

### Block B — Mechanism robustness (small, independent, land anytime)
- [ ] **B1. Vehicle validity checks** — at `assembleMechanism(existingVehicle)` entry, and in the
  1-tick delay lambda (`if (!vehicle.isValid()) { disassemble/return; }`). *(minecarts #5/#9, blockships #3)*
- [ ] **B2. Defensive copy** in `tickMechanisms()` (`new ArrayList<>(activeMechanisms.values())`). *(minecarts #3)*
- [ ] **B3. Collider initial-spawn XZ rounding** (`Math.round` the XZ offset for grid alignment). *(blockships #1)*
- [ ] **B4. Object pooling** in the tick loop — pre-allocate `workBase` (and `workDec/workVec`) instead
  of `new Matrix4f` per block per tick. *(minecarts #4, blockships Phase 4A)*
- [ ] **B5. Particle ticking** for assembled mechanism blocks (replace the `// TODO`). *(minecarts #6, blockships #4)*
- [ ] **B6. Flood-fill truncation feedback** in `MinecartShipManager` — *low priority, glue obsoletes it (see conflict #3).*

### Block C — Rotation-power network Phase 3 (independent track)
*Files: `RotationNetwork`, `RotationBlocks`. All verified **not** done unless noted.*
- [ ] **C1 (B1). `recalculateAdjacentNetworks` early-`return`** still inside the loop → only the first
  adjacent network recalcs. Remove the `return`.
- [ ] **C2 (B2). Passive-source boundary scan**: `countedSources.add(neighbor)` runs **before** axis
  validation → a windmill first probed by a wrong-axis member is wrongly skipped. Move the add into the
  validated branch.
- [ ] **C3 (B3). `previouslyJammed` snapshot** misses torn-down neighbor networks → spurious jam
  smoke/sound on merge. Snapshot neighbor-network jammed members too.
- [ ] **C4 (B4). `toBlock()`** lacks an `isChunkLoaded` guard before `getBlockAt()` (passive-source
  scan can sync-load a neighbor chunk).
- [ ] **C5 (E2 — the perf win). Cache `locked` (and reverser-powered) on `RotationNode`** so
  `getConnections()` is a pure in-memory traversal (~1500 world reads/recalc avoided on big networks).
- [ ] **C6. Elegance (optional): E1** `teardownNetwork(netId)` helper (3× copy-paste), **E3** shared
  rotation-overlay builder (~8× dup), **E4** decompose the ~210-line `doRecalculate()`.
- [ ] **C7. I2** wire in `removeNodesInChunk()` (O(1) chunk-unload vs N recalcs — defined but unused);
  **I3** count flexible windmills in debug output; **I4** return empty bucket on lava-bucket fuel.
- [ ] **C8. I5 — open design decision: idle water wheel transmits rotation.** A dry water wheel is a
  0-power `TRANSMITTER`, so `shaft → dry water_wheel → shaft` still passes power through it. Decide:
  **intended** (mechanically coupled even when still) → document it; or **should disconnect** → small
  `getConnections()` rule change. *(rotation-power.md "Decisions needed" #1 — needs your call.)*

### Block D — Glue (shared block selection; `rotation-mechanisms.md` Phase 3, not started)
- [ ] **D1.** `GlueManager` + anchor-owned PDC offset storage (`glue`/`unglue`/`resolveStructure`).
- [ ] **D2.** Glue-mode authoring UX (slime-glue item, sessions, particle outline, cuboid fill,
  connectivity/cap), incl. the three interaction-conflict fixes vs `onPlayerInteract`.
- [ ] **D3.** Retrofit Rotator/DoorDemo and `MinecartShipManager` to `resolveStructure(anchor)` with
  flood-fill fallback — **removes the Phase-2 plank-leak caveat** and the need for B6.
  - ⚠️ **Highest-regression-risk item after A2:** this is a block-*selection* rewrite — a bug here
    assembles/disassembles the **wrong blocks**. Verify the glue path reproduces flood-fill selection
    (assemble/disassemble parity on doors *and* minecarts) **before** removing the flood-fill fallback.

### Block E — BlockShips integration (`blockships-integration.md`; depends on A, ideally B)
- [ ] **E1. Phase 1 — Decouple from CustomHeadBlock**: `BlockSnapshotProvider` interface +
  `CustomBlockSnapshotProvider`; rebase on Block A (conflict #2). *Nothing done — assembly is still fully
  coupled to `CustomBlockRegistry`.*
- [ ] **E2. Phase 2 — Persistence & entity recovery** (`MechanismPersistence`, chunk index, incremental recovery).
- [ ] **E3. Phase 3 — Health, damage, seats.**
- [ ] **E4. Phase 4 — Perf (folds in B4) + `MechanismConfig` builder + migration surface.**

### Block F — Polish / config (anytime)
- [ ] **F1.** Distinct head textures for `rotation:reverser` (`@copper_gear`) and `rotation:rotator`
  (`@iron_block`) — currently placeholders.
- [ ] **F2.** Move hardcoded Rotator tuning (`SPEED_K`, `MIN_DEG`, `MAX_DEG`, `SWING_DEMAND`) into
  `rotation-config.yml` after live tuning.
- [ ] **F3.** Optional: gate the Rotator's right-click angle-cycle to empty-hand (it currently hijacks
  block placement; sneak-place still works).
- [ ] **F4.** Code-comment staleness in `RotationNetwork`/`RotationBlocks` ("4 neighbors", "gearbox").
- [ ] **F5.** `MechanismBlockData.collisionScale` is stored (always `1.0f`) but never read — document
  the intent (future shulker scaling) or remove. *(minecarts.md Additional #7.)*

---

## Suggested sequencing

1. **C-track** (network cleanup) — independent, can start immediately; C1–C4 are correctness, C5 the perf win.
2. **Block A** (A1 → A2 together → A3) — the geometry foundation; unblocks BlockShips and fixes drawbridges.
3. **Block B** — quick robustness wins; B4 also serves E4.
4. **Block D** (Glue) — makes selection robust for rotator + minecart; obsoletes B6.
5. **Block E** (BlockShips) — rebased on A/B.
6. **Block F** — polish, ongoing.
