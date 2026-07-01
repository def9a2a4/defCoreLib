# mech

Kinetic rotation mechanisms — power sources, transmission, and machines you wire together.

<!-- img: rotation network -->

## Features

- **Power sources** — windmill (plus Large & Huge tiers), water wheel, fuel engine, redstone
  generator.
- **Transmission** — shafts, gears, clutch (disconnects on redstone), reverser (flips spin on
  redstone). Power flows source → shaft/gear → machine.
- **Machines** — millstone (grinds items), extractor press (presses items into juices/oils),
  fan (pushes entities), drill (mines), placer.
- **Rotators** — glued doors & drawbridges that swing on rotation power.
- **Mechanism minecarts** and the glue authoring workflow.

## Requires

- [DefCoreLib](./defCoreLib.md).
- **[bbanners](./bbanners.md) for Large & Huge windmills** — without it, plain windmills still
  craft but the large/huge tiers are uncraftable.

## Configuration

- `rotation-config.yml` — network/structure size caps, machine tick rates, fuel & power tables.
- `mill-recipes.yml`, `press-recipes.yml` — millstone and press recipe definitions.

## Docs

Full block & recipe list: https://def9a2a4.github.io/defCoreLib/?q=mech · Mechanism showcases:
https://def9a2a4.github.io/defCoreLib/showcases.html
