- textures/recipes -- agent has all info
- **hoist reel-in chain dupe (farmable).** Reeling a load up, if a player mines a swallowed chain
  link and then stops the reel-in partway (cut power / redstone clock), the emerging "swallowed"
  ghost link lands on the now-air cell via `placeBlock` and mints a real chain from nothing — rising
  has no reservation to pay for it. Repeatable by pulsing power. The 5aced89 ghost discard-on-identical
  only handles the case where that cell still holds an identical real chain
  (`BasicMechanism.disassemble`: `mb.ghost && target.getBlockData().equals(mb.blockData)`); the
  mined-to-air case falls through to `placeBlock`. Fix: a RISING emerging-ghost is visual-only (it
  represents a link being swallowed into the hoist) and must NEVER materialize — discard it at landing
  regardless of the target cell (on a full reel it already lands on the protected hoist cell; on a
  partial reel it currently mints). Descending ghosts are unaffected — they are real paid-out chain
  covered by the descend reservation.
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
  - shared movability guards: apply `MovableBlocks.isMovable` to the minecart default seed (block above cart), the rotator default seed (attachment block), and the glue brush (single + cuboid, with a rejection message) — was de-scoped to piston-only to avoid regressing shared glue/authoring
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