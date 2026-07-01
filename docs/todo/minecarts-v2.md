# Minecarts v2 — freeze-refinement + minecart-train readiness

## Context

Mechanism minecarts now support **glue** (an anchor-owned block selection stored in the cart's entity PDC —
see the door/rotator glue system and the `EntityAnchor` retrofit). A glued *unassembled* cart must not move,
because its glue offsets are resolved relative to the cart's **live** cell (`EntityAnchor.originBlock()` =
`cart.getLocation().getBlock()`): push the cart one cell without assembling and the whole selection drifts
onto empty space.

The shipped fix **freezes** a glued+unassembled cart with `Minecart.setMaxSpeed(0)` and lifts the freeze on
assemble/unglue. This doc covers (1) a refinement to stop that freeze from stomping `maxSpeed` every tick,
required before **chained minecarts / minecart trains**, and (2) the train-feature work that remains.

See [`minecarts.md`](minecarts.md) for the original mechanism-minecart design this builds on.

## What already shipped (baseline)

- **`EntityAnchor`** — `Anchor` backed by a minecart's entity PDC (persistent, no `BlockState.update()`).
  Offsets relative to the cart's current cell → glue is "assemble in place only".
- **`MechanismMinecartManager.assemble()`** — **glue-only**: assembles `glueManager.resolveStructure(anchor)`,
  or does nothing when the resolve is null/empty (the material allow-list flood-fill fallback was removed);
  rebinds glue on disassembly.
- **`GlueAuthoring.onInteractEntity`** — right-click a mechanism minecart with the glue item to author it;
  sneak-right-click clears its glue (mirrors the block-anchor `unglueAllAt`).
- **The freeze** — `MechanismMinecartManager.tick()` currently:
  ```java
  boolean frozen = state.mechanism == null
      && glueManager.hasGlue(new EntityAnchor(state.minecart, () -> true));
  state.minecart.setMaxSpeed(frozen ? 0.0d : DEFAULT_MINECART_MAX_SPEED);   // DEFAULT = 0.4
  ```
  Correct in-game today: `maxSpeed=0` clamps the cart's per-tick travel **inside the physics step**, so the
  cart never crosses a cell and glue can't drift — no teleport, no jitter, no boundary race.

## Why this needs a v2 (the problem)

The freeze writes `setMaxSpeed(...)` **every tick for every tracked cart**, including an unconditional
`0.4` on non-frozen carts. A minecart-train system will almost certainly want to **own cart speed** (uniform
train speed, parking a consist, custom acceleration). Our per-tick `0.4` write would silently clobber it
every tick — a nasty, hard-to-debug contention bug. We must only touch `maxSpeed` when *we* are actively
freezing, and leave the field alone otherwise.

### Two design decisions, both from adversarial critique

**1. Keep `setMaxSpeed(0)` as the freeze mechanism — do NOT switch to velocity-zero / teleport re-snap.**
`maxSpeed` is read *during* the vanilla movement step, so `0` prevents the move before it happens. Every
alternative freeze (per-tick `setVelocity(0)`, teleport-to-cell-centre) runs in our scheduled task **after**
entity physics — too late to stop a cart that already moved up to 0.4 and crossed a cell boundary *this*
tick, which then re-pins glue to the **wrong** cell. That boundary race is exactly why the first design
round rejected the teleport approach. `maxSpeed=0` is uniquely correct here.

**2. Drop the `savedMaxSpeed` snapshot idea — restore to the constant.**
An earlier refinement saved `cart.getMaxSpeed()` on entering freeze and restored it on exit. It has two
stuck-cart bugs:
- **Save-0:** if `getMaxSpeed()` is already `0` at capture time (a prior stale pin, another subsystem),
  we save `0`, "restore" `0`, and the cart is stuck forever.
- **Reload:** a soft `/reload` leaves the persistent entity pinned at `0` but rebuilds `MinecartState` with
  `frozen=false`; if the cart is unglued in the gap, nothing ever restores it → stuck.

A frozen cart is stationary and (by the train contract below) never a train member, so there is no custom
train speed to preserve. **Restore to the constant `DEFAULT_MINECART_MAX_SPEED` (0.4)** and skip the
snapshot entirely.

