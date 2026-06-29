# /// script
# requires-python = ">=3.10"
# dependencies = ["PyYAML>=6"]
# ///
"""
Generate the user-facing item catalog for the docs site from DefCoreLib's YAML
resource files, and vendor every image it references into docs/assets/ so the page
never depends on flaky third-party CDNs at runtime.

Run it with:

    make docs                          # generate JSON + vendor images
    uv run scripts/generate_catalog.py
    uv run scripts/generate_catalog.py --no-assets   # JSON only, fast

Outputs:
    docs/data/items.json               (committed)
    docs/assets/skins/<hash>.png       (gitignored — head skins)
    docs/assets/items/<material>.png   (gitignored — vanilla item/block textures)

The browser code (docs/util/*) only consumes these, so all knowledge of the block
schema lives here in one place.
"""

from __future__ import annotations

import argparse
import base64
import json
import re
import sys
import urllib.parse
import urllib.request
from pathlib import Path

import yaml


# YAML 1.1 resolves off/on/yes/no as booleans, but DefCoreLib uses `off`/`on` as
# state names (e.g. `default_state: off`, `from: off`). Narrow the bool resolver to
# only true/false so those stay strings; real config booleans all use true/false.
class CatalogLoader(yaml.SafeLoader):
    pass


CatalogLoader.yaml_implicit_resolvers = {
    ch: [(tag, rx) for (tag, rx) in resolvers if tag != "tag:yaml.org,2002:bool"]
    for ch, resolvers in yaml.SafeLoader.yaml_implicit_resolvers.items()
}
CatalogLoader.add_implicit_resolver(
    "tag:yaml.org,2002:bool",
    re.compile(r"^(?:true|True|TRUE|false|False|FALSE)$"),
    list("tTfF"),
)

ROOT = Path(__file__).resolve().parent.parent
RESOURCES = ROOT / "src" / "main" / "resources"
DOCS = ROOT / "docs"
DOCS_DATA = DOCS / "data"
SKINS_DIR = DOCS / "assets" / "skins"
ITEMS_DIR = DOCS / "assets" / "items"
OCTAGON_MAP = Path(__file__).resolve().parent / "octagon-textures.json"

# Resource files that define craftable/placeable items (namespace + `blocks:` map).
# minecart-ship-blocks.yml is excluded — it is a wildcard allow-list, not custom items.
BLOCK_FILES = [
    "demo-blocks.yml",
    "rotation-blocks.yml",
    "slabs.yml",
]

# Vanilla-texture sources, mirroring HeadSmith/docs/util/catalog.js (pinned 1.21.4).
OCTAGON_BASE = "https://raw.githubusercontent.com/MyOctagon/Minecraft-Block-Textures/main"
MC_ASSETS_BASE = "https://raw.githubusercontent.com/InventivetalentDev/minecraft-assets/1.21.4/assets/minecraft/textures"


# ── YAML helpers ───────────────────────────────────────────────────────────

def load_yaml(path: Path):
    with path.open(encoding="utf-8") as f:
        return yaml.load(f, Loader=CatalogLoader) or {}


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


def texture_to_url(value, aliases: dict[str, str]) -> str | None:
    """Resolve an @alias/base64 texture value to a skin URL (or None)."""
    resolved = resolve_texture(value, aliases)
    return texture_url(resolved) if isinstance(resolved, str) else None


def parse_ingredient(node) -> dict | None:
    """Normalize an ingredient/slot into {kind, value}: material / tag / block."""
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
        ings = [parse_ingredient(i) for i in (r.get("ingredients") or [])]
        out.append({"type": "shapeless", "ingredients": [i for i in ings if i], "amount": r.get("amount", 1)})
    for r in recipes.get("stonecutter") or []:
        out.append({"type": "stonecutter", "input": parse_ingredient(r.get("input")), "amount": r.get("amount", 1)})
    return out


def summarize_trigger(trigger) -> str:
    if not isinstance(trigger, dict):
        return "?"
    t = trigger.get("type", "?")
    if t == "interact" and trigger.get("item"):
        return f"interact:{trigger['item']}"
    return str(t)


