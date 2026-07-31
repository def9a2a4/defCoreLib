"""One-shot: (re)generate the 12 per-wood mech:chassis_<wood> entries in rotation-blocks.yml.

Rewrites everything between the "Mechanical Chassis" and "Millstone" section headers, so it is safe
to re-run after editing the template below. A chassis is a casing that ALSO grabs every adjacent
block like slime (StickySpread.Family.CHASSIS) while still joining any casing frame — the recipe
rings an existing casing with iron-nugget "spikes". Mirrors gen_casings.py (same upside-down stair
base, same display transform); differs in id/texture/recipe/notes.

Depends on gen_casings.py's end marker having been pointed at "# ─── Mechanical Chassis" so the two
generators no longer fight over the same span.
"""
from pathlib import Path

YML = Path("src/main/resources/rotation-blocks.yml")
text = YML.read_text()

WOODS = ["oak", "acacia", "bamboo", "birch", "cherry", "crimson", "dark_oak",
         "jungle", "mangrove", "pale_oak", "spruce", "warped"]

# Every copy pinned identical: uniform half+facing is what keeps vanilla's stair-shape rule from
# cornering chassis against each other, and half=top makes ordinary right-side-up stairs ignore them.
BASE_DATA = "[half=top,facing=north,shape=straight,waterlogged=false]"

HEADER = """\
  # ─── Mechanical Chassis ───────────────────────────────────────────────────
  # A casing that ALSO auto-glues to every adjacent block like a slime block: a chassis in a moving
  # contraption drags all its cardinal neighbours (world blocks, machines, and other casings alike),
  # while still counting as part of any casing frame (casings bond to it and it to them). Slime reach
  # with casing-frame membership — see StickySpread.Family.CHASSIS, hooked into every mover (piston /
  # rotator / door / minecart / chain hoist). Movable by mechanisms; ordinary to drill/break. No Java
  # overlay — pure data. Same upside-down `<wood>_STAIRS` bare block and display transform as the
  # casing (see the Mechanical Casing header for why a stair, not a plank); the texture bakes the
  # casing frame plus spikes (assets/rotation/casing/make_chassis.py, chassis-mask.png).
"""

OAK_NOTES = """\
    catalog_notes:
      - "&fA super-casing: grabs every adjacent MOVABLE block except honey (slime parity) — a moving chassis drags its whole payload, no glue brush."
      - "&7Wood-picky for frames: it joins casings, gearboxes and chassis of the SAME wood only, and ignores a different-wood frame."
      - "&7A plain block never drags the chassis itself; a vanilla piston moves a lone chassis as an ordinary block."
      - "&7Craft by ringing a Mechanical Casing with iron nuggets — the casing's wood picks the variant."
"""


def entry(wood: str) -> str:
    title = wood.replace("_", " ").title()
    stairs = wood.upper() + "_STAIRS"   # the block under the hood
    extra = OAK_NOTES if wood == "oak" else "    catalog_variant_of: chassis_oak\n"
    return f"""\
  chassis_{wood}:
    name: "&f{title} Mechanical Chassis"
    lore:
      - "&7Grabs every movable block like slime,"
      - "&7joins same-wood casing frames"
{extra}\
    base_block: {stairs}
    base_block_data: "{BASE_DATA}"
    texture: "@chassis_{wood}"
    drops: self
    default_state: idle
    states:
      idle:
        display_entities:
          - texture: "@chassis_{wood}"
            tag: shell
            # Identical to the casing shell — see the Mechanical Casing entry for the derivation of
            # this transform (half-extent 0.5015 over the stair face, hat layer left empty by
            # make_chassis.py so a wall does not double every seam).
            transform: {{ translation: [0, 0.501, 0], scale: [2.006, 2.006, 2.006] }}
    recipes:
      craft:
        shaped:
          - pattern: ["NNN", "NCN", "NNN"]   # iron-nugget "spikes" ringing a casing of the same wood
            key:
              N: {{ material: IRON_NUGGET }}
              C: {{ block: casing_{wood} }}
"""


new_block = HEADER + "\n".join(entry(w) for w in WOODS)

end = text.index("  # ─── Millstone")
if "  # ─── Mechanical Chassis" in text:
    start = text.index("  # ─── Mechanical Chassis")   # re-run: replace the existing section
else:
    start = end                                        # first run: insert just before Millstone
# keep exactly one blank line between the last chassis entry and the millstone header
text = text[:start] + new_block + "\n" + text[end:]
YML.write_text(text)
print("replaced; chassis entries now:", text.count("  chassis_"))
