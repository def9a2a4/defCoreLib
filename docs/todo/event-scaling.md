# Scaling to many custom blocks: authoring refactor + shared-infra perf

Provenance: 2026-06 banner deep-review → 4 independent optimization agents → 3 critique agents. The
critiques materially corrected an earlier "add a central event dispatcher" idea. This doc is the
corrected direction. **Do not build a central event dispatcher** — the `CustomHeadBlock` registry
(`getTypeFromBlock` → per-type callbacks, dispatched from CoreLibPlugin's central handlers) already
*is* the per-block dispatcher; a bus would re-implement Bukkit's `HandlerList` with worse semantics.

## Verified corrections (don't redo / don't break)
- **Already implemented:** the `isCustomBlock` fast-set check and the `isNeighborReactive` guard before
  `getTypeFromBlock` already exist in `CoreLibPlugin` (physics/destruction handlers). Do not "add" them.
- **`getState()` on the liquid path is already cheap** — `getTypeFromBlock` returns null on a material
  compare for non-heads, so BlockFromTo/Burn don't pay a tile-entity snapshot for ordinary blocks.
- **Regression to avoid:** do NOT gate the `EntityDamage` handlers on `instanceof Display||Shulker` —
  `corelib:mech:` tags live on **ArmorStand** and **Minecart** vehicles/carriers too
  (`MechanismRegistry`), so that guard would strip damage protection from mechanism vehicles. If the
  listeners are kept, gate on `getScoreboardTags().isEmpty()` (cheap + complete); better, see C.

---

## A. Authoring / extensibility refactor  — highest leverage for "many more special-cased head blocks"
The registry scales *dispatch* fine; the **authoring pattern** is the real cliff as block-type count
grows (and it already is — `RotationBlocks` now overlays shaft/gear/reverser/clutch/water-wheel/engine/
millstone/press/generator/drill/fan + 3 windmills, and `CustomHeadBlock`'s callback surface keeps
growing: `DisplayTransformResolver`, `StateResolver`, `bannerTier`, …).

Two concrete problems:
1. **`toBuilder()` hand-copies every field.** Add a `CustomHeadBlock` field, forget one copy line, and
   every YAML-overlaid block silently loses it — no compile error, no test. Monotonically more
   dangerous per new field × per overlaid type.
2. **Overlay boilerplate.** Every rotation block repeats the identical network-lifecycle trio
   (`onChunkLoad`→`addNode` / `onChunkUnload`+`onBlockRemoved`→`removeNode`) and the identical
   wrench/debug `onInteract` dispatch. Hundreds of lines whose only real variation is a few
   enum/int params.

Work:
- **Extract the per-type callbacks into one immutable `BlockCallbacks` holder** referenced by a single
  field on `CustomHeadBlock`. Then `toBuilder()` copies one reference, and adding a future hook
  (explosion / piston-push / burn / place / craft / damage) touches `BlockCallbacks` + one central
  dispatch line — not the ~6 places it does today. This kills the silent field-drop bug and makes
  "broaden hooks" a one-place edit (the earlier "add hooks lazily" framing was wrong — do this
  generalization once, up front).
- **Factor the repeated overlay lifecycle into a shared helper** (e.g. `withNodeLifecycle(builder,
  blockId, role, power, gearLike)` + a shared wrench/debug interact) so each rotation block declares
  only its genuine variation.

## B. Unified display-entity index — the real remaining perf win
The `DisplayUtil.findByTag` → `world.getNearbyEntities(2.5)` scan is on **four** recurring paths, not
just banners:
- `BannerManager.handleRemoval` — every liquid-flow tick / piston / explosion block.
- `CustomBlockRegistry.applyConfig` — **every redstone power change** (re-spawns display entities).
- `CustomBlockRegistry.trackAnimations` — **every chunk load**, per animated block.
- `BannerManager.reloadConfig` — all entities in all worlds (dev-only, but folds in).

Work: index corelib display entities by **host location** at the single chokepoint `DisplayUtil.spawn`
(covers banner *and* custom-block displays uniformly; the tag encodes host coords) and on
`EntitiesLoad`; redirect all four scan sites to it.
Correctness (critique-mandated): key by **host coords, not entity chunk** (large/wall banners spawn on
`clicked.getRelative(face)`, possibly a different chunk); query the **3×3 chunk neighborhood** (the 2.5
radius crosses borders); `isValid()`-validate UUIDs and **fall back to `findByTag` on a miss** so a
desync can't leak/ghost a display; bed-break must check both foot and broken-block chunks.

## C. Quick wins (small, verify-first)
- **`setInvulnerable(true)` on banner + mechanism entities at spawn → delete BOTH `EntityDamage`
  listeners.** Removing a server-wide per-hit listener beats guarding it. Verify nothing depends on the
  cancel path (e.g. explosion knockback interactions).
- **Cache `LocationKey` in the tick-tracked records** to kill per-tick `LocationKey.of` allocations.
  (Coalescing the 4 per-tick sweeps into one is RISKY — redstone is 2-tick, and a merged order couples
  `applyConfig`'s `startTick` reset with the same-tick animation frame → visual snap. Skip unless the
  intra-tick order is pinned.)
- Investigate **dropping `BannerManager.onBlockFromTo`** entirely (Display entities aren't liquid-washed).

## D. Per-chunk index — real but de-prioritized (do last, folded into B)
`onChunkUnload` runs ~7 full-map `removeIf` scans; a per-chunk bucket (`chunkKey → Set<LocationKey>`)
makes it O(blocks-in-chunk) and yields an O(1) "anything corelib here?" gate. But it's MONITOR-priority
bookkeeping (microseconds at realistic counts), not the hot path — lower value than A and B. If built:
build it **with displays as a category from day one** (don't ship B standalone then re-home it — that
touches every banner site twice); preserve `saveStoragesInChunk` side effects + the tile-entity
unload-callback scan; and keep `getTypeFromBlock` authoritative for block *discovery* (the set is only
eventually consistent — not a safe discovery gate). Optional follow-on: collapse the parallel feature
maps into one `Map<LocationKey, BlockComponent>`.

## Recommended order
A (authoring refactor — serves the stated future best) → B (display index — real perf) → C (cheap) →
D (last, folded into B). Skip the dispatcher.

## Verification
- A: add a throwaway field to `BlockCallbacks` → every overlaid block still carries it (the bug that
  exists today); all rotation blocks still add/remove network nodes and respond to the wrench.
- B: liquid past flags, redstone toggling a display block, chunk reload with many animated blocks → no
  `getNearbyEntities` churn; break/explode/shear still removes + drops every display; the 3×3 + fallback
  prevent cross-chunk leaks.
- C: damage banner/mechanism entities in survival → still protected with the listeners gone.
