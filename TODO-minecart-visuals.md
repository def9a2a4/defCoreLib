# TODO — minecart chain-coupler visual (passenger-mount)

## Problem
Between two coupled train carts, `CartTrainManager.updateChainDisplays` renders one stretched chain
`BlockDisplay`. It looks correct on a **stopped** train but **jitters/thrashes** on a **moving** one.

## Root cause
The cart and the chain are drawn by two client motion models that can't stay phase-locked while moving:
- **Minecart** — *velocity dead-reckoning*: the client extrapolates each cart smoothly forward from the
  per-tick `cart.setVelocity(heading*speed)` hint (set in `CartTrainManager.place`), so it's *drawn*
  ~1 tick ahead of its server `getLocation()`.
- **BlockDisplay** — *teleport + transform interpolation*: ignores velocity; moves via two independent
  interpolators (`teleportDuration` for position, `interpolationDuration`/`Delay` for the matrix),
  each restarted every tick.

Stopped, all velocities are ~0 so every clock converges → steady. Moving, they beat against each other
→ jitter. (The v4 `getVelocity()*LERP_LEAD` endpoint lead also injects span noise on curves.)

Dead ends already ruled out: **pure snap** (teleportDuration 0) removes the beat but 20 Hz-steps and
detaches up to a block at speed; **interpolate** (current) desyncs into thrash.

## Approach — passenger-mount (inherit the cart's own drawn motion)
Make each chain display a **passenger** of one cart so it rides along on the cart's exact client-drawn
position — collapsing the three clocks to one. Minecarts reject non-living passengers at NMS level, so
mount through a **marker `ArmorStand` intermediary**. This is the repo's proven pattern for gluing
displays to moving carts: see `MechanismRegistry.java:500-535` and
`BasicMechanism.updateFromVehicle` (`BasicMechanism.java:746-781`).

### Steps (all in `CartTrainManager.java`)
1. **Spawn once per coupling** (in `updateChainDisplays`, when a coupling gains a display):
   - marker `ArmorStand`: `setMarker(true)`, `setInvisible(true)`, `setGravity(false)`, `setSilent(true)`,
     `setPersistent(false)`, tag `corelib:cart_chain`.
   - `cartA.addPassenger(armorStand)` (A = the coupling's first cart). Defer to the next tick if the
     same-tick `addPassenger` is rejected (mirror the mech precedent).
   - chain `BlockDisplay` (same block/material/brightness/viewRange as today) → `armorStand.addPassenger(display)`.
2. **Display settings:** `setTeleportDuration(0)`, `setInterpolationDuration(1)`, `setInterpolationDelay(0)`.
   **Never `teleport()` the display** — the passenger stack carries its position.
3. **Per tick, set only the matrix:**
   - `span = cartB.getLocation() − cartA.getLocation()` (server positions — the shared `train.speed`
     dead-reckon cancels in the difference, so **no velocity lead needed**).
   - Rotate `span` into cart A's **yaw-local frame** (`−cartA.getYaw()`) so the inherited passenger yaw
     doesn't double-rotate the chain (the hazard handled at `BasicMechanism.java:773-780`).
   - Build `T(mid)·R(+Y→span)·S(CHAIN_WIDTH, len, CHAIN_WIDTH)·T(−0.5,−0.5,−0.5)`, offset by `CHAIN_Y`
     plus the minecart+armorstand mount offset. `setTransformationMatrix(m)`.
4. **Lifecycle:** despawn the armorStand **and** its display together on uncouple, cart destruction,
   train rebuild, and shutdown. Extend `CartTrain.chainDisplays` (and `clearChainDisplays`) to track the
   armorStand alongside the display; keep both non-persistent so nothing survives a restart.
5. **Cleanup constants:** delete `CHAIN_INTERP` and `LERP_LEAD` (and the v4 forward-prediction). Keep
   `CHAIN_Y` (0.05), `CHAIN_WIDTH` (1.0), `CHAIN_MATERIAL`.

### Caveat
The anchored cart's single passenger slot is consumed, so an anchored train cart can't also seat a
player. Minor here — the coal/blast carts open a GUI on right-click and aren't ridden; only a plain
rideable minecart used as a coupling's cart A would be affected.

## Files
- `src/main/java/anon/def9a2a4/corelib/CartTrainManager.java` — `updateChainDisplays` (~583–636),
  `CartTrain.chainDisplays` + `clearChainDisplays`, and the uncouple/destroy/rebuild/shutdown paths;
  `CHAIN_*` constants (~65–72).
- Precedent to mirror: `MechanismRegistry.java:500-535`, `BasicMechanism.java:746-781`.

## Verification (user runs `make docs-server`; do NOT start the server)
- Couple two carts → chain sits just above the rail bridging the adjacent ends (stopped look preserved).
- Drive at 1.0 b/t on **straight** and through a **curve** → chain stays glued, **no jitter, no
  detachment gap**.
- Break / uncouple / relog → no orphaned armor stands or displays
  (`/kill`-count of `@e[type=armor_stand,tag=corelib:cart_chain]` is 0 when no trains exist).
