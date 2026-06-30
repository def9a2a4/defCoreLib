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
import os
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
MODELS_DIR = DOCS / "assets" / "models"
TEXTURES_DIR = DOCS / "assets" / "textures"
OCTAGON_MAP = Path(__file__).resolve().parent / "octagon-textures.json"
BUNDLED_MODELS = Path(__file__).resolve().parent / "models"   # hand-authored builtin/entity models

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


def canonical_block(name: str) -> str:
    """Vanilla block/material id (lowercase, no namespace) with wool/banner → white."""
    n = name.split(":")[-1].lower()
    if n.endswith("_wool") or n == "wool":
        return "white_wool"
    if n.endswith("_banner") or n == "banner":
        return "white_banner"
    return n


def parse_block(namespace: str, block_id: str, sec: dict, aliases: dict) -> dict:
    item_material = sec.get("item_material")
    if item_material:
        icon = {"type": "material", "material": str(item_material).upper()}
        in_hand = {"kind": "item", "block": canonical_block(str(item_material))}
        base_head = None
    else:
        url = texture_to_url(sec.get("item_texture") or sec.get("texture"), aliases)
        icon = {"type": "head", "textureUrl": url}
        in_hand = {"kind": "head", "textureUrl": url}
        base_head = texture_to_url(sec.get("texture"), aliases)

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
        "inHand": in_hand,
        "placedVariants": [],   # filled from display-spec.json (headless export)
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
        if icon["type"] == "material":
            in_hand = {"kind": "item", "block": canonical_block(icon["material"])}
        else:
            in_hand = {"kind": "head", "textureUrl": icon.get("textureUrl")}
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
            "inHand": in_hand,
            "placed": {"baseHead": None, "displayEntities": []},
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
        tmp = dest.with_name(dest.name + ".tmp")
        tmp.write_bytes(data)
        os.replace(tmp, dest)   # atomic: a crash mid-write never leaves a truncated PNG in cache
        return True
    except Exception as e:
        print(f"    ! {url} -> {e}", file=sys.stderr)
        return False


def block_id_from_ref(ref: str) -> str:
    """A display 'block' ref is a full blockdata string (minecraft:smooth_stone_slab[...]);
    reduce it to the bare model id (smooth_stone_slab)."""
    return ref.split("[")[0].split(":")[-1].lower()


def collect_skin_urls(items: list[dict]) -> set[str]:
    urls: set[str] = set()

    def add(u):
        if u:
            urls.add(u)

    for it in items:
        if it["icon"].get("type") == "head":
            add(it["icon"].get("textureUrl"))
        for v in it.get("variants", []):
            add(v.get("textureUrl"))
        in_hand = it.get("inHand") or {}
        if in_hand.get("kind") == "head":
            add(in_hand.get("textureUrl"))
        for variant in it.get("placedVariants", []):
            add(variant.get("baseHeadTextureUrl"))
            for de in variant.get("displays", []):
                if de.get("kind") == "head":
                    add(de.get("ref"))
    return urls


def collect_block_models(items: list[dict]) -> set[str]:
    """Vanilla block/item ids used by placed display entities or in-hand item models."""
    blocks: set[str] = set()
    for it in items:
        in_hand = it.get("inHand") or {}
        if in_hand.get("kind") == "item" and in_hand.get("block"):
            blocks.add(in_hand["block"])
        for variant in it.get("placedVariants", []):
            for de in variant.get("displays", []):
                if de.get("kind") == "item":
                    blocks.add(canonical_block(de["ref"]))
                elif de.get("kind") == "block":
                    blocks.add(canonical_block(block_id_from_ref(de["ref"])))
    return blocks


# Representative item vendored for a recipe #tag, so the docs can show an icon (mirrors the
# TAG_PLACEHOLDER map in docs/util/render.js).
TAG_PLACEHOLDER_MATERIAL = {"banners": "WHITE_BANNER", "banner": "WHITE_BANNER", "wool": "WHITE_WOOL"}


