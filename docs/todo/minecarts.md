# Minecart Mechanism: Off-Center Pivot Rotation Bug

## Symptom

When a minecart mechanism is assembled while the cart is not exactly at the center of
a block, visual rotation is wrong. Rotation looks correct when the cart IS centered.

User report: "rotation seems mostly right now, unless the cart is activated when not
in the center of a block."


## Background: How The Transform System Works

### Entity hierarchy

```
vehicle (Minecart or ArmorStand)
  └── parent (BlockDisplay AIR, invisible intermediary)
        ├── display_0 (BlockDisplay or ItemDisplay — primary)
        ├── display_1 (additional display entity)
        └── ...

collider_0 (ArmorStand marker + Shulker — NOT a passenger, teleported independently)
collider_1 ...
```

Two vehicle paths:
- **Owned (ArmorStand, e.g. DoorDemo)**: parent mounts as passenger of vehicle
  (`vehicle.addPassenger(parent)`). Parent inherits vehicle position automatically.
  `ownsVehicle = true`.
- **External (Minecart, e.g. MinecartShipManager)**: parent is teleported to vehicle
  position each tick (minecarts silently reject `addPassenger()` for non-living entities
  at NMS level). `ownsVehicle = false`.

### Local transform computation (MechanismRegistry.assembleCore, lines 88-98)

At assembly, block offsets are computed relative to a **snapped pivot** — the nearest
block center to the vehicle position:

```java
float snapX = (float)(Math.floor(pivot.getX()) + 0.5);   // line 91
float snapZ = (float)(Math.floor(pivot.getZ()) + 0.5);   // line 92
// ...
Matrix4f local = new Matrix4f().translation(
    (block.getX() + 0.5f) - snapX,                        // line 96
    block.getY() - (float) pivot.getY(),                   // line 97
    (block.getZ() + 0.5f) - snapZ);                        // line 98
```

This guarantees XZ offsets are **always integers** (center-to-center). Integer offsets
are load-bearing: `rotateY(90°)` maps integers to integers (e.g. `(1,0,0)` → `(0,0,-1)`),
so `Math.round()` in `disassemble()` gives exact block positions.

Proven broken without integers: vehicle at `(10.0, 64, 20.0)`, block offset `(0.5, 0, 1.5)`,
rotated 90° → `Math.round(1.5) = 2`, wrong block. The integer invariant is not optional.

### Display positioning (BasicMechanism.rotate, lines 107-152)

Displays are passengers of the parent entity. Their transforms are set via
`display.setTransformationMatrix(matrix)`, which positions them **relative to the parent**.

```java
Matrix4f rot = new Matrix4f().rotateY((float) Math.toRadians(-yaw));  // line 110
Matrix4f dm = new Matrix4f(rot).mul(mb.localTransform);               // line 115
dm.m31(dm.m31() - rideOffset);                                        // line 116
```

So `display_world_position = parent_position + rot * local_offset - rideOffset_Y`.

### Collider positioning (BasicMechanism.rotate, lines 144-151)

Colliders are **NOT** passengers. They're teleported to world coordinates each rotation:

```java
Vector3f worldOff = rot.transformPosition(
    blocks.get(cp.blockIndex()).localTransform.getTranslation(...));
TeleportCompat.teleport(cp.carrier(),
    pivot.clone().add(worldOff.x, worldOff.y, worldOff.z));           // line 150
```

So `collider_world_position = pivot + rot * local_offset`.

### The invariant that must hold

For displays and colliders to agree, the parent entity and the pivot must be at the
**same position** (the snapped position that the local transforms are relative to):

```
display_world = parent_pos + rot * offset
collider_world = pivot      + rot * offset
                 ↑ must equal ↑
```


## Root Cause (Two Interacting Issues)

### Issue 1: Pivot overwrites raw vehicle position

`BasicMechanism.updateFromVehicle()` (line 307) overwrites the pivot with the raw vehicle
location every tick:

```java
this.pivot = loc.clone();   // line 307 — raw vehicle position, NOT snapped
```

The local transforms were computed from the snapped pivot (e.g. X=5.5). If the vehicle
moves to X=5.7, the pivot becomes 5.7, but the local offsets still assume the origin is
at 5.5. The rotation center shifts by 0.2 blocks. Over a full 360° rotation, blocks
visibly wobble around the wrong center.

### Issue 2: 1-tick delay allows NMS physics drift

