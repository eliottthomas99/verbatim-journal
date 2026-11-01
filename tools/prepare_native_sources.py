"""Build the native source package published next to each APK (not model weights).

Usage: python -I tools/prepare_native_sources.py <new-or-cached-directory> <release-version>
About 780 MB. See NATIVE_SOURCES.md for what it holds and how it was determined.
Archives stay unextracted, avoiding long Windows paths and unsafe extraction.
"""
import hashlib
import json
from pathlib import Path
import runpy
import shutil
import sys
import tarfile

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = Path(__file__).with_name("native_sources.json")
EXTRAS = {
    "manifest.json": MANIFEST,
    "LICENSE.txt": ROOT / "LICENSE",
    "README.md": ROOT / "tools/NATIVE_SOURCES.md",
    "android_dependencies.txt": ROOT / "tools/android_dependencies.txt",
}


def prepare(target: Path, version: str) -> Path:
    target.mkdir(parents=True, exist_ok=True)
    fetch = runpy.run_path(str(Path(__file__).with_name("prepare_licenses.py")))["fetch"]
    specs = json.loads(MANIFEST.read_text(encoding="utf-8"))
    for spec in specs:
        name = spec["name"]
        if Path(name).name != name or "/" in name or "\\" in name:
            raise ValueError("Source archive names must be simple filenames")
        fetch(spec["url"], spec["sha256"], target / name)
        print(f"Verified: {name}")
    prefix = f"verbatim-{version}-native-sources"
    bundle = target / f"{prefix}.tar"
    part = bundle.with_suffix(".tar.part")
    sums = "".join(f"{spec['sha256']}  {spec['name']}\n" for spec in specs)
    (target / "SHA256SUMS").write_text(sums, encoding="utf-8", newline="\n")
    with tarfile.open(part, "w") as tar:
        for name, source in EXTRAS.items():
            tar.add(source, f"{prefix}/{name}")
        tar.add(target / "SHA256SUMS", f"{prefix}/SHA256SUMS")
        for spec in specs:
            tar.add(target / spec["name"], f"{prefix}/{spec['name']}")
    part.replace(bundle)
    digest = hashlib.sha256()
    with bundle.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            digest.update(chunk)
    print(f"Ready: {bundle} ({bundle.stat().st_size} bytes; sha256 {digest.hexdigest()})")
    return bundle


if __name__ == "__main__":
    prepare(Path(sys.argv[1]).resolve(), sys.argv[2])
