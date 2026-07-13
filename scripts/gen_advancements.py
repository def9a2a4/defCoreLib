#!/usr/bin/env python3
"""Generate the `mech` advancement datapack JSON tree.

Single source of truth for the tree. Re-run to regenerate all advancement JSONs.
Grant logic (which milestone grants which node) lives in Java (MechAdvancements.java);
these files only define display + parent + the impossible criterion.
"""
import json, os, pathlib

PACK_DIR = pathlib.Path(__file__).resolve().parent.parent / \
    "mech/src/main/resources/mech_datapack"
ADV_DIR = PACK_DIR / "data/mech/advancement"

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

# (path, parent, frame, icon, title, color, description)
# parent == None -> root. color applies to the title.
NODES = [
    # ── ROOT ──
    ("root", None, "task", "minecraft:copper_block", "The Mechanism", "gold",
     "The fluorescent hum of turning copper."),

    # ── Branch A · Discovery / Crafting ──
    ("craft/bearing", "root", "task", "minecraft:copper_ingot", "First Bearing", "white",
     "Every machine starts here."),
    ("craft/wrench", "craft/bearing", "task", "minecraft:golden_axe", "Right Tool for the Job", "white",
     "Turn it to face the world."),
    ("craft/glue_brush", "craft/bearing", "task", "minecraft:slime_ball", "Stuck On You", "white",
     "Choose what moves."),
    ("craft/shaft", "craft/bearing", "task", "minecraft:lightning_rod", "First Shaft", "white",
     "A straight line of power."),
    ("craft/gear", "craft/shaft", "task", "minecraft:copper_ingot", "Turning the Corner", "white",
     "Power learns to turn."),
    ("craft/water_wheel", "craft/shaft", "task", "minecraft:oak_slab", "Paddle Power", "white",
     "The river does the work."),
    ("craft/windmill_item", "craft/shaft", "task", "minecraft:white_banner", "Tilting at Windmills", "white",
     "Four sails and a bearing."),
    ("craft/clutch", "craft/gear", "task", "minecraft:copper_block", "Clutch Play", "white",
     "Cut the line on command."),
    ("craft/reverser", "craft/gear", "task", "minecraft:copper_block", "Reverse Course", "white",
     "Same source, opposite spin."),
    ("craft/drill", "craft/gear", "task", "minecraft:diamond", "Boring Work", "white",
     "It eats through stone."),
    ("craft/millstone", "craft/gear", "task", "minecraft:smooth_stone_slab", "Grind House", "white",
     "Two stones, endless dust."),
    ("craft/fan", "craft/gear", "task", "minecraft:white_banner", "Fan Favourite", "white",
     "A beam of pure wind."),
    ("craft/press", "craft/millstone", "task", "minecraft:piston", "Under Pressure", "white",
     "Squeeze out the good stuff."),
    ("craft/placer", "craft/drill", "task", "minecraft:dispenser", "Autoplacement", "white",
     "Let the machine build."),
    ("craft/motor", "craft/water_wheel", "task", "minecraft:redstone", "Off the Grid", "white",
     "Redstone into rotation."),
    ("craft/engine", "craft/water_wheel", "goal", "minecraft:blast_furnace", "Full Steam", "yellow",
     "Fuel in, power out."),
    ("craft/master_machinist", "craft/engine", "challenge", "minecraft:copper_block", "Master Machinist", "light_purple",
     "One of every machine on the wall."),

    # ── Branch B · Rotation Power ──
    ("rotation/first_power", "root", "goal", "minecraft:copper_bulb", "It Lives", "yellow",
     "The shaft begins to spin."),
    ("rotation/wind_power", "rotation/first_power", "task", "minecraft:white_banner", "Winds of Change", "white",
     "Free power on the breeze."),
    ("rotation/water_power", "rotation/first_power", "task", "minecraft:water_bucket", "Go With the Flow", "white",
     "The current turns the wheel."),
    ("rotation/engine_power", "rotation/first_power", "goal", "minecraft:blast_furnace", "Ignition", "yellow",
     "Burn fuel, make torque."),
    ("rotation/chain_8", "rotation/first_power", "task", "minecraft:lightning_rod", "Power Line", "white",
     "Eight blocks turning as one."),
    ("rotation/chain_32", "rotation/chain_8", "goal", "minecraft:lightning_rod", "The Long Haul", "yellow",
     "Route it across the base."),
    ("rotation/chain_max", "rotation/chain_32", "challenge", "minecraft:lightning_rod", "Rube Goldberg", "light_purple",
     "Right up against the limit."),
    ("rotation/torque_5", "rotation/first_power", "task", "minecraft:copper_block", "Getting Torque-y", "white",
     "Real load, real power."),
    ("rotation/torque_15", "rotation/torque_5", "goal", "minecraft:gold_block", "Powerhouse", "yellow",
     "A huge windmill's worth of grunt."),
    ("rotation/torque_30", "rotation/torque_15", "challenge", "minecraft:netherite_block", "Overdrive", "light_purple",
     "Stack the sources."),
    ("rotation/clutch_cut", "rotation/first_power", "task", "minecraft:redstone", "Disengage", "white",
     "Kill the branch mid-spin."),
    ("rotation/reverse_spin", "rotation/first_power", "task", "minecraft:copper_block", "About Face", "white",
     "Flip the whole line."),

    # ── Branch C · Structures / Moving ──
    ("structures/first_glue", "root", "task", "minecraft:slime_ball", "Bound Together", "white",
     "These blocks move as one now."),
    ("structures/assemble", "structures/first_glue", "goal", "minecraft:slime_block", "It Moves!", "yellow",
     "The structure comes alive."),
    ("structures/door", "structures/assemble", "goal", "minecraft:oak_door", "Open Sesame", "yellow",
     "A door with a spine of copper."),
    ("structures/drawbridge", "structures/door", "goal", "minecraft:oak_trapdoor", "Lower the Drawbridge", "yellow",
     "Cross when you say so."),
    ("structures/minecart", "structures/assemble", "challenge", "minecraft:furnace_minecart", "All Aboard", "light_purple",
     "Ride your own moving room."),
    ("structures/big_move", "structures/assemble", "challenge", "minecraft:piston", "Moving Day", "light_purple",
     "Thirty-two blocks in motion."),
    ("structures/earthshaker", "structures/big_move", "challenge", "minecraft:slime_block", "Earthshaker", "light_purple",
     "The ground itself gives way."),

    # ── Branch D · Windmills ──
    ("windmill/plain", "root", "goal", "minecraft:white_banner", "Windfall", "yellow",
     "Set the sails spinning."),
    ("windmill/large", "windmill/plain", "goal", "minecraft:light_gray_banner", "Catching the Breeze", "yellow",
     "Bigger sails, bigger bite."),
    ("windmill/huge", "windmill/large", "challenge", "minecraft:cyan_banner", "Lord of the Winds", "light_purple",
     "It blots out the sun."),

    # ── Branch E · Mastery capstones ──
    ("mastery/automation", "root", "challenge", "minecraft:dispenser", "Set It and Forget It", "light_purple",
     "A source feeds a working machine, untended."),
    ("mastery/grand_engineer", "root", "challenge", "minecraft:netherite_block", "Grand Engineer", "light_purple",
     "Master of every craft."),
]

ROOT_BACKGROUND = "minecraft:textures/block/copper_block.png"


def build(node):
    path, parent, frame, icon, title, color, desc = node
    display = {
        "icon": {"id": icon},
        "title": {"text": title, "color": color},
        "description": {"text": desc, "color": "gray", "italic": True},
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
    PACK_DIR.mkdir(parents=True, exist_ok=True)
    (PACK_DIR / "pack.mcmeta").write_text(
        json.dumps(PACK_MCMETA, indent=2) + "\n", encoding="utf-8")
    count = 0
    for node in NODES:
        path = node[0]
        obj = build(node)
        out = ADV_DIR / f"{path}.json"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(obj, indent=2) + "\n", encoding="utf-8")
        count += 1
    print(f"wrote pack.mcmeta + {count} advancement files under {PACK_DIR}")


if __name__ == "__main__":
    main()
