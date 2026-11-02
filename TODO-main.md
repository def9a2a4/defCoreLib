## Next up (review of 2026-11-01)

Ordered by priority. Done this round: 26.3 in the CI matrix + datapack max_format 109, glue-brush
sessionless hint (GitHub #3), hoist reel-in ghost dupe, corrupt-state re-parse cache, first unit tests.

- **reply to GitHub #3** (glue brush / minecart) — draft written; post it, close after the release ships.
- **BlockShips** (../BlockShips; verified against 7bb2e37 on 2026-11-01, none fixed yet):
  - **locked ship absorbs a foreign wheel** — `scanFrozen` has no foreign-wheel exclusion; the frozen glue
    set can gain a victim's head after the lock (brush glue isn't refused). Leads to wheel-menu hijack.
    Fix: give `scanFrozen` the same `preCapture` hook as `scanStructure`. S, exploit.
  - **`forceclearwheel` on a sailing ship** dumps the hull, ejects riders, deletes the sidecar — no LOADED
    check (`ShipWheelManager.forceClearWheelLink` → `reconcileOrphan`). Refuse when LOADED. S, data loss.
  - **foreign wheel still bridges the flood fill** — exclusion drops the head's cell after the fill
    already expanded through it. Pass a predicate into `detectShipInternal`. M.
  - **bad recipe pattern disables the plugin** (`ItemUtil` `recipe.shape` uncaught; on reload after all
    recipes were removed). S.
  - **empty block-config override fails open** on reload, and CHANGELOG-v0.0.18:78 claims otherwise. S.
  - **quarantined (world-not-loaded) wheel records** are invisible to the foreign-wheel guard. S.
  - **reload has no per-step guards** — one throw skips the remaining steps. S.
  - **corrupt-sidecar message** says "move it aside", which doesn't release the wheel; name
    `forceclearwheel` and the restart. S.
  - **`stats.enabled` default flip** is deliberate but in no changelog. S.
  - **`wheel_decoy`/`wheel_reserved` bot tests are manual-only** — run them as their own CI step. M.
  - `loadConfig` has no tests; local bot run shows `double_chest` and `cannon_tnt_disabled` failing.
  - **seat never freed on dismount** — `VehicleExitEvent` never fires for a Shulker vehicle (not a `Vehicle`),
    so `DisplayShip.onPlayerExitVehicle` has never run for a seat: no `freeSeat`, no safe-dismount teleport, no
    momentum hand-off. `hasDriver` hides it (recomputed per tick). Move it to `EntityDismountEvent`. S–M.
  - **cushion seats (26.3)** — in-game check still owed: sit on the driver cushion → assemble → steer → dock
    seated; restart mid-voyage with cushions + frames aboard. Camera distance isn't applied to a rider moved
    off a cushion at assembly (only on click). S.
