# Power Chains — long-distance rotational link + minecart ski-lift

## Context

A **chain wheel** (sprocket) block mounts on a rotation shaft and links to a second, distant
chain wheel by a rendered chain loop. The loop transmits rotational power between the two wheels at
a distance, and a minecart can clip onto a powered chain and be carried along it like a **ski lift**
(a gondola glued to the cart rides along for free).

See [`rotation-power.md`](rotation-power.md) for the network this extends, and
[`minecarts.md`](minecarts.md) / [`blockships-integration.md`](blockships-integration.md) for the
glue + mechanism-minecart machinery the ski-lift reuses.

> **Naming:** this is unrelated to [`chain-shaft.md`](chain-shaft.md), which renders
> `rotation:shaft` as a vanilla `Material.CHAIN` block. Here a "chain wheel" is a **skull node** and
> the chain between wheels is a **display entity**, not a placed `CHAIN` block. The feature is named
> *power chains* to keep the two distinct.

### Core insight — a chain is just a graph edge at a distance

`RotationNetwork` is a pure graph solve: `getConnections()` (~`RotationNetwork.java:548`) derives
edges from block positions, and `doRecalculate()` BFS (~`:293`) propagates spin direction +
supply/demand and flags jams. **There is no edge-at-a-distance concept today** — every edge is
cardinal adjacency (or 6-neighbour gear mesh). The whole feature is: inject a `Connection` between
two non-adjacent nodes. Once the edge exists, power propagation, direction anchoring, and jam
detection all work unchanged.

## Design decisions

- **Block: `mech:chain_wheel`** — a skull node registered via the `RotationBlocks.overlayStandard`
  builder pattern (`.toBuilder()` → `onChunkLoad`/`onNeighborChange`/`onInteract`/`onBlockRemoved`
  → `build()`). Role `NodeRole.TRANSMITTER`, `powerUnits = 0`, `gearLike = false`; axis from its
  mounting via `RotationNetwork.axisFromState`.
- **Link = mutual, persisted, non-adjacent edge.** Wrench-click wheel A, then wheel B, to link them.
  Each wheel stores its partner (world + x/y/z + a `reverses` flag) in its **skull PDC**. A link is
  **live only when both wheels reference each other** — a dangling half-link is inert. That mutual
  requirement *is* the enforced "**complete loop**": the chain wraps both wheels and returns, so the
  circuit is closed on both ends before any power flows.
- **Link validation (at wrench time):** the partner must be a chain wheel; the two axes must be
  **parallel/equal** (both X, both Y, or both Z — cross-axis is out of scope for v1); the wheels must
  be within a max distance; and the straight segment between them must be unobstructed. Reject with a
  player-facing action-bar message otherwise.
- **Direction:** a chain/belt does **not** invert rotation sense between parallel wheels, so the chain
  edge carries `reverses = false` (unlike a meshing gear, which reverses). A deliberately crossed
  ("figure-8") link would carry `reverses = true` — optional/advanced, gated behind a sneak-wrench.
- **Power model:** transmitter only (no supply, no demand) in v1. A small transient friction demand
  while transmitting is a later tuning option (`addTransientDemand`, as the rotator already does).

## Work blocks (execution order)

References are `file` `symbol` (`~line`) against current source.

### A. Chain-wheel block + node — low risk, do first
- `rotation-blocks.yml` — add `mech:chain_wheel` (head texture, `idle_*`/`spinning_*` states,
  `placement_state_map` for axis like the shaft/gear entries).
- `RotationBlocks` — new `overlayChainWheel` (or reuse `overlayStandard`) registering the wheel:
  `onChunkLoad` → `network.addNode(b, "mech:chain_wheel", axisFromState(state), TRANSMITTER, 0, false)`;
  `onNeighborChange` → `recalcIfKnown`; `onBlockRemoved`/`onChunkUnload` → `removeNode` (+ break link,
  see B).
- **Verify:** a wheel placed on a shaft joins the network and spins like a gear/shaft would.

