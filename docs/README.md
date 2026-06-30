# DefCoreLib Catalog (docs site)

A static, user-facing webpage listing every custom item DefCoreLib adds — what each
does (from its in-game lore), how to craft it, and its different states/textures. It is
**auto-generated** from the plugin's own YAML resource files, so it stays in sync with
the actual blocks.

## Regenerate

```sh
make docs                 # full pipeline (see below)
make docs KEEP_ALIVE=1    # same, but keep a joinable server up to inspect blocks in-game
make docs-fast            # only re-run the catalog generator (no server); fast frontend iteration
```

`make docs` is a single command that:
1. builds the plugin (`gradle shadowJar`);
2. boots a **separate** headless server (`test-server/`, *not* the playtest `server/`) with
   `-Ddefcorelib.export`, which places one of every registered block in each orientation, reads
   back the **actual spawned display entities** (transforms, `wall_offset`, base head, baked
   animation keyframes — ground-truth, never re-derived) into `docs/data/display-spec.json`, then
   shuts down;
3. runs `scripts/generate_catalog.py`, which merges `display-spec.json` into `docs/data/items.json`
   (the recipes/lore/textures still come from the YAML) and **vendors all referenced images** into
   `docs/assets/` (head skins, vanilla item/block textures + flattened models).

The `placedVariants` in `items.json` drive the interactive 3D "placed" views; everything else (the
catalog, recipes, in-hand icon) comes from the YAML as before. Because it reads the running plugin,
**new blocks (and blocks from dependent plugins whose jars are in `test-server/plugins/`) appear
automatically** with zero per-block code.

### Inspect in-game
`make docs KEEP_ALIVE=1` lays every block + variant out in a grid and keeps the server running on
**localhost:25575** (offline mode). Join it and `/tp @s 0 101 0` to fly the grid and compare against
the docs. `Ctrl-C` when done, then `make docs-fast` to regenerate the catalog.

> The first `make docs` copies the Paper jar from your playtest `server/` into `test-server/`
> (gitignored). Resolver/world-dependent blocks (e.g. Pipes) need representative-neighbour
> placement and are a later phase.

## Preview locally

```sh
cd docs && python3 -m http.server 8000
# open http://localhost:8000/
```

All images load from `docs/assets/` (same-origin), so previews work offline once
`make docs` has vendored them.

## Images & deployment (important)

`docs/assets/` is **gitignored** — the vendored images are local-only for now. That means
the **published GitHub Pages site does not include them yet**: it will show text-label
fallbacks until the assets are made available to the live site. To self-host images on the
live site later, either:

1. remove `/docs/assets/` from `.gitignore` and commit the images, or
2. add a CI step that runs `make docs` before the Pages deploy.

## Files

| Path | Purpose |
| --- | --- |
| `index.html` | Catalog grid (main items + Vertical Slabs + Millstone sections) |
| `item.html` | Per-item detail page (`item.html?id=<namespace:id>`) |
| `util/render.js` | Shared rendering: color codes, icons, recipe grids, head hydration |
| `util/catalog.js` | Catalog grid: cards, search, namespace filters, slab/mill sections |
| `util/item.js` | Detail page: full lore, recipes, states/variants, transitions |
| `util/head-icon.js` | Renders player-head skins as isometric icons (from `assets/skins/`) |
| `util/catalog.css` | Styling (Monocraft font, dark theme) |
| `data/items.json` | **Generated** — do not edit by hand |
| `data/extras.yml` | Recipes defined in Java, not YAML (see below) — edit by hand |
| `assets/` | **Gitignored, generated** — vendored head skins + vanilla textures |
| `../scripts/generate_catalog.py` | Generator + image vendoring |
| `../scripts/octagon-textures.json` | Material→render mapping (vendored from HeadSmith) |

## Manual sync point

Two recipes are defined in Java, not in the block YAML, so the generator cannot see
them. They live in `data/extras.yml` and must be kept in sync by hand if the Java changes:

- `large_banner` — `LargeBannerRecipes.java`
- `rotation_wrench` — `RotationBlocks.java` (`registerWrenchRecipe`)

## Publishing

In the GitHub repo settings → Pages, set the source to **branch `main`, folder `/docs`**.
Pushing to `main` then publishes the catalog automatically (see the images caveat above).