def parse_variants(sec: dict, aliases: dict) -> list[dict]:
    """Collect an item's distinct textures: named states, redstone power, facing."""
    variants: list[dict] = []

    for name, state in (sec.get("states") or {}).items():
        if isinstance(state, dict) and state.get("texture"):
            url = texture_to_url(state["texture"], aliases)
            if url:
                variants.append({"group": "states", "label": name, "textureUrl": url})

    redstone = sec.get("redstone") or {}
    for power, tex in (redstone.get("textures") or {}).items():
        url = texture_to_url(tex, aliases)
        if url:
            variants.append({"group": "power", "label": f"Power {power}", "textureUrl": url})
    for rng, tex in (redstone.get("texture_ranges") or {}).items():
        url = texture_to_url(tex, aliases)
        if url:
            variants.append({"group": "power", "label": str(rng), "textureUrl": url})

    for face, tex in (sec.get("directional_textures") or {}).items():
        url = texture_to_url(tex, aliases)
        if url:
            variants.append({"group": "facing", "label": str(face), "textureUrl": url})

    return variants


def parse_transitions(sec: dict) -> list[dict]:
    out = []
    for t in sec.get("transitions") or []:
        if not isinstance(t, dict):
            continue
        out.append({
            "from": t.get("from"),
            "to": t.get("to"),
            "trigger": summarize_trigger(t.get("trigger")),
        })
    return out


def parse_block(namespace: str, block_id: str, sec: dict, aliases: dict) -> dict:
    item_material = sec.get("item_material")
    if item_material:
        icon = {"type": "material", "material": str(item_material).upper()}
    else:
        url = texture_to_url(sec.get("item_texture") or sec.get("texture"), aliases)
        icon = {"type": "head", "textureUrl": url}

    lore = sec.get("lore") or []
    if isinstance(lore, str):
        lore = [lore]

    return {
        "namespace": namespace,
        "id": block_id,
        "fullId": f"{namespace}:{block_id}",
        "name": sec.get("name") or block_id,
        "lore": list(lore),
        "icon": icon,
        "glint": bool(sec.get("item_glint", False)),
        "recipes": parse_recipes(sec.get("recipes")),
        "variants": parse_variants(sec, aliases),
        "transitions": parse_transitions(sec),
    }


def load_block_file(path: Path) -> list[dict]:
    data = load_yaml(path)
    namespace = data.get("namespace", "custom")
    aliases = data.get("textures") or {}
    items = []
    for block_id, sec in (data.get("blocks") or {}).items():
        if isinstance(sec, dict):
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
        namespace = entry.get("namespace", "custom")
        items.append({
            "namespace": namespace,
            "id": entry["id"],
            "fullId": f"{namespace}:{entry['id']}",
            "name": entry.get("name") or entry["id"],
            "lore": list(entry.get("lore") or []),
            "icon": icon,
            "glint": bool(entry.get("glint", False)),
            "recipes": recipes,
            "variants": [],
            "transitions": [],
        })
    return items


def load_grind(path: Path) -> list[dict]:
    if not path.exists():
        return []
    data = load_yaml(path)
    return [
        {"input": str(r.get("input", "")).upper(),
         "output": str(r.get("output", "")).upper(),
         "amount": r.get("amount", 1)}
        for r in (data.get("recipes") or [])
    ]


# ── Asset vendoring ────────────────────────────────────────────────────────

def download(url: str, dest: Path) -> bool:
    """Download url -> dest. Skip if already present. Returns True on success."""
    if dest.exists() and dest.stat().st_size > 0:
        return True
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "defcorelib-docs/1.0"})
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = resp.read()
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(data)
        return True
    except Exception as e:
        print(f"    ! {url} -> {e}", file=sys.stderr)
        return False


def collect_skin_urls(items: list[dict]) -> set[str]:
    urls: set[str] = set()
    for it in items:
        if it["icon"].get("type") == "head" and it["icon"].get("textureUrl"):
            urls.add(it["icon"]["textureUrl"])
        for v in it.get("variants", []):
            if v.get("textureUrl"):
                urls.add(v["textureUrl"])
    return urls


