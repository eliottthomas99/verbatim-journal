"""Collect verified native source archives for release review (not model weights).

Usage: python -I tools/prepare_native_sources.py <new-or-cached-directory>
About 326 MB. This baseline collection is NOT a complete corresponding-source
offer: see NATIVE_SOURCES.md for dependency closure and provenance checks.
Archives stay unextracted, avoiding long Windows paths and unsafe extraction.
"""
import json
from pathlib import Path
import runpy
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = Path(__file__).with_name("native_sources.json")


def prepare(target: Path) -> None:
    target.mkdir(parents=True, exist_ok=True)
    fetch = runpy.run_path(str(Path(__file__).with_name("prepare_licenses.py")))["fetch"]
    for spec in json.loads(MANIFEST.read_text(encoding="utf-8")):
        name = spec["name"]
        if Path(name).name != name or "/" in name or "\\" in name:
            raise ValueError("Source archive names must be simple filenames")
        fetch(spec["url"], spec["sha256"], target / name)
        print(f"Verified: {name}")
    shutil.copyfile(MANIFEST, target / "manifest.json")
    shutil.copyfile(ROOT / "LICENSE", target / "VERBATIM-LICENSE.txt")
    shutil.copyfile(ROOT / "tools/NATIVE_SOURCES.md", target / "COLLECTION_STATUS.md")
    print("Baseline source collection ready. Complete the documented release review before distribution.")


if __name__ == "__main__":
    prepare(Path(sys.argv[1]).resolve())
