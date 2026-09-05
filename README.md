
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
- **[HeadSmith](https://github.com/def9a2a4/defCoreLib/blob/main/docs/readmes/headsmith.md)** - decorative player-head blocks (alphabet, candles, chimneys, barrels, and more)

## Gallery

| Plugin | Preview |
| --- | --- |
| [VerticalSlabs](https://github.com/def9a2a4/defCoreLib/blob/main/docs/readmes/vslab.md) | <img src="https://def9a2a4.github.io/defCoreLib-docs/readmes/assets/vslab/vslabs.png" width="220"> <img src="https://def9a2a4.github.io/defCoreLib-docs/readmes/assets/vslab/catalog.png" width="220"> |
| [BetterBanners](https://github.com/def9a2a4/defCoreLib/blob/main/docs/readmes/bbanners.md) | <img src="https://def9a2a4.github.io/defCoreLib-docs/readmes/assets/bbanners/all.png" width="220"> <img src="https://def9a2a4.github.io/defCoreLib-docs/readmes/assets/bbanners/huge.png" width="220"> |
| [Mechanism](https://github.com/def9a2a4/defCoreLib/blob/main/docs/readmes/mech.md) | <img src="https://def9a2a4.github.io/defCoreLib-docs/readmes/assets/mech/mech.gif" width="220"> <img src="https://def9a2a4.github.io/defCoreLib-docs/readmes/assets/mech/mech-ingame.gif" width="220"> <img src="https://def9a2a4.github.io/defCoreLib-docs/readmes/assets/mech/catalog-1.png" width="220"> |
| [RedstoneDisplays](https://github.com/def9a2a4/defCoreLib/blob/main/docs/readmes/redstonedisplays.md) | <img src="https://def9a2a4.github.io/defCoreLib-docs/readmes/assets/rsd/indicators-wall.png" width="220"> <img src="https://def9a2a4.github.io/defCoreLib-docs/readmes/assets/rsd/indicators-lectern.png" width="220"> |
| [Pipes](https://github.com/def9a2a4/defCoreLib/blob/main/docs/readmes/pipes.md) | <img src="https://def9a2a4.github.io/defCoreLib-docs/readmes/assets/pipes/demo-1.png" width="220"> <img src="https://def9a2a4.github.io/defCoreLib-docs/readmes/assets/pipes/demo-2.png" width="220"> |

## Commands

`/defcorelib catalog` browses every registered block and item in-game, and is available to
everyone (`corelib.catalog`). The rest are admin commands under `corelib.admin`.

See [the full command table](docs/readmes/defCoreLib.md#commands) — kept in one place so the copies
stop drifting apart.

## Requires

Nothing - this is the base plugin.

## Links

- Docs & item catalog: https://def9a2a4.github.io/defCoreLib-docs/
- Repository: https://github.com/def9a2a4/defCoreLib/
- Issues: https://github.com/def9a2a4/defCoreLib/issues


## Requirements

- Java 21
- Paper 1.21.9 or newer for DefCoreLib itself. `api-version` is still `1.21`, so an older server
  will load the plugin and then fail at runtime rather than refusing it cleanly: the rotation shafts
  use the copper chain blocks added in 1.21.9.
- **Paper 1.21.11 or newer if you run the Mechanism jar** — its bootstrapper registers the
  advancement datapack through the 1.21.11 datapack-discovery API. Since that is the usual setup,
  treat 1.21.11 as the practical floor for the suite.

## Build

```sh
./gradlew shadowJar
```

Produces an uber-JAR in `build/libs/`. Drop it into your server's `plugins/` directory.

## Usage from another plugin

1. Add the DefCoreLib JAR as a `compileOnly` dependency.
2. Declare `depend: [DefCoreLib]` in your `plugin.yml` — every plugin here does, as does BlockShips,
   because step 3 returns null without it. Use `softdepend` only if you genuinely degrade gracefully.
3. Access registries via `CoreLibPlugin.getInstance()`.

Commands: see [the table above](#commands).

## Block definitions

Blocks and mechanisms are declared in YAML under `src/main/resources/` (e.g. `demo-blocks.yml`, `rotation-blocks.yml`, `slabs.yml`). See `docs/` for the generated block catalog.
