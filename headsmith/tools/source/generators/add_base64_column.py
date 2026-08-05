#!/usr/bin/env python3
import sys
import csv
import base64
import json


def hash_to_base64(texture_hash):
    """Convert a texture hash to base64-encoded texture JSON."""
    texture_json = {
        "textures": {
            "SKIN": {"url": f"http://textures.minecraft.net/texture/{texture_hash}"}
        }
    }
    json_str = json.dumps(texture_json, separators=(",", ":"))
    return base64.b64encode(json_str.encode()).decode()


def main():
    input_file: str = sys.argv[1]

    with open(input_file, "r", encoding="utf-8") as infile:
        reader = csv.reader(infile)
        rows = list(reader)

    # Add header for new column
    if rows:
        rows[0].append("base64_texture")

    # Process each row (skip header)
    for i, row in enumerate(rows[1:], start=1):
        if len(row) >= 3:
            texture_hash = row[2]  # hash is in column 3 (index 2)
            base64_texture = hash_to_base64(texture_hash)
            row.append(base64_texture)
        else:
            row.append("")

    # write to stdout
    writer = csv.writer(sys.stdout)
    writer.writerows(rows)


if __name__ == "__main__":
    main()
