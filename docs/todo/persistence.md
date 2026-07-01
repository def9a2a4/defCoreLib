# Mechanism Persistence — BlockShips-style, minecart-first, engine built to port BlockShips later

> Status: PLAN (unbuilt). Line numbers are `~`-approximate anchors — re-grep before editing.
> Reference: `BlockShips/blockships/src/main/java/anon/def9a2a4/blockships/` (`ShipWorldData.java`,
> `ship/ShipInstance.java`, `ShipTags.java`, `DisplayShip.java`).
> Hardened by three rounds of adversarial critique (concurrency, BlockShips-fidelity, DefCoreLib-fit,
> data-round-trip, generalizability, identity-bridge, over-complexity). Fixes are the invariants
> **R1–R9** + inline notes. Round 3 added: persist `mechId` (P0), default to **sync I/O**, and defer the
> incremental collector — see those sections.

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
- **TWO identities, both persisted (P0 — the crux).** The metadata FILE + chunk INDEX key is the stable
  **vehicle UUID** (`minecart.getUniqueId()`) — the only identity the minecart hooks
  (`scanChunkForMinecarts`, tick) can see synchronously, which makes the recovery guard reachable (R2).
  But every mechanism ENTITY is tagged `corelib:mech:{mechId}:…` where `mechId = UUID.randomUUID()` per
  assembly (`MechanismRegistry.java:74/102`) — a DIFFERENT uuid. So `{vehicleUuid}.yml` **must also store
  `mechId`**, and recovery must inject it back into `BasicMechanism.id` (R2). Without it, recovery knows
  the file key but cannot construct the `corelib:mech:{mechId}:…` tag prefix to FIND the parent/displays/
  colliders — entity adoption is impossible. (BlockShips avoids this only because there the entity-tag
  uuid IS the file key; DefCoreLib has two uuids and must bridge them.) File = `{vehicleUuid}.yml`; index
  `"x,z" → [vehicleUuid]`; `mechId` is a field inside the file.
- **I/O is synchronous** (matches the existing chunk-hint persistence, `CustomBlockRegistry.java:1071`).
  Three critiques agreed async is a self-inflicted hazard at DefCoreLib's scale (it exists in the doc
  only to serve the deferred BlockShips port). Sync collapses R4's ordering fragility and most of R6.
  The `saveMechanismAsync`-shaped seam is kept so swapping in an `ExecutorService` at BlockShips scale is
  mechanical — a **build-time** decision, defaulting sync.
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
5. **Cleanup in the same `EntitiesLoadEvent` deletes recoverable entities** unless it runs AFTER
   recovery adoption ⇒ order recover-then-cleanup, gated on `hasMetadata` + the recovering-guard (R4).
   Straight-line under sync I/O.
6. **No startup sweep**: `EntitiesLoadEvent` doesn't re-fire for chunks resident at enable (R5).

