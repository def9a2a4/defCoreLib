#!/usr/bin/env python3
"""Generate the `mech` advancement datapack JSON tree.

Single source of truth for the tree. Re-run to regenerate all advancement JSONs + pack.mcmeta.
Grant logic (which milestone grants which node) lives in Java (MechAdvancements.java);
these files only define display + parent + the impossible criterion.

Icons: an `@alias` icon is emitted as a textured player head, resolving the alias to its base64
texture from rotation-blocks.yml's top-level `textures:` map (values start with `eyJ`). A
`minecraft:<item>` icon is a plain vanilla item. Verified identical on 1.21.11 / 26.1.x / 26.2.
"""
import json, re, pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
PACK_DIR = ROOT / "mech/src/main/resources/mech_datapack"
ADV_DIR = PACK_DIR / "data/mech/advancement"
TEXTURES_YML = ROOT / "src/main/resources/rotation-blocks.yml"

NS = "mech"

# pack.mcmeta version range. `supported_formats` was removed in 1.21.9 (25w31a); the current fields
# are min_format / max_format. Data-pack formats of the target versions: 1.21.11 = 94, 26.1 = 101,
# 26.2 = 107. A bare-integer max_format means "any minor of that major", so 94..107 spans all three
# and leaves headroom for future 26.2 minors. Do NOT add a legacy `pack_format` (only for formats <82).
PACK_MCMETA = {
    "pack": {
        "description": "Mechanism advancements",
        "min_format": 94,
        "max_format": 107,
    }
}

# Root tab backdrop. Since 25w04a (before all three target versions) `background` is a plain resource
# location, NOT a texture path: the game prepends `textures/` and appends `.png` itself. The old
# `minecraft:textures/block/copper_block.png` double-wraps → missing-texture checkerboard.
ROOT_BACKGROUND = "minecraft:block/copper_block"