def collect_materials(items: list[dict], grind: list[dict]) -> set[str]:
    mats: set[str] = set()

    def add_ing(ing):
        if ing and ing.get("kind") == "material":
            mats.add(ing["value"])

    for it in items:
        if it["icon"].get("type") == "material":
            mats.add(it["icon"]["material"])
        for r in it["recipes"]:
            if r["type"] == "shaped":
                for ing in r["key"].values():
                    add_ing(ing)
            elif r["type"] == "shapeless":
                for ing in r["ingredients"]:
                    add_ing(ing)
            elif r["type"] == "stonecutter":
                add_ing(r.get("input"))
    for g in grind:
        mats.add(g["input"])
        mats.add(g["output"])
    return mats


def fetch_json(url: str):
    req = urllib.request.Request(url, headers={"User-Agent": "defcorelib-docs/1.0"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def wiki_invicon_url(name: str) -> str:
    """Minecraft wiki rendered inventory icon, e.g. OAK_SLAB -> Invicon_Oak_Slab.png.
    Covers model items (slabs/stairs), banners, etc. that have no flat texture."""
    display = "_".join(w.capitalize() for w in name.split("_"))
    filename = f"Invicon_{display}.png"
    return f"https://minecraft.wiki/w/Special:FilePath/{urllib.parse.quote(filename)}"


def resolve_material_url(name: str, octagon: dict, item_list: set, block_list: set) -> str | None:
    """HeadSmith's priority chain: octagon render -> item flat -> block flat -> wiki invicon."""
    if name in octagon:
        folder = urllib.parse.quote(octagon[name]["folder"])
        file = urllib.parse.quote(octagon[name]["file"])
        return f"{OCTAGON_BASE}/{folder}/{file}"
    if name in item_list:
        return f"{MC_ASSETS_BASE}/item/{name}.png"
    if name in block_list:
        return f"{MC_ASSETS_BASE}/block/{name}.png"
    return wiki_invicon_url(name)


def vendor_assets(items: list[dict], grind: list[dict]) -> None:
    # Head skins.
    skin_urls = collect_skin_urls(items)
    print(f"  vendoring {len(skin_urls)} head skins -> docs/assets/skins/")
    ok = 0
    for url in sorted(skin_urls):
        h = url.rstrip("/").split("/")[-1]
        if download(url, SKINS_DIR / f"{h}.png"):
            ok += 1
    print(f"    {ok}/{len(skin_urls)} skins present")

    # Vanilla item/block textures.
    materials = collect_materials(items, grind)
    octagon = json.loads(OCTAGON_MAP.read_text()) if OCTAGON_MAP.exists() else {}
    try:
        item_list = {f.replace(".png", "") for f in fetch_json(f"{MC_ASSETS_BASE}/item/_list.json")["files"]}
        block_list = {f.replace(".png", "") for f in fetch_json(f"{MC_ASSETS_BASE}/block/_list.json")["files"]}
    except Exception as e:
        print(f"  ! could not fetch InventivetalentDev _list.json ({e}); item textures may be sparse", file=sys.stderr)
        item_list, block_list = set(), set()

    print(f"  vendoring {len(materials)} material textures -> docs/assets/items/")
    got, missing = 0, []
    for mat in sorted(materials):
        name = mat.lower()
        url = resolve_material_url(name, octagon, item_list, block_list)
        if url and download(url, ITEMS_DIR / f"{name}.png"):
            got += 1
        else:
            missing.append(mat)
    print(f"    {got}/{len(materials)} materials present"
          + (f"; no texture for: {', '.join(sorted(missing))}" if missing else ""))


# ── Main ───────────────────────────────────────────────────────────────────

def main() -> int:
    ap = argparse.ArgumentParser(description="Generate the DefCoreLib docs catalog.")
    ap.add_argument("--no-assets", action="store_true", help="skip downloading images (JSON only)")
    args = ap.parse_args()

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

    catalog = {
        "namespaces": sorted({it["namespace"] for it in items}),
        "items": items,
        "grindRecipes": grind,
    }

    DOCS_DATA.mkdir(parents=True, exist_ok=True)
    out_path = DOCS_DATA / "items.json"
    with out_path.open("w", encoding="utf-8") as f:
        json.dump(catalog, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print(f"  = wrote {out_path.relative_to(ROOT)}: {len(items)} items, "
          f"{len(grind)} grind recipes, namespaces={catalog['namespaces']}")

    if args.no_assets:
        print("  (--no-assets: skipped image vendoring)")
    else:
        vendor_assets(items, grind)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
