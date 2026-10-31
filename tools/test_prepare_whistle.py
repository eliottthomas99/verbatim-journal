"""Offline checks: python -I -B tools/test_prepare_whistle.py."""

import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location(
    "prepare_whistle", Path(__file__).with_name("prepare_whistle.py")
)
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)


class PrepareWhistleTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.payload = b"verified model bytes"
        self.sha = hashlib.sha256(self.payload).hexdigest()
        self.target = self.root / "assets" / "model.cact"
        self.target.parent.mkdir()
        for name, value in [
            ("DEST", self.root),
            ("FILES", [("test/resolve/pin/model.cact", "assets/model.cact", self.sha)]),
        ]:
            mock = patch.object(setup, name, value)
            mock.start()
            self.addCleanup(mock.stop)

    def test_valid_cache_does_not_use_network(self):
        self.target.write_bytes(self.payload)
        with patch.object(setup.urllib.request, "urlopen") as fetch:
            setup.prepare()
        fetch.assert_not_called()

    def test_corrupt_cache_is_replaced_after_validation(self):
        self.target.write_bytes(b"partial")
        with patch.object(setup.urllib.request, "urlopen", return_value=io.BytesIO(self.payload)):
            setup.prepare()
        self.assertEqual(self.target.read_bytes(), self.payload)
        self.assertFalse(self.target.with_suffix(".cact.part").exists())

    def test_bad_download_keeps_previous_file_and_removes_partial(self):
        self.target.write_bytes(b"old file")
        with patch.object(setup.urllib.request, "urlopen", return_value=io.BytesIO(b"wrong bytes")):
            with self.assertRaisesRegex(ValueError, "Checksum mismatch"):
                setup.prepare()
        self.assertEqual(self.target.read_bytes(), b"old file")
        self.assertFalse(self.target.with_suffix(".cact.part").exists())


if __name__ == "__main__":
    unittest.main()