`MinecartShipManager.assemble()` calls `snapAndStop()` before assembly, which teleports
the minecart to block center and zeros velocity:

```java
// MinecartShipManager lines 244-249
Location center = minecart.getLocation().getBlock().getLocation().add(0.5, 0, 0.5);
center.setYaw(minecart.getLocation().getYaw());
center.setPitch(0);
minecart.teleport(center);
minecart.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
```

After `snapAndStop`, the vehicle IS at block center, so `snapX - pivot.X = 0`. All good.

But `assembleCore()` schedules a 1-tick delay (lines 230-241) before mounting displays and
setting initial transforms. During that tick:

1. **NMS minecart physics run** — the minecart is on a powered activator rail. Adjacent
   powered rails or rail slopes can impart new velocity despite `setVelocity(0,0,0)`,
   because NMS recalculates movement independently from the Bukkit velocity.
2. **Y adjusts** — `snapAndStop` sets Y to the block's integer Y, but minecarts ride at
   `blockY + 0.0625` on flat rails. The rail physics correct the Y on the next tick.
3. **Yaw may recalculate** — `AbstractMinecart.tick()` recalculates yaw from movement
   direction every tick.

When the delay lambda runs on tick N+1:

```java
// MechanismRegistry line 238 — reads CURRENT vehicle position (possibly drifted)
TeleportCompat.teleport(parentDisplay, vehicle.getLocation());
```

Then `updateFromVehicle()` runs in the same tick's `tickMechanisms()`, detects movement
(raw vehicle pos vs stored snapped pos), and overwrites `pivot` with the drifted position.
From this point on, the snapped frame is lost.

### How both issues interact

`snapAndStop` centers the cart (offset = 0), but the 1-tick delay lets it drift (offset ≠ 0).
Then `updateFromVehicle` overwrites pivot with the drifted position, destroying the
snapped coordinate frame. All subsequent rotations orbit the wrong center.


## Fix: Delta-Tracked Snapped Pivot

### Concept

Instead of overwriting `pivot` with the raw vehicle position each tick, **track the
vehicle's delta movement** and apply it to the existing pivot. The pivot starts at the
snapped position (from `assembleCore`) and accumulates vehicle deltas. It always stays
in the snapped coordinate frame — offset from the vehicle by a constant correction.

```
pivot_new = pivot_old + (vehicle_pos_now - vehicle_pos_prev)
         = snap_center + Σ(all vehicle deltas)
         = snap_center + (vehicle_pos_now - vehicle_pos_at_assembly)
```

No new fields needed. No offset to remember to apply. One clean invariant: **pivot is
always in the snapped frame**.

### Why not explicit snap offset fields?

Three critique agents evaluated this alternative and found problems:
- `move()` (line 155) doesn't apply offsets → colliders/displays desync after `move()`
- Owned vehicle path: ArmorStand spawns at raw position (line 61) before `assembleCore`
  snaps — if caller passes non-centered Location, ArmorStand and snap frame disagree
- Non-obvious invariant: "remember to add snapOffset at every site that reads pivot or
  positions the parent" — easy to miss in future changes

Delta tracking avoids all three: no offsets to apply, no invariant to remember beyond
"don't overwrite pivot with raw vehicle pos".

### Why not just fix snapAndStop?

`snapAndStop` already works. The issue is the 1-tick delay undoing it. More importantly,
the fix should be in the core mechanism layer — future consumers (BlockShips migration,
new vehicle types) shouldn't need to know about snapping.

### Why not use non-integer offsets?

Proven broken for disassembly. See the numeric proof in the "Local transform computation"
section above. The integer offset invariant is load-bearing.


## Detailed Changes

### Change 1: MechanismRegistry.assembleCore() — snap the pivot (lines 91-92)

After computing `snapX`/`snapZ`, snap the pivot Location before any downstream use.
Currently the raw pivot flows through to collider spawning (line 195), parent spawning
(line 142), and the BasicMechanism constructor (line 218).

**Current code (lines 91-92):**
```java
float snapX = (float)(Math.floor(pivot.getX()) + 0.5);
float snapZ = (float)(Math.floor(pivot.getZ()) + 0.5);
```

**New code (insert after line 92):**
```java
pivot = pivot.clone();       // don't mutate caller's Location
pivot.setX(snapX);
pivot.setZ(snapZ);
```

