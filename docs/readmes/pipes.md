# Pipes

Item-transport pipes for moving items between containers — build hoppers-at-a-distance and
sorting networks. Purely server-side Paper plugin, no mods or resource packs required.

See [the catalog of pipes](https://def9a2a4.github.io/defCoreLib-docs/index.html?ns=pipes).

[Download on Modrinth](https://modrinth.com/plugin/pipes)

![Pipes moving items between containers.](https://def9a2a4.github.io/defCoreLib-docs/readmes/assets/pipes/demo-1.png)

## Features

- **Regular pipes** pull items from the container behind them and push them out the front. They
  face away from the block you place them against, and can point any direction (including up/down).
- **Corner pipes** only relay items pushed into them — they never pull. Use them to turn corners
  and route flow. They face toward the block you place them against.
- **Material variants** — copper, iron, gold, and oxidized copper — with configurable transfer
  speeds and their own textures. Chain them into complex transport networks.
- Copper pipes oxidize into their oxidized-copper variant by throwing them into a water cauldron
  (or crafting with a water bucket). Textures, variants, recipes, and transfer rates are all
  configurable.

![Corner pipes routing flow around a build.](https://def9a2a4.github.io/defCoreLib-docs/readmes/assets/pipes/demo-2.png)

## Commands

Permission `pipes.admin`.

| Command | Description |
| --- | --- |
| `/pipes give <variant>` | Give a pipe item |
| `/pipes reload` | Reload configuration |
| `/pipes recipes` | Unlock all pipe recipes |
| `/pipes info` | Info about currently loaded pipes |
| `/pipes delete_all` | Delete all pipes **(dangerous)** |

## Requires

[DefCoreLib](https://github.com/def9a2a4/defCoreLib/blob/main/docs/readmes/defCoreLib.md).

The pipe blocks live in DefCoreLib; this plugin enables their crafting recipes and drives the
item-transfer logic. Without it the blocks still exist (obtainable via
`/defcorelib give pipes:copper_pipe`), they just aren't craftable and won't move items.

## Links

- Full type list & recipes: https://def9a2a4.github.io/defCoreLib-docs/index.html?ns=pipes
- Download on Modrinth: https://modrinth.com/plugin/pipes
- Repository: https://github.com/def9a2a4/defCoreLib/
- Issues: https://github.com/def9a2a4/defCoreLib/issues


[![Catalog](https://def9a2a4.github.io/defCoreLib-docs/readmes/assets/pipes/demo-1.png)](https://def9a2a4.github.io/defCoreLib-docs/index.html?ns=pipes)
