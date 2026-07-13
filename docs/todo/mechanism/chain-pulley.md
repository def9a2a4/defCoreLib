# Chain Pulley — implementation status & plan

The **chain pulley** (`mech:chain_pulley`) is the first shipped slice of the power-chains feature
(design vision: [`power-chains.md`](power-chains.md)). A shaft-like rotation **transmitter** that links
to other pulleys by iron chain, so rotational power jumps a gap around a **closed loop**. This doc is
the living implementation status / round plan; the design rationale lives in `power-chains.md`.

Code: `ChainPulley.java` (block overlay + link interaction + chain display), `RotationNetwork.java`
(the `chainOut` distance-edge graph), `rotation-blocks.yml` (`chain_pulley` block + recipe),
`rotation-config.yml` (`chain-pulley.max-distance`), `RotationConfig.java`, `RotationBlocks.java`.

## Model
- **Directed functional graph.** Each pulley stores ONE outgoing partner in its skull PDC
  (`mech:chain_out`, `INTEGER_ARRAY {x,y,z}`); `RotationNetwork.chainOut` mirrors it in memory.
- **Closed loop required.** `RotationNetwork.getConnections` injects a pulley's out-edge only when
  `onClosedLoop(k)` (following `chainOut` returns to `k`). Reverse-duplicate links are rejected, so the
  minimum working loop is a ring of **3+** pulleys (`A→B→C→A`).
- **Floor placement only** (`placement: allowed_faces: [DOWN]`); pulleys stack/branch and the chain
  runs between wheel centres.

## Done (rounds 1–5, committed)
- Block + recipe (a `mech:bearing` surrounded by iron chains), floor-only placement, placed head =
  floor-windmill hub (`@windmill_hub_up`), in-hand item + spinning wheel display = `@chainwheel`.
- Link UX: right-click a pulley with a chain to select, another to link; right-click a linked pulley to
  remove (refund). Directed, one outgoing link each, same-world, config-max-distance.
- Chain visual: a single stretched `IRON_CHAIN` block display along the link, **rotating about its long
  axis when the loop is powered** (per-tick ticker gated on `isPowered && onClosedLoop`).
- **Ring power fix:** `doRecalculate` teardown is transitive over `getConnections`, so closing a 3+ ring
  powers every member (previously fragmented, leaving far wheels dead).
- Chain **cost = rounded block distance**; too few in hand → message, no link; unlink refunds the amount.
- **Configurable** max link distance (`rotation-config.yml chain-pulley.max-distance`, default 32).

## Round 6 (in progress) — three small items
1. **Lore** — drop the stale "(max 10 blocks apart)" from `chain_pulley` `catalog_notes` (distance is
   config-driven now; links cost chains + power). Rewrite number-free:
   ```yaml
       catalog_notes:
         - "&fA transmitter you can link to other pulleys with a chain."
         - "&7Right-click one pulley with a chain, then another, to link them — uses chains equal to the distance."
         - "&7Power flows only around a closed loop of three or more pulleys, and each link draws a little power."
   ```
2. **Power cost per link (balance vs gears).** Each wheel draws `ceil(span/10)` power on its live loop
   link. In `RotationNetwork.doRecalculate`, at the member demand sum: if node is `mech:chain_pulley`
   and `onClosedLoop(loc)`, `demand += ceil(dist(loc, chainOut.get(loc)) / 10)`. Live per-recalc, gated
   on the closed loop so a dead chain costs nothing.

