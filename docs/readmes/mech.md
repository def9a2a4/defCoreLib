# Mechanism

Kinetic rotation mechanisms - build power networks that drive machines, doors, and vehicles. Inspired by Create mod, loosely also inspired by slimefun, pylon, and classic minecraft tekkit/technic/buildcraft/industrialcraft. *Purely server-side paper plugin, **no mods or resource packs!***

See the [items and their recipes](https://def9a2a4.github.io/defCoreLib-docs/index.html?ns=mech), or the [things you can build](https://def9a2a4.github.io/defCoreLib-docs/showcases.html).

[Download on Modrinth](https://modrinth.com/plugin/mechanism)

[![Windmill-driven sand generator](https://def9a2a4.github.io/defCoreLib-docs/readmes/assets/mech/mech.gif)](https://def9a2a4.github.io/defCoreLib-docs/showcase.html?id=sand_generator)
[![Mechanisms in-game](https://def9a2a4.github.io/defCoreLib-docs/readmes/assets/mech/mech-ingame.gif)](https://def9a2a4.github.io/defCoreLib-docs/readmes/assets/mech/mech-ingame.webm)

## How it works

Rotation flows from a **power source**, through **transmission** blocks, into **machines** that
consume it. Each source produces a fixed amount of power; a machine runs when the network reaching
it supplies enough, and some machines work faster with surplus power. You wire it together with
shafts and gears, branch or turn corners with gears, and gate the flow with clutches and reversers.

## Power sources

- [**Windmill**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Awindmill) - always spinning; low power. [**Large**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Alarge_windmill) and [**Huge**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Ahuge_windmill) windmills produce progressively more (crafted with large/huge banners - see Requires).
- [**Water wheel**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Awater_wheel) - wall-mounted; spins when placed next to water.
- [**Engine**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Aengine) - burns fuel (coal, logs, planks, blaze rods, lava) for high power; right-click to refuel.
- [**Redstone motor**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Aredstone_motor) - steady power, toggled by a redstone signal. No fuel needed, but low power and expensive.

## Transmission

- [**Shaft**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Ashaft) - carries rotation along its axis.
- [**Gear**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Agear) - meshes with perpendicular or in-line gears to route power around corners and branch it.
- [**Clutch**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Aclutch) - disconnects the line while it receives a redstone signal.
- [**Reverser**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Areverser) - flips spin direction on a redstone signal. Mostly useful for Rotators.

## Machines

- [**Millstone**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Amillstone) - grinds items (e.g. cobblestone -> gravel -> sand, bone -> bone meal).
- [**Extractor press**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Apress) - presses items into juices and oils (consumes glass bottles).
- [**Fan**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Afan) - pushes entities and items in front of it; range scales with surplus power.
- [**Drill**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Adrill) - mines the blocks in front of it.
- [**Placer**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Aplacer) - places blocks from an attached inventory.

## Power output

- [**Redstone dynamo**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Aredstone_dynamo) - the inverse of the motor: reads your rotation network's power and emits an **analog 0-15 redstone signal** (read it with a comparator against any side). Right-click to choose what it reports (total / used / unused power) and how it scales to 0-15 (clamp / mod-15 / ÷15). Transmits rotation along its axis like a shaft, and draws no power itself.
- [**Throttle lever**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Athrottle_lever) - a hand-set **analog 0-15 redstone source**, no rotation network needed. Right-click to raise the output, sneak-right-click to lower it; the lever handle tilts to show the strength. Powers adjacent redstone directly (dust, lamps, and the block beneath — weighted-plate rules), and **ignores footsteps** so the signal stays put. Floor placement only.

## Structures & vehicles

- [**Rotators**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Arotator) - glue any block structure to a rotator to make swinging **doors** and **drawbridges**,
  powered by the network.
- [**Mechanical minecarts**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Amechanism_minecart) - carry a glued block structure along rails. Mostly decorative for now, more features coming soon!
- [**Glue brush**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Aglue_item) - the authoring tool that binds blocks into a movable structure.

[![Mechanism catalog](https://def9a2a4.github.io/defCoreLib-docs/readmes/assets/mech/catalog-1.png)](https://def9a2a4.github.io/defCoreLib-docs/index.html?ns=mech)

## Requires

- **[DefCoreLib](https://github.com/def9a2a4/defCoreLib/blob/main/docs/readmes/defCoreLib.md)** is required.
- Soft depend: [BetterBanners](https://github.com/def9a2a4/defCoreLib/blob/main/docs/readmes/bbanners.md) for **Large & Huge windmills** - without it, plain windmills still
  craft but the large/huge tiers are uncraftable.

## Configuration

- `rotation-config.yml` - network/structure size caps, machine tick rates, and the fuel & power
  tables.
- `mill-recipes.yml`, `press-recipes.yml` - millstone and press recipe definitions.

## Links

- Items, recipes & showcases: https://def9a2a4.github.io/defCoreLib-docs/index.html?ns=mech
- Download on Modrinth: https://modrinth.com/plugin/mechanism
- Repository: https://github.com/def9a2a4/defCoreLib/
- Issues: https://github.com/def9a2a4/defCoreLib/issues
