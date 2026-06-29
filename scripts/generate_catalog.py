# /// script
# requires-python = ">=3.10"
# dependencies = ["PyYAML>=6"]
# ///
"""
Generate the user-facing item catalog (docs/data/items.json) from DefCoreLib's
YAML resource files.

This is the "auto-generated docs" pipeline: the same YAML that the plugin loads at
runtime (BlockLoader.java) is the source of truth for the docs website. Run it with:

    make docs            # from defCoreLib/
    uv run scripts/generate_catalog.py

The browser code (docs/util/catalog.js) only consumes the JSON this produces, so all
knowledge of the (fairly involved) block schema lives here in one place.
"""

from __future__ import annotations

import base64
import json
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
RESOURCES = ROOT / "src" / "main" / "resources"
DOCS_DATA = ROOT / "docs" / "data"

# Resource files that define craftable/placeable items (namespace + `blocks:` map).
# NOTE: minecart-ship-blocks.yml is intentionally excluded — it is a wildcard allow-list
# of vanilla blocks the minecart scanner may pick up, not a set of custom items.
BLOCK_FILES = [
    "demo-blocks.yml",
    "rotation-blocks.yml",
    "slabs.yml",
]


def load_yaml(path: Path):
    with path.open(encoding="utf-8") as f:
        return yaml.safe_load(f) or {}


def texture_url(base64_value: str) -> str | None:
    """Decode a base64 head-texture value into its textures.minecraft.net skin URL."""
    try:
        decoded = base64.b64decode(base64_value).decode("utf-8")
        return json.loads(decoded)["textures"]["SKIN"]["url"]
    except Exception:
        return None


def resolve_texture(value, aliases: dict[str, str]):
    """Mirror BlockLoader.resolveTexture: '@alias' -> registered base64."""
    if isinstance(value, str) and value.startswith("@"):
        return aliases.get(value[1:])
    return value


def parse_ingredient(node) -> dict | None:
    """Normalize a single ingredient/slot into {kind, value}.

    A node may reference a vanilla `material`, a material `tag`, or another custom
    `block` (namespace:id). Used for shaped keys, shapeless ingredients, and inputs.
    """
    if not isinstance(node, dict):
        return None
    if "block" in node:
        return {"kind": "block", "value": node["block"]}
    if "material" in node:
        return {"kind": "material", "value": str(node["material"]).upper()}
    if "tag" in node:
        return {"kind": "tag", "value": str(node["tag"]).lower()}
    return None


def parse_recipes(recipes) -> list[dict]:
    """Flatten a block's `recipes:` section into a list of normalized recipe dicts."""
    out: list[dict] = []
    if not isinstance(recipes, dict):
        return out

    craft = recipes.get("craft") or {}

    for r in craft.get("shaped") or []:
        out.append({
            "type": "shaped",
            "pattern": list(r.get("pattern") or []),
            "key": {k: parse_ingredient(v) for k, v in (r.get("key") or {}).items()},
            "amount": r.get("amount", 1),
        })

    for r in craft.get("shapeless") or []:
        ingredients = [parse_ingredient(i) for i in (r.get("ingredients") or [])]
        out.append({
            "type": "shapeless",
            "ingredients": [i for i in ingredients if i],
            "amount": r.get("amount", 1),
        })

    for r in recipes.get("stonecutter") or []:
        out.append({
            "type": "stonecutter",
            "input": parse_ingredient(r.get("input")),
            "amount": r.get("amount", 1),
        })

    return out


def parse_block(namespace: str, block_id: str, sec: dict, aliases: dict) -> dict:
    full_id = f"{namespace}:{block_id}"

    # Icon: a vanilla item_material wins (e.g. slabs render as the real slab item);
    # otherwise use the head texture (prefer item_texture over the placed texture).
    item_material = sec.get("item_material")
    if item_material:
        icon = {"type": "material", "material": str(item_material).upper()}
    else:
        raw = resolve_texture(sec.get("item_texture") or sec.get("texture"), aliases)
        url = texture_url(raw) if isinstance(raw, str) else None
        icon = {"type": "head", "textureUrl": url}

    lore = sec.get("lore") or []
    if isinstance(lore, str):
        lore = [lore]

    return {
        "namespace": namespace,
        "id": block_id,
        "fullId": full_id,
        "name": sec.get("name") or block_id,
        "lore": list(lore),
        "icon": icon,
        "glint": bool(sec.get("item_glint", False)),
        "recipes": parse_recipes(sec.get("recipes")),
    }


