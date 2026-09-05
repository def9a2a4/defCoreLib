# HeadSmith

Thousands of decorative player-head blocks — the full alphabet, candles, chimneys, barrels, books,
pumpkins, mini-blocks and more. Purely server-side Paper plugin, no mods or resource packs required.

Browse every head in-game with `/defcorelib catalog`. (The web catalog does not carry the HeadSmith
namespace yet.)

[Download on Modrinth](https://modrinth.com/plugin/headsmith)

## Features

- **A huge decoration set** — barrels, candles, chimneys, books, bottles, buckets, bundles, chalices,
  pumpkins, a big mini-block collection, and the **full alphabet** (letters, numbers, symbols, arrows,
  and several scripts) rendered across 23 block materials. Browse it all in the in-game catalog,
  organized by family with a cross-cutting **color** grouping (every black head — pumpkin, candle,
  wool, book — gathers under one place).
- **Interactive heads** — 11 **station** heads open the matching vanilla GUI (workbench, anvil,
  smithing table, cartography, loom, stonecutter, grindstone, enchanting, ender chest); **candles**
  light with flint & steel and go out on a right-click; **chimneys** light the same way and puff
  rising campfire smoke while lit; **barrels** are 3-row storage containers.
- **Recipes** — most heads are carved in a **stonecutter**, usually from a HeadSmith mini-block
  rather than from a vanilla one: craft a mini birch-planks block, then cut birch letters from it.
  Chalices and chimneys come straight off their base block (any cobblestone chimney from a
  cobblestone block). A small number of heads have ordinary shaped or shapeless recipes. Head
  recipes are kept out of the vanilla recipe book by default so they don't clutter it — reveal them
  per-player with `/headsmith recipes give`.
- **Migration** — if you ran the old standalone HeadSmith plugin, this adopts the heads you already
  placed and re-stamps head items sitting in chests/inventories, so they keep working and stack with
  freshly-crafted ones. It runs automatically on enable; re-run it any time with `/headsmith migrate`.

## Commands

| Command | Permission | Description |
| --- | --- | --- |
| `/headsmith give <id> [amount]` | `headsmith.give` (op) | Give a head item by id |
| `/headsmith recipes <give\|take> [player]` | `headsmith.recipes` (default) | Reveal/hide head recipes in the recipe book |
| `/headsmith migrate` | `headsmith.migrate` (op) | Adopt heads placed by the old standalone plugin |

## Requires

[DefCoreLib](https://github.com/def9a2a4/defCoreLib/blob/main/docs/readmes/defCoreLib.md).

The head blocks live in DefCoreLib; this plugin loads them, enables their crafting recipes, and runs
the legacy migration. Without it the heads are unavailable.

## Links

- Full type list & recipes: in-game, `/defcorelib catalog`
- Download on Modrinth: https://modrinth.com/plugin/headsmith
- Repository: https://github.com/def9a2a4/defCoreLib/
- Issues: https://github.com/def9a2a4/defCoreLib/issues