### Corrections carried from earlier drafts (were wrong)
- BlockShips uses a **single-chunk index** (the vehicle's `>>4`); stragglers caught by a 32-block
  sweep. No `computeOverlappingChunks`, no multi-chunk footprint index.
- **No recovery timeout** — incomplete recovery just waits for its chunks. No 30s fallback.
- Periodic save is **60s**. **Do not store/read position** — derive from the recovered vehicle.

## Reuse
`CustomBlockRegistry.saveHintsForWorld/loadHintsForWorld/saveAllHints` (`:1015-1090`) as the per-world
YAML template (main-thread, timer + `onDisable` — the sync pattern to mirror); Base64 inventory codec
(`~:1485-1520`) extracted Skull-free; `takeStorageSnapshot`/`restoreStorageSnapshot` (`~:1528-1555`);
`EntitiesLoadEvent` hook `onEntitiesLoad` (`CoreLibPlugin.java:224-249`; scan `:228`, cleanup `:232`);
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
Mechanism: `vehicleUuid` (FILE/INDEX KEY), **`mechId`** (entity-tag identity, injected into
`BasicMechanism.id` on recovery — P0/R2), `type` (= `mech:mechanism_minecart` for the cart), `worldName`,
`assemblyYaw` (load-bearing, R8),
`rotationAxis` (3f), `rideOffset`, `ownsVehicle`, `@Nullable ConfigurationSection consumerData`.
**No position, no `currentYaw`** (both re-derived from the live vehicle, R8). Per block: `block_data`
(`BlockData.getAsString()`), `offset` (localTransform translation, 3f), **`has_collision`,
`collision_scale`** (capture fields — needed for the collider-count math, R3), `custom_type`,
`custom_state` (the CURRENT mutable state), `spin_reversed`, `wall_facing` (nullable 3f), `storage`
(Base64). `spin_reversed`/`wall_facing`/`has_collision` are **not** re-derivable post-assembly (source
block gone) — they must round-trip. Derived display/particle configs re-resolve from
`custom_type`+`custom_state`. `startTick` is NOT persisted → animated displays (spinning gears) restart
their phase at 0 on recovery — accepted cosmetic; document it.

### 3. `MechanismPersistence` (port `ShipWorldData`, synchronous)
`mechanisms/{world}/chunks.yml` (`"x,z" → [vehicleUuid]`, keyed on the **vehicle's** chunk) +
`mechanisms/{world}/{vehicleUuid}.yml`. **Synchronous main-thread I/O** (mirror the chunk-hint code);
`loadAllChunkIndices()` at enable; `validateAndCleanChunkIndices` (drop index entries with a missing
`{uuid}.yml`); idempotent `removeMechanism(worldName, vehicleUuid)` that scans ALL index entries (never
reads a possibly-dead vehicle's location). Methods: `saveMechanism`, `loadMechanism`, `removeMechanism`,
`saveAll`, `hasMetadata`. Keep the method boundary shaped so an executor swaps in later (seam only).
> Under sync I/O the `metadataExistsCache` and its 5-min clear are unnecessary (a `File.exists()` on the
> main thread is fine); they return only if the executor is adopted for the BlockShips port.

### 4. Recovery — re-adopt entities (port `fromState`+`recoverEntities`+incremental collect)
- `BasicMechanism.fromRecoveredState(state, resolver, mechReg)` — entity-less; **inject the restored
  `mechId` into `BasicMechanism.id`** (so `cleanupOrphanedEntities` sees it in `activeMechanisms` and
  doesn't delete the just-adopted entities — P0); rebuild `blocks` from YAML (feed
  `spin_reversed`/`wall_facing`/`has_collision`/`custom_state` from the snapshot). **Yaw (R8):** inject
  the restored `assemblyYaw`; re-seed `previousVehicleLoc`/`previousVehicleYaw` from the **live** cart.
  Do NOT re-read `assemblyYaw` live (collapses the delta → snaps to the raw rail heading).
- `recoverEntities(Chunk)`: re-link the cart by `vehicleUuid` (via `scanChunkForMinecarts`'s
  `corelib:mechanism_minecart` tag); build the entity-tag prefix `corelib:mech:{state.mechId}:…`; find
  `:parent`, displays (by index), `carrier`+`collider` (paired by blockIdx) via a **32-block sweep
  centered on the re-linked CART** (not the parent — the parent is at `pivot+2.5Y`, `assembleCore:193`,
  and can straddle a chunk corner; 2.5≪32). If the cart isn't in the triggering chunk → return false,
  drop this chunk's index entry, defer to the cart's chunk.
- **Adopt atomically** for the minecart: all displays/colliders spawn co-located on one `:parent`, so a
  single sweep gets them; expected count **recomputed from configs + `has_collision`** (R3) just gates
  "adoption complete." The BlockShips **incremental collector** (`pendingCarriers/pendingShulkers`,
  `collectEntitiesFromChunk`, partial `recoveryComplete`, `chunksBeingRecovered`) is a **deferred
  BlockShips seam**, not day-one minecart code. **No timeout.**
- Orchestration (`MechanismRegistry` + `CoreLibPlugin.onEntitiesLoad`), exact order — R2/R4:
  1. seed a `vehicleUuid` recovering-guard from the (synchronously) pre-loaded chunk index;
  2. `scanChunkForMinecarts` — **skip** any cart whose UUID is in the guard;
  3. if the chunk indexes any `vehicleUuid`: load `{uuid}.yml`, adopt entities, register + tick only when
     adoption is complete (R1), `serializer.onRecoveryComplete`, **then** run cleanup;
  4. else run `cleanupOrphanedEntities` now.
  All synchronous (sync I/O) → the R4 ordering is a straight-line sequence, not an async continuation.
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
  (idempotent; worldName captured at assembly, not read from a dead vehicle) — deletes `{uuid}.yml` and
  the index entry so a later-loading stray display isn't protected by a false `hasMetadata` (D6).
- Vehicle chunk change: track `currentChunkX/Z` scalars on the mechanism (seeded at assembly and in
  `fromRecoveredState`); call `updateChunkIndex` **only on an actual crossing** (mirror
  `ShipInstance.java:1317`), else a riding cart rewrites the index every few ticks.
- Periodic **60s** main-thread save (spawn-chunk mechanisms never unload). Sync `saveAll` on `onDisable`.
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
  the tick (a separate scheduled task) can't assemble a duplicate before recovery sets `state.mechanism`
  (R2/D3). Sync recovery closes the same-event gap; the guard covers the tick that fires between events.
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
- **`vehicleUuid` keying already generalizes** (a ship's owned root is stable/persistent too). For a
  ship, `mechId` and the root UUID may coincide (owned root) — the two-uuid bridge is a minecart quirk,
  harmless when they're equal.
- **Deferred seams (build when BlockShips lands, not now):** the async `ExecutorService`+`CompletableFuture`
  I/O (swap behind the sync `MechanismPersistence` methods) and the **incremental multi-chunk collector**
  (`pendingCarriers/pendingShulkers`/`collectEntitiesFromChunk`/`chunksBeingRecovered`) — a large ship's
  entities load piecemeal across chunks; a single cart's don't.

## Build-time decisions (not pinned here)
`persists()` on `MechanismSerializer` (a `null` serializer already means transient — that's the default);
the `swingTarget` field + snap semantics on doors/rotators (no such field today; design with the door
consumer); `consumerData`/reserved `seat` role/injected block-resolver signatures (finalize against the
real `ShipModel` at port time). Line anchors are `~`-approximate — re-grep before editing.

## Invariants (the critique fixes — treat as binding)
- **R1** Never register/tick a mechanism until `isRecoveryComplete()` (dense tick loop NPEs on partials).
- **R2** File/index key = **vehicle UUID** (reachable synchronously from `scanChunkForMinecarts`/tick);
  **`mechId` also round-trips** in the file and is injected into `BasicMechanism.id` on recovery (else
  the `corelib:mech:{mechId}:…` entity tags can't be found — P0). The recovering-guard (vehicleUuid) is
  consulted by `scanChunkForMinecarts` **and** the minecart tick before either tracks/assembles.
- **R3** Recompute expected entity count from re-resolved configs **and** persisted `has_collision`.
- **R4** Orphan cleanup runs **after** recovery adoption for the chunk; synchronous only when nothing is
  indexed to recover. (Under sync I/O this is a straight-line sequence; the gate is `hasMetadata` +
  the recovering-guard.)
- **R5** Startup sweep of already-loaded chunks, after `loadAllChunkIndices()` (the minecart's
  `register()` already sweeps `getLoadedChunks()` — make that sweep recovery-aware).
- **R6** All live-state reads happen on the main thread when building `MechanismState` (trivially true
  under sync I/O). If the executor seam is ever adopted, never capture a `BasicMechanism` in the lambda.
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
  duplicate assembly from the tick (recovering-guard).
- Assembled cart with a container custom block → restart → inventory intact.
- Cart whose displays straddle a chunk corner → unload/reload the display chunk → survives (hasMetadata
  gate), recovery completes via the 32-block sweep.
- Door mid-swing → `/stop` → restart → **blocks at the swing destination** (via `swingTarget`), no
  stranded mechanism.
- Destroy a persisted cart (`VehicleDestroyEvent`) → `{vehicleUuid}.yml` + index entry removed, cache
  put(false), no strays.
- `make build`; `make docs KEEP_ALIVE=1`, join localhost:25575.
