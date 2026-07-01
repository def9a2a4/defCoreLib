# /// script
# requires-python = ">=3.9"
# dependencies = [
#     "requests==2.34.2",
# ]
# ///
"""Upload skin PNGs to mineskin and cache the resulting texture as base64.

The game (HeadUtil.parseTexture) only reads textures.SKIN.url, so we store the
minimal texture JSON  {"textures":{"SKIN":{"url":"..."}}}  base64-encoded — the
same form used by the @alias entries in rotation-blocks.yml.

A CSV cache (scripts/skin_cache.csv) keyed by image name + sha256 avoids
re-hitting the API: an image whose bytes are unchanged is served from cache.

Usage:
    # process specific images (paths relative to assets/rotation, or absolute)
    uv run scripts/upload_skin.py rod/copper-rod-up.png copber2-fwd.png

    # process every *.png under assets/rotation (excluding dev/)
    uv run scripts/upload_skin.py --all

    # re-upload even if cached
    uv run scripts/upload_skin.py --force rod/copper-rod-up.png

Prints "name<TAB>base64" for each processed image.
"""

import argparse
import base64
import csv
import hashlib
import json
import sys
import time
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parent.parent          # defCoreLib/
ASSETS = ROOT / "assets" / "rotation"
CACHE = Path(__file__).resolve().parent / "skin_cache.csv"
FIELDS = ["name", "sha256", "texture_id", "url", "base64"]

API = "https://api.mineskin.org/generate/upload"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def make_base64(url: str) -> str:
    payload = json.dumps({"textures": {"SKIN": {"url": url}}}, separators=(",", ":"))
    return base64.b64encode(payload.encode("utf-8")).decode("ascii")


def upload(path: Path, retries: int = 4) -> str:
    """Upload a PNG, return the minecraft texture id (hex hash)."""
    last = None
    for attempt in range(retries):
        with path.open("rb") as fh:
            resp = requests.post(API, files={"file": fh}, timeout=60)
        if resp.status_code == 200:
            body = resp.json()
            tex_id = body.get("hash")
            if not tex_id:  # fall back to parsing the profile value
                val = body["data"]["texture"]["value"]
                inner = json.loads(base64.b64decode(val))
                tex_id = inner["textures"]["SKIN"]["url"].rsplit("/", 1)[-1]
            return tex_id
        last = f"HTTP {resp.status_code}: {resp.text[:200]}"
        # 429 / transient — back off and retry
        delay = float(resp.headers.get("Retry-After", 2 ** attempt))
        time.sleep(min(delay, 30))
    raise RuntimeError(f"upload failed for {path}: {last}")


def load_cache() -> dict:
    if not CACHE.exists():
        return {}
    with CACHE.open(newline="") as fh:
        return {row["name"]: row for row in csv.DictReader(fh)}


def save_cache(cache: dict) -> None:
    rows = sorted(cache.values(), key=lambda r: r["name"])
    with CACHE.open("w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=FIELDS)
        w.writeheader()
        w.writerows(rows)


def resolve(name: str) -> tuple[str, Path]:
    """Return (cache-name, path) for a user-supplied name or path."""
    p = Path(name)
    if p.is_absolute() or p.exists():
        path = p.resolve()
        try:
            rel = path.relative_to(ASSETS.resolve())
            return rel.as_posix(), path
        except ValueError:
            return path.name, path
    return name, (ASSETS / name)


def gather_all() -> list[str]:
    names = []
    for p in sorted(ASSETS.rglob("*.png")):
        if "dev" in p.relative_to(ASSETS).parts:
            continue
        names.append(p.relative_to(ASSETS).as_posix())
    return names


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("images", nargs="*", help="image paths relative to assets/rotation")
    ap.add_argument("--all", action="store_true", help="process every png under assets/rotation (excl. dev/)")
    ap.add_argument("--force", action="store_true", help="re-upload even if cached")
    args = ap.parse_args()

    names = gather_all() if args.all else args.images
    if not names:
        ap.error("no images given (use --all or list paths)")

    cache = load_cache()
    for name in names:
        cname, path = resolve(name)
        if not path.exists():
            print(f"# MISSING {cname} ({path})", file=sys.stderr)
            continue
        digest = sha256(path)
        row = cache.get(cname)
        if row and row["sha256"] == digest and not args.force:
            print(f"{cname}\t{row['base64']}")
            continue
        tex_id = upload(path)
        url = f"http://textures.minecraft.net/texture/{tex_id}"
        row = {
            "name": cname,
            "sha256": digest,
            "texture_id": tex_id,
            "url": url,
            "base64": make_base64(url),
        }
        cache[cname] = row
        save_cache(cache)  # persist after each success so a mid-batch failure keeps progress
        print(f"{cname}\t{row['base64']}")
        time.sleep(1)  # be gentle with the API

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
