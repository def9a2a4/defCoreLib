#!/usr/bin/env python3
"""Generate gearbox (holed casing) plank variants.

For each casing skin in cased/, produce gearbox/<name>.png: the finished casing texture with
gearbox-mask.png composited straight on top. The mask is mostly transparent — it only adds a small
patch of dark pixels at the centre of each of the head's six faces, reading in-world as a shaft
socket ("hole") on every axis.

Unlike make_cased.py / make_chassis.py, this composites over the ALREADY-CASED texture (cased/), not
the plain plank: a gearbox is exactly a casing plus holes. And unlike those masks, gearbox-mask.png's
pixels already sit in base-layer face-centre coordinates (x 0-32), so it drops straight on with no
crop/remap. The hat half (x 32-64) stays empty, same as the casing (see make_cased.py for why).
"""
from pathlib import Path
from PIL import Image

HERE = Path(__file__).parent
cased_dir = HERE / "cased"
gearbox_out = HERE / "gearbox"
gearbox_out.mkdir(exist_ok=True)

mask = Image.open(HERE / "gearbox-mask.png").convert("RGBA")

for src in sorted(cased_dir.glob("*.png")):
    cased = Image.open(src).convert("RGBA")
    out = cased.copy()
    out.alpha_composite(mask)                      # dark hole at each face centre; rest unchanged
    out.save(gearbox_out / src.name)
    print(f"wrote gearbox/{src.name}")