# (path, parent, frame, icon, title, color, description)
# icon: "@alias" -> textured head (resolved below); "minecraft:x" -> vanilla item.
# parent == None -> root. Titles stay playful; descriptions are informative (what the block does /
# how the milestone is earned).
NODES = [
    # ── ROOT ──
    ("root", None, "task", "@windmill_item", "The Mechanism", "gold",
     "Craft any Mechanism part to begin — rotational power, moving structures, and windmills await."),

    # ── Branch A · Discovery / Crafting (granted on crafting the item) ──
    ("craft/bearing", "root", "task", "@windmill_hub_fwd", "First Bearing", "white",
     "Craft a Bearing — the copper core most rotation machines are built from."),
    ("craft/wrench", "craft/bearing", "task", "minecraft:golden_axe", "Right Tool for the Job", "white",
     "Craft the Rotation Wrench — right-click a machine to rotate it in place."),
    ("craft/glue_brush", "craft/bearing", "task", "minecraft:brush", "Stuck On You", "white",
     "Craft the Glue Brush — pick exactly which blocks a Rotator or Minecart carries."),
    ("craft/shaft", "craft/bearing", "task", "@rod_fwd", "First Shaft", "white",
     "Craft a Shaft — carries rotational power in a straight line along its axis."),
    ("craft/gear", "craft/shaft", "task", "@copper_gear", "Turning the Corner", "white",
     "Craft a Gear — meshes with gears on its sides to turn power around a 90° corner."),
    ("craft/water_wheel", "craft/shaft", "task", "@water_wheel_copper", "Paddle Power", "white",
     "Craft a Water Wheel — a passive source worth 2 power, spun by water against a wall."),
    ("craft/windmill_item", "craft/shaft", "task", "@windmill_item", "Tilting at Windmills", "white",
     "Craft a Windmill — an always-spinning passive source; its blades take your four banners' patterns."),
    ("craft/clutch", "craft/gear", "task", "@clutch_fwd", "Clutch Play", "white",
     "Craft a Clutch — a shaft that disconnects and cuts power when it receives a redstone signal."),
    ("craft/reverser", "craft/gear", "task", "@reverser_fwd", "Reverse Course", "white",
     "Craft a Reverser — flips a line's spin direction while it's redstone-powered."),
    ("craft/drill", "craft/gear", "task", "@screw_fwd", "Boring Work", "white",
     "Craft a Drill — breaks the block directly in front of it while powered."),
    ("craft/millstone", "craft/gear", "task", "@stone_slabs", "Grind House", "white",
     "Craft a Millstone — grinds its stored items (wheat→flour, ores→more) while powered from above."),
    ("craft/fan", "craft/gear", "task", "@fan_item", "Fan Favourite", "white",
     "Craft a Fan — blows a ~5-block beam that pushes mobs, players, and dropped items away."),
    ("craft/press", "craft/millstone", "task", "@compressor_item", "Under Pressure", "white",
     "Craft an Extractor Press — squeezes stored items into juices, oils, honey, and dyes."),
    ("craft/placer", "craft/drill", "task", "@placer_item", "Autoplacement", "white",
     "Craft a Block Placer — places its stored blocks into the world, one per cycle, while powered."),
    ("craft/motor", "craft/water_wheel", "task", "@copper_bulb", "Off the Grid", "white",
     "Craft a Redstone Motor — a steady 1-power source that runs while UNpowered by redstone."),
    ("craft/engine", "craft/water_wheel", "goal", "@bronze_engine", "Full Steam", "yellow",
     "Craft an Engine — the strongest source at 5 power, but it burns furnace fuel to run."),
    ("craft/master_machinist", "craft/engine", "challenge", "@copper_gear", "Master Machinist", "light_purple",
     "Craft at least one of every Mechanism machine."),

    # ── Branch B · Rotation Power (granted when a network becomes powered) ──
    ("rotation/first_power", "root", "goal", "@shaft_ring_up", "It Lives", "yellow",
     "Power a rotation network — connect a source to a shaft and watch it spin."),
    ("rotation/wind_power", "rotation/first_power", "task", "@windmill_item", "Winds of Change", "white",
     "Drive a network with a Windmill — free, fuel-less power on the breeze."),
    ("rotation/water_power", "rotation/first_power", "task", "@water_wheel_copper", "Go With the Flow", "white",
     "Drive a network with a Water Wheel turned by the current."),
    ("rotation/engine_power", "rotation/first_power", "goal", "@bronze_engine", "Ignition", "yellow",
     "Drive a network with a fuelled Engine — burn fuel, make torque."),
    ("rotation/chain_8", "rotation/first_power", "task", "@chainwheel", "Power Line", "white",
     "Build a powered rotation network of 8 or more connected blocks."),
    ("rotation/chain_32", "rotation/chain_8", "goal", "@chainwheel", "The Long Haul", "yellow",
     "Build a powered rotation network of 32 or more connected blocks."),
    ("rotation/chain_max", "rotation/chain_32", "challenge", "@chainwheel", "Rube Goldberg", "light_purple",
     "Build a powered rotation network of 200+ blocks, near the 256-block limit."),
    ("rotation/torque_5", "rotation/first_power", "task", "@copper_gear", "Getting Torque-y", "white",
     "Drive a network supplying 5 or more power."),
    ("rotation/torque_15", "rotation/torque_5", "goal", "@copper_gear", "Powerhouse", "yellow",
     "Drive a network supplying 15 or more power — a huge windmill's worth."),
    ("rotation/torque_30", "rotation/torque_15", "challenge", "@copper_gear", "Overdrive", "light_purple",
     "Drive a network supplying 30 or more power by stacking sources."),
    ("rotation/clutch_cut", "rotation/first_power", "task", "@clutch_fwd", "Disengage", "white",
     "Cut a running line with a Clutch's redstone signal."),
    ("rotation/reverse_spin", "rotation/first_power", "task", "@reverser_fwd", "About Face", "white",
     "Flip a running line's direction with a Reverser."),

    # ── Branch C · Structures / Moving (granted when a mechanism assembles) ──
    ("structures/first_glue", "root", "task", "minecraft:slime_ball", "Bound Together", "white",
     "Glue blocks to a Rotator or Mechanism Minecart with the Glue Brush so they move as one."),
    ("structures/assemble", "structures/first_glue", "goal", "minecraft:slime_block", "It Moves!", "yellow",
     "Assemble a glued structure into a moving mechanism."),
    ("structures/door", "structures/assemble", "goal", "@rotator_up", "Open Sesame", "yellow",
     "Swing a floor-mounted Rotator as a door."),
    ("structures/drawbridge", "structures/door", "goal", "@rotator_side", "Lower the Drawbridge", "yellow",
     "Swing a wall-mounted Rotator as a drawbridge."),
    ("structures/minecart", "structures/assemble", "challenge", "minecraft:furnace_minecart", "All Aboard", "light_purple",
     "Assemble and move a Mechanism Minecart — a structure that rides the rails."),
    ("structures/big_move", "structures/assemble", "challenge", "@rotator_side", "Moving Day", "light_purple",
     "Move a mechanism of 32 or more blocks."),
    ("structures/earthshaker", "structures/big_move", "challenge", "minecraft:slime_block", "Earthshaker", "light_purple",
     "Move a massive mechanism of 128 or more blocks."),

    # ── Branch D · Windmills (granted when the windmill tier is placed) ──
    ("windmill/plain", "root", "goal", "@windmill_item", "Windfall", "yellow",
     "Build a Windmill (1 power) — place it and let the sails spin."),
    ("windmill/large", "windmill/plain", "goal", "@windmill_item", "Catching the Breeze", "yellow",
     "Build a Large Windmill (5 power) — craft it with Large banners (needs BetterBanners)."),
    ("windmill/huge", "windmill/large", "challenge", "@windmill_item", "Lord of the Winds", "light_purple",
     "Build a Huge Windmill (15 power) — craft it with Huge banners (needs BetterBanners)."),

    # ── Branch E · Mastery capstones ──
    ("mastery/automation", "root", "challenge", "@machine_base", "Set It and Forget It", "light_purple",
     "Run a network that both makes power and drives a working machine at the same time."),
    ("mastery/grand_engineer", "root", "challenge", "@bronze_engine", "Grand Engineer", "light_purple",
     "Earn every branch capstone: Master Machinist, Rube Goldberg, Earthshaker, Lord of the Winds, and Lower the Drawbridge."),
]


