# Rotation Mechanisms — Reverser → Rotator → Glue

Network-driven, redstone-controlled doors and drawbridges, built by bridging the rotation-power
network to the mechanism system, plus a reusable block-selection ("glue") layer. Three phases
(plus a small Phase 0), each independently shippable and testable in-game.

> **Status (2026-06-29):** Phase 0 ✅, Phase 1 (reverser) ✅, Phase 2 (rotator + mechanism
> axis-generalization) ✅ **shipped** (commits `c1e8767`, `9b1feb0`, + the pulse-trigger rework).
> Phase 3 (glue) **not started**. The Rotator design changed during implementation — it is now
> **stateless and redstone-pulse-triggered**, not the open/closed direction-polled machine
> originally written below; sections 2a/2b have been corrected. See **Remaining issues** at the
> bottom for what's left (chiefly the drawbridge rotation origin and glue).

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

## Phase 1 — Reverser block ✅ SHIPPED (commit `c1e8767`)

### 1a. The reversal model (as implemented)

**Bug:** flagging both of a reverser's `Connection(neighbor, reverses)` edges true double-reverses
(A→R→B = identity) — per-edge flags can't express "reverse once across this block."

**Implemented fix:** the reverser is **along-axis only** (not gearLike, max 2 ports), and an
along-axis edge reverses **iff its lower (−axis) endpoint is a powered reverser**
(`isPoweredReverser` in `RotationNetwork.getConnections`, keyed off the `rotation:reverser` id +
live `getBlockPower()`). This is symmetric (both endpoints compute the same flag) and yields
exactly one flip across the reverser, with no false jam on the back-edge. Gear-mesh per-edge
reversal is unchanged.

### 1b. The `rotation:reverser` block ✅

- Plain along-axis TRANSMITTER registered via the existing `overlayStandard` — all the
  reverser-specific behavior lives in `getConnections`, so `reactsToNeighbors` (recalc on redstone
  change) is all the overlay needs. YAML block + `placementStateMap` added (placeholder
  `@copper_gear` texture — **swap for a distinct head later**). No recipe (given-only, like the
  other rotation blocks).

**Verify (in-game):** source → reverser → shaft/gear chain; toggle redstone → downstream spin
reverses; reverser+clutch combos behave; existing networks unaffected.

---

## Phase 2 — Rotator (doors + drawbridges) ✅ SHIPPED (commit `9b1feb0` + pulse rework)

### 2a. Mechanism rotation generalized to an arbitrary cardinal axis ✅ (as implemented)

- Added a **rotation axis** (`Vector3f`) to `BasicMechanism` (param on `assembleMechanism`; default
  **Y** → existing callers / minecart / DoorDemo unchanged). `rotate`/`disassemble` use
  `new Matrix4f().rotate(toRadians(−angle), axis)` instead of `rotateY`. Kept `currentYaw` (the
  rename was cosmetic).
- **Correction to the original plan:** rideOffset and the BlockDisplay corner-shift did **NOT** need
  axis-conditioning. rideOffset is a world-Y mount correction (the parent BlockDisplay is
  yaw-frozen/axis-aligned), and the corner-shift `(-0.5,0,-0.5)` produces a correct unit cube in
  *local* space that `rot` then rotates rigidly — both are axis-agnostic. They were left unchanged.
- `disassemble`: snaps the angle about the axis; added an **off-world Y guard** (drops blocks that
  swing below world-min/above world-max as items). For v1's **oak-planks-only** selection,
  `BlockRotation.rotateBlockData` is a no-op (planks have no orientation), so block-data rotation is
  moot.
- ⚠️ **Known remaining issue — drawbridge rotation origin (deferred):** for horizontal (X/Z) axes
  the `localTransform` reference is center-in-XZ but **bottom-corner-in-Y**, so rotation drifts the
  block ~0.5 off. The simple "center-Y" fix is **wrong** (it breaks Y-doors and minecarts — adds
  0.5 to an on-axis offset → blocks restore 0.5/1 block high). The correct fix is "center
  everything" (pass the pivot as block-center **and** make `localTransform` center-based together,
  which cancels for Y → stays integer → keeps doors/minecarts exact; plus corner-shift
  `(-0.5,-0.5,-0.5)`, collider, disassembly), touching `MechanismRegistry`, `BasicMechanism`,
  `DoorDemo`, `MinecartShipManager`, `RotationRotator`. Needs in-game iteration. See Remaining issues.

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

## Phase 3 — Glue (anchor-owned block selection; retrofits Rotator + minecart)

The shared "which blocks move together?" layer, replacing per-consumer flood-fill. **Glue is not
global per-block state.** Each **anchor** — a hinge skull, a minecart entity, or any mechanism
block/entity — **owns its glued set**, stored in **its own PDC** as offsets relative to the anchor.
A glued plank carries no marker of its own; it is "glued" only by being in some anchor's list.
**Only the anchor tracks gluing.**

### Why anchor-owned (consequences)
- **No global registry, no reverse index, no chunk-glue store, no `MechanismBlockData.glued`.**
- **Persistence is free for hinges:** the skull PDC is already round-tripped by CoreLib on chunk
  load (mirror `markBlock`/`setState` in `CustomBlockRegistry`, which set PDC then `skull.update()`).
- **Stale entries self-heal:** `resolveStructure` re-runs connectivity from the anchor, so a broken
  block — and any blocks it orphaned — drops out and is pruned. No `BlockBreak` bookkeeping needed.