- **in-game regression scenarios** — ceiling propeller/windmill, a hoist that actually moves (the `crane`
  showcase's `hoist_moved` is commented out), the rising-hoist dupe, corrupt-state recovery; plus
  assertions for the showcases that have none (sand_generator, icon_machine, steam_sieve, water_wheel_fan).
- **asymmetric carts jam on curves** — cart rotation is frozen (`BasicMechanism` `// rotate(delta);`
  ~:1763). Most player-visible remaining bug. M–L.
- **protection plugins (WorldGuard etc.)** — mechanism, glue, hoist and piston writes ignore regions; no
  hook exists. Design first: docs/todo/mechanism/protection-awareness.md. L.
- **locked chain pulley still draws power** on a loop — `RotationNetwork` demand (~:538) has no
  `isLocked(partner)` check (docs/todo/mechanism/chain-pulley.md:71). S–M.
- **owned-vehicle ArmorStand removed by /kill or a lag-clearer** loses the structure's aired-out blocks —
  no `EntityRemoveEvent` handling for mechanisms (deferred-from-glue-landing-round §3). S.
- **network-size cap fails silently** — `RotationNetwork` BFS stops at the cap with no warning (~:526). S.
- **particles don't tick on assembled mechanism blocks** — `MechanismRegistry` `// TODO: particle ticking`. S.
- **glue-brush hint** fires on every sessionless left-click, including plain block breaking — add a
  cooldown if players find it noisy.
- **unit-test gaps** — inject a clock into `MechanismPersistence` to cover the 60s corrupt-read retry;
  `BlockLoader` parsing (6 past fixes), `MassRegistry`, `RotationSolver.solve`, `TeleportCompat` version
  parse (needs extracting from its static init).
- **cleanup** — dead `MechanismSerializer`; `reloadbanners` tooling; dead `pipes/.github/workflows`
  (GitHub never runs workflows in subdirectories); move windmill banner capture onto `IngredientCapture`.
- **stale TODO docs** — `docs/todo/TODO.md` is almost all done (archive it); also out of date: roadmap's
  "cart coupling not built", vertical-slabs.md part 3, plugin-split.md, rotation-and-mechanism-fixes.md
  C1/C10/B2, chain-pulley.md fix 1, TODO-minecart-visuals.md (passenger-mount plan was rejected; check in
  game whether trains still jitter).

## Older items

- textures/recipes -- agent has all info
- hoist glue authoring — follow-ups (from review of the authoring commit):
  - **dual glue sources aren't reconciled.** `resolveGroup` takes hoist-held glue OR legacy
    seed-skull glue either-or, but `startMove` still separately reads and rebinds the seed skull's
    own glue (`seedGlue`). If a glue-bearing skull rides as the seed AND the hoist has authored glue,
    the skull's sub-structure isn't captured (either-or skipped it) yet its offsets get rebound on
    landing — a corner-of-corner desync. Pick one source and document it, or union under a single cap.
  - **a load-top that is itself an anchor block hijacks the session.** If the block directly under the
    chain is a rotator / piston-head / door-controller, `isAnchor(clicked)` is true so clicking it opens
    a session on THAT block (head-relative), not the hoist — `owningHoist` only runs for non-anchor
    blocks. Author such loads from the hoist head or a rope link, or add a disambiguation.
  - **session lingers if the hoist head is broken mid-session** — reads/writes no-op against the
    now-non-skull cell until timeout. Close the session on head removal.
  - **`inRopeColumn` is unbounded upward** — a platform block can never occupy the hoist's own X/Z
    column above the seed (can't wrap/surround the chain). Confirm this is the intended limitation.
  - **verify end-to-end** — the feature was reviewed statically only. Build + drive on test-server:
    both entry points (head / load-top / rope link), corridor rejection, a lower/raise cycle carrying
    the glued platform, and glue persistence across chunk reload.
  - **scope check:** the authoring working-set also touched `BasicMechanism.destroy` (vehicle-tag strip)
    and `MechanismRegistry.cleanupOrphanedEntities` (skip `corelib:mechanism_minecart`) — these are
    minecart glue-survival fixes, unrelated to the hoist. Keep them only if intended as a minecart fix;
    otherwise they don't belong with hoist glue.
- fix displays for redstone dynamo
- fixes for extender pistons
  - DONE: shared movability guards: apply `MovableBlocks.isMovable` to the minecart default seed (block above cart), the rotator default seed (attachment block), and the glue brush (single + cuboid, with a rejection message) — was de-scoped to piston-only to avoid regressing shared glue/authoring
  - crash-mid-slide persistence: a hard crash (not graceful /stop) while the rod is moving loses the rod blocks — mechanisms aren't persisted across restarts (engine-wide, affects all mechanisms)
  - display-entity lighting: moving mechanism displays sample block-light at the vehicle height (pivot + rideOffset), so a sliding structure can be lit as if up in the air — spawn/anchor the light sample lower (at the core/block cell). Interacts with the ride-offset display chain in MechanismRegistry.assembleCore / BasicMechanism.rotate
- more showcases?
- CME / iterate-while-mutate hardening (latent — no live bug; audit 2026-07-14 found the real crash already
  fixed in commits 0699058 + 91fa8c9, and no other *live* hazard anywhere in the repo). These are safe TODAY
  only by a non-obvious invariant; harden for consistency and to defuse future footguns. Load-bearing
  invariants are now marked with comments in-code (search "LOAD-BEARING" / "NOT snapshotted"):
  - **openStorages save loops depend on a deferral in another file.** `CustomBlockRegistry.saveAllOpenStorages`
    and `saveStoragesInChunk` iterate `openStorages` while calling `closeInventory()`, which fires
    `InventoryCloseEvent`. They avoid a CME ONLY because `CoreLibPlugin.onInventoryClose` defers the
    `openStorages.remove` by a tick via `runTask`. That deferral exists for a Bukkit-viewer reason, not
    documented (until now) as CME protection — a future dev making it synchronous reintroduces the exact CME
    we just fixed. Optional real fix: make the two save loops self-contained (snapshot values / two-phase
    collect-then-remove) so they don't rely on the listener's timing. Comments added at all three sites.
  - **tickParticles / tickAnimations are not snapshotted** (unlike their sibling `tickCustomBlocks`, which is).
    Safe only because their bodies (`spawnParticle`, `setTransformationMatrix`) never place/remove a block
    that would re-enter registration and mutate `particleTracked`/`animationTracked`. If a particle/animation
    config ever gains a block-registering callback, they become CME sites. Cheap consistency fix: wrap each in
    `new ArrayList<>(...)` like `tickCustomBlocks`. Left out for now to avoid a per-tick allocation on
    provably-safe loops; comments added noting the asymmetry.
  - **RotationNetwork safety rests entirely on the `recalculating` guard.** `recalculate()` routes re-entrant
    calls (from block events / `RotationNetworkPoweredEvent` fired mid-`doRecalculate`) into `pendingRecalcs`
    instead of recursing, which is what keeps `nodes`/`networks` from being mutated mid-iteration. Relevant to
    the planned migration of `doRecalculate` onto `RotationSolver` (see RotationSolver.java TODO): the guard
    must survive that move, or the maps become exposed to mid-iteration mutation from the `:580` event.
    Comment added at the guard.
  - **Piston `active` / minecart `tracked` iterator loops** are safe because no `onNeighborChange`/physics
    handler currently calls `ExtendablePistonManager.forget` (`active.remove`) or `MechanismMinecartManager`'s
    `tracked.remove` synchronously during a tick. If a future handler does, both iterator loops CME. No code
    change; noted here as a constraint on future physics/neighbor callbacks.