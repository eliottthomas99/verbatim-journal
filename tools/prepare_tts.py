"""Fetch the pinned on-device text-to-speech assets; Python standard library only.

Run from any directory: python -I tools/prepare_tts.py
Outputs are ignored under .local-models/tts/ and bundled into the APK at build
time (never downloaded on the phone):

  .local-models/tts/sherpa-onnx.aar          official k2-fsa runtime 1.13.8 (Apache-2.0, bundles GPL espeak-ng)
  .local-models/tts/assets/tts/fr/            Piper fr_FR-siwis-medium int8 (CC BY 4.0)
  .local-models/tts/assets/tts/en/            Piper en_US-ljspeech-medium int8 (public-domain data)
  .local-models/tts/assets/tts/espeak-ng-data shared phonemizer data, French + English only
"""

import hashlib
from pathlib import Path
import shutil
import tarfile
import tempfile
import urllib.request
import runpy

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / ".local-models" / "tts"
# 1.13.8, not 2.x: sherpa-onnx 2.0.0 (2026-10-23) removed the built-in espeak-ng
# phonemizer, and Piper voices then need phonemes computed outside the library.
SHERPA = (
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/"
    "sherpa-onnx-static-link-onnxruntime-1.13.8.aar",
    "b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471",
)
VOICES = {
    "fr": ("vits-piper-fr_FR-siwis-medium-int8",
           "3909cff9b3cfd4820c66aa13bf554315c82e34899c161f0b446ece372bc4b5ec"),
    "en": ("vits-piper-en_US-ljspeech-medium-int8",
           "24dc3bd77dd48c291e52c297878d3437c9492f245d823d7f6a06c4bbb67f4b6b"),
}
VOICE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/{}.tar.bz2"
# espeak-ng ships a pronunciation dictionary for ~100 languages; keep ours.
KEEP_DICTS = {"fr_dict", "en_dict"}


def fetch(url: str, sha256: str, target: Path) -> None:
    if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == sha256:
        return
    print(f"downloading {url}")
    part = target.with_suffix(target.suffix + ".part")
    with urllib.request.urlopen(url) as response, part.open("wb") as out:
        shutil.copyfileobj(response, out)
    digest = hashlib.sha256(part.read_bytes()).hexdigest()
    if digest != sha256:
        part.unlink()
        raise SystemExit(f"checksum mismatch for {url}: {digest}")
    part.replace(target)


def main() -> None:
    DEST.mkdir(parents=True, exist_ok=True)
    fetch(SHERPA[0], SHERPA[1], DEST / "sherpa-onnx.aar")

    assets = DEST / "assets" / "tts"
    if assets.exists():
        shutil.rmtree(assets)
    for lang, (name, sha256) in VOICES.items():
        archive = DEST / f"{name}.tar.bz2"
        fetch(VOICE_URL.format(name), sha256, archive)
        with tempfile.TemporaryDirectory() as tmp, tarfile.open(archive) as tar:
            tar.extractall(tmp, filter="data")  # "data": refuses absolute paths / ".."
            src = Path(tmp) / name
            voice_dir = assets / lang
            voice_dir.mkdir(parents=True)
            shutil.copy(next(src.glob("*.onnx")), voice_dir / "model.onnx")
            shutil.copy(src / "tokens.txt", voice_dir / "tokens.txt")
            shutil.copy(src / "MODEL_CARD", voice_dir / "MODEL_CARD.txt")
            espeak = assets / "espeak-ng-data"
            if not espeak.exists():
                shutil.copytree(src / "espeak-ng-data", espeak)
                for f in espeak.glob("*_dict"):
                    if f.name not in KEEP_DICTS:
                        f.unlink()

    (assets / "NOTICE.txt").write_text(
        "On-device text-to-speech\n"
        "- Runtime: sherpa-onnx 1.13.8 (k2-fsa), Apache-2.0, https://github.com/k2-fsa/sherpa-onnx\n"
        "  (includes espeak-ng, GPL-3.0, for text-to-phoneme conversion)\n"
        "- French voice: Piper fr_FR-siwis-medium, SIWIS dataset CC BY 4.0 (University of Edinburgh)\n"
        "- English voice: Piper en_US-ljspeech-medium, LJ Speech dataset (public domain)\n"
        "- Phonemizer data: espeak-ng, GPL-3.0, https://github.com/espeak-ng/espeak-ng\n",
        encoding="utf-8",
    )
    size = sum(f.stat().st_size for f in assets.rglob("*") if f.is_file())
    print(f"TTS assets ready in {assets} ({size / 1e6:.1f} MB)")
    runpy.run_path(str(ROOT / "tools/prepare_licenses.py"))["prepare"]()


if __name__ == "__main__":
    main()
