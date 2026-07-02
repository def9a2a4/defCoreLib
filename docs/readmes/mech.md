# Mechanism

Kinetic rotation mechanisms - build power networks that drive machines, doors, and vehicles. Inspired by Create mod, loosely also inspired by slimefun, pylon, and classic minecraft tekkit/technic/buildcraft/industrialcraft. *Purely server-side paper plugin, **no mods or resource packs!***

See the [items and their recipes](https://def9a2a4.github.io/defCoreLib-docs/index.html?ns=mech), or the [things you can build](https://def9a2a4.github.io/defCoreLib-docs/showcases.html).

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
- [**Redstone generator**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Agenerator) - steady power, toggled by a redstone signal. No fuel needed, but low power and expensive.

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

## Structures & vehicles

- [**Rotators**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Arotator) - glue any block structure to a rotator to make swinging **doors** and **drawbridges**,
  powered by the network.
- [**Mechanism minecarts**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Amechanism_minecart) - carry a glued block structure along rails. Mostly decorative for now, more features coming soon!
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

- Repository: https://github.com/def9a2a4/defCoreLib/
- Issues: https://github.com/def9a2a4/defCoreLib/issues
