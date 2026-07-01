# DefCoreLib

Shared core library for the *def* plugin family — a data-driven custom-block engine plus demo
content. On its own it only ships the engine and some command-only demo items; other plugins build
their features on top of it.

<!-- img: demo blocks showcase -->

## What it provides

- **Custom-block engine** — YAML-driven player-head blocks with states, redstone behavior,
  particles, light, storage, and animated display entities.
- **Custom items & heads, recipe registration, and persistence** (chunk scan + self-healing) that
  companion plugins reuse instead of reimplementing.
- **Mechanism engine** — turns groups of blocks into moving display entities + colliders:
  glue-based doors/drawbridges and mechanism minecarts.
- **Recipe gating** — companion plugins switch their content's recipes on by namespace, so
  installing one adds a coherent, craftable feature set.
- ~20 **command-only** demo blocks (candles, redstone/binary displays, storage barrels, alarms,
  spinning/pulsing decorations) — grab them with `/defcorelib give`.

## Used by

DefCoreLib is a dependency other plugins install alongside:

- **[vslab](./vslab.md)** — vertical slabs
- **[bbanners](./bbanners.md)** — flag banners + large/huge banners
- **[mech](./mech.md)** — rotation mechanisms, glue, mechanism minecarts
- **Pipes** — item-transport pipes (separate repo)

## Commands

Permission `corelib.admin`.

| Command | Description |
| --- | --- |
| `/defcorelib give <id> [n]` | Give a custom item (`namespace:id` or shorthand; `give glue` → glue brush) |
| `/defcorelib list` | List all registered block ids |
| `/defcorelib colliders` | Toggle mechanism collider glow visualization |
| `/defcorelib cleanorphans [confirm]` | Find (and, with `confirm`, remove) orphaned display entities |

## Requires

Nothing — this is the base plugin.

## Links

- Docs & item catalog: https://def9a2a4.github.io/defCoreLib/
- Repository: https://github.com/def9a2a4/defCoreLib/
- Issues: https://github.com/def9a2a4/defCoreLib/issues
