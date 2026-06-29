# DefCoreLib Catalog (docs site)

A static, user-facing webpage listing every custom item DefCoreLib adds — what each
does (from its in-game lore) and how to craft it. It is **auto-generated** from the
plugin's own YAML resource files, so it stays in sync with the actual blocks.

Live site: served by GitHub Pages from this `docs/` folder.

## Regenerate

```sh
make docs          # runs scripts/generate_catalog.py via uv
```

This reads the block definitions under `src/main/resources/`
(`demo-blocks.yml`, `rotation-blocks.yml`, `slabs.yml`, `grind-recipes.yml`) plus the
hand-authored `docs/data/extras.yml`, and writes `docs/data/items.json`, which the
page loads at runtime. Commit the regenerated `items.json` along with any block changes.

## Preview locally

```sh
cd docs && python3 -m http.server 8000
# open http://localhost:8000/
```

Head icons are rendered in-browser from `textures.minecraft.net` via a CORS proxy
(`corsproxy.io`); they may take a moment to appear and require network access.

## Files

| Path | Purpose |
| --- | --- |
| `index.html` | Page skeleton |
| `util/catalog.js` | Loads `data/items.json`, renders item cards + recipes + grindstone section, search/filter |
| `util/head-icon.js` | Renders player-head textures as isometric icons on a canvas |
| `util/catalog.css` | Styling (Monocraft font, dark theme) |
| `data/items.json` | **Generated** — do not edit by hand |
| `data/extras.yml` | Recipes defined in Java, not YAML (see below) — edit by hand |

## Manual sync point

Two recipes are defined in Java, not in the block YAML, so the generator cannot see
them. They live in `data/extras.yml` and must be kept in sync by hand if the Java changes:

- `large_banner` — `LargeBannerRecipes.java`
- `rotation_wrench` — `RotationBlocks.java` (`registerWrenchRecipe`)

## Publishing

In the GitHub repo settings → Pages, set the source to **branch `main`, folder `/docs`**.
Pushing to `main` then publishes the catalog automatically.
