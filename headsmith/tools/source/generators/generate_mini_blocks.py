#!/usr/bin/env python3
"""Generate mini_blocks.yml and alphabet.yml entries from heads-database-with-base64.csv"""

import argparse
import csv
import fnmatch
import tomllib
from pathlib import Path

# Load config from TOML
CONFIG_PATH = Path(__file__).parent / "generate_config.toml"
with open(CONFIG_PATH, "rb") as f:
    CONFIG = tomllib.load(f)

MATERIAL_PROPERTIES: dict[str, list[str]] = CONFIG["material_properties"]
FONT_TO_MINI_BLOCK: dict[str, str] = CONFIG["font_to_mini_block"]
MATERIAL_TAG_PATTERNS: dict[str, list[str]] = CONFIG.get("material_tag_patterns", {})
MATERIAL_TAG_LISTS: dict[str, list[str]] = CONFIG.get("material_tag_lists", {})
VALUABLE_DROP_MATERIALS: list[str] = CONFIG.get("valuable_drop_materials", [])

MATERIALS: list[str] = CONFIG["materials"]

# Build ALPHABET_CHARACTERS from config sections
ALPHABET_CHARACTERS: dict[str, tuple[str, str]] = {}
for section in [
    "letters",
    "numbers",
    "punctuation",
    "brackets",
    "arrows",
    "accented",
    "greek",
    "cyrillic",
    "zodiac",
    "gender",
]:
    if section in CONFIG.get("alphabet", {}):
        for search_suffix, (yaml_suffix, display_char) in CONFIG["alphabet"][
            section
        ].items():
            ALPHABET_CHARACTERS[search_suffix] = (yaml_suffix, display_char)


# Colors used for material name parsing
COLORS = [
    "White",
    "Orange",
    "Magenta",
    "Light Blue",
    "Yellow",
    "Lime",
    "Pink",
    "Gray",
    "Light Gray",
    "Cyan",
    "Purple",
    "Blue",
    "Brown",
    "Green",
    "Red",
    "Black",
]


def get_material_tags(material: str) -> list[str]:
    """Get all tags for a material based on patterns and explicit lists."""
    tags = []
    # Check pattern-based tags
    for tag_name, patterns in MATERIAL_TAG_PATTERNS.items():
        for pattern in patterns:
            if fnmatch.fnmatch(material, pattern):
                tags.append(tag_name)
                break
    # Check explicit list tags
    for tag_name, materials in MATERIAL_TAG_LISTS.items():
        if material in materials:
            tags.append(tag_name)
    return tags


def material_to_search_names(material):
    """Returns list of possible CSV names for a material"""
    base = material.replace("_", " ").title()
    names = [base]

    # Handle "Color BlockType" -> "BlockType (color)" pattern
    # e.g. "Orange Concrete" -> "Concrete (orange)"
    for color in COLORS:
        if base.startswith(color + " "):
            rest = base[len(color) + 1 :]  # e.g. "Concrete"
            names.append(f"{rest} ({color.lower()})")
            break

    # Special aliases
    if base == "Hay Block":
        names.append("Hay Bale")

    return names


def material_to_yaml_id(material):
    """QUARTZ_BLOCK -> mini_quartz_block"""
    return "mini_" + material.lower()


def material_to_display_name(material):
    """QUARTZ_BLOCK -> Mini Quartz Block"""
    return "Mini " + material.replace("_", " ").title()


def find_best_texture(search_names, rows):
    """Find best texture (case-insensitive, prefer blocks category, then decoration)"""
    # Try each search name
    for search_name in search_names:
        # Try blocks category first, then decoration
        for category in ["blocks", "decoration"]:
            candidates = [
                r
                for r in rows
                if r[0].lower() == search_name.lower() and r[1] == category
            ]

            # Prefer Vanilla Block with Inner Layer Block
            for c in candidates:
                tags = c[3] if len(c) > 3 else ""
                if "Vanilla Block" in tags and "Inner Layer Block" in tags:
                    return c[4] if len(c) > 4 else None

            # Fall back to just Vanilla Block
            for c in candidates:
                tags = c[3] if len(c) > 3 else ""
                if "Vanilla Block" in tags:
                    return c[4] if len(c) > 4 else None

            # Fall back to any Inner Layer Block
            for c in candidates:
                tags = c[3] if len(c) > 3 else ""
                if "Inner Layer Block" in tags:
                    return c[4] if len(c) > 4 else None

            # Fall back to first match in this category
            if candidates:
                return candidates[0][4] if len(candidates[0]) > 4 else None

    return None