**What this affects downstream (all correct):**
- Line 97: `block.getY() - (float) pivot.getY()` — Y unchanged, still correct
- Line 142: `spawnLoc = pivot.clone().add(0, 2.5, 0)` — temporary spawn pos, fine
- Line 195: `carrierLoc = pivot.clone().add(initOff.x, ...)` — colliders now at snapped pos ✓
- Line 218: `new BasicMechanism(..., pivot, ...)` — receives snapped pivot ✓

### Change 2: MechanismRegistry 1-tick delay — teleport parent to snapped pivot (line 238)

Currently the parent is teleported to the raw vehicle position, which may have drifted
during the 1-tick delay. Also, the initial teleport doesn't zero yaw (unlike
`updateFromVehicle` which does).

**Current code (lines 237-238):**
```java
// Teleport parent to vehicle position; updateFromVehicle() will maintain this each tick
TeleportCompat.teleport(parentDisplay, vehicle.getLocation());
```

**New code:**
```java
Location parentLoc = mech.pivot();
parentLoc.setYaw(0);
parentLoc.setPitch(0);
TeleportCompat.teleport(parentDisplay, parentLoc);
```

This fixes two things:
1. Parent starts at snapped position, not drifted vehicle position
2. Zeroes yaw on initial teleport (prevents double-rotation on first frame)

### Change 3: BasicMechanism constructor — init previousVehicleLoc from vehicle (line 76)

The movement detection in `updateFromVehicle()` compares `vehicle.getLocation()` against
`previousVehicleLoc`. If `previousVehicleLoc` is initialized from the snapped pivot
(current behavior) and the vehicle drifted during the 1-tick delay, the first tick would
see a spurious delta equal to the drift. With delta tracking, this spurious delta would
shift the pivot by the drift amount — which is actually fine (it corrects for the drift),
but semantically we should track the raw vehicle position.

**Current code (line 76):**
```java
this.previousVehicleLoc = pivot.clone();
```

**New code:**
```java
this.previousVehicleLoc = vehicle.getLocation();
```

Note: after Change 1, `pivot` is the snapped position. The vehicle may or may not be at
that position (depends on whether `snapAndStop` succeeded and whether the 1-tick delay
caused drift). Using `vehicle.getLocation()` ensures the first delta is zero if the
vehicle hasn't moved, or exactly the drift if it has.

### Change 4: BasicMechanism.updateFromVehicle() — delta-track pivot (lines 307, 312-316)

**Current code (line 307):**
```java
this.pivot = loc.clone();
```

**New code (replace line 307):**
```java
this.pivot.add(
    loc.getX() - previousVehicleLoc.getX(),
    loc.getY() - previousVehicleLoc.getY(),
    loc.getZ() - previousVehicleLoc.getZ());
```

The pivot accumulates vehicle movement deltas instead of being overwritten. It stays in
the snapped frame.

**Current parent teleport code (lines 312-316):**
```java
if (!ownsVehicle) {
    Location parentLoc = loc.clone();
    parentLoc.setYaw(0);
    parentLoc.setPitch(0);
    TeleportCompat.teleport(parent, parentLoc);
}
```

**New parent teleport code:**
```java
if (!ownsVehicle) {
    Location parentLoc = this.pivot.clone();
    parentLoc.setYaw(0);
    parentLoc.setPitch(0);
    TeleportCompat.teleport(parent, parentLoc);
}
```

The parent follows the snapped pivot, not the raw vehicle position.

### Also update the cross-world guard (lines 298-301)

The cross-world guard resets `previousVehicleLoc` on world change. It should also reset
the pivot to the vehicle's new snapped position:

**Current code (lines 298-301):**
```java
if (!loc.getWorld().equals(previousVehicleLoc.getWorld())) {
    previousVehicleLoc = loc.clone();
    previousVehicleYaw = yaw;
    return;
}
```

**New code:**
```java
if (!loc.getWorld().equals(previousVehicleLoc.getWorld())) {
    this.pivot = loc.clone();
    this.pivot.setX(Math.floor(loc.getX()) + 0.5);
    this.pivot.setZ(Math.floor(loc.getZ()) + 0.5);
    previousVehicleLoc = loc.clone();
    previousVehicleYaw = yaw;
    return;
}
```


## What Doesn't Change

- **`rotate()`** (lines 107-152): Uses `this.pivot` for collider positioning and
  `currentTransform` for display matrices. Both are correct once pivot is in the
  snapped frame.

