"""Offline regression tests for the release APK packaging gate."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location("check_release_apk", Path(__file__).with_name("check_release_apk.py"))
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class ReleaseApkTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "tools").mkdir()
        (self.root / "LICENSE").write_bytes(b"full licence")
        (self.root / "THIRD_PARTY_NOTICES.md").write_bytes(b"notices")
        (self.root / "tools/native_licenses.json").write_text("[]")
        self.apk = self.root / "test.apk"
        replacement = patch.object(checker, "ROOT", self.root)
        replacement.start()
        self.addCleanup(replacement.stop)

    def write_apk(self, extra=None, gpl=b"full licence"):
        entries = {
            "assets/licenses/GPL-3.0.txt": gpl,
            "assets/licenses/NOTICES.txt": b"notices",
            "lib/arm64-v8a/libsherpa-onnx-jni.so": b"runtime",
            "assets/tts/fr/MODEL_CARD.txt": b"French attribution",
            "assets/tts/en/MODEL_CARD.txt": b"English attribution",
        }
        entries.update(extra or {})
        with zipfile.ZipFile(self.apk, "w") as z:
            for name, value in entries.items():
                z.writestr(name, value)

    def test_expected_packaging_passes(self):
        self.write_apk()
        self.assertEqual([], checker.problems(self.apk))

    def test_bundled_needle_or_whistle_is_rejected(self):
        for name in ["lib/arm64-v8a/libverbatim-jni.so", "assets/asr/whistle.cact"]:
            self.write_apk({name: b"removed payload"})
            self.assertTrue(any("Removed speech" in e for e in checker.problems(self.apk)))

    def test_missing_or_changed_full_gpl_is_rejected(self):
        self.write_apk(gpl=b"GPL name only")
        self.assertTrue(any("GPL-3.0" in e for e in checker.problems(self.apk)))

    def test_vcs_metadata_and_unexpected_abi_are_rejected(self):
        self.write_apk({"META-INF/vcs-info.textproto": b"private revision", "lib/x86/libtest.so": b"wrong abi"})
        errors = checker.problems(self.apk)
        self.assertTrue(any("VCS" in e for e in errors))
        self.assertTrue(any("ABI" in e for e in errors))


if __name__ == "__main__":
    unittest.main()