- **Overlap = first-come:** if two anchors list the same block, whichever assembles first turns it
  to air; the other resolves it to air and skips. No conflict detection.
- The set is the anchor's persistent **shape definition** — authored once, reused every actuation;
  assemble/disassemble never mutate it.

### Storage
- Anchor PDC `NamespacedKey("corelib","glue")` → packed `int[]` of `(dx,dy,dz)` offsets relative to
  the anchor block. **Relative** ⇒ cross-chunk and anchor-movement are free (offsets stay valid
  wherever the anchor reloads); resolution still needs the target chunks loaded (bounds the
  structure, like vanilla redstone).
- Hinge: skull PDC (auto-persisted). Minecart: entity PDC — persist via the existing
  `MechanismSerializer` (entity PDC isn't part of the custom-block chunk-restore path).

### API — `GlueManager` (CoreLib, `MechanismRegistry` package)
- `glue(Anchor, Block)` — connectivity-checked; writes anchor PDC.
- `unglue(Anchor, Block)` — remove one block from the set.
- `unglueAll(Anchor)` — reset.
- `resolveStructure(Anchor)` → `List<Block>` — **BFS from the anchor through the stored offsets**,
  returning only the component still connected to the anchor; missing blocks **and blocks orphaned
  by a broken middle** are dropped and pruned from the set.
- `resolveStructure(Anchor, Matrix4f rot)` — for a displaced consumer (an open door): offsets are
  the canonical rest-frame shape; the consumer passes its current rotation.
- `Anchor` abstracts "block-with-PDC (skull)" vs "entity-with-PDC (minecart)".

### Authoring UX — glue mode
A per-player session bound to one anchor (`Map<Player,GlueSession>{anchor, pendingCorner}`,
in-memory, cleared on quit/timeout), driven by a PDC-tagged **slime-glue** item (not plain slime;
`isGlueItem(stack)` checks the tag):
- **Enter:** right-click the anchor with glue → start a session for it.
- **Rest-state only:** glue-mode requires the anchor at rest (not assembled / mid-swing) so offsets
  are stored in the canonical frame; entering on an assembled anchor is refused with feedback.
- **Single:** left-click a block → glue it; **sneak-left-click** a glued block → unglue just it.
- **Cuboid:** right-click a block = corner 1; right-click another = corner 2 → fill; corner resets.
- **Connectivity:** glues only blocks cardinally adjacent to the anchor or an already-glued block of
  this anchor (first glue must touch the anchor; cuboid glues the connected subset via BFS within
  box ∪ existing set; disconnected/air cells skipped, with feedback).
- **Visualize:** while the session is active, particle-outline this anchor's glued set (+ the
  pending corner) every ~10 ticks.
- **Reset:** sneak-right-click the anchor with glue → unglue all (feedback). **Exit:** right-click
  the anchor again, or switch off the glue item.
- **Cap** (256, configurable) checked on glue/cuboid; message the player on hit.

### Interaction conflicts (resolve via `isGlueItem`; verified vs `CoreLibPlugin.onPlayerInteract`)
The hinge already uses `onInteract` (target-angle GUI), the wrench, and sneak handling:
- **(A) glue-mode entry vs. target-angle GUI** (both right-click the hinge): the rotator
  `onInteract` checks `isGlueItem` **first** → enter glue-mode + return true; else fall through to
  the GUI.
- **(B) sneak-reset blocked by the sneak early-return** (`onPlayerInteract` ~line 666 only proceeds
  for the wrench): add an `isGlueItem` branch there so sneak+glue reaches the reset.
- **(C) left-click-to-glue vs. block breaking:** add a `LEFT_CLICK_BLOCK` handler — for the glue
  item in glue-mode, branch on sneak (left = glue, sneak-left = unglue), `setCancelled(true)` either
  way, and guard creative instant-break.

### Retrofit (this is what makes selection robust)
- **Rotator / DoorDemo:** structure = `resolveStructure(hinge)` instead of oak-planks `floodFill`.
  Authored once in glue mode, reused every open/close. **Removes the Phase-2 plank-leak caveat** —
  you glue exactly the door, with no material dependence, fixing same-material doors **and**
  drawbridges.
- **`MinecartShipManager`:** structure = `resolveStructure(cart)` instead of the material flood-fill
  (`:330`); the cart owns its set, authored before activation.
- **Transition:** keep material flood-fill as a fallback when an anchor has no glued set yet (so
  current demos work); glue is canonical once authored.

### Round-trip
The glued set is the anchor's unchanging shape definition: assemble resolves offsets → blocks →
removes them; disassemble restores at rest/snapped positions; the set stays in the anchor PDC for
the next actuation. Server-stop mid-swing: displays orphan (`cleanupOrphanedEntities`), and the
anchor still remembers its shape on reload.

### Notes
- **Session lifecycle:** end the session if the player disconnects or the anchor is broken;
  per-player sessions, last-write-wins if two players edit one anchor.
- **Minecart frame:** offsets are relative to the cart's *block* and must align with the assembly's
  snapped frame (the integer-offset invariant in `minecarts.md`).
- **Boilerplate:** slime-glue item creation + give command + recipe; config for the cap.

**Verify:** right-click hinge with glue → mode + particle outline; left-click adjacent / cuboid-fill
builds a door, non-adjacent rejected; open/close reuse the same set (no re-author); reset → all
unglued; break a glued block → pruned next open; glue a platform onto a cart → only glued blocks
ride; reload → the anchor still remembers its set; hit the cap → message.

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