**3. Add a `corelib:frozen` scoreboard tag** alongside the freeze. It serves two purposes:
- **Train contract:** the future train system's rule is "never couple or move a cart carrying
  `corelib:frozen`." Makes the freeze↔train boundary explicit and one-directional instead of a fight over a
  shared field.
- **Reload marker:** scoreboard tags persist on the entity, so a re-tracked cart's freeze state is
  recoverable after chunk reload / `/reload` / crash, closing the stale-pin gap.

## Work blocks (execution order)

All in `MechanismMinecartManager.java` unless noted. References are against current source.

### A. State + helpers
- `MinecartState` — add one field:
  ```java
  boolean frozen;   // are WE pinning this cart (maxSpeed 0 + corelib:frozen tag)?
  ```
  **Do not** add `savedMaxSpeed`.
- Add a constant + two helpers:
  ```java
  private static final String FROZEN_TAG = "corelib:frozen";

  private void freeze(MinecartState state) {
      if (state.minecart.getMaxSpeed() != 0.0d) state.minecart.setMaxSpeed(0.0d);
      state.minecart.addScoreboardTag(FROZEN_TAG);     // idempotent
      state.frozen = true;
  }

  private void unfreeze(MinecartState state) {
      state.minecart.setMaxSpeed(DEFAULT_MINECART_MAX_SPEED);
      state.minecart.removeScoreboardTag(FROZEN_TAG);   // idempotent
      state.frozen = false;
  }
  ```
  `getMaxSpeed() != 0.0d` exact-double compare is safe: this class only ever assigns the literals `0.0d` /
  `0.4d`, and Bukkit stores `maxSpeed` as a plain field (no arithmetic).

### B. Transition-based `tick()` (replace the current lines ~219-224)
```java
boolean shouldFreeze = state.mechanism == null
    && glueManager.hasGlue(new EntityAnchor(state.minecart, () -> true));
if (shouldFreeze) {
    freeze(state);            // enters, or re-asserts the pin while already frozen
} else if (state.frozen) {
    unfreeze(state);          // leaving freeze (assembled or unglued) → restore vanilla speed
}
// not frozen and shouldn't be: ZERO maxSpeed writes — a train system owns the field.
```
Re-asserting `freeze` each frozen tick keeps the pin even if something else disturbs `maxSpeed`; the tag/set
calls are idempotent so this is cheap.

### C. Reconcile the lifecycle edges the tick loop lags
The rising-edge assemble/disassemble runs *below* the freeze block in `tick()`, and `onMinecartInteract`
calls them entirely outside `tick()`, so `frozen` can lag `mechanism` by a tick. Fix the ones that matter:
- **`assemble()`** — after `state.mechanism = mech;` (~line 286) call `unfreeze(state)` so an assembled cart
  rides *this* tick instead of staying pinned for one tick.
- **`disassemble()`** — no special-casing needed: it already calls `snapAndStop` (cart stopped), and the
  next tick re-freezes if still glued. (No meaningful movable window.)
- **`shutdown()`** (~line 155) — before `tracked.clear()`, `unfreeze(state)` every tracked cart so no
  persistent entity is left pinned at `maxSpeed 0` across `/reload`.
- **`scanChunkForMinecarts()`** (~line 104) — when re-tracking, if the cart already carries `FROZEN_TAG`,
  set `state.frozen = true` on the fresh `MinecartState`. Next tick's `shouldFreeze` then correctly keeps it
  pinned (still glued) or `unfreeze`s it (no longer glued) — closing the stale-pin gap after crash/reload:
  ```java
  MinecartState st = new MinecartState(minecart);
  if (minecart.getScoreboardTags().contains(FROZEN_TAG)) st.frozen = true;
  tracked.put(minecart.getUniqueId(), st);
  ```

No `GlueAuthoring` change.

## Verification (in-game)

1. **Regression:** glue → cart won't move; sneak-clear → rolls free; assemble on activator rail → rides
   **immediately** (no 1-tick stall); disassemble → frozen again.
