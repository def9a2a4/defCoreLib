#!/usr/bin/env python3
"""Generate cased plank variants.

For each texture in planks/, produce cased/<name>.png: the plain plank with
cased-planks.png's frame baked on top, all in the head's BASE layer (x 0-32).
The hat layer (x 32-64) is left empty on purpose — see below.
"""
from pathlib import Path
from PIL import Image

HERE = Path(__file__).parent
planks_dir = HERE / "planks"
cased_out = HERE / "cased"
cased_out.mkdir(exist_ok=True)

cased = Image.open(HERE / "cased-planks.png").convert("RGBA")
w, h = cased.size
mid = w // 2

# The frame outline lives in cased-planks' hat half, which shares the base layer's UV layout
# (same pixels, x + 32) — so it composites straight onto the plank with no remapping.
frame = cased.crop((mid, 0, w, h))

for src in sorted(planks_dir.glob("*.png")):
    plank = Image.open(src).convert("RGBA")
    out = Image.new("RGBA", (w, h))
    out.paste(plank.crop((0, 0, mid, h)), (0, 0))  # base layer = plain plank
    out.alpha_composite(frame, (0, 0))             # ...with the frame over it; gaps show plank
    # The hat half stays transparent, and must: a skull's hat cube is inflated to 17/16 of its
    # base, so at the casing's scale of 2.006 a hat would render at half-extent 0.5328 and poke
    # 0.033 into every neighbour, doubling each seam line on a wall. Single layer is what buys
    # the 2.006 that covers the stair — restoring a hat means reverting the scale to 1.888.
    out.save(cased_out / src.name)
    print(f"wrote cased/{src.name}")