- **`move()`** (lines 155-159): Consumer-driven API. Sets pivot absolutely — the caller
  provides the position. If the consumer wants snapped positions, they snap before
  calling. No current consumers use `move()` for the minecart path (minecarts use
  autonomous `updateFromVehicle`). DoorDemo uses `rotate()` only, not `move()`.

- **`disassemble()`** (lines 192-225): Uses `this.pivot` + `Math.round(rotated_offset)`
  for block placement. With pivot in the snapped frame (X/Z at .5), this gives correct
  block positions. Example: `pivot=(10.5, 64, 20.5)`, offset `(1, 0, 0)` rotated 90° →
  `(0, 0, -1)`, block at `(10.5+0, 64, 20.5-1)` = `(10.5, 64, 19.5)`, `getBlock()` = 
  `(10, 64, 19)`. Correct.

- **`assemblyYaw`** (line 75): Raw vehicle yaw at assembly, used for delta rotation
  (`yaw - assemblyYaw`). Not affected by XZ snapping.

- **ArmorStand (owned) path**: For the DoorDemo, `pivot` is `head.getLocation().add(0.5, 0, 0.5)`.
  Since `head.getLocation()` returns integer block coords, `pivot.X` is already at `.5`.
  `snapX = floor(.5) + 0.5 = 0.5 = pivot.X`. Snap offset is zero. `ownsVehicle = true`,
  so the `updateFromVehicle` parent-teleport branch is never hit (parent is a passenger,
  follows automatically). Delta tracking is harmless — pivot tracks the ArmorStand's
  movement, which matches the passenger chain.

- **No new fields on BasicMechanism.** The delta is computed inline from
  `(loc - previousVehicleLoc)` each tick.


## Numeric Walk-Through

Vehicle at assembly after snapAndStop: `(5.5, 64, 10.5)`.
Block originally at world position `(6.5, 65, 10.5)`.
`snapX = 5.5`, `snapZ = 10.5`.
`localTransform = (6.5 - 5.5, 65 - 64, 10.5 - 10.5) = (1, 1, 0)`.

### No rotation (yaw = 0)

- `rot = identity`
- `display_world = parent_pos + (1, 1, 0) = (5.5+1, 64+1, 10.5+0) = (6.5, 65, 10.5)` ✓
- `collider_world = pivot + (1, 1, 0) = (5.5+1, 64+1, 10.5+0) = (6.5, 65, 10.5)` ✓

### 90° rotation

- `rotateY(-90°)` maps `(1, 1, 0)` → `(0, 1, 1)` (JOML: `x'=-z, z'=x` for angle -90°,
  but with `cos(0)=0, sin(-90°)=-1`: `x' = x*0 + z*(-1) = 0`, `z' = -x*(-1) + z*0 = 1`)
- `display_world = parent_pos + (0, 1, 1) = (5.5, 65, 11.5)` ✓
- `collider_world = pivot + (0, 1, 1) = (5.5, 65, 11.5)` ✓

### After vehicle moves to `(8.3, 64, 12.7)`

Delta = `(8.3-5.5, 0, 12.7-10.5) = (2.8, 0, 2.2)`.
`pivot = (5.5+2.8, 64, 10.5+2.2) = (8.3, 64, 12.7)`.

Wait — that's the same as the vehicle. Is the snap offset lost?

No. At the NEXT tick, if the vehicle moves to `(8.4, 64, 12.8)`:
Delta = `(8.4-8.3, 0, 12.8-12.7) = (0.1, 0, 0.1)`.
`pivot = (8.3+0.1, 64, 12.7+0.1) = (8.4, 64, 12.8)`.

In this example the snap offset was zero (snapAndStop worked). The delta tracking
preserves this — pivot equals vehicle position, exactly as before.

### After vehicle moves, with 1-tick drift

Assembly: vehicle at `(5.5, 64, 10.5)` (snapped). `pivot = (5.5, 64, 10.5)`.
1-tick delay: NMS pushes vehicle to `(5.6, 64.0625, 10.5)`.
`previousVehicleLoc = (5.5, 64, 10.5)` (from `vehicle.getLocation()` at construction).

First `updateFromVehicle`:
- `loc = (5.6, 64.0625, 10.5)`
- `delta = (0.1, 0.0625, 0)`
- `pivot = (5.5+0.1, 64+0.0625, 10.5+0) = (5.6, 64.0625, 10.5)`
- Parent teleported to `(5.6, 64.0625, 10.5)` with yaw=0

