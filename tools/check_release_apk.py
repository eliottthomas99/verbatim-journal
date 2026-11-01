"""Check APK packaging, not legal compliance: python -I tools/check_release_apk.py <apk>."""
import hashlib
import json
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def problems(path: Path) -> list[str]:
    errors = []
    with zipfile.ZipFile(path) as apk:
        names = set(apk.namelist())
        for name in names:
            lower = name.lower()
            if any(s in lower for s in ("needle", "whistle", "verbatim-jni")) or lower.endswith(".cact"):
                errors.append(f"Removed speech component: {name}")
            if name.startswith("lib/") and not name.startswith("lib/arm64-v8a/"):
                errors.append(f"Unexpected ABI: {name}")
            if "vcs-info" in lower:
                errors.append(f"VCS metadata must not be packaged: {name}")
            if name.endswith(".dex") and b"WhistleNative" in apk.read(name):
                errors.append(f"Removed speech class in {name}")
        required = {
            "assets/licenses/GPL-3.0.txt": (ROOT / "LICENSE").read_bytes(),
            "assets/licenses/NOTICES.txt": (ROOT / "THIRD_PARTY_NOTICES.md").read_bytes(),
        }
        for name, expected in required.items():
            if name not in names or apk.read(name) != expected:
                errors.append(f"Missing/outdated text: {name}")
        for spec in json.loads((ROOT / "tools/native_licenses.json").read_text(encoding="utf-8")):
            name = "assets/licenses/" + spec["name"]
            if name not in names or hashlib.sha256(apk.read(name)).hexdigest() != spec["sha256"]:
                errors.append(f"Missing/invalid native licence: {name}")
        for name in ("lib/arm64-v8a/libsherpa-onnx-jni.so", "assets/tts/fr/MODEL_CARD.txt", "assets/tts/en/MODEL_CARD.txt"):
            if name not in names:
                errors.append(f"Missing runtime/voice attribution: {name}")
    return errors


if __name__ == "__main__":
    issues = problems(Path(sys.argv[1]))
    for issue in issues:
        print(issue)
    if issues:
        sys.exit(1)
    print("Packaging check passed: no Whistle/Needle, arm64 only, full pinned native licence texts present.")
    print("Corresponding-source completeness and release approval are separate checks.")
