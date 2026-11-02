#!/usr/bin/env python3
"""Generate the per-wood in-hand wheel skins.

For each plank texture in ../casing/planks/ and each wheel size, produce <size>/<wood>.png: the plain
plank with that size's mask composited on top, all in the head's BASE layer (x 0-32). The tyre is
built from the wood's slabs, so the item reads as a wooden block with a metal hub on its two flat
(axle) faces; the hub shrinks as the wheel grows.

Like make_gearbox.py, the masks' hub pixels already sit at base-layer face-centre coordinates, so they
drop straight on with no remap. Only the head's rows (y 0-16) are used: below them the masks carry the
full wheel art the hubs were cut from, which is reference only. The hat half (x 32-64) stays empty,
same as the casing (see ../casing/make_cased.py for why).
"""
from pathlib import Path
from PIL import Image

HERE = Path(__file__).parent
planks_dir = HERE.parent / "casing" / "planks"

# output dir (= block id prefix) -> mask
SIZES = {"wheel": "wheel-mask-small.png", "large_wheel": "wheel-mask-medium.png",
         "huge_wheel": "wheel-mask-large.png"}
HEAD_ROWS = 16

for size, mask_name in SIZES.items():
    mask = Image.open(HERE / mask_name).convert("RGBA")
    hub = mask.crop((0, 0, mask.width, HEAD_ROWS))
    out_dir = HERE / size
    out_dir.mkdir(exist_ok=True)
    for src in sorted(planks_dir.glob("*.png")):
        plank = Image.open(src).convert("RGBA")
        out = Image.new("RGBA", plank.size)
        out.paste(plank.crop((0, 0, plank.width // 2, plank.height)), (0, 0))   # base layer = plain plank
        out.alpha_composite(hub, (0, 0))                                        # ...with the hub over it
        out.save(out_dir / src.name)
        print(f"wrote {size}/{src.name}")