Hmm, pivot is now at the drifted position, not the snap center. But the parent is ALSO
at the drifted position. And colliders use pivot. So displays and colliders agree.

The question is: is the drift (0.1 blocks) visible? On the first tick, the blocks shift
0.1 blocks from their original world positions. This is a small visual pop, smaller than
the block-scale artifacts the user was reporting.

For comparison, WITHOUT this fix: the pivot is overwritten to `(5.6, 64.0625, 10.5)` on
the first tick anyway (same end state). The fix doesn't prevent the 1-tick drift — it
ensures the drift is handled consistently rather than as a frame-1 glitch.

The key value of the fix is when `snapAndStop` fails to fully center the cart (e.g.
right-click on a moving cart, or rail physics fight the teleport). In that case, the
initial snap offset is non-zero, and delta tracking preserves it through all subsequent
movement.

### With failed snapAndStop (the actual bug case)

Vehicle at assembly: `(5.7, 64, 10.3)` (snapAndStop failed or was bypassed).
`snapX = 5.5`, `snapZ = 10.5`.
Change 1 snaps pivot: `pivot = (5.5, 64, 10.5)`.
`localTransform` for block at `(6.5, 65, 10.5)`: offset `(1, 1, 0)`.
`previousVehicleLoc = vehicle.getLocation() = (5.7, 64, 10.3)`.

1-tick delay: parent teleported to `mech.pivot() = (5.5, 64, 10.5)`. Parent is at snap
center, NOT at vehicle position. Displays render at correct world positions.

