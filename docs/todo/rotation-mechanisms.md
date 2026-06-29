# Rotation Mechanisms — Reverser → Rotator → Glue

Network-driven, redstone-controlled doors and drawbridges, built by bridging the rotation-power
network to the mechanism system, plus a reusable block-selection ("glue") layer. Three phases
(plus a small Phase 0), each independently shippable and testable in-game.

## Context

A **Rotator** is the bridge between the rotation-power network and the mechanism system: a
CONSUMER node that assembles an attached block group and rotates it. A **reverser** lets redstone
flip the network's spin direction, which is how a door opens vs. closes. **Glue** is a shared
selection layer (replacing per-consumer flood-fill) so doors, drawbridges, and minecart mechanisms
all answer "which blocks move together?" the same way.

Locked design decisions:
- **Reverser** (not clutch) for redstone open/close — clutch only disconnects.
- **Restore real blocks** at rest (door closed/open = real blocks; mechanism exists only mid-swing).
- **Speed locked at swing-start** from `K · surplus / mass`; **not** recomputed if power changes
  mid-swing.
- Demand **registered only while swinging** ("power consumed while rotating"). This makes
  multi-Rotator contention self-resolving: a door that starts while another is swinging sees the
  reduced surplus and runs slower/stalls — no special sharing logic.
- **Oak-planks flood-fill** for block selection in v1 → **glue** later.

## Current state / dependencies (verified in code)

- **Direction is already built** in `RotationNetwork`: `getDirection`, `nodeDirection`, BFS
  direction propagation, `getNetworkStats`, `isPowered`. Spin animations are materialized
  (display-system Phase 2).
- **Mechanism system exists** (`MechanismRegistry.assembleMechanism`, `Mechanism.rotate`,
  `disassemble`, `DoorDemo`) but is **yaw-only** (`BasicMechanism` hardcodes `rotateY`).
- The pending windmill passive-source reversal fix + verification items 24/25 may still be unlanded.

---

## Phase 0 — Verify/fix the direction system (small prerequisite)

Both Phase 1 and 2 build on direction, so de-risk it first.

- Confirm in-game: source→gear reversal, jammed networks, per-node direction.
- Land the pending **windmill passive-source reversal** fix + verification items 24/25.

**Why first:** bugs in direction cascade into both the reverser and the rotator.

---

## Phase 1 — Reverser block (+ the reversal-model fix)

### 1a. Fix the reversal model (regression-sensitive)

**Bug:** `Connection(neighbor, reverses)` is a *per-edge* flag, and BFS applies it per edge
(`conn.reverses() ? myDir.reversed() : myDir`). A reverser between A and B has **two** edges, so
flagging both `reverses=true` double-reverses (A→R→B = identity) — per-edge flags cannot express
"reverse once across this block."

**Fix:** model the reverser's reversal as a **node-level** property applied **once** when BFS
visits the reverser node. (Gear-mesh per-edge reversal stays as-is; only the reverser is
node-level.)

★ **Regression risk:** this edits the shipped direction BFS — re-verify gears/shafts still
propagate and counter-rotate correctly afterward.

### 1b. The `rotation:reverser` block

- TRANSMITTER. `getBlockPower() > 0` → reverse-once; unpowered → pass-through.
  `onNeighborChange` → `recalculate`. Coexists with the existing `clutch` (disconnect) on one
  network.
- Block boilerplate: YAML def + texture + recipe + give command + axis `placementStateMap` +
  register in `RotationBlocks`.

**Verify:** source → reverser → shaft/gear chain; toggle redstone → downstream spin visibly
reverses; reverser+clutch combos behave; existing networks unaffected.

---

## Phase 2 — Rotator (doors + drawbridges)

### 2a. Generalize mechanism rotation to an arbitrary cardinal axis (the real engine work)

- Add a **rotation axis** to `BasicMechanism` (param on `assembleMechanism`; default **Y** → all
  existing callers / minecart / DoorDemo unchanged). Rename `currentYaw` → `currentAngle`.
- `rotate(angle)` → `new Matrix4f().rotate(toRadians(±angle), axisVec)`.
- ★ **rideOffset:** apply in **local (pre-rotation) space**, not the post-rotation `m31`
  subtraction (`BasicMechanism` line 116) — that corrupts position on X/Z.
- ★ **BlockDisplay corner-shift:** make axis-conditional — Y `(-0.5, 0, -0.5)`,
  X `(0, -0.5, -0.5)`, Z `(-0.5, -0.5, 0)` (line 124).
- `disassemble`: snap angle around the axis; ★ guard **off-world Y / obstruction** — a tall
  drawbridge swung 90° lands blocks far from the hinge (possibly below world-min); reuse the
  existing **three-tier** placement (air→place, fragile→break+place, solid→drop as item) and skip
  out-of-world targets.
- Collider repositioning and the minecart `updateFromVehicle` (Y-only) path: unchanged.

### 2b. The `rotation:rotator` block + control loop

- CONSUMER; axis from placement (**floor → door (Y)**, **wall → drawbridge (X/Z)**).
- **Demand registered only while swinging** (0 at rest) → loads the network during a swing.
- **State machine:** PDC stores `open|closed` + `target ∈ {90, 180, 270}`. Direction
  CW = open/raise, CCW = close/lower. A swing fires **only when direction disagrees with the
  rest-state**.
