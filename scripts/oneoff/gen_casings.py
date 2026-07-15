"""One-shot: (re)generate the 12 per-wood mech:casing_<wood> entries in rotation-blocks.yml.

Rewrites everything between the "Mechanical Casing" and "Millstone" section headers, so it is safe
to re-run after editing the template below.
"""
from pathlib import Path

YML = Path("src/main/resources/rotation-blocks.yml")
text = YML.read_text()

WOODS = ["oak", "acacia", "bamboo", "birch", "cherry", "crimson", "dark_oak",
         "jungle", "mangrove", "pale_oak", "spruce", "warped"]

# Every copy pinned identical: uniform half+facing is what keeps vanilla's stair-shape rule from
# cornering casings against each other, and half=top makes ordinary right-side-up stairs ignore them.
BASE_DATA = "[half=top,facing=north,shape=straight,waterlogged=false]"

HEADER = """\
  # ─── Mechanical Casing ────────────────────────────────────────────────────
  # A block that auto-glues to adjacent blocks like a vanilla slime block: any casing in a moving
  # contraption drags its cardinal neighbours (transitively through other casings). Slime propagation
  # lives in CasingExpansion, hooked into every mover (piston / rotator / door / minecart / chain
  # hoist). Movable by mechanisms; ordinary to drill/break. No Java overlay — pure data.
  #
  # Under the hood each variant is a real upside-down `<wood>_STAIRS` bare block — identity in the
  # display-backed bare-block registry (durable chunk PDC + the tagged shell display), not a
  # block-entity PDC. Stairs rather than planks because a stair does NOT occlude, and a display entity
  # samples light at its own cell: inside an opaque plank the shell rendered pitch black. (The dynamo
  # hits the same wall and buys out with setBrightness(15,15) — fine for one machine block, a glowing
  # wall for something you place by the hundred.) The price is a 3/4 collider and a casing wall that
  # casts no shade: "blocks light" and "a display inside it is lit" are the same property, so you
  # cannot have both. base_block_data pins every copy to the same upside-down straight stair; see
  # BASE_DATA in scripts/oneoff/gen_casings.py.
"""

OAK_NOTES = """\
    catalog_notes:
      - "&fA full block that auto-glues to every adjacent block like a slime block — build a moving contraption without the glue brush."
      - "&7Any casing carried by a piston, rotator, minecart, or chain hoist drags its neighbours along, spreading through other casings."
      - "&7A vanilla piston moves a lone casing as an ordinary block (no slime spread)."
      - "&7Comes in every plank wood — the center plank of the recipe picks the variant."
"""


def entry(wood: str) -> str:
    title = wood.replace("_", " ").title()
    planks = wood.upper() + "_PLANKS"   # the recipe ingredient — picks the variant
    stairs = wood.upper() + "_STAIRS"   # the block under the hood
    extra = OAK_NOTES if wood == "oak" else "    catalog_variant_of: casing_oak\n"
    return f"""\
  casing_{wood}:
    name: "&f{title} Mechanical Casing"
    lore:
      - "&7Sticks to adjacent blocks like slime —"
      - "&7move it with any mechanism"
{extra}\
    base_block: {stairs}
    base_block_data: "{BASE_DATA}"
    texture: "@casing_{wood}"
    drops: self
    default_state: idle
    states:
      idle:
        display_entities:
          - texture: "@casing_{wood}"
            tag: shell
            # 1.888 = 2.006 × 16/17, and 17/16 is exactly the skull hat layer's inflate (8.5px vs 8):
            # so the hat — which carries the frame art — lands flush at half-extent 0.5015 and
            # neighbouring casings' frames meet cleanly instead of each poking 0.033 into the next.
            # Do not "fix" to 2.006: that covers the stair, but doubles every seam line on a wall.
            transform: {{ translation: [0, 0.501, 0], scale: [1.888, 1.888, 1.888] }}
    recipes:
      craft:
        shaped:
          - pattern: ["N N", " P ", "N N"]   # X: iron-nugget corners, plank center (edges empty)
            key:
              N: {{ material: IRON_NUGGET }}
              P: {{ material: {planks} }}
"""


new_block = HEADER + "\n".join(entry(w) for w in WOODS)

start = text.index("  # ─── Mechanical Casing")
end = text.index("  # ─── Millstone")
# keep exactly one blank line between the last casing entry and the millstone header
text = text[:start] + new_block + "\n" + text[end:]
YML.write_text(text)
print("replaced; casing entries now:", text.count("  casing_"))
