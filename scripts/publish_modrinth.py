# /// script
# requires-python = ">=3.10"
# dependencies = ["requests>=2"]
# ///
"""
Publish every plugin jar of the current version to Modrinth.

    make publish-dry    # build, check, print each payload, upload nothing
    make publish        # build, upload (needs MODRINTH_TOKEN)

Re-runnable: a project that already has this version is skipped, so a run that dies halfway
just resumes. Uploads go in PLUGINS order (core first) so a companion is never live before
the core it requires. The version name is the changelog's `# ...` heading line.
"""
import json, os, pathlib, re, subprocess, sys

import requests

ROOT = pathlib.Path(__file__).resolve().parent.parent
API = "https://api.modrinth.com/v2"
# 1.21.11 and up (the changelog floor); server-test.yml tests a subset.
GAME_VERSIONS = ["1.21.11", "26.1", "26.1.1", "26.1.2", "26.2", "26.3"]
LOADERS = ["paper", "purpur"]

# (slug, jar path with {v}, changelog name, required deps, optional deps) — upload order.
PLUGINS = [
    ("defcorelib", "build/libs/defCoreLib-{v}.jar", "DefCoreLib", [], ["dynlight"]),
    ("betterbanners", "bbanners/build/libs/BetterBanners-{v}.jar", "bbanners", ["defcorelib"], []),
    ("pipes", "pipes/build/libs/Pipes-{v}.jar", "pipes", ["defcorelib"], []),
    ("vslab", "vslab/build/libs/vslab-{v}.jar", "vslab", ["defcorelib"], []),
    ("redstonedisplays", "rsd/build/libs/RedstoneDisplays-{v}.jar", "rsd", ["defcorelib"], []),
    ("railbound", "railbound/build/libs/Railbound-{v}.jar", "railbound", ["defcorelib"], []),
    ("headsmith", "headsmith/build/libs/HeadSmith-{v}.jar", "headsmith", ["defcorelib"], []),
    ("mechanism", "mech/build/libs/Mechanism-{v}.jar", "mech", ["defcorelib"],
     ["betterbanners", "pipes", "dynlight"]),
]


def main():
    dry = "--dry-run" in sys.argv
    ver = re.search(r'^version = "(.*)"', (ROOT / "build.gradle.kts").read_text(), re.M)[1]
    dirty = subprocess.run(["git", "status", "--porcelain"], cwd=ROOT, capture_output=True, text=True, check=True).stdout
    assert dry or not dirty, f"working tree not clean:\n{dirty}"

    jobs = []
    for slug, jar, log, req, opt in PLUGINS:
        jar = ROOT / jar.format(v=ver)
        log = ROOT / f"docs/changelogs/v{ver}/{log}-v{ver}.txt"
        assert jar.is_file(), f"missing jar {jar}"
        assert log.is_file(), f"missing changelog {log}"
        heading, _, body = log.read_text().partition("\n")
        assert heading.startswith("# "), f"{log} must start with a '# <name>' heading"
        jobs.append((slug, jar, heading[2:].strip(), body.strip(), req, opt))

    s = requests.Session()
    s.headers["User-Agent"] = "def9a2a4/defCoreLib publish script"
    if not dry:
        s.headers["Authorization"] = os.environ["MODRINTH_TOKEN"]

    ids = {}
    def project_id(slug):
        if slug not in ids:
            r = s.get(f"{API}/project/{slug}")
            r.raise_for_status()
            ids[slug] = r.json()["id"]
        return ids[slug]

    for slug, jar, name, changelog, req, opt in jobs:
        r = s.get(f"{API}/project/{slug}/version")
        r.raise_for_status()
        if any(v["version_number"] == ver for v in r.json()):
            print(f"skip {slug}: {ver} already published")
            continue
        data = {
            "project_id": project_id(slug),
            "name": name,
            "version_number": ver,
            "changelog": changelog,
            "dependencies": [{"project_id": project_id(d), "dependency_type": "required"} for d in req]
                          + [{"project_id": project_id(d), "dependency_type": "optional"} for d in opt],
            "game_versions": GAME_VERSIONS,
            "loaders": LOADERS,
            "version_type": "alpha",
            "featured": False,
            "file_parts": ["file"],
            "primary_file": "file",
        }
        if dry:
            print(f"== {slug}  {jar.relative_to(ROOT)}")
            print(json.dumps({**data, "changelog": changelog[:120] + "..."}, indent=2))
            continue
        with jar.open("rb") as f:
            r = s.post(f"{API}/version", data={"data": json.dumps(data)},
                       files={"file": (jar.name, f, "application/java-archive")})
        assert r.ok, f"{slug}: {r.status_code} {r.text}"
        print(f"published {slug} {ver}")


if __name__ == "__main__":
    main()
