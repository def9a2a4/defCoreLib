# Extendable Pistons — Create-style multiblock piston

## Context

A **piston core** block, a straight line of **piston pole** segments, and a separate **piston head**
form a multiblock that extends the head — and any blocks glued to it — several blocks along an axis,
then retracts. Rotation-powered, in the style of Create's mechanical piston.

Reuses the glue mechanism from minecarts/rotators ([`rotation-mechanisms.md`](rotation-mechanisms.md),
[`minecarts.md`](minecarts.md)) and the contraption engine described in
[`blockships-integration.md`](blockships-integration.md).

### Core insight — translate via `move()`, no matrix rewrite

The contraption transform is **rotation-only**: `BasicMechanism.rotate()` (~`BasicMechanism.java:117`)
builds a pure-rotation `Matrix4f` about `rotationAxis`; there is no translation field. **But
`move(position, yaw)` (`:202-207`) teleports the vehicle and sets `pivot = position`, then calls
`rotate(yaw)`** — so the entire contraption translates bodily by driving its vehicle/pivot, using only
existing code. And `disassemble()` (`:246`) snaps yaw to 90° and lands blocks at
`pivot + Math.round(offset)`, so translating to an **integer cell with yaw 0 disassembles cleanly**.
This means the piston needs **no transform-math changes** — it drives the head+payload via `move()`
cell-by-cell and disassembles at the target cell.

## MVP scope (Round 1 — decided)

- **Arm-only first.** core + poles + head extend/retract as a moving contraption (head assembled as a
  1-block mechanism) + a rendered pole arm. **No glued payload yet** — this round exists to prove the
  `move()`-translation keystone end-to-end. Glue-carried payload is Round 2.
- **Power = rotation-gated + redstone edge.** The core is a rotation-network `CONSUMER`; a **redstone
  rising edge** fires an extend *only if* the core's network is currently powered (`isPowered`); a
  **falling edge** retracts. (Reverse-spin-to-retract is deferred to keep Round 1 simple.)

### Keystone verified (safe, minimal)
- `move(pos, yaw)` (`BasicMechanism.java:202`) teleports the vehicle + sets `pivot` + `rotate(yaw)` —
  bodily translation with no matrix change. **`Mechanism.move()` currently has ZERO callers** in the
  codebase, so it is free to adjust.
- **One required 2-line fix:** the tick loop runs `updateFromVehicle()` every tick
  (`MechanismRegistry.java:397`), and its `pivot.add(delta)` (`BasicMechanism.java:394`) would
  *double-count* a `move()` teleport because `move()` never updates `previousVehicleLoc`. Fix: at the
  end of `move()`, set `previousVehicleLoc = position.clone()` / `previousVehicleYaw = yaw` so
  `updateFromVehicle()` sees "no drift" and stays inert. Doors/rotators keep their owned vehicle
  stationary so they're unaffected; minecarts never call `move()`.
- **Assemble path:** `assembleMechanism(type, blocks, pivot, axisVec, serializer)`
  (`MechanismRegistry.java:71`) already spawns an owned invisible ArmorStand vehicle — assemble the
  head block through it, then drive `move()` each tick from the piston's own ticker.

## Design decisions

- **Three blocks** (all via the builder pattern):
  - `mech:piston_core` — the powered base; a `RotationNetwork` `CONSUMER` node, oriented to a facing
    axis.
  - `mech:piston_pole` — a repeatable shaft segment; structural, `cancelPistons(true)`, glueable.
  - `mech:piston_head` — the pushing face; structural, glueable on its front.
- **Multiblock validation (new — none exists today).** The codebase has only connectivity flood-fill
  (`GlueManager.java:53-168`, `FloodFill.java:37`), no shape/pattern matching. Add a short
  **axis-directed linear scan**: from the core, step along its facing axis consuming contiguous
  `piston_pole` blocks, requiring a terminating `piston_head`. The pole count sets the max extension
  distance. Reject a malformed assembly (gap, no head, forked) with player feedback.
- **Payload = glue on the head.** Blocks are glued to the head with `GlueManager` on a `BlockAnchor`
  at the head block. On extension the head + glued payload travel together.
- **Rotation-powered.** The core is a `CONSUMER`; on a **redstone rising edge** (like
  `RotationRotator.onNeighborChange`, `RotationRotator.java`) with network power available, it extends;
  a redstone falling edge (or a reversed network spin, CCW) retracts. Extension speed is
  `clamp(K * surplus / mass, MIN, MAX)` (reuse the `RotationRotator` clamp, `:37-42`), and the core
  registers transient demand while moving so contending consumers slow each other.
  *(Rotation-powered is recommended to fit the ecosystem and reuse the network + speed clamp; a
  redstone-only variant is the simpler fallback if rotation coupling proves fiddly.)*