2. **Reload:** `/reload` (or unload+reload the chunk) a glued frozen cart → still frozen after; then clear
   its glue → it frees (no permanent stuck-at-0).
3. **No stomp:** with a command/other plugin set an *unglued* cart's `maxSpeed` to 0.8 → confirm our tick no
   longer resets it to 0.4 (proves we stopped owning speed on non-frozen carts).
4. **Tag:** `/data get entity <cart> Tags` shows `corelib:frozen` only while glued+unassembled, gone once
   assembled or cleared.

---

## Companion workstream — namespace move, furnace-minecart item, recipe, + polish fixes

Independent of the freeze refinement above, but touches the same files. Rehomes the mechanism minecart
into the `rotation` namespace, gives it a real craftable identity (it currently has **no recipe** and a
player-head texture), and fixes three behavior rough edges found while reviewing it. **After this lands,
the `demo:mechanism_minecart` id below becomes `rotation:mechanism_minecart`** — update references
accordingly.

### Decisions (locked)
- **Namespace: `rotation`** (not a new `mechanisms` namespace). Chosen because `rotation` is already in
  `generate_catalog.py` `BLOCK_FILES`, in `catalog.js` `NS_LABELS`/`NS_PRIORITY`, and in
  `give_demo_rotation` — so docs + dev-give pick it up with zero pipeline edits. Accepted cosmetic
  side-effects: the item now shows in `give_demo_rotation`, sorts into the **Redstone** crafting-book
  tab (`CustomBlockRegistry` rotation→REDSTONE category), and lands in the docs "rotation" quad.
- **Held item: `FURNACE_MINECART`** with a custom name, lore, and enchant glint. **The spawned cart
  keeps its LODESTONE display block** — deliberately unchanged (the furnace look is item-only).

### A. YAML move: `demo-blocks.yml` → `rotation-blocks.yml`
Remove the `mechanism_minecart:` entry (+ its banner comment) from `demo-blocks.yml`; add it under
`blocks:` in `rotation-blocks.yml` (namespace already `rotation`). Drop `texture:` (unused once
`item_material` is set — `BlockLoader` makes texture optional then) and drop `drops: self` (a
`placeable:false` item never takes the block-break drop path; its drop is handled by
`onMinecartDestroyed`):

```yaml
  # ─── Mechanism Minecart ──────────────────────────────────────────────
  # Item-only (placeable: false): right-clicking a rail spawns a minecart entity.
  # Spawn/tick/assemble/destroy logic lives in MechanismMinecartManager (Java).
  mechanism_minecart:
    name: "&6Mechanism Minecart"
    lore:
      - "&7Right-click a rail to spawn a cart."
      - "&7Glue blocks to it, then power an"
      - "&7activator rail to assemble & ride them."
    item_material: FURNACE_MINECART
    item_glint: true
    placeable: false
    recipes:
      craft:
        shaped:
          - pattern: ["IPI", "ROR", " M "]
            key:
              I: { material: IRON_INGOT }
              P: { material: STICKY_PISTON }
              R: { material: REDSTONE }
              O: { block: "rotation:rotator" }
              M: { material: MINECART }
```

Recipe layout:
```
I P I     I = iron ingot        P = sticky piston
R O R     R = redstone          O = rotation:rotator (custom item → ExactChoice)
· M ·     M = vanilla minecart  · = empty
```
`item_material`/`item_glint`/`lore` are all supported keys (precedent: `wrench`, `glue_item`, slabs;
`FURNACE_MINECART` is a valid `Material`). The custom-item ingredient mirrors the existing `rotator`
recipe (`{ block: "rotation:bearing" }`). Recipes register in one batch in
`CustomBlockRegistry.finalizeLoading()` after all types load, so rotator-before-minecart ordering is a
non-issue.

### B. Rename the registry id in `MechanismMinecartManager.java`
Replace **every** occurrence of the string `"demo:mechanism_minecart"` with
`"rotation:mechanism_minecart"` (5 sites: register() existence check + its warning text,
`isMechanismMinecartItem` PDC compare, `mechRegistry.assembleMechanism(...)` id, and the
`registry.getType(...)` drop lookup). **Leave** `new NamespacedKey(plugin, "mechanism_minecart")` and
the `"corelib:mechanism_minecart"` scoreboard tag — both unrelated to the block namespace. Verify:
`grep -rn "demo:mechanism_minecart" src` → no results.

