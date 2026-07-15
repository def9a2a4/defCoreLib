"""One-shot: replace the single mech:casing entry with 12 per-wood variants."""
from pathlib import Path

YML = Path("src/main/resources/rotation-blocks.yml")
text = YML.read_text()

WOODS = ["oak", "acacia", "bamboo", "birch", "cherry", "crimson", "dark_oak",
         "jungle", "mangrove", "pale_oak", "spruce", "warped"]

HEADER = """\
  # ─── Mechanical Casing ────────────────────────────────────────────────────
  # A full solid block that auto-glues to adjacent blocks like a vanilla slime block: any casing in a
  # moving contraption drags its cardinal neighbours (transitively through other casings). Under the
  # hood it is a real `<wood>_PLANKS` bare block (one variant per plank wood) — its identity lives in
  # the display-backed bare-block registry (durable chunk PDC + the tagged shell display), not a
  # block-entity PDC. Movable by mechanisms; ordinary to drill/break. Slime propagation lives in
  # CasingExpansion, hooked into every mover (piston / rotator / door / minecart / chain hoist).
  # No Java overlay — pure data + base_block.
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
    mat = wood.upper() + "_PLANKS"
    extra = OAK_NOTES if wood == "oak" else "    catalog_variant_of: casing_oak\n"
    return f"""\
  casing_{wood}:
    name: "&f{title} Mechanical Casing"
    lore:
      - "&7Sticks to adjacent blocks like slime —"
      - "&7move it with any mechanism"
{extra}\
    base_block: {mat}
    texture: "@casing_{wood}"
    drops: self
    default_state: idle
    states:
      idle:
        display_entities:
          - texture: "@casing_{wood}"
            tag: shell
            # 1.888 = 2.006 × 16/17: shell half-extent 0.472 < 0.5, so the matching base
            # plank shows as a thin inset frame around the cased face. Intentional (user-
            # ratified) — do not "fix" back to full-cover 2.006.
            transform: {{ translation: [0, 0.501, 0], scale: [1.888, 1.888, 1.888] }}
    recipes:
      craft:
        shaped:
          - pattern: ["N N", " P ", "N N"]   # X: iron-nugget corners, plank center (edges empty)
            key:
              N: {{ material: IRON_NUGGET }}
              P: {{ material: {mat} }}
"""


new_block = HEADER + "\n".join(entry(w) for w in WOODS)

start = text.index("  # ─── Mechanical Casing")
end = text.index("  # ─── Millstone")
# keep exactly one blank line between the last casing entry and the millstone header
text = text[:start] + new_block + "\n" + text[end:]
YML.write_text(text)
print("replaced; casing entries now:", text.count("  casing_"))