def collect_materials(items: list[dict], grind: list[dict]) -> set[str]:
    mats: set[str] = set()

    def add_ing(ing):
        if not ing:
            return
        if ing.get("kind") == "material":
            mats.add(ing["value"])
        elif ing.get("kind") == "tag" and ing.get("value") in TAG_PLACEHOLDER_MATERIAL:
            mats.add(TAG_PLACEHOLDER_MATERIAL[ing["value"]])

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


# Vanilla block/item MODELS, flattened at build time so the front-end can render them
# in three.js without a runtime model resolver. Output: a self-contained resolved model
# (textures key -> vendored png path, plus elements) per block id.
MODELS_BASE = MC_ASSETS_BASE.replace("/textures", "/models")
_MODEL_CACHE: dict = {}


def fetch_model_json(name: str):
    """Fetch assets/.../models/<name>.json (e.g. 'block/oak_slab'), cached. None on 404."""
    name = name.split(":")[-1]
    if name in _MODEL_CACHE:
        return _MODEL_CACHE[name]
    try:
        data = fetch_json(f"{MODELS_BASE}/{name}.json")
    except Exception:
        data = None
    _MODEL_CACHE[name] = data
    return data


def flatten_block_model(block_id: str):
    """Resolve the parent chain of block/<block_id> into (textures, elements).
    Child textures override parents; the nearest `elements` wins."""
    textures: dict = {}
    elements = None
    cur = f"block/{block_id}"
    seen: set = set()
    while cur:
        name = cur.split(":")[-1]
        if name in seen:
            break
        seen.add(name)
        data = fetch_model_json(name)
        if data is None:
            break
        for k, v in (data.get("textures") or {}).items():
            textures.setdefault(k, v)          # child seen first → wins
        if elements is None and data.get("elements"):
            elements = data["elements"]
        cur = data.get("parent")
    if not elements:
        if block_id.startswith("waxed_"):
            return flatten_block_model(block_id[len("waxed_"):])  # waxed copper reuses the unwaxed model
        return None
    return textures, elements


def _resolve_ref(value, textures: dict):
    seen = set()
    while isinstance(value, str) and value.startswith("#") and value not in seen:
        seen.add(value)
        value = textures.get(value[1:])
    if isinstance(value, str) and not value.startswith("#"):
        return value.split(":")[-1]            # 'block/oak_planks'
    return None


def vendor_models(items: list[dict]) -> dict:
    """Flatten + vendor the vanilla models/textures for every block/item display entity.
    Returns a manifest {block_id: bool} of which resolved models are available."""
    blocks = collect_block_models(items)
    manifest: dict = {}
    print(f"  vendoring {len(blocks)} block/item models -> docs/assets/models/")
    ok = 0
    for block_id in sorted(blocks):
        res = flatten_block_model(block_id)
        if not res:
            # builtin/entity items (banners, etc.) have no vanilla elements model — fall back to a
            # hand-authored bundled model under scripts/models/ and vendor its textures.
            bundled = BUNDLED_MODELS / f"{block_id}.json"
            if bundled.exists():
                try:
                    m = json.loads(bundled.read_text())
                except (json.JSONDecodeError, OSError) as e:
                    print(f"    ! bundled model {bundled.name} unreadable -> {e}", file=sys.stderr)
                    manifest[block_id] = False
                    continue
                for path in set((m.get("textures") or {}).values()):
                    download(f"{MC_ASSETS_BASE}/{path}.png", TEXTURES_DIR / f"{path}.png")
                MODELS_DIR.mkdir(parents=True, exist_ok=True)
                (MODELS_DIR / f"{block_id}.json").write_text(
                    json.dumps({k: v for k, v in m.items() if not k.startswith("_")}), encoding="utf-8")
                manifest[block_id] = True
                ok += 1
            else:
                manifest[block_id] = False
            continue
        textures, elements = res
        resolved = {}
        for k, v in textures.items():
            path = _resolve_ref(v, textures)
            if path:
                resolved[k] = path
        # Vendor each referenced texture png.
        for path in set(resolved.values()):
            download(f"{MC_ASSETS_BASE}/{path}.png", TEXTURES_DIR / f"{path}.png")
        MODELS_DIR.mkdir(parents=True, exist_ok=True)
        (MODELS_DIR / f"{block_id}.json").write_text(
            json.dumps({"textures": resolved, "elements": elements}), encoding="utf-8")
        manifest[block_id] = True
        ok += 1
    missing = [b for b, v in manifest.items() if not v]
    print(f"    {ok}/{len(blocks)} models present"
          + (f"; no model for: {', '.join(sorted(missing))}" if missing else ""))
    return manifest


