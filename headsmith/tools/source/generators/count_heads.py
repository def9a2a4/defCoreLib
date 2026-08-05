#!/usr/bin/env python3
# /// script
# dependencies = [
#   "pyyaml",
# ]
# ///
"""Count heads per YAML file and generate manifest for docs/plugin."""

import json
import yaml
from pathlib import Path


def scan_head_files(repo_root: Path) -> list[str]:
    """Scan for all head YAML files in the resources directory.

    Args:
        repo_root: Path to the repository root

    Returns:
        Sorted list of file paths relative to resources (e.g., "heads/candles.yml")
    """
    heads_dir = repo_root / "headsmith/src/main/resources/heads"
    resources_dir = repo_root / "headsmith/src/main/resources"

    head_files = []
    for yml_path in sorted(heads_dir.rglob("*.yml")):
        rel_path = yml_path.relative_to(resources_dir)
        head_files.append(str(rel_path).replace("\\", "/"))

    return head_files


def count_heads(repo_root: Path, head_files: list[str]) -> dict:
    """Count heads from all YAML files.

    Args:
        repo_root: Path to the repository root
        head_files: List of head file paths relative to resources

    Returns:
        Dict with counts per file and total
    """
    resources = repo_root / "headsmith/src/main/resources"

    counts = {}
    total = 0
    for head_file in head_files:
        path = resources / head_file
        if path.exists():
            with open(path) as f:
                data = yaml.safe_load(f)
                heads = data.get("heads", {})
                count = len(heads)
                counts[head_file] = count
                total += count

    counts["total"] = total
    return counts


def main():
    """CLI entry point."""
    repo_root = Path.cwd()

    # Scan for head files
    head_files = scan_head_files(repo_root)

    # Count heads
    counts = count_heads(repo_root, head_files)

    for file, count in counts.items():
        if file != "total":
            print(f"{file}: {count} heads")
    print(f"\nTotal: {counts['total']} heads")

    # Write head-count.json for docs and JAR
    docs_count_path = repo_root / "docs/util/head-count.json"
    docs_count_path.write_text(json.dumps(counts, indent=2))
    print(f"Written to {docs_count_path}")

    jar_count_path = repo_root / "headsmith/src/main/resources/head-count.json"
    jar_count_path.write_text(json.dumps(counts, indent=2))
    print(f"Written to {jar_count_path}")


if __name__ == "__main__":
    main()