## Work blocks (execution order)

References are `file` `symbol` (`~line`) against current source.

### A. Three blocks + core node — low risk, do first
- `rotation-blocks.yml` — add `mech:piston_core` (with `idle_*`/`spinning_*` + `placement_state_map`
  for the facing axis), `mech:piston_pole`, `mech:piston_head`.
- `RotationBlocks` — overlay the core as a `CONSUMER` (`onChunkLoad` → `addNode(... CONSUMER, power,
  false)`, `onNeighborChange` → rising-edge check, `onBlockRemoved` → `removeNode`); overlay pole/head
  as inert structural blocks (`drillable(false)`, `cancelPistons(true)`).
- **Verify:** all three place; the core joins the network and reacts to redstone/power.

### B. Multiblock validator
- New `ExtendablePistonManager.validate(core)`: read the core's facing axis; step
  `block = block.getRelative(axisFace)` while `getTypeFromBlock(block)` is `piston_pole`, counting
  segments; require the next block to be `piston_head`. Return `(poleCount, headBlock)` or a typed
  failure. Directed analogue of `FloodFill.component` (`FloodFill.java:37`).
- **Verify:** a well-formed core→poles→head validates with the right length; a gap / missing head /
  branch is rejected with the right message.

### C. Extend / retract motion — the core
- New `ExtendablePistonManager`. On an activation trigger:
  1. Validate (B); resolve the head's glued payload via `GlueManager.resolveStructure` on a
     `BlockAnchor` at the head.
  2. Assemble `{head + glued payload}` into a mechanism via
     `MechanismRegistry.assembleMechanism(type, blocks, vehicle, rideOffset, serializer)` (`:100`) on
     an **owned** vehicle (armour stand — `ownsVehicle = true` so it is cleaned up on disassembly).
  3. Each tick, `move(pivot + axisStep, 0)` at `clamp(K*surplus/mass)` speed until the target
     extension (pole count) or an obstruction is reached, then `disassemble()` at the final integer
     cell (yaw 0 → clean landing).
  4. Retract reverses the axis step back toward the core, then disassembles at the retracted cell.
- **Obstruction:** before advancing into the next cell, apply the same conflict check `disassemble()`
  uses (solid block / off-world / fluid, `BasicMechanism.java:246`); stop (and optionally stall the
  network like a jam) if blocked.
- **Glue rebind on stop:** set `mech.setOnDisassembled(placed -> glueManager.setStructure(anchor,
  placed))` so the payload's offsets track its new rest position (the same seam the door/rotator/
  minecart paths use).
- **Verify:** the head + a glued payload travels N blocks out and back; an obstruction stops it; the
  landed blocks are grid-aligned; the payload stays glued across the cycle.

### D. Pole rendering
- Render the extending shaft as block/item-display segments between the core and the moving head,
  stretched each tick. Simplest v1: a single scaled/placed pole `BlockDisplay` spanning core→head;
  richer version: one segment per exposed pole cell.
- **Verify:** the pole visually spans core→head throughout the travel and disappears on full retract.

## Risks

1. **Obstruction & entities mid-travel** — blocking blocks (handled) and entities in the path
   (push or stop?) need a policy.
2. **Retract collision at the core** — the head must not overrun the core; clamp the retract target.
3. **Glue rebind** — depends on the `setOnDisassembled` → `setStructure` seam being wired correctly,
   or the payload detaches after one cycle.
4. **Render vs. collider sync** — the pole render and the moving colliders must stay aligned during
   translation (colliders are handled by `BasicMechanism` already; the pole render is the new part).
5. **Vanilla piston interaction** — pole/head/core must set `cancelPistons(true)` so a vanilla piston
   can't shear the multiblock.
6. **No transform-math change** relies on the `move()` + integer-cell + yaw-0 disassembly invariant;
   if a future combined translate+rotate is wanted, that *does* require a `BasicMechanism` translation
   field (out of scope here).

## Verification (end-to-end, Paper 1.21.8)

`./gradlew build`, load on a test server, then:
1. Build core → N poles → head along an axis; validate → correct length; malformed layouts rejected.
2. Glue a small structure to the head; power + pulse the core → head + payload extends N blocks and
   retracts, landing grid-aligned, payload still glued.
3. Place a block in the extension path → the piston stops at the obstruction (and stalls/holds).
4. Reload / restart mid-cycle → no orphaned displays or armour stands (`ownsVehicle` cleanup).
5. Confirm a vanilla piston cannot move the core/pole/head (`cancelPistons`).

Temp artifacts → `defCoreLib/.temp/` (per module CLAUDE.md).
