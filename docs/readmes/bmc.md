# BetterMinecarts

Self-driving minecart trains and powered rail logistics on plain vanilla rails — couple carts into
fuel-powered trains, then route and regulate them with special rails. Purely server-side Paper plugin,
*no mods or resource packs required.*

See the [catalog of carts & rails](https://def9a2a4.github.io/defCoreLib-docs/index.html?ns=bmc).

> _Screenshots & gifs coming soon._

<!-- HERO: ![A fuel-powered minecart train driving itself along the rails.](https://def9a2a4.github.io/defCoreLib-docs/readmes/assets/bmc/hero.gif) -->

## How it works

Couple minecarts together with **chains** to form a **train**. Any fuelled locomotive anywhere in the
train — a **blast-furnace cart** or an ordinary lit **furnace minecart** — powers the whole consist; it
then **drives itself** along the track. Movement is computed along the rails rather than shoved by
physics, so a train holds a steady speed, follows curves and junctions, and its **top speed doesn't drop
as it gets longer** (only its acceleration eases, scaling with train length). **Special rails** let you
cross lines, set cruise speeds, and recycle carts.

## Locomotives & carts

- [**Blast-furnace cart**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=bmc%3Ablast_furnace_cart) —
  the engine: three fuel slots plus in-GUI **−/+ speed buttons** that set a cruise speed (in redstone-sized
  steps, like a controller rail), and a high, configurable top speed. Powers a whole train from anywhere in
  the consist. (A plain lit **furnace minecart** also works, as a weaker engine.)
- [**Coal tender**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=bmc%3Acoal_cart) — a rolling fuel
  store: a 9-slot (3×3), fuel-only inventory that automatically feeds any furnace or blast-furnace cart in the same
  train, so a long haul doesn't stall.
- [**Dispenser cart**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=bmc%3Adispenser_cart) — a 3×3
  inventory that launches one item **straight up** every time it crosses a powered activator rail. Projectile
  items (arrows, snowballs, potions…) fire as their real projectile entities; everything else pops out as an
  item.

Right-click a cart to open its inventory.

## Coupling & trains

- **Couple:** hold a **chain** and right-click one cart, then the cart you want to link it to. A chain link
  renders between the two, and they now drive as one train. Repeat to build a train of any length.
- **Uncouple:** right-click a coupled cart with **shears**.

The visible chain coupler is cosmetic — trains behave identically with it toggled off (`chain-visuals` in the
config).

## Special rails

Placed as ordinary rails wearing a distinct shell, so they slot straight into normal track.

- [**Junction**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=bmc%3Ajunction) — a crossing rail: a
  cart passing over it **keeps its heading and goes straight** instead of curving, so north–south and
  east–west lines can intersect without turning carts.
- [**Controller rail**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=bmc%3Acontroller_rail) — sets
  a fuelled blast-furnace train's cruise speed from its **redstone signal** (0 → stop, 15 → full speed, linear
  between). The train brakes or accelerates toward that target and **holds it even after leaving the rail** —
  place another controller to set a new one. Braking is gradual, so build a **run** of them: a zone of
  unpowered controllers makes a stop-and-hold station that departs when re-powered.
- [**Destructor rail**](https://def9a2a4.github.io/defCoreLib-docs/item.html?id=bmc%3Adestructor_rail) — a
  detector rail that **recycles** a passing minecart: it ejects any riders, then drops the cart item, its
  whole inventory, and any coupling chains into the container directly below (barrel / dropper / dispenser) —
  or onto the ground if there's none. Mechanical minecarts are exempt and pass over unharmed.

<!-- CATALOG SHOT: [![BetterMinecarts catalog](https://def9a2a4.github.io/defCoreLib-docs/readmes/assets/bmc/catalog.png)](https://def9a2a4.github.io/defCoreLib-docs/index.html?ns=bmc) -->

## Requires

[DefCoreLib](https://github.com/def9a2a4/defCoreLib/blob/main/docs/readmes/defCoreLib.md).

The carts and rails live in DefCoreLib; this plugin enables their crafting recipes and drives the train,
fuel, and special-rail logic. Without it the blocks still exist (obtainable via
`/defcorelib give bmc:blast_furnace_cart`), they just aren't craftable and trains won't drive.

## Configuration

- `carts-config.yml` — the fuel burn-time whitelist, cart top speeds, train-drive tuning (coupling spacing,
  acceleration, rolling drag, coupling distance), the special-rail knobs (controller cruise speed, brake
  rate), dispenser launch velocity, and the `chain-visuals` toggle.

## Links

- Carts, rails & recipes: https://def9a2a4.github.io/defCoreLib-docs/index.html?ns=bmc
- Repository: https://github.com/def9a2a4/defCoreLib/
- Issues: https://github.com/def9a2a4/defCoreLib/issues
