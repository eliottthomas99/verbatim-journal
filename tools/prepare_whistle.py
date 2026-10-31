"""Fetch pinned Whistle Android assets; Python standard library only.

Run from any directory: python -I tools/prepare_whistle.py
Outputs are ignored under .local-models/whistle/, never downloaded on the phone.
"""

import hashlib
from pathlib import Path
import shutil
import urllib.request


ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / ".local-models" / "whistle"
MODEL_REV = "741118687e1f008f06d1351a402a731274789790"
ENGINE_REV = "d6c7d6a2e9655505e0f8e23339356dd1705900eb"
FILES = [
    (
        f"Cactus-Compute/whistle/resolve/{MODEL_REV}/whistle.cact",
        "assets/asr/whistle.cact",
        "b6e02f048568ac5d01a2042556c658061e699acbc0aa2a1439f52f3d461dffeb",
    ),
    (
        f"Cactus-Compute/needle3/resolve/{ENGINE_REV}/android-arm64/libneedle.a",
        "native/arm64-v8a/libneedle.a",
        "7aa6de6132a22bf45f5e903425feb66f159ad46f47fa558921bc4fd02c3a2fa0",
    ),
    (
        f"Cactus-Compute/needle3/resolve/{ENGINE_REV}/android-arm64/needle.h",
        "native/needle.h",
        "a27dac4b3239eead91d124b7f7dbce5495c13019903fd817b68c16d39b055a99",
    ),
    (
        f"Cactus-Compute/whistle/resolve/{MODEL_REV}/LICENSE",
        "assets/asr/WHISTLE-LICENSE.txt",
        "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30",
    ),
    (
        f"Cactus-Compute/needle3/resolve/{ENGINE_REV}/LICENSE",
        "assets/asr/NEEDLE-LICENSE.txt",
        "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30",
    ),
]


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def prepare():
    for remote, relative, expected in FILES:
        target = DEST / relative
        if expected and target.exists() and digest(target) == expected:
            print(f"Verified cached: {relative}")
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_name(target.name + ".part")
        try:
            request = urllib.request.Request(
                "https://huggingface.co/" + remote,
                headers={"User-Agent": "Verbatim-model-setup/1.0"},
            )
            with urllib.request.urlopen(request, timeout=60) as response:
                with temporary.open("wb") as output:
                    shutil.copyfileobj(response, output)
            actual = digest(temporary)
            if expected and actual != expected:
                raise ValueError(f"Checksum mismatch for {relative}: {actual}")
            if temporary.stat().st_size == 0:
                raise ValueError(f"Empty asset: {relative}")
            temporary.replace(target)
            print(f"Prepared: {relative} ({target.stat().st_size} bytes; sha256 {actual})")
        finally:
            temporary.unlink(missing_ok=True)
    print(f"Ready: {DEST}")
    print("Model and library verified.")


if __name__ == "__main__":
    prepare()
