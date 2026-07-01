# DefCoreLib

Shared core library for the *def* plugin family — a data-driven custom-block engine plus demo
content. The companion plugins (`vslab`, `bbanners`, `mech`) build on it.

<!-- img: demo blocks showcase -->

## Features

- **Custom-block engine** — YAML-driven player-head blocks with states, redstone behavior,
  particles, light, storage, and animated display entities. This is the foundation companion
  plugins register their content against.
- **~20 demo blocks** (command-only) — candles, mini crafting table, redstone & binary displays,
  signal meter, storage barrel / hopper crate / dropper box, alarm, enchanting pedestal, and
  spinning/pulsing display decorations. Grab them with `/defcorelib give`.
- **Glued multi-block structures** — the "glue brush" authoring tool binds blocks into rotating
  doors & drawbridges.
- **Mechanism minecarts** — carts that carry and move block structures.

## Commands

All under permission `corelib.admin`.

| Command | Description |
| --- | --- |
| `/defcorelib give <id> [n]` | Give a custom item (`namespace:id` or shorthand; `give glue` → glue brush) |
| `/defcorelib give_demo` | Give every registered block |
| `/defcorelib give_demo_rotation` | Give the mechanism (`mech`) blocks |
| `/defcorelib list` | List all registered block ids |
| `/defcorelib colliders` | Toggle mechanism collider glow visualization |
| `/defcorelib reloadbanners` | Reload banner config from file |
| `/defcorelib cleanorphans [confirm]` | Find (and, with `confirm`, remove) orphaned display entities |
| `/defcorelib showcase <build\|list\|export\|anchor> [id]` | Build/manage demo showcases |

## Requires

Nothing — this is the base plugin. `vslab`, `bbanners`, and `mech` all depend on it.

## Docs

Full block & item catalog with recipes: https://def9a2a4.github.io/defCoreLib/
