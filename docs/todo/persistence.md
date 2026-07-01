# Mechanism Persistence — BlockShips-style, minecart-first, engine built to port BlockShips later

> Status: PLAN (unbuilt). Line numbers are `~`-approximate anchors — re-grep before editing.
> Reference: `BlockShips/blockships/src/main/java/anon/def9a2a4/blockships/` (`ShipWorldData.java`,
> `ship/ShipInstance.java`, `ShipTags.java`, `DisplayShip.java`).
> Hardened by two rounds of adversarial critique (concurrency, BlockShips-fidelity, DefCoreLib-fit,
> data-round-trip, generalizability). Their fixes are the invariants **R1–R9** and the notes inline.

## Context & decisions

DefCoreLib mechanisms (`MechanismRegistry.activeMechanisms`) are in-memory only; `MechanismSerializer`
is never called (all consumers pass `null`). On a crash with an assembled mechanism, the captured
blocks — removed from the world at assembly (`MechanismRegistry.java:185-190`) — are lost, entities
survive as orphans, and restart's `cleanupOrphanedEntities` (`:371`) deletes them (`mechId` ∉ the
now-empty `activeMechanisms`). minecarts-v2 §1 (HIGH data loss).

**Decisions (locked):**
- **Minecarts persist like BlockShips** — survive `/stop`+restart and chunk reload by **re-adopting**
  their surviving persistent entities, via a generic engine.
- **Persistence is opt-in per consumer** via `MechanismSerializer.persists()` (see below). No
  persisting serializer ⇒ finalize-and-disassemble on shutdown, as today.
- **Doors / rotators / drawbridges do NOT persist.** On `/stop` they **snap to their swing target and
  disassemble to blocks** (needs a `swingTarget` — see R9 / transient note). This keeps today's
  transient behavior and avoids stranding a half-assembled door whose consumer map is empty next boot.