def load_block_file(path: Path) -> list[dict]:
    data = load_yaml(path)
    namespace = data.get("namespace", "custom")
    aliases = data.get("textures") or {}
    blocks = data.get("blocks") or {}
    items = []
    for block_id, sec in blocks.items():
        if not isinstance(sec, dict):
            continue
        items.append(parse_block(namespace, block_id, sec, aliases))
    return items


def load_extras(path: Path) -> list[dict]:
    """Java-defined recipes, authored by hand in docs/data/extras.yml."""
    if not path.exists():
        return []
    data = load_yaml(path)
    items = []
    for entry in data.get("items") or []:
        icon = entry.get("icon") or {}
        if "material" in icon:
            icon = {"type": "material", "material": str(icon["material"]).upper()}
        elif "textureUrl" in icon:
            icon = {"type": "head", "textureUrl": icon["textureUrl"]}
        else:
            icon = {"type": "head", "textureUrl": None}
        namespace = entry.get("namespace", "custom")
        block_id = entry["id"]
        recipes = []
        for r in entry.get("recipes") or []:
            if r.get("type") == "shaped":
                recipes.append({
                    "type": "shaped",
                    "pattern": list(r.get("pattern") or []),
                    "key": {k: parse_ingredient(v) for k, v in (r.get("key") or {}).items()},
                    "amount": r.get("amount", 1),
                })
            elif r.get("type") == "shapeless":
                ings = [parse_ingredient(i) for i in (r.get("ingredients") or [])]
                recipes.append({"type": "shapeless", "ingredients": [i for i in ings if i], "amount": r.get("amount", 1)})
            elif r.get("type") == "stonecutter":
                recipes.append({"type": "stonecutter", "input": parse_ingredient(r.get("input")), "amount": r.get("amount", 1)})
        items.append({
            "namespace": namespace,
            "id": block_id,
            "fullId": f"{namespace}:{block_id}",
            "name": entry.get("name") or block_id,
            "lore": list(entry.get("lore") or []),
            "icon": icon,
            "glint": bool(entry.get("glint", False)),
            "recipes": recipes,
        })
    return items


def load_grind(path: Path) -> list[dict]:
    if not path.exists():
        return []
    data = load_yaml(path)
    out = []
    for r in data.get("recipes") or []:
        out.append({
            "input": str(r.get("input", "")).upper(),
            "output": str(r.get("output", "")).upper(),
            "amount": r.get("amount", 1),
        })
    return out


def main() -> int:
    items: list[dict] = []

    for name in BLOCK_FILES:
        path = RESOURCES / name
        if not path.exists():
            print(f"  ! skipping missing {name}", file=sys.stderr)
            continue
        loaded = load_block_file(path)
        print(f"  + {name}: {len(loaded)} items")
        items.extend(loaded)

    extras = load_extras(DOCS_DATA / "extras.yml")
    print(f"  + extras.yml: {len(extras)} items")
    items.extend(extras)

    grind = load_grind(RESOURCES / "grind-recipes.yml")
    print(f"  + grind-recipes.yml: {len(grind)} recipes")

    namespaces = sorted({it["namespace"] for it in items})

    catalog = {
        "namespaces": namespaces,
        "items": items,
        "grindRecipes": grind,
    }

    DOCS_DATA.mkdir(parents=True, exist_ok=True)
    out_path = DOCS_DATA / "items.json"
    with out_path.open("w", encoding="utf-8") as f:
        json.dump(catalog, f, indent=2, ensure_ascii=False)
        f.write("\n")

    print(f"  = wrote {out_path.relative_to(ROOT)}: {len(items)} items, "
          f"{len(grind)} grind recipes, namespaces={namespaces}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