### C. Docs
No code change needed. Regenerating (`make docs-fast` / `make docs`) moves the item under **Rotation**
with a material icon `{type: material, material: FURNACE_MINECART}`. **Verify the icon renders non-blank**
— item textures vendor differently from block textures in `generate_catalog.py`; if it comes up blank,
patch the catalog image step (the one thing to watch).

### D. Fix — don't consume the item in creative
`onPlaceMinecart` currently decrements the stack unconditionally. Guard it with the existing
`BannerManager.consumeItem` idiom:
```java
if (event.getPlayer().getGameMode() != org.bukkit.GameMode.CREATIVE) {
    item.setAmount(item.getAmount() - 1);
}
```

### E. Fix — creative pick-block returns the custom item
The existing `CoreLibPlugin.onPickBlock` handles `PlayerPickBlockEvent` (placed **blocks** only). The
cart is an **entity**, so add a handler for `io.papermc.paper.event.player.PlayerPickEntityEvent`
(present in paper-api 1.21.8: `getEntity()`, `getPlayer()`, `getTargetSlot()`/`setTargetSlot()`,
`setCancelled()`). When the picked entity is a tracked mechanism minecart, cancel and place
`type.createItem(1)` into the target hotbar slot, reusing `onPickBlock`'s slot-selection logic (factor
it into a shared helper). Cleanest home is `MechanismMinecartManager` (owns `tracked` /
`isMechanismMinecart`):
```java
@EventHandler(priority = EventPriority.HIGHEST)
public void onPickEntity(io.papermc.paper.event.player.PlayerPickEntityEvent event) {
    if (!(event.getEntity() instanceof Minecart cart) || !isMechanismMinecart(cart)) return;
    CustomHeadBlock type = registry.getType("rotation:mechanism_minecart");
    if (type == null) return;
    event.setCancelled(true);
    // place type.createItem(1) into event.getTargetSlot() (reuse onPickBlock's hotbar logic)
}
```
**Runtime caveat (verify in-game):** the *class* is confirmed present, but not that Paper *fires* it for
a middle-clicked minecart in creative (minecarts have a vanilla item form). If it doesn't fire, fall back
to intercepting the creative give another way (e.g. `InventoryCreativeEvent` swapping a picked
`MINECART`/`FURNACE_MINECART` cursor result when the player is looking at a tracked cart).

---

## Known issues / gaps (found while reviewing; not yet fixed)

Sharp edges in the current mechanism-minecart + glue code, surfaced by an audit. Ordered by impact.

### 1. Orphan-on-restart wipes an *assembled* cart (DATA LOSS) — HIGH
Mechanisms are in-memory only (`MechanismRegistry.activeMechanisms`), never serialized. If the server
**restarts** (or, less severely, a chunk cycles) while a cart is **assembled**, on boot
`MechanismRegistry.cleanupOrphanedEntities` (run from `EntitiesLoadEvent`) deletes every `corelib:mech:*`
display as orphaned, while `scanChunkForMinecarts` re-tracks the cart as **un**assembled
(`MinecartState.mechanism == null`). The structure vanishes: displays removed, the captured blocks are
**never placed back**, and the cart reverts to a bare lodestone/furnace minecart. Not minecart-specific —
this is the transient-mechanism design and hits doors/rotators too — but carts are the easiest way to be
caught mid-assembly. Fixing properly means either persisting enough to rebuild the mechanism on load, or
force-disassembling (placing blocks back) any assembled mechanism **before** unload/shutdown rather than
just dropping the in-memory state. `shutdown()` already disassembles on clean plugin-disable; the gap is
hard restarts / crashes and chunk unload.