def generate_yaml_entry(material, texture):
    yaml_id = material_to_yaml_id(material)
    display_name = material_to_display_name(material)
    properties = MATERIAL_PROPERTIES.get(material, [])
    tags = get_material_tags(material)

    # Build tags section (only if tags exist)
    tags_section = ""
    if tags:
        tags_section = "    tags:\n"
        for tag in tags:
            tags_section += f"      - {tag}\n"

    # Build properties section
    props_section = ""
    if properties:
        props_section = "    properties:\n"
        for prop in properties:
            props_section += f"      - {prop}\n"

    # Build drops section for valuable materials
    drops_section = ""
    if material in VALUABLE_DROP_MATERIALS:
        drops_section = f'''    drops:
      on_break:
        - when: {{ silk_touch: false }}
          drops:
            - {{ material: "{material}" }}
        - when: {{ silk_touch: true }}
          drops:
            - {{ head: "{yaml_id}" }}
'''

    return f'''  {yaml_id}:
    texture: "{texture}"
    name: "&7{display_name}"
{tags_section}{props_section}    recipes:
      stonecutter:
        - id: "{yaml_id}"
          input: {{ material: "{material}" }}
{drops_section}'''


def yaml_escape(s: str) -> str:
    """Escape a string for use in YAML double-quoted strings."""
    return s.replace("\\", "\\\\").replace('"', '\\"')


def generate_alphabet_entry(
    font_key: str,
    yaml_suffix: str,
    display_char: str,
    texture: str,
    mini_block_id: str,
    inherited_tags: list[str],
    inherited_properties: list[str],
    parent_material: str,
) -> str:
    """Generate a single alphabet YAML entry."""
    font_id = font_key.lower().replace(" ", "_")
    yaml_id = f"{font_id}_{yaml_suffix}"
    display_name = yaml_escape(f"{font_key} {display_char}")

    # Create hierarchical alphabet tag + character tag + inherited tags
    all_tags = [f"alphabet/{font_id}", f"character/{yaml_suffix}"] + inherited_tags

    tags_section = "    tags:\n"
    for tag in all_tags:
        tags_section += f"      - {tag}\n"

    props_section = ""
    if inherited_properties:
        props_section = "    properties:\n"
        for prop in inherited_properties:
            props_section += f"      - {prop}\n"

    # Build drops section for alphabet blocks from valuable materials
    drops_section = ""
    if parent_material in VALUABLE_DROP_MATERIALS:
        drops_section = f'''    drops:
      on_break:
        - when: {{ silk_touch: false }}
          drops:
            - {{ material: "{parent_material}" }}
        - when: {{ silk_touch: true }}
          drops:
            - {{ head: "{yaml_id}" }}
'''

    return f'''  {yaml_id}:
    texture: "{texture}"
    name: "&7{display_name}"
{tags_section}{props_section}    recipes:
      stonecutter:
        - id: "{yaml_id}"
          input: {{ head: "{mini_block_id}" }}
{drops_section}'''


