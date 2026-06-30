import sys
from pathlib import Path

import requests


def head_url(png_path: Path) -> str:
    with png_path.open("rb") as fh:
        resp: requests.Response = requests.post(
            "https://api.mineskin.org/generate/upload",
            files={"file": fh},
            timeout=60,
        )
    resp.raise_for_status()
    return resp.json()["data"]["texture"]["url"]


if __name__ == "__main__":
    print(head_url(Path(sys.argv[1])))