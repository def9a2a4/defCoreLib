# Deferred items — glue-landing / unload-hardening round

Tracked-but-not-done items surfaced while fixing glue-tracks-placement, cart destroyability, and
world-unload data loss. Ordered by rough user impact.

## 1. Full crash / restart persistence (data loss) — biggest remaining risk

Assembly airs source cells to AIR **on disk immediately** (`MechanismRegistry.airOutSourceBlocks`);
only a clean `onDisable` / `EntitiesUnloadEvent` / `WorldUnloadEvent` disassemble restores them. A
**hard crash** (`kill -9`, OOM, power loss) while something is assembled leaves a permanent hole, and
the cart returns *unassembled + frozen* (`scanChunkForMinecarts` re-registers `mechanism == null`).

The persistent display/collider entities survive the crash, so **recovery-from-entities** (aligns with
`blockships-integration.md`) is the right architecture — but it is NOT lossless today: a custom
block's primary is an `ItemDisplay` head texture with no registry id; mid-rotation `currentYaw`, ghost
flags, `collision.offset`, carried container storage, and per-block `configPdc` are stored nowhere.

**Design to implement later:** at assembly, stamp a small PDC on each primary display (localOffset,
customTypeId+state, collision {enabled,size,offset}, ghost/spinReversed/wallFacing, glueOffsets,
configPdc, storage) and mech-level data on the vehicle (type, rotationAxis, currentYaw, rideOffset,
ownsVehicle, blockCount); on chunk load, group `corelib:mech:{id}:*` entities by id, rehydrate
`MechanismBlockData`/`ColliderPair`, rebuild the `BasicMechanism`, re-register (the cart's
`corelib:mech:{id}:vehicle` tag links cart→id), and demote `cleanupOrphanedEntities` to a fallback
that only reaps ids recovery couldn't rebuild. Fits the unused `MechanismSerializer.onRecoveryComplete`
stub.

## 2. Asymmetric mechanism-cart curve jam (frozen-rotation stopgap)

Mechanism-cart rotation is frozen (`BasicMechanism.updateFromVehicle` no longer calls `rotate(delta)`;
the yaw-fold is commented out). `CartCollision` reads the frozen identity transform, so an **asymmetric**
glued cart keeps its assembly orientation through a rail curve while its travel axis flips — its
sideways footprint then jams against tunnel walls (repeated hard-stop + `resnapIfPenetrating`).
Symmetric carts are fine (which is why it survives casual testing). Real fix: the **cumulative-turn
rotation accumulator** (integrate per-tick signed heading change; treat a 1-tick ~180° jump as a rail
reversal → no rotation; real arcs accumulate) to un-freeze rotation.

## 3. Carried hoist-skull authored glue is not reoriented on landing (low)

A rotator can carry a glued hoist. The hoist skull's glue offsets are stored **seed-relative**
(`HoistAnchor`), not skull-relative, so the engine's generic `GlueManager.rebindLanded` (BlockAnchor,
skull-relative) cannot re-stamp them correctly. As of the glue-landing round, hoist skulls are
**excluded** from the Path-B rebind (`BasicMechanism.disassemble`, `isHoist` guard) — the landed hoist
re-derives its platform from its seed/chain instead (correct HoistAnchor-domain behavior, and better
than the old code which wrote transformed-wrong offsets). A proper HoistAnchor-aware reorientation
(so an authored platform survives a carry-and-turn as authored glue rather than re-derived) is deferred.

## 4. `/reload` wrong-drop race (low)

A mechanism cart destroyed in the narrow window between `MechanismMinecartManager.shutdown()` (clears
`tracked`) and `register()`'s catch-up scan drops a **vanilla** minecart item instead of the custom
mechanism-minecart item (`onMinecartDestroyed` returns on `state == null`, leaving the vanilla drop).
No data loss (shutdown already disassembled). Narrow and pre-existing; fix would re-check the
`corelib:mechanism_minecart` tag / PDC before allowing the vanilla drop.
