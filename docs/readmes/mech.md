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
shafts and gears, branch or turn corners with gears or gearboxes, and gate the flow with clutches,
reversers, and ratchets.

## Power sources

- [**Windmill**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Awindmill) - always spinning; low power. [**Large**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Alarge_windmill) and [**Huge**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Ahuge_windmill) windmills produce progressively more (crafted with large/huge banners - see Requires).
- [**Water wheel**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Awater_wheel) - wall-mounted; spins when placed next to water.
- [**Engine**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Aengine) - burns fuel (coal, logs, planks, blaze rods, lava) for high power; right-click to refuel.
- [**Redstone motor**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Aredstone_motor) - steady power, toggled by a redstone signal. No fuel needed, but low power and expensive.
- **Steam engine** ([**Burner**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Aburner) → [**Boiler**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Aboiler) → [**Steam piston**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Asteam_piston)) - a three-block stack: the burner burns fuel to heat a boiler fed piped-in water, and the steam piston on top puts out high (20) power along its axle while both below are fed.

## Transmission

- [**Shaft**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Ashaft) - carries rotation along its axis.
- [**Gear**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Agear) - meshes with perpendicular or in-line gears to route power around corners and branch it.
- [**Gearbox**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Agearbox_oak) - an omnidirectional hub: it passes power on all six faces but **not** spin direction (each side settles its own rotation). Split one input several ways, or turn a corner without a gear's counter-rotation. A wood-keyed frame block.
- [**Clutch**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Aclutch) - disconnects the line while it receives a redstone signal.
- [**Reverser**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Areverser) - flips spin direction on a redstone signal. Mostly useful for Rotators.
- [**Ratchet**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Aratchet) - a one-way rotation valve: it drives one direction and freewheels (disconnects) the other instead of jamming. Wrench to set the allowed direction.
- [**Chain pulley**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Achain_pulley) - links rotational power across a gap: chain pulleys together into a closed loop (three or more) to carry power between them.

## Machines

- [**Millstone**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Amillstone) - grinds items (e.g. cobblestone -> gravel -> sand, bone -> bone meal).
- [**Extractor press**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Apress) - presses items into juices and oils (consumes glass bottles).
- [**Sieve**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Asieve) - pans items from its storage when powered (needs water below) - sifts gravel and sand for nuggets, flint, and other finds.
- [**Fan**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Afan) - pushes entities and items away in a fixed ~5-block beam; surplus power pushes harder, not farther.
- [**Drill**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Adrill) - breaks the block in front of it in timed stages while powered. Mounted on a rotator it sweep-mines an arc at any angle, at 2x speed.
- [**Placer**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Aplacer) - places blocks from an attached inventory into the space in front of it.
- [**Mechanical dispenser**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Amechanical_dispenser) - a dispenser whose contents fly out with a rotation-driven launch when powered.
- [**Copper water pump**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Apump) - pumps a bucket of water upward each cycle when powered; wrench flips the flow.
- [**Suction hopper**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Asuction_hopper) - pulls nearby dropped items inward while powered and feeds the container it's mounted on.

The drill, placer, and fan reorient when carried by a mechanism, aiming along their own facing rotated by the mechanism's motion.

## Ship propulsion

These push a ship rather than the world, so they only do anything aboard a [BlockShips](https://github.com/def9a2a4/BlockShips) vessel. **Which way you mount one decides what it does**: pointing fore or aft adds speed, sideways adds turning, and any vertical mount — floor or ceiling — adds lift. Like the fan, propellers act *away* from their mount, which is why a floor propeller lifts rather than steers. The reaction wheel is the exception to the whole rule: it turns the ship whichever way it sits.

- [**Propeller**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Apropeller) - the base tier of ship thrust; draws 5 power while running and produces none. Craft from a Windmill; its blades carry over.
- [**Large propeller**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Alarge_propeller) - roughly three times a propeller's push for 10 power. Craft from a Large Windmill.
- [**Huge propeller**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Ahuge_propeller) - a serious investment at 20 power; enough on its own to fly a mid-sized hull. Craft from a Huge Windmill.
- [**Thruster**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Athruster) - burns fuel instead of drawing rotation power, so it works with no network at all. Craft from a Fan, four iron ingots, two blaze rods and a blast furnace.
- [**Reaction wheel**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Areaction_wheel) - a gyroscope: turns the ship and nothing else, whichever way it sits. The cheapest turning you can buy — 1 power against a propeller's 5 — and the only turning a floor mount can give you, since a floor propeller blows downward and lifts instead. It is *not* the most turning per unit of power: a huge propeller beats it, and a fuel-burning thruster on a wall draws no rotation power at all. Craft from two redstone, an iron block and a bearing.

The three propeller tiers match the windmill they are crafted from — base, large and huge.

Rotation power is all-or-nothing — an under-supplied network runs nothing — so split large propeller banks across separate networks rather than one that can brown out.

## Power output

- [**Redstone dynamo**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Aredstone_dynamo) - the inverse of the motor: reads your rotation network's power and emits an **analog 0-15 redstone signal** (read it with a comparator against any side). Right-click to choose what it reports (total / used / unused power) and how it scales to 0-15 (clamp / mod-15 / ÷15). Transmits rotation along its axis like a shaft, and draws no power itself.
- [**Throttle lever**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Athrottle_lever) - a hand-set **analog 0-15 redstone source**, no rotation network needed. Right-click to raise the output, sneak-right-click to lower it; the lever handle tilts to show the strength. Powers adjacent redstone directly (dust, lamps, and the block beneath — weighted-plate rules), and **ignores footsteps** so the signal stays put. Floor placement only.

## Structures & vehicles

- [**Rotators**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Arotator) - glue any block structure to a rotator to make swinging **doors**, **drawbridges**, and
  ceiling **hatches**, powered by the network. It keeps its chosen open angle even when carried by another mechanism.
- [**Chain hoist**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Achain_hoist) - pays out chain to raise and lower whatever hangs beneath it; a shaft through it drives the lift (CW lowers, CCW raises).
- [**Mechanical piston**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Apiston_core) - a rotation-powered piston: spin it one way to extend, the other to retract, pushing a glued structure.
- [**Mechanical minecarts**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Amechanism_minecart) - carry a glued block structure along rails. Mostly decorative for now, more features coming soon!
- **Frame blocks** ([Casing](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Acasing_oak), [Gearbox](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Agearbox_oak), [Chassis](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=mech%3Achassis_oak)) - wood-keyed building blocks that auto-glue to same-wood frame neighbours; a **Chassis** also grabs every adjacent movable block like a slime block, dragging a whole payload with no brush.
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
