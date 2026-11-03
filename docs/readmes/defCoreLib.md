# DefCoreLib

[![Download on Modrinth](https://img.shields.io/badge/download-modrinth-00AF5C?style=for-the-badge&logo=modrinth&logoColor=white)](https://modrinth.com/plugin/defcorelib) [![Join the Discord](https://img.shields.io/badge/discord-join-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://def9a2a4.github.io/discord.html)

Shared core library for the [*def9a2a4*](https://def9a2a4.github.io/) plugin family - a data-driven custom-block engine plus demo content. On its own it only ships the engine and some command-only demo items; other plugins build their features on top of it. See https://def9a2a4.github.io/defCoreLib-docs/

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
- **[HeadSmith](https://github.com/def9a2a4/defCoreLib/blob/main/docs/readmes/headsmith.md)** - decorative player-head blocks (alphabet, candles, chimneys, barrels, and more)

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

| Command | Description | Permission |
| --- | --- | --- |
| `/defcorelib help` | List the commands you can use | everyone |
| `/defcorelib catalog [category\|search <query>]` | Browse every registered block and item, with recipes, in-game | `corelib.catalog` (default: everyone) |
| `/defcorelib give <id> [amount] [player]` | Give a custom item (`namespace:id` or shorthand; `give glue` -> glue brush) | `corelib.admin` |
| `/defcorelib give_demo` | Give yourself one of every registered block | `corelib.admin` |
| `/defcorelib give_demo_rotation` | Give yourself one of every Mechanism block | `corelib.admin` |
| `/defcorelib list` | List all registered block ids | `corelib.admin` |
| `/defcorelib colliders` | Toggle mechanism collider glow visualization | `corelib.admin` |
| `/defcorelib debug [on\|off]` | Show or set verbose `[debug]` logging (DefCoreLib, Pipes, HeadSmith) until restart; `debug` in `config.yml` keeps it on | `corelib.admin` |
| `/defcorelib refreshdisplays [confirm]` | Report (and, with `confirm`, respawn) the displays of every loaded custom block, removing orphans | `corelib.admin` |
| `/defcorelib reloadbanners` | Reload banner configuration | `corelib.admin` |
| `/defcorelib cleanorphans [confirm]` | Find (and, with `confirm`, remove) orphaned display entities | `corelib.admin` |
| `/defcorelib showcase [build <id>\|anchor\|export <id>]` | Admin/dev tool: list showcase machines, build one at your feet, or export a glue-marked build | `corelib.admin` |

`corelib.admin` (default: op) includes `corelib.catalog`.

## Configuration

`plugins/DefCoreLib/config.yml` holds a single setting, `debug` (default `false`), also switched live, until restart, with
`/defcorelib debug on|off`. Mechanism fuel rates can be overridden in
`plugins/DefCoreLib/config/fuel.yml` - see [Mechanism](https://github.com/def9a2a4/defCoreLib/blob/main/docs/readmes/mech.md#configuration).

## Requires

Nothing - this is the base plugin.

## Links

- Docs & item catalog: https://def9a2a4.github.io/defCoreLib-docs/
- Repository: https://github.com/def9a2a4/defCoreLib/
- Issues: https://github.com/def9a2a4/defCoreLib/issues
