# DefCoreLib Catalog (docs site)

A static, user-facing webpage listing every custom item DefCoreLib adds — what each
does (from its in-game lore), how to craft it, and its different states/textures. It is
**auto-generated** from the plugin's own YAML resource files, so it stays in sync with
the actual blocks.

## Regenerate

```sh
make docs          # runs scripts/generate_catalog.py via uv
```

This reads the block definitions under `src/main/resources/`
(`demo-blocks.yml`, `rotation-blocks.yml`, `slabs.yml`, `grind-recipes.yml`) plus the
hand-authored `docs/data/extras.yml`, writes `docs/data/items.json`, **and downloads all
referenced images into `docs/assets/`** (head skins + vanilla item/block textures) so the
page never depends on third-party CDNs at runtime. Commit the regenerated `items.json`
along with any block changes.

```sh
make docs                                       # full: JSON + vendored images
uv run scripts/generate_catalog.py --no-assets  # JSON only (fast; skips downloads)
```

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
| `index.html` | Catalog grid (main items + Vertical Slabs + Grindstone sections) |
| `item.html` | Per-item detail page (`item.html?id=<namespace:id>`) |
| `util/render.js` | Shared rendering: color codes, icons, recipe grids, head hydration |
| `util/catalog.js` | Catalog grid: cards, search, namespace filters, slab/grind sections |
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
