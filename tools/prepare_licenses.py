"""Prepare pinned native licence texts for the APK; no model downloads.

Normally called by prepare_tts.py. Files are generated under .local-models/tts.
This is a notice bundle, not a replacement for corresponding source.
"""
import hashlib
import json
from pathlib import Path
import shutil
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = Path(__file__).with_name("native_licenses.json")
DEST = ROOT / ".local-models/tts/assets/licenses"


def fetch(url: str, expected: str, target: Path) -> None:
    if target.is_file() and hashlib.sha256(target.read_bytes()).hexdigest() == expected:
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(target.name + ".part")
    try:
        with urllib.request.urlopen(url, timeout=60) as response, temporary.open("wb") as out:
            shutil.copyfileobj(response, out)
        if hashlib.sha256(temporary.read_bytes()).hexdigest() != expected:
            raise ValueError(f"Checksum mismatch: {target.name}")
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)


def prepare() -> None:
    DEST.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ROOT / "LICENSE", DEST / "GPL-3.0.txt")
    shutil.copyfile(ROOT / "THIRD_PARTY_NOTICES.md", DEST / "NOTICES.txt")
    for spec in json.loads(MANIFEST.read_text(encoding="utf-8")):
        name = spec["name"]
        if Path(name).name != name or "/" in name or "\\" in name:
            raise ValueError("Licence names must be simple filenames")
        fetch(spec["url"], spec["sha256"], DEST / name)
    (DEST / "SOURCES.txt").write_text(
        "Verbatim source: https://github.com/eliottthomas99/verbatim-journal\n"
        "Native source revisions and release requirements: THIRD_PARTY_NOTICES.md in that repository.\n"
        "The native licence manifest records upstream text URLs and SHA-256 checksums.\n",
        encoding="utf-8",
    )
    print("Native licence texts and GPL prepared.")


if __name__ == "__main__":
    prepare()