def vendor_assets(items: list[dict], grind: list[dict]) -> dict:
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

    # Vanilla block/item models for placed display entities.
    return vendor_models(items)


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

    # Ground-truth placed display data from the headless export (make docs).
    # display-spec.json is a committed source input (like extras.yml): a full `make docs` run
    # writes it from .temp/. Fail loud rather than silently zeroing every placedVariants.
    spec_path = DOCS_DATA / "display-spec.json"
    if not spec_path.exists():
        sys.exit(f"ERROR: {spec_path} is missing — run `make docs` first to produce the "
                 f"ground-truth placed-display export (the committed items.json depends on it).")
    spec = json.loads(spec_path.read_text())
    item_ids = {it["fullId"] for it in items}
    matched = 0
    for it in items:
        variants = (spec.get(it["fullId"]) or {}).get("variants", [])
        it["placedVariants"] = variants
        if variants:
            matched += 1
    unmatched = sorted(k for k in spec if k not in item_ids)
    print(f"  + display-spec.json: matched {matched}/{len(spec)} spec types to items")
    if unmatched:
        print(f"    ! {len(unmatched)} spec types with no catalog item: {', '.join(unmatched[:8])}"
              + (" …" if len(unmatched) > 8 else ""), file=sys.stderr)

    # Multi-block "showcases" (make showcase-capture). Optional input; when present, emit showcases.json
    # and fold its display refs into the vendoring pass below (pseudo-items reuse the placedVariants path).
    showcase_pseudo: list[dict] = []
    sc_path = DOCS_DATA / "showcase-spec.json"
    if sc_path.exists():
        showcases = json.loads(sc_path.read_text()).get("showcases", [])
        with (DOCS_DATA / "showcases.json").open("w", encoding="utf-8") as f:
            json.dump({"showcases": showcases}, f, indent=2, ensure_ascii=False)
            f.write("\n")
        used: dict[str, list[dict]] = {}   # fullId -> [{id,name}] for item→showcase backlinks
        for s in showcases:
            for blk in s.get("blocks", []):
                showcase_pseudo.append({
                    "icon": {}, "variants": [], "recipes": [],
                    "placedVariants": [{
                        "baseHeadTextureUrl": blk.get("baseHeadTextureUrl"),
                        "displays": blk.get("displays", []),
                    }],
                })
                bid = blk.get("id")
                if bid:
                    entries = used.setdefault(bid, [])
                    if not any(u["id"] == s["id"] for u in entries):
                        entries.append({"id": s["id"], "name": s.get("name", s["id"])})
        for it in items:
            it["usedInShowcases"] = used.get(it["fullId"], [])
        print(f"  + showcase-spec.json: {len(showcases)} showcases -> showcases.json")
    else:
        print("  (no showcase-spec.json -- run `make showcase-capture` to generate showcases.json)")

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
        manifest = vendor_assets(items + showcase_pseudo, grind)
        with (DOCS_DATA / "models-manifest.json").open("w", encoding="utf-8") as f:
            json.dump(manifest, f, indent=2, ensure_ascii=False)
            f.write("\n")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
