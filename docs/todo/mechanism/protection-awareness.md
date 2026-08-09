# TODO: Protection-aware rider-less movers (WorldGuard)

> Deferred follow-up surfaced during the 2026-08 mechanism-hardening / mow-fragiles work.
> Line numbers are `~`-approximate — re-grep before editing.

## Problem

defCoreLib has **no protection layer**. Every automation-driven world write bypasses region
protection:
- mechanism assembly air-out (`MechanismRegistry.airOutSourceBlocks` ~987–990, `setType(AIR)` on every source block),
- mechanism landing (`BasicMechanism.placeBlock` `setBlockData` ~1108, fragile-branch `breakNaturally` ~913),
- fluid endpoints (`WorldSourceEndpoint`, `CauldronEndpoint`),
- the **automated drill** (`RotationBlocks` ~2159–2161) — breaks *any* non-blacklisted solid block (the biggest bypass),
- the **plant-mow** feature (`ExtendablePistonManager:634`, `ChainHoistManager:674` / `:908`) — the one that surfaced this.

The only protection-aware break is `CartCollision.tryBreak` (fires `BlockBreakEvent` + honors cancel), and it works **only because a cart has a rider** to attribute the break to. Rider-less movers structurally can't use that path.

The plant-mow is **consistent with this stance, not an outlier** — it silently shreds a WorldGuard-protected
garden, but so does the landing and (worse) the drill. Deferred rather than treated as a blocking bug.

## Design (option D) — from a 3-agent investigation

### Dependency direction forces the seam
defCoreLib is the **lower** layer (BlockShips `depend: [DefCoreLib]`, pulls core as `compileOnly`). So core
**cannot** reference BlockShips' `WorldGuardHook` and should **not** take on the WorldGuard dependency.

- **Core owns** a tiny `ProtectionHook` abstraction (new `anon.def9a2a4.corelib.integration` package):
  ```java
  public interface ProtectionHook {
      boolean isBuildDenied(Location loc, @Nullable Player player); // null = system / rider-less
      boolean mightRestrict(World world);                          // O(1); MANDATORY gate before isBuildDenied
      default boolean isMowDenied(Location loc) {                  // convenience for rider-less movers
          World w = loc.getWorld();
          return w != null && mightRestrict(w) && isBuildDenied(loc, null);
      }
      ProtectionHook[] HOLDER = { new NoOpProtectionHook() };
      static ProtectionHook get() { return HOLDER[0]; }
      static void set(ProtectionHook h) { HOLDER[0] = (h != null) ? h : new NoOpProtectionHook(); }
  }
  ```
  `NoOpProtectionHook`: everything false → standalone core behaves exactly as today.
- **BlockShips injects** a ~12-line `CoreProtectionAdapter` delegating to its existing `WorldGuardHookImpl`,
  wired in `BlockShipsPlugin.setupWorldGuardHook()` (both branches: real hook, and `set(null)`→NoOp on
  disabled/absent). Keeps the subtle WG query code in ONE place (BlockShips), no WG dep in core.

### The measure/mow-consistency crux (do not get this wrong)
Piston + rising-hoist **measure** reach counting fragiles as passable (`clearForAll(..., throughFragile=true)`),
then **mow** later in `advance()`. If the mow refuses a protected plant but the stroke already committed to
reach past it, the mover lands *through* it. Fix: **one shared predicate used at BOTH the measure branch AND
the mow site**:
```java
static boolean mowable(Block cell, boolean prot) {
    return FragileBlocks.isFragile(cell.getType())
        && !(prot && ProtectionHook.get().isBuildDenied(cell.getLocation(), null));
}
```
A protected fragile then reads as a **wall** at measure time → the stroke stops one cell short and never mows
it. Compute `prot = ProtectionHook.get().mightRestrict(world)` **once per stroke**, store on `ActiveMove`.

- **Descending hoist** is self-consistent for free (it mutates-then-remeasures per cell) — only
  `mowFragilesAhead` (~908) needs the `mowable` guard; a protected plant stays non-air → `clearForAll` reads
  it blocked → the descent freezes above it.

### Policy
- **null player** always (rider-less) → `NON_MEMBER`: denied inside any region, allowed outside.
- **Fail-OPEN** on a transient WG fault (mow proceeds). Failing closed would make every plant read as a wall
  during a fault → freezes every mover server-wide. (Matches BlockShips: fail-closed only on the assembly
  gate for anti-laundering; fail-open on destructive paths.)
- **`mightRestrict` gate is MANDATORY** at every site: a `useRegions:false` world resolves `isBuildDenied` to
  DENY *everywhere*, which ungated would freeze all strokes.
- No `systemPathPlacesInRegions` — the mow destroys *third-party* blocks, so "mow-anyway" has no legitimate use.

## Scope / stages

- **Stage 1 (this doc) — mow-only.** A coherent first cut: closes the distinct "a rider-less mover destroys
  protected blocks it doesn't own, uncompensated" invariant. Effort: 2 core files (~40 lines) + ~15 BlockShips
  lines + ~6 focused edit points across `ExtendablePistonManager` (measure ~466, `startMove`/`ActiveMove`, mow
  ~634) and `ChainHoistManager` (measure ~882, `payOut` prot, `ActiveMove` field, rise mow ~673, descend
  `mowFragilesAhead` ~908).
- **Stage 2 (later).** Same `ProtectionHook` seam gates the **mechanism landing** (`placeBlock` —
  drop-instead-of-write, likely via the existing `cellPlacePolicy` seam, which BlockShips already earmarks as
  a TODO) and the **automated drill** (`RotationBlocks` ~2159–2161). Larger surface (matter-conserving, own
  blocks, dupe/refund hazards) — a dedicated change.

## Reference
BlockShips is the impl template for the WG traps: denied ≡ `state != ALLOW` (not `== DENY`);
`Associables.constant(NON_MEMBER)` for the null-player subject (BUILD requires a subject); explicit
`hasBypass` check (queries ignore bypass); `Class.forName`-guarded impl (softdepend); one-shot error throttle.
See `../BlockShips/blockships/src/main/java/anon/def9a2a4/blockships/integration/WorldGuardHook*.java` and its
call sites in `customships/BlockStructureScanner.java`. Related: [blockships-integration.md](blockships-integration.md).

## Checklist
- [ ] **Decide**: pursue option D (mow-only Stage 1) vs accept + document the bypass (matches existing stance).
- [ ] Core: `ProtectionHook` interface + `NoOpProtectionHook` + static holder (`corelib.integration`).
- [ ] Core: shared `mowable(cell, prot)`; gate piston measure (`clearForAll` throughFragile branch) + mow; thread `prot` through `ActiveMove`.
- [ ] Core: gate hoist rising (measure + mow) and descending (`mowFragilesAhead` only); thread `prot` through `ActiveMove`.
- [ ] BlockShips: `CoreProtectionAdapter` wrapping `WorldGuardHookImpl`; inject via `ProtectionHook.set(...)` in `setupWorldGuardHook()` (both branches).
- [ ] Verify: build both repos; mow stops at a protected plant (fail-open, `mightRestrict` gate); no regression in region-free / `useRegions:false` worlds.
- [ ] **Stage 2 (later)**: gate mechanism landing + automated drill via the same hook.

> Cross-repo change (core + BlockShips) touching mow files another session actively edits — coordinate/relay.
