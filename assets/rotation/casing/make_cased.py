#!/usr/bin/env python3
"""Generate cased plank variants.

For each texture in planks/, produce cased/<name>.png where the left half is
the plain plank and the right half is taken from cased-planks.png.
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

for src in sorted(planks_dir.glob("*.png")):
    plank = Image.open(src).convert("RGBA")
    out = Image.new("RGBA", (w, h))
    out.paste(plank.crop((0, 0, mid, h)), (0, 0))    # left = plain plank
    out.paste(cased.crop((mid, 0, w, h)), (mid, 0))  # right = cased-planks
    out.save(cased_out / src.name)
    print(f"wrote cased/{src.name}")