def generate_alphabet(rows: list) -> tuple[dict[str, list[str]], dict[str, list[str]]]:
    """Generate alphabet block entries from CSV rows.

    Returns (entries_by_font, missing_by_font) where:
      - entries_by_font maps font_id to list of YAML entries
      - missing_by_font maps font name to missing chars.
    """
    # Filter to alphabet category
    alphabet_rows = [r for r in rows if len(r) > 1 and r[1] == "alphabet"]

    entries_by_font: dict[str, list[str]] = {}
    missing_by_font: dict[str, list[str]] = {}

    for font_name, mini_block_id in FONT_TO_MINI_BLOCK.items():
        font_id = font_name.lower().replace(" ", "_")
        font_pattern = f"Font ({font_name})"
        missing_chars = []
        font_entries = []

        # Get inherited tags and properties from parent material
        # Convert mini_oak_planks -> OAK_PLANKS
        material = mini_block_id.replace("mini_", "").upper()
        inherited_tags = get_material_tags(material)
        inherited_properties = MATERIAL_PROPERTIES.get(material, [])

        for search_suffix, (yaml_suffix, display_char) in ALPHABET_CHARACTERS.items():
            # Look for entries like "Oak Wood A" or "Birch Exclamation Mark" with matching font tag
            texture = None
            for row in alphabet_rows:
                name = row[0]
                tags = row[3] if len(row) > 3 else ""

                if font_pattern not in tags:
                    continue

                # Skip Standard Galactic alphabet variants
                if "Standard Galactic" in name:
                    continue

                # Match patterns like "Oak Wood A", "Birch Exclamation Mark"
                if name.endswith(f" {search_suffix}"):
                    texture = row[4] if len(row) > 4 else None
                    break

            if texture:
                font_entries.append(
                    generate_alphabet_entry(
                        font_name,
                        yaml_suffix,
                        display_char,
                        texture,
                        mini_block_id,
                        inherited_tags,
                        inherited_properties,
                        material,
                    )
                )
            else:
                missing_chars.append(search_suffix)

        # Collect runes for this font - they all have the same name but different textures
        rune_idx = 0
        for row in alphabet_rows:
            name = row[0]
            tags = row[3] if len(row) > 3 else ""

            if font_pattern not in tags:
                continue

            # Match "Oak Wood Rune" exactly (not "Standard Galactic" variants)
            if name.endswith(" Rune") and "Standard Galactic" not in name:
                texture = row[4] if len(row) > 4 else None
                if texture:
                    font_entries.append(
                        generate_alphabet_entry(
                            font_name,
                            f"rune_{rune_idx}",
                            "Rune",
                            texture,
                            mini_block_id,
                            inherited_tags,
                            inherited_properties,
                            material,
                        )
                    )
                    rune_idx += 1

        # Collect Standard Galactic alphabet entries (A-Z only)
        for letter in "ABCDEFGHIJKLMNOPQRSTUVWXYZ":
            for row in alphabet_rows:
                name = row[0]
                tags = row[3] if len(row) > 3 else ""

                if font_pattern not in tags:
                    continue

                # Match "Cherry Plank Standard Galactic Alphabet A" pattern
                if name.endswith(f" Standard Galactic Alphabet {letter}"):
                    texture = row[4] if len(row) > 4 else None
                    if texture:
                        font_entries.append(
                            generate_alphabet_entry(
                                font_name,
                                f"galactic_{letter.lower()}",
                                f"Galactic {letter}",
                                texture,
                                mini_block_id,
                                inherited_tags,
                                inherited_properties,
                                material,
                            )
                        )
                    break

        entries_by_font[font_id] = font_entries
        if missing_chars:
            missing_by_font[font_name] = missing_chars

    return entries_by_font, missing_by_font


def main():
    parser = argparse.ArgumentParser(
        description="Generate mini_blocks.yml and alphabet.yml"
    )
    parser.add_argument("--input", "-i", type=str, default="data/heads-db-b64.csv")
    parser.add_argument("--output-dir", "-o", type=str, default="data")
    parser.add_argument(
        "--no-alphabet", action="store_true", help="Skip alphabet generation"
    )
    args = parser.parse_args()

    input_file = args.input
    output_dir: Path = Path(args.output_dir)

    with open(input_file, "r", encoding="utf-8") as f:
        rows = list(csv.reader(f))[1:]

    entries = []
    missing = []

    for material in MATERIALS:
        search_names = material_to_search_names(material)
        texture = find_best_texture(search_names, rows)
        if texture:
            entries.append(generate_yaml_entry(material, texture))
        else:
            missing.append(material)

    # Write to file
    with open(output_dir / "mini_blocks_GENERATED.yml", "w") as f:
        f.write("# Generated Mini Blocks\n\nheads:\n")
        for entry in entries:
            f.write(entry)

        if missing:
            f.write("\n# ==========================================================\n")
            f.write("# MISSING BLOCKS - Could not find textures in CSV:\n")
            f.write("# ==========================================================\n")
            for m in missing:
                f.write(f"# {m}\n")

    print(f"Mini blocks: Generated {len(entries)} entries, {len(missing)} missing")
    if missing:
        print(f"Missing: {', '.join(missing)}")

    # Generate alphabet blocks (one file per font)
    if not args.no_alphabet:
        alpha_entries_by_font, alpha_missing = generate_alphabet(rows)

        # Create alphabet output directory
        alphabet_dir: Path = output_dir / "alphabet_GENERATED"
        alphabet_dir.mkdir(exist_ok=True)

        total_alpha = 0
        for font_id, font_entries in alpha_entries_by_font.items():
            if not font_entries:
                continue

            # Get display name from font_id (e.g., "cherry_planks" -> "Cherry Planks")
            display_name = font_id.replace("_", " ").title()

            output_file = alphabet_dir / f"{font_id}.yml"
            with open(output_file, "w") as f:
                f.write(f"# Generated {display_name} Alphabet Blocks\n\nheads:\n")
                for entry in font_entries:
                    f.write(entry)

            total_alpha += len(font_entries)
            print(f"  {font_id}.yml: {len(font_entries)} entries")

        if alpha_missing:
            print("Missing characters by font:")
            for font, chars in alpha_missing.items():
                print(f"  {font}: {', '.join(chars)}")

        total_missing = sum(len(chars) for chars in alpha_missing.values())
        print(
            f"Alphabet: Generated {total_alpha} entries across {len(alpha_entries_by_font)} files, {total_missing} missing"
        )


if __name__ == "__main__":
    main()
