# Deferred items & known gaps — mechanism / glue / cart

Tracked-but-not-done items surfaced across the glue-landing, cart-destroyability, world-unload, and
review rounds. Each verified against the code by investigation agents. Ordered by rough real-world
impact. Statuses: **DEFERRED** (real, own effort), **PENDING** (approved fix, not yet written),
**NON-ISSUE** (verified not real / by-design), **INFO** (harmless).

Calibration that governs priority: owned mechanisms (door/piston/hoist/rotator) are assembled ONLY
for the ~1s of their animation — at rest they are real world blocks. The only long-lived assembled
entity is a mechanism minecart (which is already protected against destruction by `VehicleDestroyEvent`
+ the `isDead()` backstop). So most "assembled at the wrong moment" risks have a sub-second window for
owned mechs and only really apply to long-lived assembled carts.

---

## 1. Full crash / restart persistence — DEFERRED (biggest data-loss risk)

Assembly airs source cells to AIR **on disk immediately** (`MechanismRegistry.airOutSourceBlocks`);
only a clean disassemble (`onDisable` / `EntitiesUnloadEvent` / `WorldUnloadEvent`) restores them. A
**hard crash** (`kill -9`, OOM, power loss) while something is assembled leaves a permanent hole; a
mechanism cart returns *unassembled + frozen* over it, an owned mechanism vanishes entirely (its
orphaned displays get reaped). `MechanismSerializer` is an unimplemented stub — all six
`assembleMechanism` call sites pass `null`; `save`/`restore`/`onRecoveryComplete` are never invoked.

Mostly matters for **long-lived assembled carts** (owned mechs are only exposed during their ~1s
animation). If scoped down, persisting just mechanism carts may be enough.

