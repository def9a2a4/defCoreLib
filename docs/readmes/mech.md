# Mechanism

Kinetic rotation mechanisms - build power networks that drive machines, doors, and vehicles. Inspired by Create mod, loosely also inspired by slimefun, pylon, and classic minecraft tekkit/technic/buildcraft/industrialcraft.

<!-- img: rotation network -->

## How it works

Rotation flows from a **power source**, through **transmission** blocks, into **machines** that
consume it. Each source produces a fixed amount of power; a machine runs when the network reaching
it supplies enough, and some machines work faster with surplus power. You wire it together with
shafts and gears, branch or turn corners with gears, and gate the flow with clutches and reversers.

## Power sources

- **Windmill** - always spinning; low power. **Large** and **Huge** windmills produce progressively
  more (crafted with large/huge banners - see Requires).
- **Water wheel** - wall-mounted; spins when placed next to water.
- **Engine** - burns fuel (coal, logs, planks, blaze rods, lava) for high power; right-click to
  refuel.
- **Redstone generator** - steady power, toggled by a redstone signal. No fuel needed, but low power and expensive.

## Transmission

- **Shaft** - carries rotation along its axis.
- **Gear** - meshes with perpendicular or in-line gears to route power around corners and branch it.
- **Clutch** - disconnects the line while it receives a redstone signal.
- **Reverser** - flips spin direction on a redstone signal. Mostly useful for Rotators.

## Machines

- **Millstone** - grinds items (e.g. cobblestone -> gravel -> sand, bone -> bone meal).
- **Extractor press** - presses items into juices and oils (consumes glass bottles).
- **Fan** - pushes entities and items in front of it; range scales with surplus power.
- **Drill** - mines the blocks in front of it.
- **Placer** - places blocks from an attached inventory.

## Structures & vehicles

- **Rotators** - glue any block structure to a rotator to make swinging **doors** and **drawbridges**,
  powered by the network.
- **Mechanism minecarts** - carry a glued block structure along rails. Mostly decorative for now, more features coming soon!
- **Glue brush** - the authoring tool that binds blocks into a movable structure.

## Requires

- **[DefCoreLib](https://github.com/def9a2a4/defCoreLib/blob/main/docs/readmes/defCoreLib.md)** is required.
- Soft depend: [BetterBanners](https://github.com/def9a2a4/defCoreLib/blob/main/docs/readmes/bbanners.md) for **Large & Huge windmills** - without it, plain windmills still
  craft but the large/huge tiers are uncraftable.

## Configuration

- `rotation-config.yml` - network/structure size caps, machine tick rates, and the fuel & power
  tables.
- `mill-recipes.yml`, `press-recipes.yml` - millstone and press recipe definitions.

## Links

- Full block & recipe list: https://def9a2a4.github.io/defCoreLib-docs/?q=mech
- Mechanism showcases: https://def9a2a4.github.io/defCoreLib-docs/showcases.html
- Repository: https://github.com/def9a2a4/defCoreLib/
- Issues: https://github.com/def9a2a4/defCoreLib/issues