### 2. Custom-block storage lost through assemble→disassemble — MEDIUM
`MechanismRegistry.assembleMechanism` captures a custom block's storage into `mb.storage`, but
`BasicMechanism.placeBlock`'s custom-block branch (`customTypeId != null`) restores identity/state only and
**never writes the inventory back** — only the vanilla-`Container` branch does. Glue a container-like custom
block to a cart → assemble → disassemble → its contents are silently emptied. Already tracked as **Known
Issue #7 in [`blockships-integration.md`](blockships-integration.md)**; noted here because gluing custom
blocks to carts is a prime trigger. Fix belongs in the mechanism layer (restore `mb.storage` for the
custom-block placement path), so it's shared with doors/rotators.

### 3. Flood-fill fallback can assemble a *surprise* structure — RESOLVED (fallback removed)
Minecarts are now **glue-only**: `assemble()` uses `glueManager.resolveStructure(anchor)` and assembles
nothing when the resolve is null or empty. The `floodFillAllowed` helper, the `allowedMaterials` allow-list,
`loadAllowedMaterials()`, and `mechanism-minecart-blocks.yml` were all removed. This eliminates the
surprise-structure hazard entirely — a cart can never assemble blocks the player didn't explicitly glue,
so there is no partial-glue-then-flood-fill path left to warn about. (Door and rotator keep their own
flood-fill fallback; only the minecart changed.)

### Carried over from [`minecarts.md`](minecarts.md) — still live
The main subject of `minecarts.md` (the **off-center pivot rotation bug** and its delta-tracked-pivot fix)
is **implemented** — `BasicMechanism.updateFromVehicle` delta-accumulates the pivot rather than overwriting
it. Of that doc's "Additional Issues (Review 2026-06-25)" list, verified against current source: **#1 float
snap, #2 flood-fill truncation warning, #9 1-tick-lambda validity are now FIXED.** Still present:

### 4. Same-world large teleport unguarded — LOW (became *live* once the pivot fix shipped)
`BasicMechanism.updateFromVehicle` blindly delta-accumulates any distance. It has a **cross-world** guard
(re-snap on world change) but **no same-world threshold** — `/tp`-ing a riding mechanism cart a long
distance makes the pivot jump discontinuously (blocks/colliders lurch). This was harmless before the pivot
fix (old code overwrote pivot each tick); the delta-tracking fix made it live. Fix: if `|delta| > ~10`
blocks, treat as a teleport — re-snap pivot to `snapped(loc)` and reset `previousVehicleLoc`, same as the
cross-world guard.

### 5. Assembly-time vehicle validity guard — LOW (defensive)
`assembleMechanism(Entity existingVehicle, ...)` doesn't check `existingVehicle.isValid()` at entry; a dead
/ removed entity silently produces a broken mechanism. Guard at entry.

### 6. `tickMechanisms()` iteration safety — LATENT
Iterates `activeMechanisms.values()` directly. Safe today (nothing removes during tick), but adding
validity-based cleanup in the tick would throw `ConcurrentModificationException`. Defensive-copy
(`new ArrayList<>(...)`) if/when cleanup moves into the tick.

### 7. Matrix allocation in the animation tick — PERF
Allocates a `new Matrix4f` per animated display per tick; only `workMatrix` is reused. Negligible now,
noticeable at 50+ mechanisms (relevant for the BlockShips scale). Pre-allocate a `workBase`.

### 8. Particle ticking not implemented — FEATURE GAP
`MechanismRegistry` still has `// TODO: particle ticking for mechanism blocks`; `ParticleConfig` is resolved
and stored on `MechanismBlockData` but never spawned while assembled. Blocks that declare particles won't
emit them inside a mechanism.

### 9. `MechanismBlockData.collisionScale` unused — trivial
Stored (always `1.0f`), never read. Placeholder for shulker scaling; document or remove.

### F. Fix — spurious 180° rotation on cart reversal
**Root cause:** `BasicMechanism.updateFromVehicle()` reads the minecart's raw `Location.getYaw()` and
applies `rotate(yaw - assemblyYaw)`. `rotate()` sets `currentYaw` **absolutely** (idempotent), re-deriving
orientation from the assembly baseline each tick. A minecart's yaw flips ~180° when it reverses along a
straight rail → the structure spins 180°. Confirmed `updateFromVehicle` is driven **only** by the minecart
path (doors/rotators use the Location-owned-vehicle path), so this is safe to change.