- **Rotation loop = per-tick `BukkitTask`** (DoorDemo style), **not** the network `onTick`
  interval. On swing-start: read surplus + mass once, **lock** `delta = clamp(K · surplus / mass,
  MIN_DEG, MAX_DEG)` for the whole swing; do **not** recompute if power changes mid-swing.
  Direction *may* flip mid-swing (reverse the swing) at the frozen speed magnitude.
- **Swing-guard:** `activeRotators` / `activeTasks` maps keyed by hinge `LocationKey` so a standing
  direction-disagreement doesn't re-assemble each tick. Reuse DoorDemo's `cancelExistingTask`.
- **Selection (v1):** oak-planks `floodFill` (reuse `DoorDemo.floodFill` verbatim), exclude the
  hinge + any `rotation:*` block, cap 256.
- **Restore real blocks:** assemble → rotate to target → `disassemble` (snap 90°, real blocks);
  at rest = real blocks (survives restart, no mechanism persistence needed).
- **Target menu:** interact-GUI; ★ **wrench-first dispatch** so it doesn't clobber the wrench
  debug or sneak-to-place.
- **Underpowered** (`surplus ≤ 0` at start): no swing + feedback sound/particle.
- **Cleanup:** `onBlockRemoved` / `onChunkUnload` → cancel + `disassemble`;
  `cleanupOrphanedEntities` covers server-stop mid-swing.
- Block boilerplate: YAML + texture + recipe + give command.

### ★ Drawbridge caveat

With oak-planks flood-fill, a plank bridge inside a **plank** wall leaks (can't tell bridge from
wall). So Phase-2 drawbridges work only for plank structures bounded by non-plank; they become
fully robust after **glue** (Phase 3). Doors (planks in a stone frame) are fully usable in Phase 2.
**Treat "robust drawbridges" as a Phase-3 deliverable.**

**Verify:** floor door opens/closes via the reverser at speed ∝ surplus/mass; enlarge the door →
slower; two doors on one network → the second starts slower (contention via consumed demand); wall
drawbridge raises/lowers; break / chunk-unload mid-swing → no orphan entities; underpowered → no
move + feedback.

---

## Phase 3 — Glue (shared selection layer; retrofits Rotator + minecart)

`GlueManager` (CoreLib, `MechanismRegistry` package). Per-block "glued" boolean; a mechanism's
moving set = the connected component of glued blocks reachable from the seed (hinge / cart block)
via cardinal-face adjacency, capped at 256. The unglued frame/wall/ground is the natural boundary.

- **API:** `glue/unglue/isGlued(Block)`, `collectStructure(Block seed, int cap)` — the single
  call all consumers use instead of bespoke flood-fill.
- **Persistence:** chunk **PersistentDataContainer** packed `long[]` of glued positions; unpack
  into memory on `ChunkLoad`, update both in-memory and PDC on glue/unglue. (No block marker to
  scan for on a plain glued plank, so a CoreLib-style chunk-hint scan can't work.)
- **Round-trip:** `MechanismBlockData` gains `boolean glued`; **assemble** records it + clears the
  chunk-PDC entry (block becomes air); **disassemble** re-glues the restored position (carries glue
  across movement/rotation, including a cart that drove into a new chunk).
- **UX (the "slime glue" item):** a PDC-tagged slimeball variant (not plain slime) — right-click a
  block to glue (slime particles + `BLOCK_SLIME_BLOCK_PLACE`), sneak-right-click / shears to
  unglue, breaking a glued block auto-ungues it (`BlockBreakEvent`). While held, periodically
  particle-outline glued blocks within ~8 blocks. Message the player when `collectStructure` hits
  the cap.
- **Retrofit:** `Rotator` / `DoorDemo` and `MinecartShipManager` swap their flood-fill for
  `collectStructure` (keep an allowed-material fallback during transition: included if
  `glued || allowedMaterial`). This is what makes drawbridges (and same-material doors) robust.

**Verify:** glue a platform onto a cart → only glued blocks ride; glue a door panel in a
same-material wall → only the panel swings; drive far + disassemble → re-glued in the new chunk;
reload → glue persists; break a glued block → its entry clears; hold the glue item → glued blocks
outline with particles.

---

## Risks / gaps to keep in mind

1. **Direction is built but unverified on this path** — Phase 0 verifies it and lands the windmill
   fix before anything builds on it.
2. **The axis-generalization (2a) is the bulk of Phase 2**, not a free precondition: rideOffset,
   the axis-conditional corner-shift, and off-world swing each break naively.
3. **Robust drawbridges depend on glue (Phase 3)** — Phase 2 ships doors + isolated-structure
   drawbridges only.
4. **The reverser fix is a regression risk** to the shipped direction BFS — re-test existing
   spinning networks after it.
5. Per-block **boilerplate** (YAML/texture/recipe/give) for both the reverser and the rotator.
6. **Door bookkeeping in PDC** (open/closed + target angle) — restore-real-blocks makes geometry
   implicit, but the hinge still needs to remember its state across reload.
7. **Reverser ↔ clutch coexistence** on one network (both redstone transmitters).
8. **Underpowered/stall UX** — define the no-power-to-open feedback.
