#!/usr/bin/env python3
"""Generate chassis (spiked casing) plank variants.

For each texture in planks/, produce chassis/<name>.png: the plain plank with
chassis-mask.png's frame+spikes baked on top, all in the head's BASE layer (x 0-32).
The hat layer (x 32-64) is left empty on purpose — same reasoning as make_cased.py
(a hat cube is inflated 17/16, so at the casing's 2.006 scale it would double every
seam on a wall). chassis-mask.png shares cased-planks.png's layout: the frame lives
in the mask's hat half (x + 32), which composites straight onto the plank.
"""
from pathlib import Path
from PIL import Image

HERE = Path(__file__).parent
planks_dir = HERE / "planks"
chassis_out = HERE / "chassis"
chassis_out.mkdir(exist_ok=True)

mask = Image.open(HERE / "chassis-mask.png").convert("RGBA")
w, h = mask.size
mid = w // 2

# The frame+spikes outline lives in the mask's hat half, which shares the base layer's UV layout
# (same pixels, x + 32) — so it composites straight onto the plank with no remapping.
frame = mask.crop((mid, 0, w, h))

for src in sorted(planks_dir.glob("*.png")):
    plank = Image.open(src).convert("RGBA")
    out = Image.new("RGBA", (w, h))
    out.paste(plank.crop((0, 0, mid, h)), (0, 0))  # base layer = plain plank
    out.alpha_composite(frame, (0, 0))             # ...with the frame+spikes over it; gaps show plank
    out.save(chassis_out / src.name)
    print(f"wrote chassis/{src.name}")