### B. Link registry + edge injection — the core (highest risk)
- Add a link store to `RotationNetwork`: `Map<LocationKey, ChainLink>` where
  `ChainLink(LocationKey partner, boolean reverses)`, populated from each wheel's PDC when its chunk
  loads (in the wheel's `onChunkLoad`).
- Enrich the edge model: either add a field to `Connection` (`:46`) or keep a side map, so that
  `getConnections()` (`:548`) **appends the chain edge** (`neighbor = partner`, `reverses` from the
  link) alongside the cardinal/gear connections. No change to BFS (`:293`), direction anchoring
  (`:376`), or jam logic — they consume `Connection`s uniformly.
- Persist the partner + `reverses` in the wheel PDC (`NamespacedKey`, e.g. `mech:chain_partner`).
  On `removeNode` for a wheel, clear the **partner's** back-reference and recalc both networks.
- **Cross-chunk partner:** if the partner's chunk is unloaded, defer edge injection until it loads
  (mirror `chain-shaft.md` Block D's chunk-load re-walk: a `ChunkLoadEvent`/`onChunkLoad` branch that
  recalcs networks whose links point into the newly loaded chunk).
- **Verify:** source → wheel A ⇒ chain ⇒ wheel B → consumer transmits across the gap; the far
  consumer stills when either wheel is broken; a wrench-reversed link inverts the far side.

### C. Wrench linking UX
- `onInteract`: first wrench-click **arms** wheel A (store a pending selection, particle/actionbar
  feedback); second wrench-click on a valid wheel B writes the mutual PDC links + recalc. Re-click A
  to cancel; sneak-wrench a linked wheel to **unlink** (clear both PDCs + recalc).
- Validation feedback: axis mismatch / too far / obstructed / not-a-wheel each produce a distinct
  action-bar message.
- **Verify:** link, unlink, cancel; each rejection path shows the right message.

### D. Chain rendering
- At link time, spawn tagged item-display "chain segment" entities along the A↔B segment (rendered as
  an out-and-back loop) — tag `corelib:mech:chain:<wheelKey>` so they can be found + cleaned.
- Re-track on chunk load by scanning tagged `Display` entities (mirror `chain-shaft.md` Block E's rod
  re-track via `EntitiesLoadEvent`), rather than storing per-segment state.
- Animate/scroll the segments when the owning network is `powered()`, in the solve's current
  direction; freeze when unpowered.
- **Verify:** the chain renders between wheels, animates only when powered, and re-tracks after a
  chunk reload / restart.

### E. Minecart ski-lift
- New `ChainRideManager`: wrench/right-click a minecart near a powered chain to **attach** it, storing
  `(link id, parametric position t)` in the cart's entity PDC.
- Per tick, if the cart's source network is `powered()`, advance `t` along the segment by
  `clamp(K * surplus / mass, MIN, MAX)` (reuse the `RotationRotator` speed clamp,
  `RotationRotator.java:37-42`) and teleport the cart along the path, overriding vanilla velocity.
  At the far wheel, stop (or reverse for a shuttle).
- A **gondola glued to the cart** (`EntityAnchor` + `GlueManager`) follows for free via
  `BasicMechanism.updateFromVehicle()` (`:372`) — the existing mechanism-minecart auto-follow path.
- **Verify:** a cart carrying a glued chair climbs the chain while powered and stops when power is cut;
  the gondola tracks the cart smoothly.

## Risks

1. **Traversal / edge injection** — the mutual-link invariant, cross-chunk deferral, and recalc on
   partner break are the load-bearing correctness points; a missed recalc silently strands power.
2. **Chunk boundaries** — a wheel whose partner is unloaded must reconnect on chunk load, not stay
   dead.
3. **Segment obstruction** — validated at link time only; a block placed across the segment later is
   not re-checked in v1 (document, or add a periodic re-check).
4. **Cart vs. vanilla physics** — the ski-lift must override rail/gravity velocity each tick; attach
   only off-rail to avoid fighting rail movement.
5. **Entity budget** — long chains spawn many display segments; cap length (the link max-distance) and
   consider one stretched display over many small ones.
6. **Disambiguation** — keep clear of `chain-shaft.md`'s `CHAIN`-material shaft; the wheel is a skull.

## Verification (end-to-end, Paper 1.21.8)

`./gradlew build`, load on a test server, then:
1. Place two chain wheels on shafts; wrench-link them → chain renders; mismatched-axis / too-far /
   obstructed links are rejected with a message.
2. Source → wheel A ⇒ chain ⇒ wheel B → consumer: power transmits across the gap; break a wheel →
   far side stills; sneak-wrench a crossed link → far side reverses.
3. Cut the source → chain animation and far consumer stop.
4. Reload the chunk / restart → links + chain displays re-track; unload/reload one endpoint's chunk →
   reconnects.
5. Attach a minecart (with a glued gondola) to a powered chain → it rides along like a ski lift,
   stops when power is cut.

Temp artifacts → `defCoreLib/.temp/` (per module CLAUDE.md).
