# DefCoreLib

Shared core library for the [*def9a2a4*](https://def9a2a4.github.io/) plugin family - a data-driven custom-block engine plus demo content. On its own it only ships the engine and some command-only demo items; other plugins build their features on top of it. See https://def9a2a4.github.io/defCoreLib-docs/

[Download on Modrinth](https://modrinth.com/plugin/defcorelib)

## What it provides

- **Custom-block engine** - YAML-driven player-head blocks with states, redstone behavior, particles, light, storage, and animated display entities. In particular, the use of custom heads and display entities allows cool visuals ***without any mods or resource packs!***
- **Custom items & heads, recipe registration, and persistence** (chunk scan + self-healing) that companion plugins reuse instead of reimplementing.
- **Mechanism engine** - turns groups of blocks into moving display entities + colliders: glue-based doors/drawbridges and mechanical minecarts.
- **Recipe gating** - companion plugins switch their content's recipes on by namespace, so installing one adds a coherent, craftable feature set.
- ~20 **command-only** demo blocks (candles, redstone/binary displays, storage barrels, alarms, spinning/pulsing decorations) - grab them with `/defcorelib give`. Not meant to be useful, just there for testing the functionality.

## Used by

DefCoreLib is a dependency other plugins install alongside:

- **[VerticalSlabs](https://github.com/def9a2a4/defCoreLib/blob/main/docs/readmes/vslab.md)** - vertical slabs
- **[BetterBanners](https://github.com/def9a2a4/defCoreLib/blob/main/docs/readmes/bbanners.md)** - flag banners + large/huge banners
- **[Mechanism](https://github.com/def9a2a4/defCoreLib/blob/main/docs/readmes/mech.md)** - rotation mechanisms, glue, mechanical minecarts
- **[RedstoneDisplays](https://github.com/def9a2a4/defCoreLib/blob/main/docs/readmes/redstonedisplays.md)** - redstone power indicator heads
- **[Pipes](https://github.com/def9a2a4/defCoreLib/blob/main/docs/readmes/pipes.md)** - item-transport pipes
- **[Railbound](https://github.com/def9a2a4/defCoreLib/blob/main/docs/readmes/railbound.md)** - self-driving minecart trains, fuel carts, and junction/controller/destructor rails

## Gallery

| Plugin | Preview |
| --- | --- |
| [VerticalSlabs](https://github.com/def9a2a4/defCoreLib/blob/main/docs/readmes/vslab.md) | <img src="https://def9a2a4.github.io/defCoreLib-docs/readmes/assets/vslab/vslabs.png" width="220"> <img src="https://def9a2a4.github.io/defCoreLib-docs/readmes/assets/vslab/catalog.png" width="220"> |
| [BetterBanners](https://github.com/def9a2a4/defCoreLib/blob/main/docs/readmes/bbanners.md) | <img src="https://def9a2a4.github.io/defCoreLib-docs/readmes/assets/bbanners/all.png" width="220"> <img src="https://def9a2a4.github.io/defCoreLib-docs/readmes/assets/bbanners/huge.png" width="220"> |
| [Mechanism](https://github.com/def9a2a4/defCoreLib/blob/main/docs/readmes/mech.md) | <img src="https://def9a2a4.github.io/defCoreLib-docs/readmes/assets/mech/mech.gif" width="220"> <img src="https://def9a2a4.github.io/defCoreLib-docs/readmes/assets/mech/mech-ingame.gif" width="220"> <img src="https://def9a2a4.github.io/defCoreLib-docs/readmes/assets/mech/catalog-1.png" width="220"> |
| [RedstoneDisplays](https://github.com/def9a2a4/defCoreLib/blob/main/docs/readmes/redstonedisplays.md) | <img src="https://def9a2a4.github.io/defCoreLib-docs/readmes/assets/rsd/indicators-wall.png" width="220"> <img src="https://def9a2a4.github.io/defCoreLib-docs/readmes/assets/rsd/indicators-lectern.png" width="220"> |
| [Pipes](https://github.com/def9a2a4/defCoreLib/blob/main/docs/readmes/pipes.md) | <img src="https://def9a2a4.github.io/defCoreLib-docs/readmes/assets/pipes/demo-1.png" width="220"> <img src="https://def9a2a4.github.io/defCoreLib-docs/readmes/assets/pipes/demo-2.png" width="220"> |
| [Railbound](https://github.com/def9a2a4/defCoreLib/blob/main/docs/readmes/railbound.md) | _preview coming soon_ <!-- <img src="https://def9a2a4.github.io/defCoreLib-docs/readmes/assets/railbound/hero.gif" width="220"> <img src="https://def9a2a4.github.io/defCoreLib-docs/readmes/assets/railbound/catalog.png" width="220"> --> |

## Commands

Permission `corelib.admin`.

| Command | Description |
| --- | --- |
| `/defcorelib give <id> [n]` | Give a custom item (`namespace:id` or shorthand; `give glue` -> glue brush) |
| `/defcorelib list` | List all registered block ids |
| `/defcorelib colliders` | Toggle mechanism collider glow visualization |
| `/defcorelib cleanorphans [confirm]` | Find (and, with `confirm`, remove) orphaned display entities |

## Requires

Nothing - this is the base plugin.

## Links

- Docs & item catalog: https://def9a2a4.github.io/defCoreLib-docs/
- Repository: https://github.com/def9a2a4/defCoreLib/
- Issues: https://github.com/def9a2a4/defCoreLib/issues