**Design (recovery-from-entities, aligns with `blockships-integration.md`):** the persistent
display/collider entities survive a crash, but recovery is NOT lossless today — a custom block's
primary is an `ItemDisplay` head texture with no registry id; mid-rotation `currentYaw`, ghost flags,
`collision.offset`, carried container storage, per-block `configPdc` are stored nowhere. So: at
assembly, stamp a small PDC on each primary display (localOffset, customTypeId+state,
collision{enabled,size,offset}, ghost/spinReversed/wallFacing, glueOffsets, configPdc, storage) + mech
-level data on the vehicle (type, rotationAxis, currentYaw, rideOffset, ownsVehicle, blockCount); on
chunk load, group `corelib:mech:{id}:*` entities by id, rehydrate `MechanismBlockData`/`ColliderPair`,
rebuild the `BasicMechanism`, re-register (the cart's `corelib:mech:{id}:vehicle` tag links cart→id),
and demote `cleanupOrphanedEntities` to a fallback for ids recovery couldn't rebuild. Fits the unused
`MechanismSerializer.onRecoveryComplete` seam.

## 2. Asymmetric mechanism-cart curve jam — DEFERRED (most user-visible; deterministic)

Mechanism-cart rotation is frozen (`BasicMechanism.updateFromVehicle` no longer calls `rotate(delta)`;
the yaw-fold is commented out), so `currentYaw` stays 0 / `currentTransform` stays identity for a
cart's whole life. `CartCollision` reads that frozen transform to build the terrain footprint, so an
**asymmetric** glued cart keeps its assembly orientation through a rail curve while its travel axis
flips — its sideways footprint jams against tunnel walls (repeated hard-stop + `resnapIfPenetrating`),
a visible stall/judder at every curve. Symmetric carts are unaffected (axis-independent extents), which
is why it survives casual testing. Unlike the crash cases this is **deterministic** — any lopsided cart
on a curve hits it every time. Real fix: the **cumulative-turn rotation accumulator** (integrate
per-tick signed heading change; treat a 1-tick ~180° jump as a rail reversal → no rotation; real arcs
accumulate) to un-freeze cart rotation. More complicated — its own round.

## 3. Owned-vehicle destroy leaves a hole — DEFERRED (niche; document + design ready)

The owned-mechanism vehicle (an invisible marker `ArmorStand`, `MechanismRegistry.assembleCore` owned
branch) is not invulnerable and has **no death/removal guard**. If it is destroyed by `/kill`, a
lag-clearer plugin, or `entity.remove()` (any cause except chunk unload), nothing disassembles the
mechanism → its aired-out blocks are lost until the next clean teardown (permanent if a crash
intervenes). The *minecart* vehicle is already covered (`VehicleDestroyEvent` + `isDead()` backstop);
this gap is owned-vehicle-only.

**Why left unguarded (niche):** owned mechs are assembled only for their ~1s animation, so the window
for an entity-removal to coincide is tiny; the long-lived cart is already protected.

**Fix design (if ever needed, ~15 lines):** a `@EventHandler(MONITOR)` on Paper's `EntityRemoveEvent`
in `CoreLibPlugin` → `mechanismRegistry.onVehicleEntityRemoved(entity, cause)`; return if
`cause == EntityRemoveEvent.Cause.UNLOAD` (that's normal chunk unload, must be ignored); else parse the
`corelib:mech:{id}:vehicle` tag (same parse as `cleanupOrphanedEntities`) → `activeMechanisms.get(id)`;
gate on `mech.ownsVehicle` (auto-excludes the minecart); `try { mech.disassemble(); } catch (log)`.
Re-entrancy is already closed by disassemble's unregister-before-remove ordering + the `disassembled`
flag. Cost is negligible (early-returns on UNLOAD and on entities without a mech tag). Optionally also
`setInvulnerable(true)` on the vehicle for parity, though it doesn't stop `/kill`/plugin removal.

## 4. Piston/hoist mid-stroke display pop on chunk unload — DEFERRED (cosmetic only)

Earlier suspected as an "up-to-1-cell landing shift" — **it is not.** `assembleCore` centre-snaps the
pivot on ALL axes (incl. Y), and `disassemble` lands via `floor(pivot + integer offsets)`, so
`floor(centred pivot)` is already round-to-nearest-cell; the body always lands in the cell it is
most-in and never shears, and glue rebinds to the actual landed cells. The only residual on a
mid-stroke `forget()` abort is a **≤0.5-block, one-frame display pop** (owned-vehicle display removal
is deferred one tick). Optional cosmetic fix: snap the pivot to cell-centre (`floor(v)+0.5` per axis)
via `mech.move(...)` in each `forget()` before disassembly — reproduces the exact same landing cells,
just aligns the lingering displays. Factor the snap out of `assembleCore:251-253` into one shared
helper if implemented.

## 5. Carried hoist non-sticky platform shear — DEFERRED (narrow, no data loss)

A hoist glued onto a rotator: on landing, hoist skulls are excluded from the engine's Path-B glue
rebind (`BasicMechanism.disassemble`, `isHoist` guard), so the landed hoist re-derives its platform
from seed/chain. Faithful for sticky/casing platforms; but a **brush-glued non-sticky** platform loses
its loose pieces (they land but are no longer glued to the hoist, so the next stroke leaves them
behind). Narrow (brush-authored non-sticky platform carried AND turned by a rotator); blocks stay in
the world, just un-glued. Proper HoistAnchor-aware (seed-relative) reorientation is deferred.

---

## Pending code fix (approved, deferred by "no code for now")

- **Ghost-mechanism leak on a teardown throw.** When `disassemble()` throws mid-teardown, the catch
  fallbacks (`MechanismMinecartManager.safeDisassemble`, `MechanismRegistry.onWorldUnload` + `shutdown`)
  remove entities but skip `onMechanismRemoved`, leaving a dead entry in `activeMechanisms` (re-ticked
  forever, no-op) + stale `colliderIndex` shulker refs. Fix = add `onMechanismRemoved(mech)` to each
  catch (idempotent). ~3 lines. Slow leak, not data loss.

## Verified NON-ISSUE / INFO (no action)

- **`/reload` wrong-drop race — NON-ISSUE.** Not reachable: disable→enable is synchronous with no
  events dispatched between, and `register()`'s catch-up scan runs before the destroy listener
  registers, so a loaded cart is always tracked while the listener is live. Even hypothetically, the
  loss would be only the custom item identity, which is re-craftable.
- **Cross-mechanism collision (cart↔cart, piston↔mech pass-through) — NON-ISSUE / by design** (the
  deferred "B3" round). Terrain-only collision; pure visual overlap, no crash/data-loss.
- **Carried-hoist chain-break guard is dead code — INFO/harmless.** `restoreConfigPdc` strips
  `corelib:` keys before it runs, so `isValidOffsets` is always false; it never fires. Leave it.
- **`catch (Exception)` vs `Throwable` in teardown loops — decided SKIP.** Swallowing `Error` (OOM
  etc.) is usually wrong; the only upside is consistency with `tickMechanisms`, not worth it.
