"""One-shot: refresh the casing @texture aliases in rotation-blocks.yml from scripts/skin_cache.csv.

upload_skin.py only prints "name<TAB>base64" — it writes nothing. Rather than hand-paste 12 base64
blobs after re-uploading the casing art, rewrite the alias values in place: each alias line already
carries its cache key as a trailing comment ("# casing/cased/<wood>.png"), so the mapping is exact.

Padding, ordering and comments are preserved — only the quoted value changes. Re-runnable; a no-op
when the yml already matches the cache. Run from the repo root, after upload_skin.py.
"""
import csv
import re
from pathlib import Path

YML = Path("src/main/resources/rotation-blocks.yml")
CACHE = Path("scripts/skin_cache.csv")

with CACHE.open(newline="") as fh:
    cache = {row["name"]: row["base64"] for row in csv.DictReader(fh)}

# Scoped to the casing + gearbox art on purpose: the other ~44 aliases are not ours to touch. Both
# share the same "# casing/<subdir>/<wood>.png" trailing-comment cache key, so one pattern covers both
# (casing_* → cased/, gearbox_* → gearbox/). Chassis aliases are hand-maintained and left alone.
LINE = re.compile(
    r'^(?P<pre>\s*(?:casing|gearbox)_\w+:\s*)"[^"]*"'
    r'(?P<post>\s*#\s*(?P<key>casing/(?:cased|gearbox)/\w+\.png)\s*)$')

out, changed, missing = [], 0, []
for line in YML.read_text().splitlines(keepends=True):
    m = LINE.match(line.rstrip("\n"))
    if not m:
        out.append(line)
        continue
    key = m.group("key")
    if key not in cache:
        missing.append(key)
        out.append(line)
        continue
    new = f'{m.group("pre")}"{cache[key]}"{m.group("post")}\n'
    changed += new != line
    out.append(new)

YML.write_text("".join(out))
print(f"aliases updated: {changed}")
if missing:
    raise SystemExit(f"not in {CACHE}: {', '.join(missing)} — run upload_skin.py first")