3. **Display → single stretched chain BLOCK (was custom head).** Swap the strand from a custom-head
   `ItemDisplay` to a real `IRON_CHAIN` `BlockDisplay`, stretched to span the gap and **rotating about
   its long axis when powered** (keep the existing per-tick ticker; linear "sliding" is too costly/hard,
   so rotation only). In `ChainPulley`: `spawnStrand` → `DisplayUtil.spawnBlock(anchor,
   CHAIN_MATERIAL.createBlockData(), matrix, tag)`; geometry `orient = rotationTo(+Y, dir)`,
   `translation = dir/2` (midpoint), `scale = (1, length, 1)`; matrix =
   `T(pos)·R(orient)·Ry(angle)·S(scale)·T(-0.5,-0.5,-0.5)` — the trailing −0.5 is the BlockDisplay
   corner-shift (ItemDisplay was centred and didn't need it). `StrandAnim.display` becomes `Display`;
   `tickStrands` builds the same matrix with the live `angle`; drop the head texture (`strandTexture`,
   `resolveTexture("strand")`) and the `LENGTH_SCALE`/`FORWARD_FRACTION`/`DIAMETER` constants (unused).

> Sliding/translation motion was considered and rejected (too costly/hard) — rotation-about-axis only.

## Deferred: seamless auto-grab ski lift (next round)
No player interaction — a normal minecart part of a rail contraption. A moving cart within a small
radius of a live chain segment is lifted off the rail, carried to the far pulley at chain
speed/direction, then released back to normal physics. Reuses the mechanism-minecart gondola-follow
(`MechanismMinecartManager` + `EntityAnchor` + `updateFromVehicle`). Needs geometry design: grab radius,
per-tick carry along the `chainOut` loop polygon, release conditions.

## Remaining open items (as of 2026-07-14, post deep-review)
Core feature, destruction-robustness (per-block piston policy, chain drops on break/glue,
entity-change protection), and the wrench/UX/hygiene pass are all shipped. Outstanding:

- **[open — correctness] Lock-gate phantom demand + recalc perf ("Fix 3").** The standalone demand
  block in `RotationNetwork.doRecalculate` charges `ceil(span/10)` for any pulley on a closed loop
  without checking `!isLocked(partner)` — so a clutched (locked) pulley on a loop still draws power for
  a link that transmits nothing, which can wrongly tip a marginal network unpowered. Fix: add a
  `chainDrive` flag to `Connection`, inject it only on the already-gated/deduped chain edge, and move
  the demand charge onto that edge (keep an explicit `PULLEY_ID` gate). Fold in a per-`doRecalculate`
  `onClosedLoop` memo (label the whole cycle → O(L), not O(L²)) and the N1 fix (an adjacent deduped
  link no longer double-charges). Vetted across 5 agent reviews; held only because it edits core power
  math. Note: on upgrade, a network previously stuck unpowered by phantom demand may correctly flip to
  powered after the first recalc.
- **[deferred] Reload strand duplication ("Fix 1").** `handleChunkLoad` re-acquires the strand via
  `DisplayUtil.findByTag` at `ChunkLoadEvent`, before Paper loads entities → a duplicate strand spawns
  and the persisted original is orphaned. The copies overlap exactly, so it's an *invisible* entity
  leak (the stale copy sits static behind the spinning one), not a visible glitch. Fix: move the
  re-acquire to `EntitiesLoadEvent` (make `ChainPulley` a `Listener`), adopt the persisted strand,
  dedup/orphan-sweep, and respawn only genuinely-missing strands. Left undone — not reproducible in a
  quick in-game check; only manifests after a server restart / chunk reload, so re-test with a restart
  before implementing.
- **[deferred — cosmetic] Wheel-spin ignores direction.** The registry-owned spinning chainwheel always
  turns one way, while the manually-ticked strand shows the true CW/CCW. A correct fix needs duplicate
  CW/CCW animation states or converting the wheel to a manually-ticked display like the strand —
  disproportionate for a cue the strand already conveys.
- **[accepted] `/setblock` / `/fill` / WorldEdit node leak (#6).** Removing a pulley via these bypasses
  Bukkit events, so its `RotationNetwork` node isn't cleaned up. Inherent to those tools; orphaned
  displays are recoverable via `/defcorelib refreshdisplays`, network nodes are not.
- **[deferred feature] Seamless auto-grab ski lift** — designed above; not started.

## Verify
`make build && make docs`. Link a 3-wheel loop → chain renders as the stretched rotating display,
spinning only when the loop is powered; lore shows no "max 10"; power flows only with surplus covering
the machines **plus** each link's `ceil(span/10)` (a too-long chain on a weak source stays dead).