**Fix:** fold the delta into `(-90°, 90°]` before rotating — shortest-path folding, **not** Java `%`
(which misbehaves on negatives):
```java
float delta = yaw - assemblyYaw;
while (delta > 90f)  delta -= 180f;
while (delta < -90f) delta += 180f;
rotate(delta);
```
A 180° reversal folds to 0° (keeps orientation); a genuine 90° curve stays 90°. Consistent with
`disassemble()`'s existing 90° snap. **Trade-off:** can't distinguish a reversal from a track that curves
a full 180° (the latter would no longer flip). Straight-rail reversal (the reported bug) is the common
case; a fully-correct fix would derive orientation from rail/travel direction — deferred unless needed.

### Verification (this workstream)
1. **Build:** `./gradlew build`; `grep -rn "demo:mechanism_minecart" src` returns nothing.
2. **Docs:** rebuild; `docs/data/items.json` lists `mechanism_minecart` under `"namespace": "rotation"`
   with the recipe (not under `demo`); page renders it in the Rotation group with a non-blank icon.
3. **In-game:** `give_demo_rotation` → glinting **Mechanism Minecart** (furnace-minecart base) w/ lore;
   craft via the pattern; creative place doesn't consume; creative middle-click returns the custom item
   (else apply E's fallback); assembled structure no longer flips on straight-rail reversal but still
   turns on 90° curves; breaking the cart drops one item.

---

## Deferred — the actual minecart-train feature

Greenfield: no train / couple / link / consist scaffolding exists in the codebase today. Open questions and
known interactions to resolve when this is built:

### Coupled-cart push resistance — MUST-TEST
`maxSpeed=0` clamps a cart's **own** per-tick distance. Whether it also fully absorbs a **shove** from a
colliding/coupled neighbour (vanilla cart-cart collision writes velocity onto the pushed cart) is unverified
in-game. Risk: a neighbour nudges a frozen cart off `originBlock()`'s cell → glue drift returns.
- First line of defence is the `corelib:frozen` **contract** (a train must not couple/move a tagged cart).
- If testing shows a shove still leaks through, add a **guarded re-snap**: teleport to the cell centre only
  when the cart is displaced beyond a small epsilon. Safe here *because* `maxSpeed=0` caps the per-tick
  distance small, so the re-snap always corrects within the same cell — no boundary race. (Reuse
  `snapAndStop`'s teleport-to-centre logic, ~line 265.) Leave it out until proven necessary.

### Policy: unassembled-glued cart in a train
A glued unassembled cart is an **immovable anchor** (it can't move, and — pending the test above — resists
being pushed). Decide the train UX:
- **Refuse to couple** an unassembled-glued cart, or
- **Treat it as an anchor** the train can't move through until it's assembled or its glue is cleared.

### Assembled mechanism carts in a train
These are fine by construction: once `mechanism != null` the freeze restores `maxSpeed 0.4`, the cart rides
on vanilla physics, and the mechanism display follows it (`BasicMechanism.updateFromVehicle`,
`MechanismRegistry` teleports the parent display each tick). A train of *assembled* carts couples and moves
normally; nothing in the freeze touches them. The train system owns `maxSpeed` on them freely (that's the
whole point of work block B).

### Architecture notes for whoever builds trains
- Movement is **vanilla-physics driven** end to end; the plugin never sets cart velocity. A train system
  that drives carts by velocity/teleport must coexist with that (and with each cart's own mechanism-follow).
- `setMaxSpeed` is **per-entity**; after this v2 refinement the freeze only writes it on frozen carts, so the
  train system can treat `maxSpeed` as its own on every non-`corelib:frozen` cart.
- Cart identity: one `rotation:mechanism_minecart` item (was `demo:mechanism_minecart` — see the
  companion-workstream rename above) → one `RideableMinecart`, tagged `corelib:mechanism_minecart` +
  a PDC key, tracked by UUID in `MechanismMinecartManager.tracked`.
