"""One-shot: (re)generate the 36 per-wood land-vehicle wheels in rotation-blocks.yml.

Three sizes (mech:wheel_<wood>, mech:large_wheel_<wood>, mech:huge_wheel_<wood>) in the 12 casing
woods. The planks in the recipe pick the wood, and the wood picks the slab the tyre is built from.
The windmill in the recipe picks the size, the same way it picks the propeller tier
(CoreLibPlugin.capturePropulsionResult), so only the small wheels carry a YAML recipe.

Rewrites everything from the "Wheels" section header to the end of the file, so it is safe to
re-run after editing the template below.

Run from the repo root:  python3 scripts/oneoff/gen_wheels.py
"""
from pathlib import Path

YML = Path(__file__).resolve().parents[2] / "src/main/resources/rotation-blocks.yml"
HEADER = "  # ─── Wheels ───"

WOODS = ["oak", "acacia", "bamboo", "birch", "cherry", "crimson", "dark_oak",
         "jungle", "mangrove", "pale_oak", "spruce", "warped"]

# size id prefix, display prefix, diameter, power, step height, tyre reach below the block, spin
# speed (deg/tick, falls with diameter so the tread speed reads the same), in-hand skin alias,
# windmill that crafts it
SIZES = [
    ("wheel",       "",       1, 2,  "1 block",    "0 blocks",   6, "@wheel_item",       "Windmill"),
    ("large_wheel", "Large ", 2, 5,  "1.5 blocks", "0.5 blocks", 3, "@large_wheel_item", "Large Windmill"),
    ("huge_wheel",  "Huge ",  3, 10, "2 blocks",   "1 block",    2, "@large_wheel_item", "Huge Windmill"),
]

THICK = 0.4        # tyre thickness along the axle
NUDGE = 0.01       # axle offset between the three parts, so their flat faces never z-fight
WALL_OFFSET = 0.25


def title(wood: str) -> str:
    return " ".join(w.capitalize() for w in wood.split("_"))


def fmt(x: float) -> str:
    s = f"{x:.4f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def parts(d: float):
    """The tyre as three slabs in the wheel plane: a horizontal bar, a vertical bar and a centre
    square. Bars are as wide as an octagon side (d / (1 + sqrt 2)) so their ends are the octagon's
    four straight edges; the square's corners sit on its four diagonal edges."""
    side = d * 0.4142
    sq = d * 0.7071
    return [("tyre_h", d, side, -NUDGE), ("tyre_v", side, d, 0.0), ("tyre_c", sq, sq, NUDGE)]


def display_line(slab: str, tag: str, w: float, h: float, nudge: float, axle: str) -> str:
    # A slab item renders in the bottom half of its cell (y -0.5..0). Its thin axis is turned onto
    # the axle so the plank face shows on the side of the wheel; scale 2*THICK on that axis gives the
    # thickness, and the translation re-centres it on the axle (plus the z-fight nudge).
    c = THICK / 2 + nudge
    if axle == "z":   # n/s walls: wheel plane XY
        tr, sc, rot = [0, 0, c], [w, THICK * 2, h], "[90, 1, 0, 0]"
    else:             # e/w walls: wheel plane YZ
        tr, sc, rot = [c, 0, 0], [h, THICK * 2, w], "[-90, 0, 0, 1]"
    return ("          - { material: %s, tag: %s, transform: { translation: [%s], scale: [%s], "
            "left_rotation: %s }, wall_offset: %s }"
            % (slab, tag, ", ".join(fmt(v) for v in tr), ", ".join(fmt(v) for v in sc), rot, fmt(WALL_OFFSET)))