def load_texture_aliases():
    """alias -> base64, parsed from rotation-blocks.yml's `textures:` map (values start with eyJ)."""
    aliases = {}
    pat = re.compile(r'^\s+([A-Za-z0-9_]+):\s*"(eyJ[A-Za-z0-9+/=]+)"')
    for line in TEXTURES_YML.read_text(encoding="utf-8").splitlines():
        m = pat.match(line)
        if m:
            aliases[m.group(1)] = m.group(2)
    return aliases


def make_icon(icon, aliases):
    if icon.startswith("@"):
        alias = icon[1:]
        b64 = aliases.get(alias)
        if b64 is None:
            raise SystemExit(f"Unknown texture alias '@{alias}' — not in {TEXTURES_YML.name}")
        return {
            "id": "minecraft:player_head",
            "components": {
                "minecraft:profile": {"properties": [{"name": "textures", "value": b64}]}
            },
        }
    return {"id": icon}


def build(node, aliases):
    path, parent, frame, icon, title, color, desc = node
    display = {
        "icon": make_icon(icon, aliases),
        "title": {"text": title, "color": color},
        "description": {"text": desc, "color": "gray"},
        "frame": frame,
        "show_toast": True,
        "announce_to_chat": True,
    }
    obj = {"display": display, "criteria": {"impossible": {"trigger": "minecraft:impossible"}}}
    if parent is None:
        display["background"] = ROOT_BACKGROUND
        display["show_toast"] = False
        display["announce_to_chat"] = False
    else:
        obj = {"parent": f"{NS}:{parent}", **obj}
    return obj


def main():
    aliases = load_texture_aliases()
    PACK_DIR.mkdir(parents=True, exist_ok=True)
    (PACK_DIR / "pack.mcmeta").write_text(
        json.dumps(PACK_MCMETA, indent=2) + "\n", encoding="utf-8")
    count = 0
    for node in NODES:
        path = node[0]
        obj = build(node, aliases)
        out = ADV_DIR / f"{path}.json"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(obj, indent=2) + "\n", encoding="utf-8")
        count += 1
    print(f"wrote pack.mcmeta + {count} advancement files under {PACK_DIR} "
          f"({len(aliases)} texture aliases resolved)")


if __name__ == "__main__":
    main()
