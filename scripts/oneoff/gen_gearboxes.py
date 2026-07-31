"""One-shot: (re)generate the 12 per-wood mech:gearbox_<wood> entries in rotation-blocks.yml.

A gearbox looks like the matching casing but with a dark shaft socket ("hole") at the centre of each
face — the @gearbox_<wood> skin is the casing skin (cased/<wood>.png) with gearbox-mask.png composited
on top (see assets/rotation/casing/make_gearbox.py). It transmits rotation power in all directions
(Java overlay in RotationBlocks + RotationNetwork gearbox edge) and auto-glues to casings (StickySpread
CASING family via the mech:gearbox_ prefix).

Rewrites everything between the "Gearbox" and "Chain Pulley" section headers, so it is safe to re-run
after editing the template below. If the "Gearbox" header is absent, inserts a fresh section directly
before "Chain Pulley" (kept out of the casing generator's Casing..Millstone range on purpose).
"""
from pathlib import Path

YML = Path("src/main/resources/rotation-blocks.yml")
text = YML.read_text()

WOODS = ["oak", "acacia", "bamboo", "birch", "cherry", "crimson", "dark_oak",
         "jungle", "mangrove", "pale_oak", "spruce", "warped"]

# Identical to the casing: pins every copy to the same upside-down straight stair (see gen_casings.py).
BASE_DATA = "[half=top,facing=north,shape=straight,waterlogged=false]"

HEADER = """\
  # ─── Gearbox ──────────────────────────────────────────────────────────────
  # A mechanical casing that also transmits rotation power in EVERY direction: on each of its six
  # faces it couples (spin-preserving) to an aligned shaft/gear or another gearbox. Behaviour is a
  # Java overlay (RotationBlocks.overlayStandard gearbox=true → RotationNetwork gearbox edge, mirrored
  # in RotationSolver for moving contraptions). Like a casing it auto-glues to casings/gearboxes
  # (StickySpread CASING family, keyed on the mech:gearbox_ prefix).
  #
  # Display = a single casing-style shell on the same upside-down <wood>_STAIRS bare block (two bare
  # types share one base material; identity is per-cell). The @gearbox_<wood> skin is the casing skin
  # plus a dark socket at each face centre (assets/rotation/casing/gearbox-mask.png composited over
  # cased/<wood>.png — see make_gearbox.py). No rods, no spinning state — the "shafts" are the holes.
  # Generated — edit the template, don't hand-edit here.
"""

OAK_NOTES = """\
    catalog_notes:
      - "&fA power hub: couples any aligned shafts or gears on all six faces, passing spin straight through without reversing."
      - "&7Use it to split one input into several directions, or bridge shafts around a corner without a gear's counter-rotation."
      - "&7A frame block like a casing: auto-glues only to SAME-WOOD frame blocks (casings, gearboxes, chassis); brush-glue to pin it to anything else."
      - "&7Comes in every plank wood — the casing in the recipe picks the variant."
"""


def entry(wood: str) -> str:
    title = wood.replace("_", " ").title()
    stairs = wood.upper() + "_STAIRS"   # the block under the hood (same as the casing)
    extra = OAK_NOTES if wood == "oak" else "    catalog_variant_of: gearbox_oak\n"
    return f"""\
  gearbox_{wood}:
    name: "&f{title} Gearbox"
    lore:
      - "&7Transmits power in every direction"
      - "&7Frame block: same-wood auto-glue"
{extra}\
    base_block: {stairs}
    base_block_data: "{BASE_DATA}"
    texture: "@gearbox_{wood}"
    drops: self
    default_state: idle
    states:
      idle:
        display_entities:
          # Shell — the casing shell verbatim, but skinned @gearbox_{wood} (casing + hole per face).
          - texture: "@gearbox_{wood}"
            tag: shell
            transform: {{ translation: [0, 0.501, 0], scale: [2.006, 2.006, 2.006] }}
    recipes:
      craft:
        shaped:
          # Casing core, gears on three sides, shaft on top. The casing wood picks the variant.
          - pattern: [" S ", "GCG", " G "]
            key:
              S: {{ block: "mech:shaft" }}
              G: {{ block: "mech:gear" }}
              C: {{ block: "mech:casing_{wood}" }}
"""


new_block = HEADER + "\n".join(entry(w) for w in WOODS)

end = text.index("  # ─── Chain Pulley")
if "  # ─── Gearbox" in text:
    start = text.index("  # ─── Gearbox")
else:
    start = end   # fresh insert directly before Chain Pulley
# keep exactly one blank line between the last gearbox entry and the Chain Pulley header
text = text[:start] + new_block + "\n" + text[end:]
YML.write_text(text)
print("replaced; gearbox entries now:", text.count("  gearbox_"))