def block(size, wood: str) -> str:
    sid, sname, d, power, step, reach, speed, item_tex, windmill = size
    bid = f"{sid}_{wood}"
    slab = f"{wood.upper()}_SLAB"
    planks = f"{wood.upper()}_PLANKS"
    out = [f"  {bid}:",
           "    cancel_pistons: true",
           "    categories: [blockships]",
           f'    name: "&f{sname}{title(wood)} Wheel"',
           "    lore:",
           f'      - "&7Consumes {power} Power"',
           '      - "&7Drives a vehicle on land"',
           "    catalog_notes:",
           '      - "&fA powered wheel. Mount it on the left or right side of a vehicle; wheels on the front"',
           '      - "&for back face do nothing. Powered wheels drive the vehicle on land; unpowered ones still roll."',
           '      - "&fIt takes power through the block it hangs on: run a shaft or engine along its axle."',
           f'      - "&7Draws {power} power while running — it produces none."',
           f'      - "&7Climbs steps up to {step} high. The tyre reaches {reach} below the block it hangs on."',
           f'      - "&7Craft from a {windmill} with four matching planks around it; the planks pick the wood."',
           '      - "&7Wall placement only."']
    if (sid, wood) != ("wheel", "oak"):
        out.append(f"    catalog_variant_of: {sid}_oak")
    if sid == "wheel":
        out += ["    recipes:",
                "      craft:",
                "        shaped:",
                "          # The windmill decides the size (windmill / large / huge, swapped in",
                "          # CoreLibPlugin.capturePropulsionResult); block_type is the tolerant match a",
                "          # bladed windmill needs. The planks must all be this wood.",
                '          - pattern: [" P ", "PWP", " P "]',
                "            key:",
                f"              P: {{ material: {planks} }}",
                '              W: { block_type: "mech:windmill" }']
    out += ['    texture: "@prop_bearing_fwd"',
            f'    item_texture: "{item_tex}"',
            "    drops: self",
            "    break_sound: { sound: block.wood.break, volume: 0.8, pitch: 0.8 }",
            "    place_sound: { sound: block.wood.place, volume: 0.8, pitch: 0.8 }",
            "    default_state: idle_wall_n",
            "    placement:",
            "      # Walls only: the axle must be horizontal. A floor/ceiling click is refused (as for the millstone).",
            "      allowed_faces: [NORTH, SOUTH, EAST, WEST]",
            "    placement_state_map:",
            "      NORTH: idle_wall_s",
            "      SOUTH: idle_wall_n",
            "      EAST: idle_wall_e",
            "      WEST: idle_wall_w",
            "    states:"]
    for face, axle, axis in (("n", "z", "[0, 0, 1]"), ("s", "z", "[0, 0, 1]"),
                             ("e", "x", "[1, 0, 0]"), ("w", "x", "[1, 0, 0]")):
        out += [f"      idle_wall_{face}:",
                '        texture: "@prop_bearing_fwd"',
                "        display_entities:"]
        out += [display_line(slab, tag, w, h, nudge, axle) for tag, w, h, nudge in parts(d)]
        out.append(f'      spinning_wall_{face}: {{ copy_from: idle_wall_{face}, texture: "@prop_bearing_fwd", '
                   f'animation: {{ type: rotate, axis: {axis}, speed: {speed} }} }}')
    return "\n".join(out)


SECTION_COMMENT = """\
  # ─── Wheels ───────────────────────────────────────────────────────────────────
  # Generated by scripts/oneoff/gen_wheels.py — edit the template there, don't hand-edit here.
  # Land-vehicle drive wheels for BlockShips. Rotation CONSUMERS that do no world work, like the
  # propellers: BlockShips reads whether each one is powered and turns that into ground thrust, and uses
  # the wheel's size for ride height and step height. Wall mounts only, so the axle is horizontal.
  # Every size comes in the 12 casing woods; Java strips the wood (RotationBlocks.wheelSize) wherever
  # power, mass, load mode or mechanism metadata are looked up, so those are set once per size.
  #
  # Model: the propeller's placed head, plus a tyre of three wooden slabs (a horizontal bar, a vertical
  # bar and a centre square) whose outline is an octagon, 0.4 thick along the axle so the head stays
  # visible behind it. The parts are nudged 0.01 apart along the axle so their faces don't z-fight.
  # Every part is centred ON the spin axis, so it spins in place; wall_offset (applied outside the
  # animation) pushes the wheel outboard of the hull.
"""


def main():
    text = YML.read_text()
    i = text.index(HEADER)
    body = "\n\n".join(block(size, wood) for size in SIZES for wood in WOODS)
    YML.write_text(text[:i] + SECTION_COMMENT + body + "\n")
    print(f"wrote {len(SIZES) * len(WOODS)} wheels to {YML}")


if __name__ == "__main__":
    main()
