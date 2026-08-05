#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["pyyaml>=6"]
# ///
"""
HeadSmith -> defCoreLib converter.

Reads the standalone HeadSmith source heads (heads/**/*.yml), applies the T1-T9
transform pipeline, runs a hard validation gate, and emits a single combined
`headsmith.yml` (namespace: headsmith) into the in-repo companion module's
resources so DefCoreLib's BlockLoader can load it verbatim.

Run:  uv run headsmith/tools/convert.py
      uv run headsmith/tools/convert.py --src <dir> --out <file>

The transform is a TOTAL FUNCTION HeadSmith-head -> core-head:
  T1 id            id -> validated headsmith:id
  T2 texture       passthrough (base64)
  T3 tags          -> categories (prefix headsmith/, dedupe; file-based for tagless)
  T4 properties    glowing -> light; 9 stations -> interact_gui; lightable -> candle states/transitions
  T5 lore          passthrough
  T6 break_sound   inject block.wood.break 0.7/1.2 (HeadSmith plays it universally)
  T7 drops         on_break/when -> flat rules; head-self -> items: self; append unconditional self catch-all
  T8 recipes       {head:X} -> {block: headsmith:X} in inputs+ingredients; unique per-recipe ids
  T9 storage       versioned headId/file -> {layout} mapping table

Then one validation gate: ids valid+unique, zero surviving `head:` keys, every
`block:` ref resolves, every layout in the enum, every drop rule -> material|self,
light.offset present for glowing/candle heads.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

import yaml

# ── config ────────────────────────────────────────────────────────────────────
# Vendored HeadSmith source lives in-repo (see tools/source/); no external checkout,
# resolved relative to this script so it works from any working directory.
DEFAULT_SRC = Path(__file__).resolve().parent / "source" / "heads"
DEFAULT_OUT = Path(__file__).resolve().parents[1] / "src/main/resources/headsmith.yml"
NAMESPACE = "headsmith"

# T4: HeadSmith interactive-station properties -> core InteractGUI enum (1:1).
STATION_GUI = {
    "workbench": "WORKBENCH", "anvil": "ANVIL", "smithing": "SMITHING",
    "cartography": "CARTOGRAPHY", "loom": "LOOM", "stonecutter": "STONECUTTER",
    "grindstone": "GRINDSTONE", "enchanting": "ENCHANTING", "enderchest": "ENDERCHEST",
}

# T9: storage layout mapping (B9). Versioned here; validated against the enum below.
VALID_LAYOUTS = {"HOPPER", "DROPPER", "CHEST_1ROW", "CHEST_2ROW", "CHEST_3ROW",
                 "CHEST_4ROW", "CHEST_5ROW", "CHEST_6ROW"}
STORAGE_BY_FILE = {"barrels": "CHEST_3ROW"}   # every head defined in barrels.yml
STORAGE_BY_ID: dict[str, str] = {}            # per-id overrides (win over file rule)

ID_RE = re.compile(r"^[a-z0-9._-]+$")

# T6: universal break sound HeadSmith plays for every head.
BREAK_SOUND = {"sound": "block.wood.break", "volume": 0.7, "pitch": 1.2}

# T4: candle wick offsets (verified to match HeadSmith's placement).
_WICK_WALL = {"NORTH": [0, 0.8, 0.25], "SOUTH": [0, 0.8, -0.25],
              "EAST": [-0.25, 0.8, 0], "WEST": [0.25, 0.8, 0]}


class ConvertError(Exception):
    pass


# ── T4 templates ───────────────────────────────────────────────────────────────
def candle_states() -> dict:
    """lightable -> a 2-state machine: no lit-texture swap; consume flint; SMOKE on extinguish."""
    return {
        "default_state": "unlit",
        "states": {
            "unlit": {},
            "lit": {
                "light": {"level": 14, "offset": [0, 1, 0]},
                "particles": {
                    "type": "SMALL_FLAME", "count": 1, "speed": 0, "interval": 5,
                    "floor_offset": [0, 0.55, 0], "wall_offsets": dict(_WICK_WALL),
                },
            },
        },
        "transitions": [
            {"trigger": {"type": "interact", "item": "FLINT_AND_STEEL"},
             "from": "unlit", "to": "lit",
             "sound": "item.flintandsteel.use", "consume": True},
            {"trigger": {"type": "interact"},
             "from": "lit", "to": "unlit",
             "sound": "block.candle.extinguish",
             "particle": {"type": "SMOKE", "count": 5, "spread": 0.1,
                          "floor_offset": [0, 0.6, 0], "wall_offsets": dict(_WICK_WALL)}},
        ],
    }


def chimney_states() -> dict:
    """chimney -> candle-like states machine, but NO light and rising campfire smoke when lit."""
    return {
        "default_state": "unlit",
        "states": {
            "unlit": {},
            "lit": {
                "particles": {
                    "type": "CAMPFIRE_COSY_SMOKE", "interval": 5,
                    "floor_offset": [0, 0.65, 0],  # spawn origin, inside the chimney top (no light block)
                    "velocity": [0, 0.1, 0],       # directional: smoke rises straight up (not random spread)
                    "speed": 0.5,                 # magnitude of that upward velocity
                },
            },
        },
        "transitions": [
            {"trigger": {"type": "interact", "item": "FLINT_AND_STEEL"},
             "from": "unlit", "to": "lit",
             "sound": "item.flintandsteel.use", "consume": True},
            {"trigger": {"type": "interact"},
             "from": "lit", "to": "unlit",
             "sound": "block.fire.extinguish",
             "particle": {"type": "SMOKE", "count": 5, "spread": 0.1, "floor_offset": [0, 0.6, 0]}},
        ],
    }


# ── T3 categories (multi-axis: family + cross-cutting color) ───────────────────
_CHAR_FAMILY_PREFIX = {
    "letter_": "letters", "number_": "numbers", "cyrillic_": "cyrillic", "greek_": "greek",
    "galactic_": "galactic", "rune_": "runes", "zodiac_": "zodiac",
}
_ARROW_GLYPHS = {"forward", "forward_ii", "backward", "backward_ii", "refresh", "pause", "note", "arrows"}


def char_family(glyph: str) -> str:
    """Bucket an alphabet `character/<glyph>` token into a browsable family."""
    for pre, fam in _CHAR_FAMILY_PREFIX.items():
        if glyph.startswith(pre):
            return fam
    if glyph.startswith("arrow") or glyph in _ARROW_GLYPHS:
        return "arrows"
    return "symbols"


def categories_for(src: dict, file_stem: str, is_alphabet: bool) -> list[str]:
    """Two orthogonal axes; a head appears under EACH category it carries.
    Axis A (family, one): file-family, sub-grouped only where a flat node would be huge.
    Axis B (color, cross-cutting): any `color/<c>` tag also yields `headsmith/color/<c>`
    so a black candle/pumpkin/wool all gather under `color/black`. Only color is cross-cutting
    (wood/stone/natural would merge the 2,626 alphabet letters into block nodes)."""
    tags = src.get("tags") or []
    props = src.get("properties") or []
    cats: list[str] = []

    def add(c: str) -> None:
        if c not in cats:
            cats.append(c)

    # Axis A — family
    if is_alphabet:
        glyph = next((t.split("/", 1)[1] for t in tags if t.startswith("character/")), "symbols")
        add(f"{NAMESPACE}/alphabet/{file_stem}/{char_family(glyph)}")
    elif file_stem == "misc":
        add(f"{NAMESPACE}/candles" if "candles" in tags else f"{NAMESPACE}/decorative")
    elif file_stem == "mini_blocks":
        if not tags:
            if any(p in STATION_GUI for p in props):
                add(f"{NAMESPACE}/mini_blocks/stations")
            elif "glowing" in props:
                add(f"{NAMESPACE}/mini_blocks/lights")
            else:
                add(f"{NAMESPACE}/mini_blocks/functional")
        else:
            cls = next((t for t in tags if not t.startswith("color/")), None)
            add(f"{NAMESPACE}/mini_blocks/{cls.split('/')[0] if cls else 'other'}")
    else:  # barrels, candles, books, pumpkins, chalices, bottles, buckets, bundles — flat family
        add(f"{NAMESPACE}/{file_stem}")

    # Axis B — color (cross-cutting)
    for t in tags:
        if t.startswith("color/"):
            add(f"{NAMESPACE}/color/{t.split('/', 1)[1]}")
    return cats


# ── transforms ───────────────────────────────────────────────────────────────
def convert_ingredient(spec: dict, head_id: str) -> dict:
    """T8: {head:X} -> {block: headsmith:X}; everything else passes through."""
    if "head" in spec:
        out = {"block": f"{NAMESPACE}:{spec['head']}"}
        return out
    return dict(spec)


def convert_recipes(recipes: dict, head_id: str) -> tuple[dict, list[str]]:
    """T8: rewrite head refs to block refs, assign unique per-recipe ids.
    Returns (core_recipes_node, list_of_block_refs)."""
    refs: list[str] = []
    shaped_in = ((recipes.get("craft") or {}).get("shaped")) or []
    shapeless_in = ((recipes.get("craft") or {}).get("shapeless")) or []
    stone_in = recipes.get("stonecutter") or []
    total = len(shaped_in) + len(shapeless_in) + len(stone_in)

    seq = 0
    def next_id() -> str:
        nonlocal seq
        seq += 1
        return head_id if total <= 1 else f"{head_id}_{seq}"

    craft: dict = {}
    if shaped_in:
        out = []
        for r in shaped_in:
            key = {}
            for k, spec in (r.get("key") or {}).items():
                conv = convert_ingredient(spec, head_id)
                if "block" in conv:
                    refs.append(conv["block"])
                key[k] = conv
            rec = {"id": next_id(), "pattern": list(r["pattern"]), "key": key}
            if int(r.get("amount", 1)) != 1:
                rec["amount"] = int(r["amount"])
            out.append(rec)
        craft["shaped"] = out
    if shapeless_in:
        out = []
        for r in shapeless_in:
            ings = []
            for spec in (r.get("ingredients") or []):
                conv = convert_ingredient(spec, head_id)
                if "block" in conv:
                    refs.append(conv["block"])
                ings.append(conv)
            rec = {"id": next_id(), "ingredients": ings}
            if int(r.get("amount", 1)) != 1:
                rec["amount"] = int(r["amount"])
            out.append(rec)
        craft["shapeless"] = out

    node: dict = {}
    if craft:
        node["craft"] = craft
    if stone_in:
        out = []
        for r in stone_in:
            conv = convert_ingredient(r["input"], head_id)
            if "block" in conv:
                refs.append(conv["block"])
            rec = {"id": next_id(), "input": conv}
            if int(r.get("amount", 1)) != 1:
                rec["amount"] = int(r["amount"])
            out.append(rec)
        node["stonecutter"] = out
    return node, refs


def convert_drops(head_id: str, drops_node) -> object:
    """T7: on_break/when wrappers -> flat rule list; head-self -> items: self;
    append an unconditional self catch-all (B4). No drops at all -> 'self'."""
    if not drops_node:
        return "self"
    on_break = drops_node.get("on_break") or []
    rules: list[dict] = []
    for rule in on_break:
        out: dict = {}
        when = rule.get("when") or {}
        if "silk_touch" in when:
            out["silk_touch"] = bool(when["silk_touch"])
        items: list[dict] = []
        self_drop = False
        for d in (rule.get("drops") or []):
            if "material" in d:
                it = {"material": d["material"]}
                if int(d.get("amount", 1)) != 1:
                    it["amount"] = int(d["amount"])
                items.append(it)
            elif "head" in d:
                if d["head"] != head_id:
                    raise ConvertError(
                        f"{head_id}: cross-head drop {{head: {d['head']}}} — core drops can only be a "
                        f"material or the head itself; cross-head head-drops are unsupported.")
                self_drop = True
        if items:
            out["items"] = items
        else:
            out["items"] = "self"  # explicit head-self, or an empty rule
            _ = self_drop
        rules.append(out)
    rules.append({"items": "self"})  # B4 unconditional catch-all (first-match-wins, so only a fallback)
    return rules


def convert_head(head_id: str, src: dict, file_stem: str, is_alphabet: bool) -> tuple[dict, list[str]]:
    """Full T1-T9 for one head. Returns (core_block, block_refs)."""
    if not ID_RE.match(head_id):
        raise ConvertError(f"invalid id '{head_id}' (must match {ID_RE.pattern})")

    out: dict = {}
    # T2 texture
    if "texture" not in src:
        raise ConvertError(f"{head_id}: missing texture")
    out["texture"] = src["texture"]
    # name / T5 lore
    if "name" in src:
        out["name"] = src["name"]
    if "lore" in src:
        out["lore"] = list(src["lore"])

    # T3 categories (multi-axis family + color; see categories_for)
    out["categories"] = categories_for(src, file_stem, is_alphabet)

    # T4 properties
    props = src.get("properties") or []
    for p in props:
        if p in STATION_GUI:
            out["interact_gui"] = STATION_GUI[p]
    if "lightable" in props:
        out.update(candle_states())          # candle states own the light cell
    elif "chimney" in props:
        out.update(chimney_states())         # candle-like, no light, campfire smoke
    elif "glowing" in props:
        out["light"] = {"level": 14, "offset": [0, 1, 0]}

    # T6 break sound
    out["break_sound"] = dict(BREAK_SOUND)

    # T7 drops
    out["drops"] = convert_drops(head_id, src.get("drops"))

    # T8 recipes
    refs: list[str] = []
    if src.get("recipes"):
        node, r = convert_recipes(src["recipes"], head_id)
        if node:
            out["recipes"] = node
        refs.extend(r)

    # T9 storage
    layout = STORAGE_BY_ID.get(head_id) or STORAGE_BY_FILE.get(file_stem)
    if layout:
        if layout not in VALID_LAYOUTS:
            raise ConvertError(f"{head_id}: invalid storage layout '{layout}'")
        out["storage"] = {"layout": layout}

    return out, refs


# ── validation gate ────────────────────────────────────────────────────────────
def find_head_keys(node, path="") -> list[str]:
    """Any surviving 'head' key in the output is a bug (must be block:)."""
    hits = []
    if isinstance(node, dict):
        for k, v in node.items():
            if k == "head":
                hits.append(path)
            hits.extend(find_head_keys(v, f"{path}.{k}"))
    elif isinstance(node, list):
        for i, v in enumerate(node):
            hits.extend(find_head_keys(v, f"{path}[{i}]"))
    return hits


def validate(blocks: dict[str, dict], all_refs: list[tuple[str, str]]) -> None:
    errs: list[str] = []
    ids = set(blocks)
    for bid, blk in blocks.items():
        for hp in find_head_keys(blk):
            errs.append(f"{bid}: surviving 'head' key at {hp} (should be block:)")
        # light.offset present for glowing/candle
        if "light" in blk and "offset" not in blk["light"]:
            errs.append(f"{bid}: light without offset (would target the head cell → no light)")
        if "states" in blk:
            lit = blk["states"].get("lit", {})
            if "light" in lit and "offset" not in lit["light"]:
                errs.append(f"{bid}: lit state light without offset")
        # drops rules resolve to material or self
        d = blk.get("drops")
        if isinstance(d, list):
            for i, rule in enumerate(d):
                it = rule.get("items")
                if it == "self":
                    continue
                if not (isinstance(it, list) and all("material" in x for x in it)):
                    errs.append(f"{bid}: drop rule [{i}] has no material/self items")
        # storage layout
        if "storage" in blk and blk["storage"].get("layout") not in VALID_LAYOUTS:
            errs.append(f"{bid}: invalid storage layout")
    # every block: ref resolves
    for bid, ref in all_refs:
        if not ref.startswith(f"{NAMESPACE}:"):
            errs.append(f"{bid}: block ref '{ref}' not in {NAMESPACE} namespace")
        elif ref.split(":", 1)[1] not in ids:
            errs.append(f"{bid}: dangling block ref '{ref}'")
    if errs:
        raise ConvertError("validation failed:\n  " + "\n  ".join(errs))


# ── driver ────────────────────────────────────────────────────────────────────
def main() -> int:
    ap = argparse.ArgumentParser(description="Convert HeadSmith heads to defCoreLib data.")
    ap.add_argument("--src", type=Path, default=DEFAULT_SRC)
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = ap.parse_args()

    src_files = sorted(args.src.rglob("*.yml"))
    if not src_files:
        print(f"No source YAML under {args.src}", file=sys.stderr)
        return 2

    blocks: dict[str, dict] = {}
    all_refs: list[tuple[str, str]] = []
    per_file: dict[str, int] = {}

    for f in src_files:
        stem = f.stem
        is_alphabet = f.parent.name == "alphabet"
        data = yaml.safe_load(f.read_text()) or {}
        heads = data.get("heads") or {}
        for head_id, sr in heads.items():
            if head_id in blocks:
                raise ConvertError(f"duplicate head id '{head_id}' (in {f})")
            blk, refs = convert_head(head_id, sr or {}, stem, is_alphabet)
            blocks[head_id] = blk
            all_refs.extend((head_id, r) for r in refs)
            per_file[stem] = per_file.get(stem, 0) + 1

    validate(blocks, all_refs)

    out_doc = {"namespace": NAMESPACE, "blocks": blocks}
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w") as fh:
        fh.write("# GENERATED by headsmith/tools/convert.py — do not edit by hand.\n")
        fh.write("# Source: HeadSmith heads/**/*.yml. Re-run the converter to regenerate.\n")
        yaml.safe_dump(out_doc, fh, sort_keys=False, allow_unicode=True, width=100000,
                       default_flow_style=False)

    storage_n = sum(1 for b in blocks.values() if "storage" in b)
    gui_n = sum(1 for b in blocks.values() if "interact_gui" in b)
    candle_n = sum(1 for b in blocks.values() if "states" in b)
    glow_n = sum(1 for b in blocks.values() if "light" in b)
    print(f"Converted {len(blocks)} heads -> {args.out}")
    print(f"  per file: " + ", ".join(f"{k}={v}" for k, v in sorted(per_file.items())))
    print(f"  storage={storage_n}  interact_gui={gui_n}  candle-states={candle_n}  glowing={glow_n}")
    print(f"  block-refs={len(all_refs)}  (all resolved)")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ConvertError as e:
        print(f"CONVERT ERROR: {e}", file=sys.stderr)
        raise SystemExit(1)