- **Persisted mechanisms are keyed by their VEHICLE entity UUID**, not the ephemeral random `mechId`.
  The vehicle UUID is stable across restart (the persistent minecart; a ship's owned root later), and
  it is the ONLY identity the minecart hooks (`scanChunkForMinecarts`, tick) can see synchronously —
  which is what makes the recovery guard reachable (R2). Metadata file = `{vehicleUuid}.yml`; chunk
  index maps `"x,z" → [vehicleUuid]`.
- **Build the engine generically** so **BlockShips can delegate later** — via the extension points in
  "Generalization" below — though only the minecart consumer is wired now.

### DefCoreLib architectural differences that reshape the port (don't copy BlockShips blindly)
1. **Single shared, DENSE tick loop.** `tickMechanisms` (`:393`) walks `0..blockCount` and assumes
   every display/collider present → a partially-recovered mechanism NPEs/IOOBEs (`updateAnimatedDisplays:411`).
   ⇒ never register/tick until recovery is complete (R1).
2. **Minecart borrows an external vehicle** (`ownsVehicle=false`, tag `:vehicle`, not `:root`), and is
   re-discovered independently by `scanChunkForMinecarts` keyed on the **cart UUID**. ⇒ vehicle-UUID
   keying + guard (R2); anchor recovery on the always-present `:parent` BlockDisplay (`:202`).
3. **Live-physics yaw, not frozen.** A cart's yaw comes from rail geometry; `updateFromVehicle` does
   `rotate(liveYaw − assemblyYaw)` (`BasicMechanism.java:408`) where `assemblyYaw` is captured once at
   ctor (`:82`) and lives on NO surviving entity. The load-bearing field is **`assemblyYaw`**, not
   `currentYaw`. ⇒ yaw reconstruction rule (R8).
4. **Variable per-block display groups** (primary + N `extra_N` + M `block_N`, `:226-254`) plus the
   per-block **capture** field `hasCollision` decide entity count. ⇒ recompute expected count from the
   re-resolved configs **and** the persisted `hasCollision`, not a stored int (R3).
5. **Async recovery vs synchronous cleanup** in the same `EntitiesLoadEvent` reintroduces the deletion
   bug. ⇒ cleanup runs after recovery adoption, gated on `hasMetadata` + `mechsBeingRecovered` (R4).
6. **No startup sweep**: `EntitiesLoadEvent` doesn't re-fire for chunks resident at enable (R5).

### Corrections carried from earlier drafts (were wrong)
- BlockShips uses a **single-chunk index** (the vehicle's `>>4`); stragglers caught by a 32-block
  sweep. No `computeOverlappingChunks`, no multi-chunk footprint index.
- **No recovery timeout** — incomplete recovery just waits for its chunks. No 30s fallback.
- Periodic save is **60s**. **Do not store/read position** — derive from the recovered vehicle.

## Reuse
`CustomBlockRegistry.saveHintsForWorld/loadHintsForWorld/saveAllHints` (`:1015-1090`) as the per-world
YAML template; Base64 inventory codec (`:1441`) extracted Skull-free; `takeStorageSnapshot`/
`restoreStorageSnapshot` (`:1466-1493`); `EntitiesLoadEvent` hook (`CoreLibPlugin.java:213-237`);
`MechanismSerializer` + `activeMechanisms`; `Matrix4f.get(float[16])` (`DisplayCapture.arr`).

## Consumer policy
| Consumer | Vehicle | `persists()` | On `/stop` / teardown |
|---|---|---|---|
| Mechanism minecart | borrowed cart (persistent) | **true** | save + leave assembled; recover on load |
| Door / Rotator / Drawbridge | owned ArmorStand | false | snap to `swingTarget`, disassemble to blocks |

## Implementation (engine generic; only the minecart serializer wired now)

### 1. `MechTags` — parse `corelib:mech:{uuid}:{idx}:{role}` (port `ShipTags`)
`mechId`, `isVehicle/isParent/isCarrier/isCollider`, `blockIndex`, `displayIndex`, `role`, **+ reserve
a `seat` role** now (unused, but avoids a tag-scheme change when BlockShips seats land). UUID is
`parts[2]`. Centralize the `split(":")` currently inlined in `cleanupOrphanedEntities`.

### 2. `MechanismState` + block schema (port `ShipState`, minus model/health/seats/position)
Mechanism: `vehicleUuid` (PRIMARY KEY), `type`, `worldName`, `assemblyYaw` (load-bearing, R8),
`rotationAxis` (3f), `rideOffset`, `ownsVehicle`, `@Nullable ConfigurationSection consumerData`.
**No position, no `currentYaw`** (both re-derived from the live vehicle, R8). Per block: `block_data`
(`BlockData.getAsString()`), `offset` (localTransform translation, 3f), **`has_collision`,
`collision_scale`** (capture fields — needed for the collider-count math, R3), `custom_type`,
`custom_state` (the CURRENT mutable state), `spin_reversed`, `wall_facing` (nullable 3f), `storage`
(Base64). `spin_reversed`/`wall_facing`/`has_collision` are **not** re-derivable post-assembly (source
block gone) — they must round-trip. Derived display/particle configs re-resolve from
`custom_type`+`custom_state`. `startTick` is NOT persisted → animated displays (spinning gears) restart
their phase at 0 on recovery — accepted cosmetic; document it.

### 3. `MechanismPersistence` (port `ShipWorldData`)
`mechanisms/{world}/chunks.yml` (`"x,z" → [vehicleUuid]`, keyed on the **vehicle's** chunk) +
`mechanisms/{world}/{vehicleUuid}.yml`. Single-thread daemon `ExecutorService` + `CompletableFuture`
(kept for the eventual BlockShips scale); `loadAllChunkIndices()` **synchronously at enable** (so the
guard/index is queryable in-event — the crux of R2); `metadataExistsCache` with `put(true)`/`put(false)`
**on the main thread at call time** (not only in the executor `finally`); `validateAndCleanChunkIndices`;
idempotent `removeMechanism(worldName, vehicleUuid)` that scans ALL index entries (never reads a
possibly-dead vehicle's location). Methods: `saveMechanismAsync`, `loadMechanismAsync`,
`removeMechanism`, `saveAll` (sync), `hasMetadata`.
> Sync I/O is acceptable at DefCoreLib's scale; async is kept only for BlockShips-portability. If async,
> R4 + R6 are mandatory.

### 4. Recovery — re-adopt entities (port `fromState`+`recoverEntities`+incremental collect)
- `BasicMechanism.fromRecoveredState(state, resolver, mechReg)` — entity-less; rebuild `blocks` from
  YAML (feed `spin_reversed`/`wall_facing`/`has_collision`/`custom_state` from the snapshot). **Yaw
  (R8):** inject the restored `assemblyYaw`; re-seed `previousVehicleLoc`/`previousVehicleYaw` from the
  **live** recovered vehicle. Do NOT re-read `assemblyYaw` from the live vehicle (that collapses the
  delta and snaps the structure to the cart's raw rail heading).
- `recoverEntities(Chunk)`: anchor on the `:parent` BlockDisplay; re-link the cart by `vehicleUuid`;
  collect displays by index, pair `carrier`+`collider` by blockIdx; **32-block nearby sweep** (needed
  even for one cart — displays spawn at `pivot+2.5Y`, `assembleCore:193`, and can straddle a chunk
  corner). Anchor not in the triggering chunk → return false, drop this chunk's index entry, defer.
- Incremental machinery (`pendingCarriers/pendingShulkers`, `collectEntitiesFromChunk`, `recoveryComplete`)
  is engine-generality; for the single-cart happy path recovery is atomic in the vehicle chunk. Expected
  count **recomputed from configs + `has_collision`** (R3). **No timeout.**
- Orchestration (`MechanismRegistry` + `CoreLibPlugin.onEntitiesLoad`), exact order — R2/R4:
  1. sync-seed a `vehicleUuid` recovering-guard from the pre-loaded chunk index;
  2. `scanChunkForMinecarts` — **skip** any cart whose UUID is in the guard;
  3. if the chunk indexes any `vehicleUuid` → async `{uuid}.yml` load → main-thread continuation:
     adopt entities, register + tick only when `isRecoveryComplete()` (R1), `serializer.onRecoveryComplete`,
     **then** cleanup;
  4. else run `cleanupOrphanedEntities` synchronously now.
  Plus startup sweep of `getLoadedChunks()` after `loadAllChunkIndices()` (R5).

### 5. Reconcile `cleanupOrphanedEntities` (`:371`)
Remove a `corelib:mech:*` entity only when its `mechId` is not active, its vehicle UUID is not in
`mechsBeingRecovered`, AND `!persistence.hasMetadata(vehicleUuid)`. Run it after recovery adoption per
step 4; synchronous only when the chunk indexes nothing to recover. (The parent/display can load in a
different chunk than the cart — the `hasMetadata` gate protects it until its anchor chunk recovers, D4.)

### 6. Save triggers, teardown, shutdown
- Assembly (end of `assembleCore`): `saveMechanismAsync` + add to the vehicle-chunk index (only if
  `serializer.persists()`).
- Teardown chokepoint: `MechanismRegistry.onMechanismRemoved` (`:482`, hit by BOTH `disassemble()` and
  `destroy()`). Gate removal on `serializer.persists()`; call `removeMechanism(worldName, vehicleUuid)`
  (idempotent; worldName captured at assembly, not read from a dead vehicle) which also
  `metadataExistsCache.put(false)` synchronously (D6).
- Vehicle chunk change: `updateChunkIndex`.
- Periodic **60s** main-thread save (spawn-chunk mechanisms never unload) — snapshot built in one
  synchronous block (R6). Sync `saveAll` on `onDisable`, ordered BEFORE the executor drain/shutdown.
- **Shutdown discrimination:** `MechanismRegistry.shutdown()` (`:329`) currently disassembles ALL —
  change it to disassemble only mechanisms where `serializer == null || !serializer.persists()`
  (transient), and `saveAll` the persisted ones first. `MechanismMinecartManager.shutdown()` (`:109`)
  and `onMinecartDestroyed`/dead-cart tick (`:170,273`) currently call `disassemble()` on carts — for a
  persisted cart that restores blocks into the world (double-blocks after recovery, R7): change them to
  NOT disassemble a persisted, still-valid cart (just cancel the task / clear `tracked`); a genuinely
  destroyed cart still disassembles + `removeMechanism`.

### 7. Consumer wiring
- **Minecart** (`MechanismMinecartManager.java:247`): pass a `persists()==true` serializer.
  `onRecoveryComplete(mech)` → set `MinecartState.mechanism` for the cart matched by `vehicleUuid`. Add
  a `recovering` flag to `MinecartState` (or don't insert into `tracked` until `onRecoveryComplete`) so
  the tick can't assemble a duplicate during the async window (R2/D3).
- **Door/Rotator/Drawbridge**: no persisting serializer → today's finalize path; add a `swingTarget`
  so shutdown lands them where they were heading (R9).

## Generalization to BlockShips (extension points that must exist NOW to avoid a rewrite)
- **Injected block resolver, not concrete `CustomBlockRegistry`.** `fromRecoveredState` and the
  block-capture loop take a resolver interface (registry-backed for DefCoreLib; `ShipModel`/provider-backed
  for BlockShips). Pairs with the Phase-1 `BlockSnapshotProvider` and a per-block opaque `providerData`.
- **Yaw reconstruction is a hook, not hard-coded.** `currentYaw` is *derived* from a live vehicle for
  carts but *consumed* from metadata for frozen-yaw ships — the recovery path must call a per-type yaw
  strategy (R8 is the minecart implementation of it).
- **Reserve the `seat` tag role** (step 1) and keep `consumerData` free for a ship's health/seat state;
  the engine must **never** touch the vehicle's attributes (health rides free on the owned root).
- **`vehicleUuid` keying already generalizes** (a ship's owned root is stable/persistent too).

## Invariants (the critique fixes — treat as binding)
- **R1** Never register/tick a mechanism until `isRecoveryComplete()` (dense tick loop NPEs on partials).
- **R2** Recovery guard + persistence index are keyed on the **vehicle UUID**, seeded synchronously from
  the pre-loaded chunk index, and consulted by `scanChunkForMinecarts` **and** the minecart tick before
  either tracks/assembles.
- **R3** Recompute expected entity count from re-resolved configs **and** persisted `has_collision`.
- **R4** Orphan cleanup runs after async recovery adoption, gated on `hasMetadata` + `mechsBeingRecovered`.
- **R5** Startup sweep of already-loaded chunks, after `loadAllChunkIndices()`.
- **R6** All live-state reads on the main thread building `MechanismState`; only file I/O on the executor
  — never capture a `BasicMechanism` in the submitted lambda; the 60s save is a main-thread task.
- **R7** No auto block-restore into the world from any recovery/shutdown path for a persisted mechanism;
  block-restore only on explicit player disassembly (recovery keeps blocks virtual).
- **R8** Reconstruct yaw by injecting the restored `assemblyYaw` and re-seeding `previousVehicle*` from
  the live vehicle; never trust a stored `currentYaw` or re-read `assemblyYaw` live.
- **R9** Transient types carry a `swingTarget`; shutdown finalizes to it (not `disassemble()`'s nearest-90°,
  which lands a sub-45° door on the wrong side).

## Accepted, documented gaps
- **Transient crash data loss (unchanged from today).** A door/rotator caught mid-swing by `kill -9`
  loses its (already-removed) blocks — no serializer, no metadata, orphans cleaned on restart. Identical
  to current behavior; acceptable given sub-second swing lifetimes. Persistence fixes this for minecarts
  only, by design.

## Verification
- Assemble a mechanism minecart → `/stop` → restart → cart comes back **still assembled**, rides at the
  **correct yaw** (no snap to rail heading).
- Assemble → `kill -9` → restart → recovers from last periodic/assembly save; no orphan deletion; no
  duplicate assembly from the tick during the async window.
- Assembled cart with a container custom block → restart → inventory intact.
- Cart whose displays straddle a chunk corner → unload/reload the display chunk → survives (hasMetadata
  gate), recovery completes via the 32-block sweep.
- Door mid-swing → `/stop` → restart → **blocks at the swing destination** (via `swingTarget`), no
  stranded mechanism.
- Destroy a persisted cart (`VehicleDestroyEvent`) → `{vehicleUuid}.yml` + index entry removed, cache
  put(false), no strays.
- `make build`; `make docs KEEP_ALIVE=1`, join localhost:25575.
