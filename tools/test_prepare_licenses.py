"""Offline download/cache safety checks: python -m unittest discover -s tools."""
import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("prepare_licenses", Path(__file__).with_name("prepare_licenses.py"))
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)


class PrepareLicensesTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.target = Path(self.temp.name) / "licence.txt"
        self.payload = b"original upstream licence"
        self.sha = hashlib.sha256(self.payload).hexdigest()

    def test_verified_cache_is_offline(self):
        self.target.write_bytes(self.payload)
        with patch.object(setup.urllib.request, "urlopen") as request:
            setup.fetch("https://example.invalid/license", self.sha, self.target)
        request.assert_not_called()

    def test_invalid_cache_is_replaced_only_with_verified_bytes(self):
        self.target.write_bytes(b"incomplete")
        with patch.object(setup.urllib.request, "urlopen", return_value=io.BytesIO(self.payload)):
            setup.fetch("https://example.invalid/license", self.sha, self.target)
        self.assertEqual(self.payload, self.target.read_bytes())

    def test_bad_download_preserves_old_file_and_cleans_partial(self):
        self.target.write_bytes(b"old bytes")
        with patch.object(setup.urllib.request, "urlopen", return_value=io.BytesIO(b"wrong")):
            with self.assertRaisesRegex(ValueError, "Checksum"):
                setup.fetch("https://example.invalid/license", self.sha, self.target)
        self.assertEqual(b"old bytes", self.target.read_bytes())
        self.assertFalse(self.target.with_name("licence.txt.part").exists())


if __name__ == "__main__":
    unittest.main()