First `updateFromVehicle`:
- `loc = (5.7, 64, 10.3)` (vehicle hasn't moved — same as assembly)
- `delta = (5.7-5.7, 0, 10.3-10.3) = (0, 0, 0)` — no movement detected
- `moved = false` → skip. Pivot stays at `(5.5, 64, 10.5)`.

Vehicle starts moving on rail to `(6.2, 64, 10.3)`:
- `delta = (6.2-5.7, 0, 10.3-10.3) = (0.5, 0, 0)`
- `pivot = (5.5+0.5, 64, 10.5+0) = (6.0, 64, 10.5)`
- Parent teleported to `(6.0, 64, 10.5)` — still offset from vehicle by `(-0.2, 0, 0.2)`

At yaw=0: display at `(6.0+1, 65, 10.5+0) = (7.0, 65, 10.5)`. Collider at same.
At yaw=90°: display at `(6.0+0, 65, 10.5+1) = (6.0, 65, 11.5)`. Collider at same.

The mechanism rotates around `(6.0, 64, 10.5)`, which is **exactly 0.5 blocks east of the
vehicle**. This is because the vehicle was 0.2 blocks east and 0.2 blocks south of the
snap center at assembly. The mechanism maintains that geometric relationship throughout
movement.

**Without the fix**: pivot = vehicle pos = `(6.2, 64, 10.3)`. Display at `(7.2, 65, 10.3)`.
Collider at `(7.2, 65, 10.3)`. At 90°: display at `(6.2, 65, 11.3)`. The rotation center
is the raw vehicle position. The integer offsets `(1, 1, 0)` are added to a non-snap
position, so blocks don't align with the world grid. Disassembly at `(6.2+1, 65, 10.3+0)` =
`(7.2, 65, 10.3)` → `getBlock()` = `(7, 65, 10)` — correct only because the offset
happened to be exactly 1. For a rotated case, `Math.round(offset)` could give the wrong
block.


## Floating-Point Drift Analysis

Over N ticks of movement, the delta-tracked pivot accumulates:

```
pivot = snap_center + Σᵢ (vehicle_posᵢ - vehicle_posᵢ₋₁)
```

Each delta is a double subtraction (15+ significant digits). For N=100,000 ticks at
60Hz ≈ 28 minutes of continuous movement, the accumulated error is on the order of
`N * ε * max_coord ≈ 10⁵ * 10⁻¹⁵ * 10⁴ ≈ 10⁻⁶` blocks — far below pixel resolution.

Mechanisms are typically active for minutes, not hours. And disassembly uses `Math.round()`
on integer-valued offsets, so sub-pixel drift in the pivot doesn't affect block placement.


## Testing Checklist

1. Place minecart on powered rail, build blocks above, activate at block center → 
   rotation correct (baseline, should still work)
2. Push minecart partway between blocks (off-center), right-click to assemble →
   rotation should now be correct (was broken)
3. Move assembled mechanism through rail curves → rotation tracks smoothly
4. Disassemble after movement + rotation → blocks restore to correct world positions
5. Disassemble after 90° rotation → blocks at correct rotated positions
6. Test door demo → completely unaffected (snap offset = 0, owned path, no changes hit)
7. Activate on powered activator rail while cart is moving → should snap and assemble
   correctly (tests snapAndStop + 1-tick delay)
8. Toggle collider glow, verify colliders align with display blocks during rotation


## Additional Issues (Review 2026-06-25)

Deep review with three parallel agents. Issues below are independent of the off-center
pivot bug above; they can be fixed separately.

### 1. Float precision in snap computation (LOW)

`MechanismRegistry.java:91-92` casts to `float` prematurely:

```java
float snapX = (float)(Math.floor(pivot.getX()) + 0.5);
float snapZ = (float)(Math.floor(pivot.getZ()) + 0.5);
```

Float mantissa is 24 bits (~7 decimal digits). At coordinates beyond ~8M blocks,
`floor(X) + 0.5` can't represent the `.5` offset — the snap silently rounds to an
integer, breaking the integer-offset invariant that `disassemble()` depends on.

**Fix:** Use `double` for snapping, cast to `float` only at Matrix4f construction:

```java
double snapX = Math.floor(pivot.getX()) + 0.5;
double snapZ = Math.floor(pivot.getZ()) + 0.5;
// ...
Matrix4f local = new Matrix4f().translation(
    (float)((block.getX() + 0.5) - snapX),
    (float)(block.getY() - pivot.getY()),
    (float)((block.getZ() + 0.5) - snapZ));
```

Same pattern applies to the cross-world re-snap in the pivot fix (Change 5).

### 2. Flood fill silent truncation (LOW)

`MinecartShipManager.java:330`: when `result.size() >= maxBlocks` (256), the ship is
silently truncated — no warning, no feedback to the player.

**Fix:** Log a warning after the loop if the limit was hit, or send a message to
the nearest player. Consider making the limit configurable in `minecart-ship-blocks.yml`.

### 3. tickMechanisms() iteration safety (LATENT)

`MechanismRegistry.java:309` iterates `activeMechanisms.values()` directly. Currently
safe because `updateFromVehicle()` never removes mechanisms. But `shutdown()` (line 258)
already defensively copies with `new ArrayList<>(...)`, suggesting awareness of the risk.

If future code adds validity-based cleanup during tick, this would throw
`ConcurrentModificationException`.

**Fix:** Defensive copy: `for (BasicMechanism mech : new ArrayList<>(activeMechanisms.values()))`.

### 4. Matrix allocation in tick loop (PERF)

`MechanismRegistry.java:329-331` allocates a new `Matrix4f` for `base` per animated
display per block per tick. Only `workMatrix` (line 41) is reused.

Negligible for <10 mechanisms but noticeable at 50+ (relevant for BlockShips migration).

**Fix:** Pre-allocate `workBase` alongside `workMatrix`.

### 5. Vehicle validity check at assembly (DEFENSIVE)

`assembleMechanism(Entity existingVehicle, ...)` at line 74 doesn't verify the vehicle
is valid/alive. A dead or removed entity silently produces a broken mechanism.

**Fix:** Guard at entry: `if (!existingVehicle.isValid()) throw new IllegalArgumentException(...)`.

### 6. Particle ticking not implemented (FEATURE GAP)

`MechanismRegistry.java:340`: `// TODO: particle ticking for mechanism blocks`.
`ParticleConfig` is resolved and stored on `MechanismBlockData` but never spawned
during the tick loop. Blocks that declare particles in their YAML config won't
emit them while assembled into a mechanism.

### 7. Minor cleanup

- `MechanismBlockData.collisionScale` — stored (always `1.0f`) but never read anywhere.
  Placeholder for future shulker scaling; harmless but should be documented or removed.
- `RotationNetwork.perpendicularNeighbors()` (line 397) — dead code, never called.
- `MINECART_RIDE_OFFSET = 0f` — previously flagged as needing tuning in
  `blockships-integration.md`, but agents confirmed 0f is correct for the external-vehicle
  path (parent follows via teleport, not as passenger). Not a bug.
