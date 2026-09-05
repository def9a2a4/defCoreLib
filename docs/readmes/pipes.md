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
- **Filter pipes** are what make a sorting network. Right-click one to open its filter GUI: copper
  filters hold 5 items as a whitelist, iron holds 9 and can be flipped to a blacklist, and gold
  holds 18 and adds an exact/similar match toggle.
- **Fluids** — oxidized copper pipes carry water when driven by a pump, which is how Mechanism's
  boiler and sieve get fed. Lava needs iron.
- Copper pipes oxidize into their oxidized-copper variant by throwing them into a water cauldron
  (or crafting with a water bucket). Textures, variants, recipes, and transfer rates are all
  configurable, in `plugins/Pipes/config.yml` — Pipes is the one plugin here that does write an
  editable config to disk.

![Corner pipes routing flow around a build.](https://def9a2a4.github.io/defCoreLib-docs/readmes/assets/pipes/demo-2.png)

## Commands

Permission `pipes.admin`.

| Command | Description |
| --- | --- |
| `/pipes help` | List the commands |
| `/pipes give <variant>` | Give a pipe item |
| `/pipes reload` | Reload configuration |
| `/pipes info` | Info about currently loaded pipes |
| `/pipes migrate` | Adopt pipes placed by v0.2.0 and earlier |
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
